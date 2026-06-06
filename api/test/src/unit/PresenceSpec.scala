package wifihaven.api.unit

import wifihaven.api.presence.{Presence, PresenceRow}
import wifihaven.shared.{CrossDeviceOverlapMode, HeartbeatFilter}
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

  /**
   * #1504: a row whose sampled `activeSeconds` is far below its reporting window — the
   * request-driven app shape that bottoms out at the ~10 s sample floor. `period_start` /
   * `period_seconds` drive the session-stitch interval `[start, start+len)`; `active_seconds` is
   * what the legacy bucket-max model (mistakenly) credited, so keeping it small is what makes the
   * two models diverge.
   */
  private def sparseRow(
      mac: MacAddress,
      offsetSec: Long,
      host: String,
      periodSeconds: Int = 60,
      activeSeconds: Int = 10,
      bytes: Long = 1_000_000L,
  ) =
    PresenceRow(
      mac,
      baseDate,
      base.plusSeconds(offsetSec),
      HostId.Fqdn(Hostname.unsafe(host)),
      activeSeconds,
      bytes,
      periodSeconds,
    )

  private val yt = HostId.Fqdn(Hostname.unsafe("youtube.com"))

  /**
   * #1465: a row at an explicit second offset with an explicit window length, for the
   * session-stitch surfaces. `period_start` / `period_seconds` drive the activity interval `[start,
   * start+len)`; `active_seconds` is irrelevant to the session model (it set the old
   * `max(activeSeconds)` floor), so we just mirror the window length into it.
   */
  private def appRow(
      mac: MacAddress,
      offsetSec: Long,
      host: String,
      periodSeconds: Int,
      bytes: Long = 1_000_000L,
  ) =
    PresenceRow(
      mac,
      baseDate,
      base.plusSeconds(offsetSec),
      HostId.Fqdn(Hostname.unsafe(host)),
      periodSeconds,
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
      test("#1504: per-site count session-stitches engaged minutes, not bucket-max activeSeconds") {
        // 16 contiguous 60 s windows of www.mathacademy.com, each sampling only the ~10 s
        // activeSeconds floor. The legacy bucket-max model credits max(activeSeconds)=10 s per
        // bucket → ~2 min (the prod undercount that made the site limit read 0). The #1464
        // session-stitch credits the full [0, 960 s) engaged span → 16 min.
        val rows = (0 until 16).map(i => sparseRow(mac1, i * 60L, "www.mathacademy.com")).toList
        assertTrue(
          Presence.patternMinutesByMac(rows, List("mathacademy.com")) ==
            Map((mac1, "mathacademy.com") -> 16),
        )
      },
      test("#1504: patternMinutesForProfile combines a site across devices per overlap mode") {
        // Two devices engaged on the same site in the SAME 16 minutes. Sum adds each device's
        // engaged time (32); Dedup unions the overlapping sessions across devices (16).
        val rows = (0 until 16).flatMap { i =>
          List(
            sparseRow(mac1, i * 60L, "www.mathacademy.com"),
            sparseRow(mac2, i * 60L, "www.mathacademy.com"),
          )
        }.toList
        assertTrue(
          Presence.patternMinutesForProfile(
            rows,
            List("mathacademy.com"),
            CrossDeviceOverlapMode.Sum,
          ) == Map("mathacademy.com" -> 32),
        ) &&
        assertTrue(
          Presence.patternMinutesForProfile(
            rows,
            List("mathacademy.com"),
            CrossDeviceOverlapMode.Dedup,
          ) == Map("mathacademy.com" -> 16),
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
      test("#1525 suppress-only infra (push.apple.com) is a heartbeat even when bytes pass") {
        // push.apple.com was folded from the retired heartbeatHostPatterns seed into the
        // InfraHosts suppress-only tier, so it suppresses by IDENTITY regardless of bytes — and
        // regardless of any operator heartbeatHostPatterns (now ignored; left empty here).
        val f = HeartbeatFilter(enabled = true, bytesThreshold = 2048)
        val r =
          row(mac1, 0, "api-push.push.apple.com", secs = 200, bytes = 50_000L, periodSeconds = 300)
        assertTrue(Presence.isHeartbeat(r, f)) &&
        assertTrue(Presence.totalMinutesByMac(List(r), Nil, f) == Map.empty[MacAddress, Int])
      },
      test(
        "#1525 heartbeatHostPatterns is no longer read — a host only listed there still counts",
      ) {
        // The operator's hand-curated list is deprecated/ignored (#1525). A non-infra host that is
        // ONLY in heartbeatHostPatterns is NOT suppressed; it counts like any foreground host.
        val f = HeartbeatFilter(
          enabled = true,
          bytesThreshold = 2048,
          heartbeatHostPatterns = List("*.example.com"),
        )
        val r = row(mac1, 0, "api.example.com", secs = 200, bytes = 50_000L, periodSeconds = 300)
        assertTrue(!Presence.isHeartbeat(r, f)) &&
        assertTrue(Presence.totalMinutesByMac(List(r), Nil, f) == Map(mac1 -> 5))
      },
      test("#1525 classifyRows surfaces infra:<pattern> for a suppress-only infra host") {
        val f    = HeartbeatFilter(enabled = true, bytesThreshold = 2048)
        val rows = List(
          row(mac1, 0, "courier.push.apple.com", secs = 200, bytes = 50_000L, periodSeconds = 300),
        )
        val out  = Presence.classifyRows(rows, f)
        assertTrue(out.head.classified == "heartbeat") &&
        assertTrue(out.head.reasons == List("infra:push.apple.com"))
      },
      test("#788 background infra only matches FQDNs, not IP literals") {
        val f = HeartbeatFilter(enabled = true, bytesThreshold = 0)
        val r = ipRow(mac1, 0, "17.57.146.1", secs = 200, bytes = 50_000L, periodSeconds = 300)
        assertTrue(!Presence.isHeartbeat(r, f))
      },
    ),
    suite("infra-host suppression (#1503)")(
      test(
        "canonical infra hosts drop from the daily cap even when absent from heartbeatHostPatterns",
      ) {
        // #1499/#1503: the unified canonical infra list (PolicyService.infraAllowHosts ≡ the
        // presence suppression set) drops device-level OS/telemetry/cert chatter even when the
        // operator's hand-curated `heartbeatHostPatterns` has drifted and misses it — the ~93%
        // background over-count leak. bytesThreshold is set so the byte floor does NOT catch
        // these chatty (>10 KB) rows; only host-IDENTITY suppression removes them.
        val f       = HeartbeatFilter(
          enabled = true,
          bytesThreshold = 10000,
          heartbeatHostPatterns = List("*.push.apple.com"), // stale: misses the infra below
        )
        // ~16 min continuous engaged session on a real (non-infra) app host.
        val genuine =
          List(appRow(mac1, 0, "www.tinkercad.com", periodSeconds = 960, bytes = 2_000_000L))
        // Five background-infra rows, far apart, each above the byte floor. Pre-fix these add
        // ~10 min of bogus presence; post-fix the canonical list suppresses all five.
        val infra   = List(
          appRow(mac1, 3600, "beacons3.gvt2.com", periodSeconds = 120, bytes = 80_000L),
          appRow(mac1, 7200, "gsp-ssl.ls.apple.com", periodSeconds = 120, bytes = 50_000L),
          appRow(
            mac1,
            10800,
            "safebrowsingohttpgateway.googleapis.com",
            periodSeconds = 120,
            bytes = 60_000L,
          ),
          appRow(mac1, 14400, "events.launchdarkly.com", periodSeconds = 120, bytes = 40_000L),
          appRow(mac1, 18000, "ocsp.digicert.com", periodSeconds = 120, bytes = 30_000L),
        )
        assertTrue(Presence.totalMinutesByMac(genuine ++ infra, Nil, f) == Map(mac1 -> 16))
      },
      test(
        "suppression keys on host identity, not bytes — a sparse low-byte genuine app still counts",
      ) {
        // #1446 guardrail: a request-driven app emits small/brief rows; those must NOT be dropped
        // for being small. With the byte floor disabled, the genuine app's full span still counts
        // while an infra host with an IDENTICAL low-byte/short profile is dropped by identity alone.
        val f = HeartbeatFilter(enabled = true, bytesThreshold = 0, heartbeatHostPatterns = Nil)
        val genuine  = appRow(mac1, 0, "app.tinkercad.com", periodSeconds = 600, bytes = 1500L)
        val infraRow = appRow(mac1, 0, "b1.nel.goog", periodSeconds = 600, bytes = 1500L)
        assertTrue(
          !Presence.isHeartbeat(genuine, f),
          Presence.isHeartbeat(infraRow, f),
          Presence.totalMinutesByMac(List(genuine), Nil, f) == Map(mac1 -> 10),
        )
      },
      test("isHeartbeat drops canonical infra regardless of the byte-filter toggle (filter Off)") {
        // Device infra is never engagement; it must not depend on the operator enabling the
        // byte filter — symmetric with PolicyService consuming the same list unconditionally.
        val r = appRow(mac1, 0, "r3---sn-abc.gvt2.com", periodSeconds = 120, bytes = 90_000L)
        assertTrue(Presence.isHeartbeat(r, HeartbeatFilter.Off))
      },
      test(
        "classifyRows surfaces infra:<pattern> for a canonical infra host even with filter Off",
      ) {
        val rows = List(appRow(mac1, 0, "beacons2.gvt2.com", periodSeconds = 120, bytes = 90_000L))
        val out  = Presence.classifyRows(rows, HeartbeatFilter.Off)
        assertTrue(
          out.head.classified == "heartbeat",
          out.head.reasons == List("infra:gvt2.com"),
        )
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
    // #1465: per-app presence is now the union of that app's sessions (the §4.1
    // session-stitch primitive), replacing the #715 byte-share weighting. Within
    // a device the sessions are unioned; across devices they combine by the
    // profile's existing `crossDeviceOverlapMode`. Heartbeat keepalives are
    // stripped *before* stitching (the byte-share weighting used to do that job).
    suite("proportionalHostSeconds — session-stitch per-app presence (#1465)")(
      test("a sparse per-app session reads its full span, not the sampled floor") {
        // youtube pinged every 60s with 10s windows over 5 minutes. The 50s
        // think-gaps are < N=120s, so the activity stitches into one continuous
        // [0, 310s) session — 5 min — instead of 6 × 10s sampled windows (1 min).
        val rows =
          (0 to 5).toList.map(i => appRow(mac1, i * 60L, "youtube.com", periodSeconds = 10))
        assertTrue(Presence.proportionalHostSeconds(rows) == Map(yt -> 310L)) &&
        assertTrue(Presence.proportionalHostMinutes(rows) == Map(yt -> 5))
      },
      test("same app on two devices double-counts under Sum, counts once under Dedup") {
        // Both devices on youtube for the same [0, 300s) window.
        val rows = List(
          appRow(mac1, 0L, "youtube.com", periodSeconds = 300),
          appRow(mac2, 0L, "youtube.com", periodSeconds = 300),
        )
        assertTrue(
          Presence.proportionalHostSeconds(rows, CrossDeviceOverlapMode.Sum) == Map(yt -> 600L),
        ) &&
        assertTrue(
          Presence.proportionalHostSeconds(rows, CrossDeviceOverlapMode.Dedup) == Map(yt -> 300L),
        )
      },
      test("N < R collapse guard: a misconfigured N is clamped to 2×R (§4.4 inv. 1)") {
        // Two youtube windows of R=60s, 60s apart (gap = 120−60 = 60). A misconfigured
        // N=10s would, unguarded, leave them as two 60s sessions; the shared #1464
        // effectiveGap clamp raises N to 2×R=120s ≥ 60, so the per-app surface bridges
        // them into one [0,180s) session — same rate-independence guard the daily cap
        // uses. R is read from period_seconds, never assumed to be 60s.
        val rows = List(
          appRow(mac1, 0L, "youtube.com", periodSeconds = 60),
          appRow(mac1, 120L, "youtube.com", periodSeconds = 60),
        )
        assertTrue(
          Presence.proportionalHostSeconds(rows, continuationSeconds = 10) == Map(yt -> 180L),
        )
      },
      test("a real idle gap beyond the guarded N is not bridged") {
        // Two 60s windows 600s apart: gap 540 > effectiveGap(120). They stay two
        // 60s sessions → 120s, not one bridged 660s span.
        val rows = List(
          appRow(mac1, 0L, "youtube.com", periodSeconds = 60),
          appRow(mac1, 600L, "youtube.com", periodSeconds = 60),
        )
        assertTrue(
          Presence.proportionalHostSeconds(rows, continuationSeconds = 120) == Map(yt -> 120L),
        )
      },
      test("keepalive-only window between two real sessions is bridged (§4.4 inv. 2)") {
        // youtube [0,60) · apns keepalive [60,120) (sub-threshold bytes) · youtube
        // [120,180). With the filter on the keepalive is stripped before stitching,
        // leaving youtube windows 60s apart (< N) which bridge into [0, 180s).
        val f    = HeartbeatFilter(enabled = true, bytesThreshold = 2048)
        val rows = List(
          appRow(mac1, 0L, "youtube.com", periodSeconds = 60),
          appRow(mac1, 60L, "apns.apple.com", periodSeconds = 60, bytes = 60L),
          appRow(mac1, 120L, "youtube.com", periodSeconds = 60),
        )
        assertTrue(Presence.proportionalHostSeconds(rows, filter = f) == Map(yt -> 180L))
      },
      test("a keepalive run longer than N does NOT bridge the two real sessions") {
        // Same shape, but the two youtube bursts are 190s apart (> N=120s), so
        // they stay separate: 60 + 60 = 120s, the keepalive span never counted.
        val f    = HeartbeatFilter(enabled = true, bytesThreshold = 2048)
        val rows = List(
          appRow(mac1, 0L, "youtube.com", periodSeconds = 60),
          appRow(mac1, 60L, "apns.apple.com", periodSeconds = 190, bytes = 60L),
          appRow(mac1, 250L, "youtube.com", periodSeconds = 60),
        )
        assertTrue(Presence.proportionalHostSeconds(rows, filter = f) == Map(yt -> 120L))
      },
      test("ten polling hosts + one heavy host: the heartbeat filter removes the pollers") {
        // The #715 byte-share weighting is gone; the heartbeat filter is now what
        // strips chatty keepalives from the per-host surface. With the filter on,
        // only the heavy real-traffic host survives — at its full session span
        // (12 contiguous 5-min windows → one 60-min session).
        val pollers = (0 until 10).map(i => s"poll-$i.example.com").toList
        val rows    = (0 until 12).toList.flatMap { b =>
          val heavyRow =
            appRow(mac1, b * 300L, "youtube.com", periodSeconds = 300, bytes = 5_000_000L)
          val pollRows =
            pollers.map(p => appRow(mac1, b * 300L, p, periodSeconds = 300, bytes = 200L))
          heavyRow :: pollRows
        }
        val f       = HeartbeatFilter(enabled = true, bytesThreshold = 2048)
        assertTrue(Presence.proportionalHostMinutes(rows, filter = f) == Map(yt -> 60))
      },
      test("with no devices/rows the per-app surface is empty") {
        assertTrue(Presence.proportionalHostSeconds(Nil) == Map.empty[HostId, Long])
      },
    ),
    suite("heartbeat filter on per-host / per-site surfaces (#1465)")(
      test("hostMinutes drops heartbeat rows when the filter is on") {
        val f    = HeartbeatFilter(enabled = true, bytesThreshold = 2048)
        val rows = List(
          appRow(mac1, 0L, "youtube.com", periodSeconds = 60),
          appRow(mac1, 0L, "apns.apple.com", periodSeconds = 60, bytes = 60L),
        )
        assertTrue(Presence.hostMinutes(rows, f) == Map(yt -> 1)) &&
        // filter off: the keepalive host still shows (legacy behaviour preserved).
        assertTrue(Presence.hostMinutes(rows).keySet.size == 2)
      },
      test("patternMinutesByMac drops heartbeat rows when the filter is on") {
        val f    = HeartbeatFilter(enabled = true, bytesThreshold = 2048)
        val rows = List(appRow(mac1, 0L, "apns.apple.com", periodSeconds = 60, bytes = 60L))
        assertTrue(
          Presence
            .patternMinutesByMac(rows, List("*.apple.com"), f) == Map
            .empty[(MacAddress, String), Int],
        ) &&
        // filter off: the row still counts toward the per-site total.
        assertTrue(
          Presence.patternMinutesByMac(rows, List("*.apple.com")) == Map((mac1, "*.apple.com") -> 1),
        )
      },
    ),
    // ── #1492: hour-clipped decomposition for the presence-based usage graph ──
    suite("#1492 hour-clip + span surfaces")(
      test("secondsByLocalHour splits a span across the hour boundary and sums to its length") {
        val zone   = java.time.ZoneId.of("UTC")
        // [00:59:30, 01:00:30) — 30s in hour 0, 30s in hour 1.
        val span   = Presence.Span(
          base.plusSeconds(3570).getEpochSecond,
          base.plusSeconds(3630).getEpochSecond,
        )
        val byHour = Presence.secondsByLocalHour(List(span), baseDate, zone)
        assertTrue(byHour.getOrElse(0, 0L) == 30L) &&
        assertTrue(byHour.getOrElse(1, 0L) == 30L) &&
        assertTrue(byHour.values.sum == 60L)
      },
      test("deviceSessionSpans union reconciles with totalSecondsByMac (the headline total)") {
        val rows     = List(
          appRow(mac1, 0L, "youtube.com", periodSeconds = 300),
          appRow(mac1, 300L, "khanacademy.org", periodSeconds = 300),
          appRow(mac2, 0L, "youtube.com", periodSeconds = 300),
        )
        val viaSpans = Presence
          .deviceSessionSpans(rows, Nil)
          .view
          .mapValues(Presence.unionSeconds)
          .toMap
        assertTrue(viaSpans == Presence.totalSecondsByMac(rows, Nil))
      },
      test("hostSessionSpans seconds reconcile with proportionalHostSeconds (Sum and Dedup)") {
        // Same host on two devices in the same window: Sum double-counts, Dedup unions.
        val rows          = List(
          appRow(mac1, 0L, "youtube.com", periodSeconds = 300),
          appRow(mac2, 0L, "youtube.com", periodSeconds = 300),
        )
        val sumViaSpans   = Presence
          .hostSessionSpans(rows, CrossDeviceOverlapMode.Sum)
          .view
          .mapValues(_.iterator.map(_.seconds).sum)
          .toMap
        val dedupViaSpans = Presence
          .hostSessionSpans(rows, CrossDeviceOverlapMode.Dedup)
          .view
          .mapValues(Presence.unionSeconds)
          .toMap
        assertTrue(
          sumViaSpans == Presence.proportionalHostSeconds(rows, CrossDeviceOverlapMode.Sum),
        ) &&
        assertTrue(
          dedupViaSpans == Presence.proportionalHostSeconds(rows, CrossDeviceOverlapMode.Dedup),
        ) &&
        // Sum = 600s (both devices), Dedup = 300s (overlap counts once).
        assertTrue(sumViaSpans.getOrElse(yt, 0L) == 600L) &&
        assertTrue(dedupViaSpans.getOrElse(yt, 0L) == 300L)
      },
    ),
  )
}
