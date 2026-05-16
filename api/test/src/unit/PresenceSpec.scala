package wifihaven.api.unit

import wifihaven.api.presence.{Presence, PresenceRow}
import wifihaven.shared.types.*
import zio.test.*

import java.time.Instant

object PresenceSpec extends ZIOSpecDefault {

  private val mac1 = MacAddress.unsafe("aa:bb:cc:dd:ee:01")
  private val mac2 = MacAddress.unsafe("aa:bb:cc:dd:ee:02")

  /** Bucket index → Instant; arbitrary epoch base since the values are opaque to Presence. */
  private val base               = Instant.parse("2026-05-13T00:00:00Z")
  private def b(i: Int): Instant = base.plusSeconds(i * 300L)

  private def row(mac: MacAddress, bucket: Int, host: String, secs: Int = 300) =
    PresenceRow(mac, b(bucket), HostId.Fqdn(Hostname.unsafe(host)), secs)

  private def ipRow(mac: MacAddress, bucket: Int, ip: String, secs: Int = 300) =
    PresenceRow(mac, b(bucket), HostId.IPv4(IpAddress.unsafe(ip)), secs)

  def spec = suite("Presence")(
    suite("totalMinutesByMac")(
      test("collapses multiple hostnames in the same bucket to one count") {
        val rows = List(
          row(mac1, 0, "youtube.com"),
          row(mac1, 0, "google.com"),
          row(mac1, 0, "dropbox.com"),
        )
        assertTrue(Presence.totalMinutesByMac(rows, Nil) == Map(mac1 -> 5))
      },
      test("counts distinct buckets per mac independently") {
        val rows = List(
          row(mac1, 0, "youtube.com"),
          row(mac1, 1, "youtube.com"),
          row(mac2, 0, "google.com"),
        )
        assertTrue(Presence.totalMinutesByMac(rows, Nil) == Map(mac1 -> 10, mac2 -> 5))
      },
      test("bucket excluded from total when every hostname matches an exempt pattern") {
        val rows = List(
          row(mac1, 0, "www.youtube.com"),
          row(mac1, 0, "m.youtube.com"),
          row(mac1, 1, "google.com"),
        )
        assertTrue(
          Presence.totalMinutesByMac(rows, List("*.youtube.com")) == Map(mac1 -> 5),
        )
      },
      test("a single non-exempt hostname pulls the whole bucket back into the total") {
        // Mixed bucket: youtube.com (exempt) + google.com (non-exempt) in the same window. The
        // device was actively on screen for those 5 minutes, so the whole bucket counts toward
        // the daily cap — only the exempt site limit's own counter ticks separately.
        val rows = List(
          row(mac1, 0, "youtube.com"),
          row(mac1, 0, "google.com"),
        )
        assertTrue(
          Presence.totalMinutesByMac(rows, List("*.youtube.com")) == Map(mac1 -> 5),
        )
      },
      test("exempt pattern matches exact host as well as subdomains") {
        val rows = List(row(mac1, 0, "youtube.com"))
        assertTrue(
          Presence.totalMinutesByMac(rows, List("*.youtube.com")) == Map.empty[MacAddress, Int],
        )
      },
      test("uses max active_seconds in the bucket as duration") {
        // The agent emits the same bucket duration on every row, but if multiple ticks land in
        // the same period (shouldn't happen in practice) we trust the largest value.
        val rows = List(
          row(mac1, 0, "a.com", 60),
          row(mac1, 0, "b.com", 300),
        )
        assertTrue(Presence.totalMinutesByMac(rows, Nil) == Map(mac1 -> 5))
      },
    ),
    suite("patternMinutesByMac")(
      test("bucket with two hostnames matching the same pattern counts once for that pattern") {
        val rows = List(
          row(mac1, 0, "m.youtube.com"),
          row(mac1, 0, "www.youtube.com"),
        )
        assertTrue(
          Presence
            .patternMinutesByMac(rows, List("*.youtube.com")) == Map((mac1, "*.youtube.com") -> 5),
        )
      },
      test("one hostname matching two patterns contributes to both independently") {
        val rows = List(row(mac1, 0, "video.youtube.com"))
        val res  =
          Presence.patternMinutesByMac(rows, List("*.youtube.com", "video.youtube.com"))
        assertTrue(
          res == Map(
            (mac1, "*.youtube.com")     -> 5,
            (mac1, "video.youtube.com") -> 5,
          ),
        )
      },
      test("patterns the bucket doesn't match don't appear in the result") {
        val rows = List(row(mac1, 0, "google.com"))
        assertTrue(
          Presence.patternMinutesByMac(rows, List("*.youtube.com")) == Map.empty,
        )
      },
      test("IPv4-literal rows never match an FQDN pattern (#391 regression)") {
        // Before the HostId refactor, an unattributed flow landed in
        // traffic_reports with hostname=\"192.0.2.1\". A naive substring or
        // suffix matcher couldn't match `*.example.com`, but the dead row
        // still polluted top-host views and \"unknown\" rollups silently
        // discarded the bucket from per-site accounting in surprising ways.
        // The fix is at the type level: IP-typed rows are filtered out of
        // pattern matching entirely (via `host.asFqdn`).
        val rows = List(
          ipRow(mac1, 0, "192.0.2.1"),
          ipRow(mac1, 1, "192.0.2.1"),
        )
        assertTrue(
          Presence.patternMinutesByMac(rows, List("*.example.com")) == Map.empty,
        ) &&
        // A blanket * pattern would have matched in the old string world;
        // it must not match IP-typed rows either.
        assertTrue(
          Presence.patternMinutesByMac(rows, List("*")) == Map.empty,
        )
      },
      test("mixed bucket of FQDN + IP only counts the FQDN side of the pattern") {
        // Same 5-min bucket: device hits youtube.com AND a direct-IP server.
        // The bucket counts once for *.youtube.com, period — the IP doesn't
        // double-count toward any FQDN pattern.
        val rows = List(
          row(mac1, 0, "m.youtube.com"),
          ipRow(mac1, 0, "192.0.2.1"),
        )
        assertTrue(
          Presence.patternMinutesByMac(rows, List("*.youtube.com")) ==
            Map((mac1, "*.youtube.com") -> 5),
        )
      },
      test("IP-typed hosts are never exempt from the daily total (#391)") {
        // Exemption patterns are FQDN-shaped (e.g. *.youtube.com). An IP
        // literal can't be exempted by a hostname pattern, so a bucket of
        // pure direct-IP traffic always counts toward the daily total.
        val rows = List(ipRow(mac1, 0, "192.0.2.1"))
        assertTrue(
          Presence.totalMinutesByMac(rows, List("*")) == Map(mac1 -> 5),
        )
      },
    ),
  )
}
