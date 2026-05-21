package wifihaven.api.unit

import wifihaven.api.presence.{Presence, PresenceRow}
import wifihaven.shared.HeartbeatFilter
import wifihaven.shared.types.*
import zio.test.*

import java.time.{Instant, LocalDate}

object PresenceSpec extends ZIOSpecDefault {

  private val mac1 = MacAddress.unsafe("aa:bb:cc:dd:ee:01")
  private val mac2 = MacAddress.unsafe("aa:bb:cc:dd:ee:02")

  /** Bucket index → Instant; arbitrary epoch base since the values are opaque to Presence. */
  private val base               = Instant.parse("2026-05-13T00:00:00Z")
  private val baseDate           = LocalDate.parse("2026-05-13")
  private def b(i: Int): Instant = base.plusSeconds(i * 300L)

  private def row(
      mac: MacAddress,
      bucket: Int,
      host: String,
      secs: Int = 300,
      bytes: Long = 1_000_000L,
      periodSeconds: Int = 300,
  ) =
    PresenceRow(
      mac,
      baseDate,
      b(bucket),
      HostId.Fqdn(Hostname.unsafe(host)),
      secs,
      bytes,
      periodSeconds,
    )

  private def ipRow(
      mac: MacAddress,
      bucket: Int,
      ip: String,
      secs: Int = 300,
      bytes: Long = 1_000_000L,
      periodSeconds: Int = 300,
  ) =
    PresenceRow(
      mac,
      baseDate,
      b(bucket),
      HostId.IPv4(IpAddress.unsafe(ip)),
      secs,
      bytes,
      periodSeconds,
    )

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
    suite("heartbeat filter (#714)")(
      test("filter off: low-byte rows still count toward the daily total") {
        val rows = List(row(mac1, 0, "apns.apple.com", secs = 60, bytes = 60L, periodSeconds = 60))
        assertTrue(Presence.totalMinutesByMac(rows, Nil, HeartbeatFilter.Off) == Map(mac1 -> 1))
      },
      test("filter on: bucket with only sub-threshold-bytes rows collapses to 0") {
        // APNs keepalive: 60s period, ~60 bytes total.
        val f    = HeartbeatFilter(enabled = true, bytesThreshold = 2048)
        val rows = List(
          row(mac1, 0, "apns.apple.com", secs = 5, bytes = 60L, periodSeconds = 60),
        )
        assertTrue(Presence.totalMinutesByMac(rows, Nil, f) == Map.empty[MacAddress, Int])
      },
      test("filter on: mixed bucket (heartbeat + real traffic) keeps the bucket's minutes") {
        val f    = HeartbeatFilter(enabled = true, bytesThreshold = 2048)
        val rows = List(
          row(mac1, 0, "apns.apple.com", secs = 5, bytes = 60L, periodSeconds = 60),
          row(mac1, 0, "youtube.com", secs = 60, bytes = 500_000L, periodSeconds = 60),
        )
        // Heartbeat row dropped; real row keeps the 60s bucket alive → 1 min.
        assertTrue(Presence.totalMinutesByMac(rows, Nil, f) == Map(mac1 -> 1))
      },
      test("filter on: above the byte floor passes regardless of active fraction") {
        // #789: with the fraction knob removed, a low active-fraction row whose bytes are above
        // the threshold counts as active. (Previously, a 2s/60s row tripped the fraction floor.)
        val f    = HeartbeatFilter(enabled = true, bytesThreshold = 100)
        val rows = List(
          row(mac1, 0, "rcs.google.com", secs = 2, bytes = 5_000L, periodSeconds = 60),
        )
        assertTrue(!Presence.isHeartbeat(rows.head, f))
      },
      test("filter on: tiny-payload rows drop even when active the whole minute") {
        val f    = HeartbeatFilter(enabled = true, bytesThreshold = 2048)
        val rows = List(
          row(mac1, 0, "time.apple.com", secs = 60, bytes = 200L, periodSeconds = 60),
        )
        assertTrue(Presence.totalMinutesByMac(rows, Nil, f) == Map.empty[MacAddress, Int])
      },
      test("classifyRows surfaces the bytes reason when a row trips the threshold") {
        val f    = HeartbeatFilter(enabled = true, bytesThreshold = 2048)
        val rows = List(row(mac1, 0, "apns.apple.com", secs = 5, bytes = 60L, periodSeconds = 60))
        val out  = Presence.classifyRows(rows, f)
        val reasons = out.head.reasons
        assertTrue(out.length == 1) &&
        assertTrue(out.head.classified == "heartbeat") &&
        assertTrue(reasons.exists(_.startsWith("bytes<")))
      },
      test("classifyRows reports rows as active when filter is disabled") {
        val rows = List(row(mac1, 0, "apns.apple.com", secs = 5, bytes = 60L, periodSeconds = 60))
        val out  = Presence.classifyRows(rows, HeartbeatFilter.Off)
        assertTrue(out.head.classified == "active") &&
        assertTrue(out.head.reasons.isEmpty)
      },
      test("#788 host pattern match classifies as heartbeat even when bytes pass") {
        // Row that would otherwise pass the bytes floor — only the FQDN allowlist trips it.
        // Confirms host-pattern path is OR'd in correctly.
        val f = HeartbeatFilter(
          enabled = true,
          bytesThreshold = 2048,
          heartbeatHostPatterns = List("*.push.apple.com"),
        )
        val r =
          row(mac1, 0, "api-push.push.apple.com", secs = 200, bytes = 50_000L, periodSeconds = 300)
        assertTrue(Presence.isHeartbeat(r, f)) &&
        assertTrue(Presence.totalMinutesByMac(List(r), Nil, f) == Map.empty[MacAddress, Int])
      },
      test("#788 classifyRows surfaces host:<pattern> reason for FQDN match") {
        val f    = HeartbeatFilter(
          enabled = true,
          bytesThreshold = 2048,
          heartbeatHostPatterns = List("*.push.apple.com", "time.apple.com"),
        )
        val rows = List(
          row(mac1, 0, "courier.push.apple.com", secs = 200, bytes = 50_000L, periodSeconds = 300),
        )
        val out  = Presence.classifyRows(rows, f)
        assertTrue(out.head.classified == "heartbeat") &&
        assertTrue(out.head.reasons == List("host:*.push.apple.com"))
      },
      test("#788 host pattern only matches FQDNs, not IP literals") {
        val f = HeartbeatFilter(
          enabled = true,
          bytesThreshold = 0,
          heartbeatHostPatterns = List("*.push.apple.com"),
        )
        val r = ipRow(mac1, 0, "17.57.146.1", secs = 200, bytes = 50_000L, periodSeconds = 300)
        assertTrue(!Presence.isHeartbeat(r, f))
      },
    ),
    suite("hostMinutes")(
      test("empty rows yields empty map") {
        assertTrue(Presence.hostMinutes(Nil) == Map.empty[HostId, Int])
      },
      test("each host in a bucket gets the bucket's minutes attributed once") {
        // A 5-min bucket with three distinct hosts: each host gets 5 minutes
        // of attribution. Per-host view is informational, not a fraction of
        // the daily total (which still counts the bucket once).
        val rows = List(
          row(mac1, 0, "youtube.com"),
          row(mac1, 0, "google.com"),
          row(mac1, 0, "dropbox.com"),
        )
        val res  = Presence.hostMinutes(rows)
        assertTrue(
          res == Map(
            HostId.Fqdn(Hostname.unsafe("youtube.com")) -> 5,
            HostId.Fqdn(Hostname.unsafe("google.com"))  -> 5,
            HostId.Fqdn(Hostname.unsafe("dropbox.com")) -> 5,
          ),
        )
      },
      test("same host across multiple buckets sums") {
        val rows = List(
          row(mac1, 0, "youtube.com"),
          row(mac1, 1, "youtube.com"),
          row(mac2, 2, "youtube.com"),
        )
        assertTrue(
          Presence.hostMinutes(rows) == Map(
            HostId.Fqdn(Hostname.unsafe("youtube.com")) -> 15,
          ),
        )
      },
      test("same host twice in one bucket only counts once for that bucket") {
        val rows = List(
          row(mac1, 0, "youtube.com"),
          row(mac1, 0, "youtube.com"),
        )
        assertTrue(
          Presence.hostMinutes(rows) == Map(
            HostId.Fqdn(Hostname.unsafe("youtube.com")) -> 5,
          ),
        )
      },
      test("IP-literal hosts are attributed under their address form") {
        val rows = List(
          ipRow(mac1, 0, "192.0.2.1"),
          row(mac1, 0, "youtube.com"),
        )
        assertTrue(
          Presence.hostMinutes(rows) == Map(
            HostId.IPv4(IpAddress.unsafe("192.0.2.1"))  -> 5,
            HostId.Fqdn(Hostname.unsafe("youtube.com")) -> 5,
          ),
        )
      },
      test("uses max active_seconds in the bucket as duration") {
        val rows = List(
          row(mac1, 0, "a.com", 60),
          row(mac1, 0, "b.com", 300),
        )
        assertTrue(
          Presence.hostMinutes(rows) == Map(
            HostId.Fqdn(Hostname.unsafe("a.com")) -> 5,
            HostId.Fqdn(Hostname.unsafe("b.com")) -> 5,
          ),
        )
      },
    ),
  )
}
