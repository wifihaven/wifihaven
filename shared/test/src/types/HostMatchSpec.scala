package wifihaven.shared.types

import zio.test.*

/**
 * #1560: pin the centralized host-suppression / pattern-matching predicates that replace the per-
 * call-site copies in `DashboardNowRoutes` and `Presence`. The whole point of the collapse is that
 * the dashboard and presence agree on which hosts are device-level infra and on the "host matches
 * any of these patterns" shape — these tests fail if a future change makes one side drift.
 */
object HostMatchSpec extends ZIOSpecDefault {

  private val infra: HostId = HostId.fqdn(Hostname.unsafe("r3---sn-abc.gvt2.com"))
  private val app: HostId   = HostId.fqdn(Hostname.unsafe("www.youtube.com"))
  private val cdn: HostId   = HostId.fqdn(Hostname.unsafe("a1.akamai.net"))
  private val privateRelay  = HostId.fqdn(Hostname.unsafe("mask.icloud.com"))
  private val ip            = HostId.ip(IpAddress.unsafe("8.8.8.8"))

  def spec = suite("HostMatch / InfraHosts host-keyed entry points (#1560)")(
    test("HostMatch.matchesAny: subdomain matches apex pattern, non-match returns false") {
      assertTrue(
        HostMatch.matchesAny(app, List("youtube.com")),
        HostMatch.matchesAny(app, List("www.youtube.com")),
        HostMatch.matchesAny(app, List("*.youtube.com")),
        !HostMatch.matchesAny(app, List("notyoutube.com")),
        !HostMatch.matchesAny(app, Nil),
        !HostMatch.matchesAny(cdn, List("youtube.com")),
      )
    },
    test("HostMatch.matchesAny: IP-literal hosts never match (FQDN-only by contract)") {
      assertTrue(
        !HostMatch.matchesAny(ip, List("8.8.8.8")),
        !HostMatch.matchesAny(ip, List("*.google.com")),
      )
    },
    test("#1708: Label hosts never match — synthetic names are not FQDN patterns") {
      val label: HostId = HostId.Label("apple-push")
      assertTrue(
        !HostMatch.matchesAny(label, List("apple-push")),
        !HostMatch.matchesAny(label, List("*.apple-push")),
        !HostMatch.matchesAny(label, List("apple.com")),
      )
    },
    test("InfraHosts.isBackground(HostId): suppresses canonical infra and suppressOnly tier") {
      assertTrue(
        InfraHosts.isBackground(infra),
        InfraHosts.isBackground(privateRelay),
        !InfraHosts.isBackground(app),
        !InfraHosts.isBackground(cdn),
        // IP-literal hosts never match (patterns key on FQDN identity).
        !InfraHosts.isBackground(ip),
      )
    },
    test(
      "InfraHosts.isBackground(HostId) agrees with InfraHosts.isBackground(String) on the FQDN — " +
        "no per-call-site copy can drift from the string predicate (#1560 / #1532)",
    ) {
      val hosts = List(
        "r3---sn-abc.gvt2.com",
        "mask.icloud.com",
        "www.youtube.com",
        "a1.akamai.net",
        "connectivitycheck.gstatic.com",
        "ocsp.digicert.com",
        "firestore.googleapis.com",
      )
      assertTrue(
        hosts.forall { h =>
          val id = HostId.fqdn(Hostname.unsafe(h))
          InfraHosts.isBackground(id) == InfraHosts.isBackground(h)
        },
      )
    },
  )
}
