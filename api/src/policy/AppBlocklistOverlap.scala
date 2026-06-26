package wifihaven.api.policy

import wifihaven.api.db.BlocklistRepo
import wifihaven.shared.*
import wifihaven.shared.types.*
import zio.*

/**
 * #1983: compute, per app, which of its hosts overlap a shipped category blocklist (and which
 * blocklist(s) each overlapping host is on). This is the data the SPA's Apps page + app selector
 * render as a "this app contains blocklisted hosts" warning.
 *
 * Matching mirrors enforcement ([[wifihaven.shared.types.HostMatch.hasApexMatch]]): a host is on a
 * blocklist if the host itself OR one of its apex parents (up to `maxHops` deep) is a member
 * domain. We therefore expand each host into its apex-candidate set, query the (indexed)
 * blocklist_domains table once for the whole bounded candidate set, then map each candidate's
 * categories back onto the hosts that produced it. No full-table scan, no re-fetch of the in-memory
 * category map.
 *
 * Membership is GLOBAL (host ∈ ANY shipped blocklist), not scoped to a profile's enabled lists —
 * the warning describes the app definition, which is profile-independent (see #1983).
 */
object AppBlocklistOverlap {

  /** Mirror of [[HostMatch.hasApexMatch]]'s walk: the host plus each apex parent it would test. */
  def apexCandidates(host: String, maxHops: Int = 5): List[String] = {
    @annotation.tailrec
    def loop(h: String, hops: Int, acc: List[String]): List[String] = {
      val acc2 = h :: acc
      val dot  = h.indexOf('.')
      if (dot < 0 || hops <= 0) acc2
      else loop(h.substring(dot + 1), hops - 1, acc2)
    }
    loop(host, maxHops, Nil)
  }

  /**
   * For each app id, the subset of its hosts that overlap a category blocklist, with the matching
   * blocklist ids (sorted). Apps with no overlap are absent from the result map.
   */
  def forApps(
      blocklistRepo: BlocklistRepo,
      hostsByApp: Map[AppId, List[Hostname]],
  ): Task[Map[AppId, List[AppBlocklistedHost]]] = {
    val allCandidates =
      hostsByApp.values.flatten.flatMap(h => apexCandidates(h.value)).toList.distinct
    blocklistRepo.categoriesForDomains(allCandidates).map { catsByDomain =>
      hostsByApp.flatMap { (appId, hosts) =>
        val flagged = hosts.flatMap { host =>
          val cats = apexCandidates(host.value)
            .flatMap(c => catsByDomain.getOrElse(c, Nil))
            .distinct
            .sorted
          if (cats.isEmpty) None else Some(AppBlocklistedHost(host, cats))
        }
        if (flagged.isEmpty) None else Some(appId -> flagged)
      }
    }
  }

  /** Single-app convenience for the detail endpoint. */
  def forHosts(
      blocklistRepo: BlocklistRepo,
      appId: AppId,
      hosts: List[Hostname],
  ): Task[List[AppBlocklistedHost]] =
    forApps(blocklistRepo, Map(appId -> hosts)).map(_.getOrElse(appId, Nil))
}
