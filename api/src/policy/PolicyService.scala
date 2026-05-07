package familydns.api.policy

import familydns.api.db.*
import familydns.shared.*
import zio.{Clock as _, *}

import java.security.MessageDigest

trait PolicyService:
  def snapshot: Task[PolicySnapshot]
  def renderRpz(category: String): Task[Option[(String, String)]]

class PolicyServiceLive(
    profileRepo: ProfileRepo,
    scheduleRepo: ScheduleRepo,
    timeLimitRepo: TimeLimitRepo,
    siteTimeLimitRepo: SiteTimeLimitRepo,
    deviceRepo: DeviceRepo,
    blocklistRepo: BlocklistRepo,
    usageRepo: TimeUsageRepo,
    extRepo: TimeExtensionRepo,
    clock: Clock,
) extends PolicyService:

  def snapshot: Task[PolicySnapshot] =
    for
      today    <- clock.today
      now      <- clock.now
      profiles <- profileRepo.listAll
      devices  <- deviceRepo.listAll
      scheds   <- ZIO.foreach(profiles)(p => scheduleRepo.listForProfile(p.id).map(p.id -> _))
      tlims    <- ZIO.foreach(profiles)(p => timeLimitRepo.findForProfile(p.id).map(p.id -> _))
      stlims   <- ZIO.foreach(profiles)(p => siteTimeLimitRepo.listForProfile(p.id).map(p.id -> _))
      usages   <- usageRepo.snapshotAll(today)
      exts     <- extRepo.snapshotAll(today)
      cats     <- blocklistRepo.listCategories
      schedMap = scheds.toMap
      tlMap    = tlims.toMap
      stlMap   = stlims.toMap
    yield
      val devsByProfile = devices.groupBy(_.profileId)
      val pProfiles     = profiles.map { p =>
        val pSched    = schedMap
          .getOrElse(p.id, Nil)
          .map(s => PolicySchedule(s.days, s.blockFrom, s.blockUntil))
        val pSiteLims = stlMap
          .getOrElse(p.id, Nil)
          .map(s => PolicySiteLimit(s.domainPattern, s.dailyMinutes, s.label))
        val devicesIn = devsByProfile.getOrElse(p.id, Nil)
        val byDomain  = stlMap
          .getOrElse(p.id, Nil)
          .foldLeft(Map.empty[String, Int]) { (acc, stl) =>
            val mins =
              devicesIn.iterator.map(d => usages.getOrElse((d.mac, stl.domainPattern), 0)).sum
            if mins == 0 then acc else acc.updated(stl.domainPattern, mins)
          }
        val siteDoms  = stlMap.getOrElse(p.id, Nil).map(_.domainPattern).toSet
        val totalMins = usages.iterator.collect {
          case ((mac, dom), m) if devicesIn.exists(_.mac == mac) && !siteDoms.contains(dom) => m
        }.sum
        val extMins   = devicesIn.map(d => exts.getOrElse(d.mac, 0)).sum
        PolicyProfile(
          id = p.id,
          name = p.name,
          paused = p.paused,
          blockedCategories = p.blockedCategories,
          extraBlocked = p.extraBlocked,
          extraAllowed = p.extraAllowed,
          schedules = pSched,
          dailyMinutes = tlMap.getOrElse(p.id, None).map(_.dailyMinutes),
          siteLimits = pSiteLims,
          timeUsedToday = PolicyTimeUsedToday(totalMins, byDomain),
          extensionsTodayMinutes = extMins,
        )
      }
      val pDevices      = devices.map(d => PolicyDevice(d.mac, d.profileId, d.name))
      val pBlocklists   = cats.map { c =>
        c -> PolicyBlocklist(version = today.toString, url = s"/api/blocklists/$c.rpz")
      }.toMap
      val defaultId     = profiles.map(_.id).minOption
      val core          = SnapshotCore(defaultId, pDevices, pProfiles, pBlocklists)
      val etag          = PolicyService.computeEtag(core)
      PolicySnapshot(
        etag = etag,
        generatedAt = now.toString,
        defaultProfileId = defaultId,
        devices = pDevices,
        profiles = pProfiles,
        blocklists = pBlocklists,
      )

  def renderRpz(category: String): Task[Option[(String, String)]] =
    for
      today   <- clock.today
      domains <- blocklistRepo.loadCategory(category)
    yield
      if domains.isEmpty then None
      else
        val origin = s"$category.rpz."
        val serial = today.toString.replace("-", "")
        val sb     = new StringBuilder
        sb.append(s"$$ORIGIN $origin\n")
        sb.append("$TTL 300\n")
        sb.append(s"@ SOA localhost. admin.localhost. $serial 3600 600 86400 60\n")
        sb.append("@ NS localhost.\n")
        domains.toList.sorted.foreach(d => sb.append(s"$d CNAME .\n"))
        val body   = sb.toString
        val etag   = s"\"${PolicyService.sha256Hex(body).take(16)}-$serial\""
        Some((etag, body))

private case class SnapshotCore(
    defaultProfileId: Option[Long],
    devices: List[PolicyDevice],
    profiles: List[PolicyProfile],
    blocklists: Map[String, PolicyBlocklist],
)

object PolicyService:
  val layer: ZLayer[
    ProfileRepo & ScheduleRepo & TimeLimitRepo & SiteTimeLimitRepo & DeviceRepo & BlocklistRepo &
      TimeUsageRepo & TimeExtensionRepo & Clock,
    Nothing,
    PolicyService,
  ] = ZLayer.fromFunction(PolicyServiceLive(_, _, _, _, _, _, _, _, _))

  def sha256Hex(s: String): String =
    val md = MessageDigest.getInstance("SHA-256")
    md.digest(s.getBytes("UTF-8")).map("%02x".format(_)).mkString

  /** Hex SHA-256 of a router/enrollment token, used as the storage key. */
  def hashToken(raw: String): String = sha256Hex(raw)

  /** Deterministic ETag over snapshot logical content. */
  private[policy] def computeEtag(core: SnapshotCore): String =
    val parts = scala.collection.mutable.ArrayBuffer.empty[String]
    parts += s"d=${core.defaultProfileId.getOrElse("-")}"
    core.devices.sortBy(_.mac).foreach(d => parts += s"dev:${d.mac}|${d.profileId}|${d.name}")
    core.profiles.sortBy(_.id).foreach { p =>
      parts += s"p:${p.id}|${p.name}|${p.paused}|${p.dailyMinutes.getOrElse(-1)}|${p.extensionsTodayMinutes}"
      parts += s"  bc:${p.blockedCategories.sorted.mkString(",")}"
      parts += s"  eb:${p.extraBlocked.sorted.mkString(",")}"
      parts += s"  ea:${p.extraAllowed.sorted.mkString(",")}"
      p.schedules.sortBy(s => (s.blockFrom, s.blockUntil)).foreach { s =>
        parts += s"  s:${s.days.mkString(",")}|${s.blockFrom}|${s.blockUntil}"
      }
      p.siteLimits.sortBy(_.domain).foreach { sl =>
        parts += s"  sl:${sl.domain}|${sl.minutes}|${sl.label}"
      }
      parts += s"  u:${p.timeUsedToday.totalMinutes}"
      p.timeUsedToday.byDomain.toList.sortBy(_._1).foreach((k, v) => parts += s"    ud:$k=$v")
    }
    core.blocklists.toList.sortBy(_._1).foreach((k, v) => parts += s"bl:$k=${v.version}")
    "\"sha256:" + sha256Hex(parts.mkString("\n")) + "\""
