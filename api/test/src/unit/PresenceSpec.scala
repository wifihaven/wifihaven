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
      test("#1464 session span ignores active_seconds — full period span is the evidence") {
        // The session model credits each non-heartbeat row its full [period_start, period_end]
        // span (300s here), NOT the sampled active_seconds. So a row that sampled only 60s of its
        // 300s window still contributes the whole window — this is the within-minute undercount
        // fix (docs/design/presence-tuning.md §4.1).
        val rows = List(
          row(mac1, 0, "a.com", secs = 60),
          row(mac1, 0, "b.com", secs = 300),
        )
        assertTrue(Presence.totalMinutesByMac(rows, Nil) == Map(mac1 -> 5))
      },
      test("#1464 (a) a continuous sparse session reads its full span, not the sampled floor") {
        // Five contiguous 60s windows, each sampling only the 10s activity floor — a kid working
        // a problem locally between requests. Old bucket model: Σ max(activeSeconds) = 50s → 0 min.
        // Session model: the five windows stitch into one [0, 300] session → 5 min.
        val rows = (0 until 5).toList.map { i =>
          PresenceRow(
            mac1,
            baseDate,
            base.plusSeconds(i * 60L),
            HostId.Fqdn(Hostname.unsafe("mathacademy.com")),
            activeSeconds = 10,
            bytes = 200_000L,
            periodSeconds = 60,
          )
        }
        assertTrue(Presence.totalSecondsByMac(rows, Nil) == Map(mac1 -> 300L)) &&
        assertTrue(Presence.totalMinutesByMac(rows, Nil) == Map(mac1 -> 5))
      },
      test("#1464 (b) re-bucketing the same day at R=10s vs R=300s yields the same minutes") {
        // §2d rate-independence: 300s of continuous activity, expressed once as 30 fine (10s)
        // windows and once as a single coarse (300s) window. Both must read 300s = 5 min — bucket
        // size is only the resolution of the evidence, never a term in the formula.
        val fine   = (0 until 30).toList.map { i =>
          PresenceRow(
            mac1,
            baseDate,
            base.plusSeconds(i * 10L),
            HostId.Fqdn(Hostname.unsafe("khanacademy.org")),
            activeSeconds = 10,
            bytes = 200_000L,
            periodSeconds = 10,
          )
        }
        val coarse = List(
          PresenceRow(
            mac1,
            baseDate,
            base,
            HostId.Fqdn(Hostname.unsafe("khanacademy.org")),
            activeSeconds = 10,
            bytes = 200_000L,
            periodSeconds = 300,
          ),
        )
        assertTrue(
          Presence.totalSecondsByMac(fine, Nil) == Presence.totalSecondsByMac(coarse, Nil),
        ) &&
        assertTrue(Presence.totalMinutesByMac(fine, Nil) == Map(mac1 -> 5))
      },
      test(
        "#1464 (c) two concurrent apps in one minute count as one minute (within-device union)",
      ) {
        // Same 60s window, two different apps. One human on one screen: the device's per-app
        // sessions union, so the minute counts once — not twice.
        val rows = List(
          PresenceRow(
            mac1,
            baseDate,
            base,
            HostId.Fqdn(Hostname.unsafe("mathacademy.com")),
            activeSeconds = 60,
            bytes = 200_000L,
            periodSeconds = 60,
          ),
          PresenceRow(
            mac1,
            baseDate,
            base,
            HostId.Fqdn(Hostname.unsafe("youtube.com")),
            activeSeconds = 60,
            bytes = 200_000L,
            periodSeconds = 60,
          ),
        )
        assertTrue(Presence.totalSecondsByMac(rows, Nil) == Map(mac1 -> 60L))
      },
      test("#1464 collapse guard: N below 2×R is raised so contiguous windows still merge") {
        // A misconfigured N (10s) below 2×R (R=60s) would, unguarded, stop contiguous windows from
        // merging and zero out presence (§2d (ii)). The effectiveGap clamp to 2×R keeps the two
        // contiguous windows stitched into one 120s session.
        val rows = (0 until 2).toList.map { i =>
          PresenceRow(
            mac1,
            baseDate,
            base.plusSeconds(i * 60L),
            HostId.Fqdn(Hostname.unsafe("mathacademy.com")),
            activeSeconds = 60,
            bytes = 200_000L,
            periodSeconds = 60,
          )
        }
        assertTrue(
          Presence.totalSecondsByMac(rows, Nil, continuationSeconds = 10) == Map(mac1 -> 120L),
        )
      },
      test("#1464 a real idle gap beyond N is NOT bridged (sessions stay separate)") {
        // Two 60s windows 600s apart (gap = 540s > N=120). They must remain two 60s sessions →
        // 120s total, not one bridged 660s span.
        val rows = List(
          PresenceRow(
            mac1,
            baseDate,
            base,
            HostId.Fqdn(Hostname.unsafe("mathacademy.com")),
            activeSeconds = 60,
            bytes = 200_000L,
            periodSeconds = 60,
          ),
          PresenceRow(
            mac1,
            baseDate,
            base.plusSeconds(600L),
            HostId.Fqdn(Hostname.unsafe("mathacademy.com")),
            activeSeconds = 60,
            bytes = 200_000L,
            periodSeconds = 60,
          ),
        )
        assertTrue(
          Presence.totalSecondsByMac(rows, Nil, continuationSeconds = 120) == Map(mac1 -> 120L),
        )
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
    // #715: byte-share-weighted per-host attribution. Same input as hostMinutes,
    // but each bucket's wall-clock duration is split across hosts by byte share
    // instead of credited in full to each host.
    suite("proportionalHostMinutes (#715)")(
      test("80/20 byte split in a single bucket yields a 4:1 minute attribution") {
        val rows = List(
          row(mac1, 0, "youtube.com", secs = 300, bytes = 800L),
          row(mac1, 0, "icloud.com", secs = 300, bytes = 200L),
        )
        // bucket = 300s. youtube 800/1000 → 240s = 4m. icloud 200/1000 → 60s = 1m.
        assertTrue(
          Presence.proportionalHostMinutes(rows) == Map(
            HostId.Fqdn(Hostname.unsafe("youtube.com")) -> 4,
            HostId.Fqdn(Hostname.unsafe("icloud.com"))  -> 1,
          ),
        )
      },
      test("ten polling hosts + one heavy host: heavy host dominates proportional minutes") {
        // Reproduces the prod shape in #715: device shows ~60 used mins, but a
        // bucket-presence breakdown lists 10 hosts at 50–80m each because each
        // one was touched in every bucket. With byte-share weighting, ~all of
        // the attributed minutes go to the host that actually moved bytes.
        val heavy   = "youtube.com"
        val pollers = (0 until 10).map(i => s"poll-$i.example.com").toList
        val rows    = (0 until 12).toList.flatMap { b =>
          val heavyRow = row(mac1, b, heavy, secs = 300, bytes = 5_000_000L)
          val pollRows = pollers.map(p => row(mac1, b, p, secs = 300, bytes = 200L))
          heavyRow :: pollRows
        }
        val out     = Presence.proportionalHostMinutes(rows)
        // 12 buckets × 5min = 60 wall-clock minutes for the mac. youtube gets
        // 5_000_000 / (5_000_000 + 10 * 200) ≈ 99.96% of each bucket → ~59 mins.
        // Each poller gets ~1/(2 500) of each bucket → 0m after the floor /60.
        assertTrue(out(HostId.Fqdn(Hostname.unsafe(heavy))) == 59) &&
        // Bucket-presence still shows every poller at 12 × 5 = 60 minutes.
        assertTrue(
          pollers.forall(p => Presence.hostMinutes(rows)(HostId.Fqdn(Hostname.unsafe(p))) == 60),
        ) &&
        // …but proportionally every poller is below the per-minute floor.
        assertTrue(pollers.forall(p => out.getOrElse(HostId.Fqdn(Hostname.unsafe(p)), 0) == 0))
      },
      test("bucket with zero total bytes contributes nothing") {
        // Defensive: in the wild the agent only emits rows with bytes > 0, but
        // we guard the divide-by-zero so test fixtures with bytes=0 don't blow
        // up. Such a bucket simply doesn't attribute to any host proportionally
        // — bucket-presence (hostMinutes) is the right view for that case.
        val rows = List(
          row(mac1, 0, "a.com", secs = 300, bytes = 0L),
          row(mac1, 0, "b.com", secs = 300, bytes = 0L),
        )
        assertTrue(Presence.proportionalHostMinutes(rows).isEmpty) &&
        assertTrue(Presence.hostMinutes(rows).valuesIterator.toSet == Set(5))
      },
      test("multiple rows for the same host in one bucket collapse before splitting") {
        // Two ipv4-typed rows resolving to the same fqdn via the read-side
        // LATERAL join. Bytes for the host must sum before computing share —
        // otherwise the host would lose weight to itself.
        val rows = List(
          row(mac1, 0, "youtube.com", secs = 300, bytes = 400L),
          row(mac1, 0, "youtube.com", secs = 300, bytes = 400L), // same host
          row(mac1, 0, "icloud.com", secs = 300, bytes = 200L),
        )
        // youtube collapses to 800. share = 800/1000 → 240s = 4m.
        assertTrue(
          Presence.proportionalHostMinutes(rows) == Map(
            HostId.Fqdn(Hostname.unsafe("youtube.com")) -> 4,
            HostId.Fqdn(Hostname.unsafe("icloud.com"))  -> 1,
          ),
        )
      },
      test("sums proportional seconds across buckets and macs") {
        val rows = List(
          // bucket 0: mac1 splits youtube 50/50 with poll → 2.5m each
          row(mac1, 0, "youtube.com", secs = 300, bytes = 500L),
          row(mac1, 0, "poll.com", secs = 300, bytes = 500L),
          // bucket 1: mac1 alone on youtube → 5m
          row(mac1, 1, "youtube.com", secs = 300, bytes = 1_000L),
          // bucket 2: mac2 alone on poll → 5m
          row(mac2, 2, "poll.com", secs = 300, bytes = 1_000L),
        )
        val out  = Presence.proportionalHostMinutes(rows)
        assertTrue(out(HostId.Fqdn(Hostname.unsafe("youtube.com"))) == 7) && // 2.5 + 5 = 7.5 → 7
        assertTrue(out(HostId.Fqdn(Hostname.unsafe("poll.com"))) == 7)       // 2.5 + 5 = 7.5 → 7
      },
    ),
  )
}
