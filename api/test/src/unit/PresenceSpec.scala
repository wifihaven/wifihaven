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
    suite("patternGroupMinutesForProfile (#1505 + #1504)")(
      test("apex + off-domain asset host in one group aggregate into one budget") {
        // The app's apex and an off-domain CDN host, hit in adjacent windows, session-stitch into
        // the app total (engaged wall-clock across the whole host-set).
        val rows = List(
          row(mac1, 0, "www.mathacademy.com"),
          row(mac1, 1, "assets.mathacademy-cdn.example"),
        )
        assertTrue(
          Presence.patternGroupMinutesForProfile(
            rows,
            List("app:math-academy" -> List("mathacademy.com", "mathacademy-cdn.example")),
          ) == Map("app:math-academy" -> 10),
        )
      },
      test("overlapping windows on two of the group's hosts are unioned once (no double count)") {
        val rows = List(
          row(mac1, 0, "www.mathacademy.com"),
          row(mac1, 0, "assets.mathacademy-cdn.example"),
        )
        assertTrue(
          Presence.patternGroupMinutesForProfile(
            rows,
            List("app:math-academy" -> List("mathacademy.com", "mathacademy-cdn.example")),
          ) == Map("app:math-academy" -> 5),
        )
      },
      test(
        "sparse request-driven activity reads engaged minutes, not the bucket-max floor (#1504)",
      ) {
        // Five contiguous 60s windows across the app's two hosts, each sampling only the 10s floor.
        // Bucket-max would credit ~0; session-stitch unions them into one [0,300] = 5 min app total.
        val rows = List(
          sparseRow(mac1, 0, "www.mathacademy.com"),
          sparseRow(mac1, 60, "assets.mathacademy-cdn.example"),
          sparseRow(mac1, 120, "www.mathacademy.com"),
          sparseRow(mac1, 180, "assets.mathacademy-cdn.example"),
          sparseRow(mac1, 240, "www.mathacademy.com"),
        )
        assertTrue(
          Presence.patternGroupMinutesForProfile(
            rows,
            List("app:math-academy" -> List("mathacademy.com", "mathacademy-cdn.example")),
          ) == Map("app:math-academy" -> 5),
        )
      },
      test("distinct groups accumulate independently; one host may count toward more than one") {
        val rows = List(row(mac1, 0, "www.youtube.com"))
        assertTrue(
          Presence.patternGroupMinutesForProfile(
            rows,
            List(
              "app:youtube" -> List("youtube.com"),
              "app:video"   -> List("youtube.com"),
            ),
          ) == Map("app:youtube" -> 5, "app:video" -> 5),
        )
      },
      test("a group whose hosts the rows never match doesn't appear") {
        val rows = List(row(mac1, 0, "google.com"))
        assertTrue(
          Presence.patternGroupMinutesForProfile(
            rows,
            List("app:math-academy" -> List("mathacademy.com")),
          ) == Map.empty,
        )
      },
      test("Dedup unions a group's sessions across devices; Sum adds them") {
        // Same app, two devices, fully overlapping windows. Sum double-counts (10), Dedup unions (5).
        val rows   = List(
          row(mac1, 0, "www.mathacademy.com"),
          row(mac2, 0, "assets.mathacademy-cdn.example"),
        )
        val groups = List("app:math-academy" -> List("mathacademy.com", "mathacademy-cdn.example"))
        assertTrue(
          Presence.patternGroupMinutesForProfile(
            rows,
            groups,
            CrossDeviceOverlapMode.Sum,
          ) == Map("app:math-academy" -> 10),
        ) &&
        assertTrue(
          Presence.patternGroupMinutesForProfile(
            rows,
            groups,
            CrossDeviceOverlapMode.Dedup,
          ) == Map("app:math-academy" -> 5),
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
    // ── #1514: app-aware cross-host gap-bridged presence ──────────────────────
    //
    // appSecondsForProfile unions the session-stitch spans across the WHOLE app
    // host-set as one stream, so an idle gap < N between DIFFERENT hosts of the
    // same app bridges (the apex + off-domain assets of #1505 count once). This
    // is the span/union analogue of patternSecondsForProfile and the single
    // source #1516/#1517/#1515 build on.
    suite("appSecondsForProfile / appSpansForProfile (#1514)")(
      test("two hosts of one app in adjacent windows with an idle gap < N bridge into one span") {
        // mathacademy.com [0,60) · gap 60s · mathacademy-cdn.example [120,180).
        // effectiveGap = max(120, 2×60) = 120 ≥ 60, so the two DIFFERENT-host
        // windows bridge across the gap into one engaged [0,180) = 180s span —
        // NOT two 60s sessions (120s). The bridged gap is counted once, and the
        // short windows are not double-counted.
        val group = "app:math-academy" -> List("mathacademy.com", "mathacademy-cdn.example")
        val rows  = List(
          appRow(mac1, 0L, "www.mathacademy.com", periodSeconds = 60),
          appRow(mac1, 120L, "assets.mathacademy-cdn.example", periodSeconds = 60),
        )
        assertTrue(
          Presence.appSecondsForProfile(rows, List(group)) == Map("app:math-academy" -> 180L),
        ) &&
        assertTrue(
          Presence.appMinutesForProfile(rows, List(group)) == Map("app:math-academy" -> 3),
        )
      },
      test("an idle gap > N between two of the app's hosts is NOT bridged") {
        // Same two hosts but 600s apart: gap 540 > effectiveGap(120). They stay
        // two 60s sessions → 120s, not one bridged 660s span.
        val group = "app:math-academy" -> List("mathacademy.com", "mathacademy-cdn.example")
        val rows  = List(
          appRow(mac1, 0L, "www.mathacademy.com", periodSeconds = 60),
          appRow(mac1, 600L, "assets.mathacademy-cdn.example", periodSeconds = 60),
        )
        assertTrue(
          Presence.appSecondsForProfile(rows, List(group)) == Map("app:math-academy" -> 120L),
        )
      },
      test("hosts of different apps do NOT bridge into each other") {
        // app A's host [0,60) and app B's host [120,180): within-app each would
        // bridge a 60s gap, but they belong to DIFFERENT groups, so each app is
        // its own [.,60) = 60s session — the gap is never bridged across apps.
        val rows   = List(
          appRow(mac1, 0L, "www.mathacademy.com", periodSeconds = 60),
          appRow(mac1, 120L, "www.khanacademy.org", periodSeconds = 60),
        )
        val groups = List(
          "app:math-academy" -> List("mathacademy.com"),
          "app:khan"         -> List("khanacademy.org"),
        )
        assertTrue(
          Presence.appSecondsForProfile(rows, groups) ==
            Map("app:math-academy" -> 60L, "app:khan" -> 60L),
        )
      },
      test("no-match / empty host-set / no rows → absent (0)") {
        val rows = List(appRow(mac1, 0L, "www.google.com", periodSeconds = 60))
        assertTrue(
          Presence.appSecondsForProfile(rows, List("app:math" -> List("mathacademy.com"))) ==
            Map.empty[String, Long],
        ) &&
        assertTrue(
          Presence.appSecondsForProfile(rows, List("app:empty" -> Nil)) == Map.empty[String, Long],
        ) &&
        assertTrue(
          Presence.appSecondsForProfile(Nil, List("app:math" -> List("mathacademy.com"))) ==
            Map.empty[String, Long],
        )
      },
      test("single-host app reconciles with proportionalHostSeconds (no regression)") {
        // A single-host app stitched as a group equals the existing per-host
        // engaged seconds for that host — the union-across-host-set degenerates
        // to the per-host session when the set has one host.
        val rows    =
          (0 to 5).toList.map(i => appRow(mac1, i * 60L, "youtube.com", periodSeconds = 10))
        val perHost = Presence.proportionalHostSeconds(rows).getOrElse(yt, 0L)
        assertTrue(
          Presence.appSecondsForProfile(rows, List("app:youtube" -> List("youtube.com"))) ==
            Map("app:youtube" -> perHost),
        ) &&
        assertTrue(perHost == 310L)
      },
      test("Sum vs Dedup across two devices behaves like usedSecondsForProfile") {
        // Same app, two devices, fully overlapping [0,300): Sum adds (600), Dedup
        // unions (300) — the same cross-device contract the daily total uses.
        val group = "app:math-academy" -> List("mathacademy.com", "mathacademy-cdn.example")
        val rows  = List(
          appRow(mac1, 0L, "www.mathacademy.com", periodSeconds = 300),
          appRow(mac2, 0L, "assets.mathacademy-cdn.example", periodSeconds = 300),
        )
        assertTrue(
          Presence.appSecondsForProfile(rows, List(group), CrossDeviceOverlapMode.Sum) ==
            Map("app:math-academy" -> 600L),
        ) &&
        assertTrue(
          Presence.appSecondsForProfile(rows, List(group), CrossDeviceOverlapMode.Dedup) ==
            Map("app:math-academy" -> 300L),
        )
      },
      test("appSpansForProfile seconds reconcile with appSecondsForProfile (Sum and Dedup)") {
        // Two devices on the app in the same window: Sum keeps each device's
        // spans (sum double-counts overlap), Dedup unions them. Summing the
        // span seconds reproduces appSecondsForProfile under both modes, so the
        // hour-clipped #1517 breakdown stays consistent with the headline.
        val group      = "app:math-academy" -> List("mathacademy.com", "mathacademy-cdn.example")
        val rows       = List(
          appRow(mac1, 0L, "www.mathacademy.com", periodSeconds = 300),
          appRow(mac2, 0L, "assets.mathacademy-cdn.example", periodSeconds = 300),
        )
        val sumSpans   = Presence.appSpansForProfile(rows, List(group), CrossDeviceOverlapMode.Sum)
        val dedupSpans =
          Presence.appSpansForProfile(rows, List(group), CrossDeviceOverlapMode.Dedup)
        val sumSeconds = sumSpans.view.mapValues(_.iterator.map(_.seconds).sum).toMap
        val dedupSeconds = dedupSpans.view.mapValues(Presence.unionSeconds).toMap
        assertTrue(
          sumSeconds == Presence.appSecondsForProfile(rows, List(group), CrossDeviceOverlapMode.Sum),
        ) &&
        assertTrue(
          dedupSeconds == Presence
            .appSecondsForProfile(rows, List(group), CrossDeviceOverlapMode.Dedup),
        ) &&
        assertTrue(sumSeconds.getOrElse("app:math-academy", 0L) == 600L) &&
        assertTrue(dedupSeconds.getOrElse("app:math-academy", 0L) == 300L)
      },
      test("appSpansForProfile bridges across hosts within a device") {
        // The span form bridges the same cross-host idle gap appSecondsForProfile
        // does: one [0,180) span, not two.
        val group = "app:math-academy" -> List("mathacademy.com", "mathacademy-cdn.example")
        val rows  = List(
          appRow(mac1, 0L, "www.mathacademy.com", periodSeconds = 60),
          appRow(mac1, 120L, "assets.mathacademy-cdn.example", periodSeconds = 60),
        )
        val spans =
          Presence.appSpansForProfile(rows, List(group)).getOrElse("app:math-academy", Nil)
        assertTrue(spans.map(_.seconds).sum == 180L) &&
        assertTrue(spans.size == 1)
      },
      test("heartbeat rows are stripped before stitching (a keepalive can't bridge across N)") {
        // mathacademy [0,60) · apns keepalive [60,250) (190s, sub-threshold) ·
        // cdn [250,310). With the filter on the keepalive is dropped, leaving the
        // two real windows 190s apart (> N=120) — they do NOT bridge: 60+60=120s.
        val f     = HeartbeatFilter(enabled = true, bytesThreshold = 2048)
        val group = "app:math-academy" -> List("mathacademy.com", "mathacademy-cdn.example")
        val rows  = List(
          appRow(mac1, 0L, "www.mathacademy.com", periodSeconds = 60),
          appRow(mac1, 60L, "apns.apple.com", periodSeconds = 190, bytes = 60L),
          appRow(mac1, 250L, "assets.mathacademy-cdn.example", periodSeconds = 60),
        )
        assertTrue(
          Presence.appSecondsForProfile(rows, List(group), filter = f) ==
            Map("app:math-academy" -> 120L),
        )
      },
    ),
    // ── #1506: app-aware background suppression ───────────────────────────────
    //
    // Attribution BEATS suppression. A host that is BOTH a background/infra
    // pattern AND a member of an ACTIVE app's host-set must NOT be dropped as
    // device infra — it attributes to the app and COUNTS. Only hosts attributed
    // to no active app fall through to background suppression, so device-level
    // infra (connectivity probes, OCSP, telemetry) with no app behind it stays
    // suppressed exactly as before (#1499 over-count protection preserved).
    suite("app-aware background suppression (#1506)")(
      test("a background host that IS in an active app's host-set is NOT a heartbeat") {
        // clientservices.googleapis.com is canonical device infra (suppressed by
        // identity). But if an app's host-set claims it, attribution wins: the
        // app-aware predicate does not classify it as a heartbeat.
        val f       = HeartbeatFilter(enabled = true, bytesThreshold = 10000)
        val r       =
          appRow(mac1, 0L, "clientservices.googleapis.com", periodSeconds = 300, bytes = 90_000L)
        val appPats = List("clientservices.googleapis.com")
        assertTrue(
          Presence.isHeartbeat(r, f, appPats) == false,
          // With NO app attribution the same host is still suppressed (no regression).
          Presence.isHeartbeat(r, f, Nil) == true,
        )
      },
      test("app attribution beats the byte floor too (a low-byte app host still counts)") {
        // The #1446 guardrail extended: a sparse, low-byte request to a host the
        // app genuinely depends on must count even when the byte filter is on.
        val f       = HeartbeatFilter(enabled = true, bytesThreshold = 2048)
        val r       = row(mac1, 0, "dl.gvt2.com", secs = 60, bytes = 60L, periodSeconds = 60)
        val appPats = List("gvt2.com")
        assertTrue(
          Presence.isHeartbeat(r, f, appPats) == false,
          Presence.isHeartbeat(r, f, Nil) == true,
        )
      },
      test("device-level infra with NO app attribution stays suppressed") {
        // connectivitycheck.gstatic.com behind no active app → still infra.
        val f = HeartbeatFilter.Off
        val r = appRow(mac1, 0L, "connectivitycheck.gstatic.com", periodSeconds = 300)
        assertTrue(
          Presence.isHeartbeat(r, f, Nil) == true,
          // and an unrelated active app's host-set does not rescue it.
          Presence.isHeartbeat(r, f, List("youtube.com")) == true,
        )
      },
      test("daily total: an app's background asset host counts when app-attributed") {
        // www.fooapp.com [0,600) · dl.gvt2.com [600,1200): gvt2.com is canonical
        // background infra. Without app attribution it is suppressed → only the
        // 10-min genuine span counts. With the app's host-set carrying gvt2.com
        // the asset attributes to the app: the two adjacent spans union into
        // [0,1200) = 20 min.
        val f       = HeartbeatFilter.Off
        val genuine = appRow(mac1, 0L, "www.fooapp.com", periodSeconds = 600, bytes = 2_000_000L)
        val asset   = appRow(mac1, 600L, "dl.gvt2.com", periodSeconds = 600, bytes = 80_000L)
        val rows    = List(genuine, asset)
        assertTrue(
          Presence.totalMinutesByMac(rows, Nil, f) == Map(mac1 -> 10),
          Presence.totalMinutesByMac(rows, Nil, f, appHostPatterns = List("gvt2.com")) ==
            Map(mac1 -> 20),
        )
      },
      test("reconciliation: a multi-host app's suppressed asset now contributes to the app") {
        // The #1506 seam: an app whose off-domain asset host happens to be a
        // background/infra pattern. Pre-fix the asset is dropped as infra and the
        // app under-counts; post-fix the app-aware predicate (driven by the app's
        // OWN host-set) lets it attribute and bridge.
        //   www.fooapp.com [0,60) · gap 60s · dl.gvt2.com [120,180).
        // gvt2.com is background infra but it is part of this app's host-set, so
        // it counts and the cross-host gap < N bridges into one [0,180) = 180s.
        val group = "app:fooapp" -> List("fooapp.com", "gvt2.com")
        val rows  = List(
          appRow(mac1, 0L, "www.fooapp.com", periodSeconds = 60),
          appRow(mac1, 120L, "dl.gvt2.com", periodSeconds = 60),
        )
        assertTrue(
          Presence.appSecondsForProfile(rows, List(group)) == Map("app:fooapp" -> 180L),
        )
      },
      test(
        "#1499 prod replay: 61 unmatched infra hosts + 40s genuine app reads ~40s, not 22 min",
      ) {
        // Direct replay of the #1499 prod scenario (Kid device, 2026-06-06): the
        // dashboard showed `usedMins ≈ 22` against a 30-min cap while the only
        // *attributed* app (Math Academy) was 40 s. The operator's 2026-06-06
        // analysis attributed ~93 % of the counted rows to device-level infra
        // (gvt2/gvt3 beacons, *.ls.apple.com, OCSP responders, safe-browsing,
        // analytics SaaS) — all of which were missing from the (then) separate
        // `heartbeatHostPatterns` list while present on the (then) separate
        // `PolicyService.infraAllowHosts`. The unified `InfraHosts` source of
        // truth (#1503/#1525, consumed by both Policy *and* Presence) suppresses
        // these by host identity regardless of byte size.
        //
        // Seed: 61 distinct infra hosts spread across the day, each ~300 s and
        // far above the 10 KB byte floor (so byte-threshold suppression cannot
        // catch them — only host-identity suppression can). Plus the genuine
        // ~40 s Math Academy session. Pre-fix this would read ~22 min; post-fix
        // the canonical list drops all 61 → only the 40 s engaged span remains.
        // No "Other" bucket: every unmatched-to-app host is either attributed
        // to its own single-host app or suppressed as infra.
        val f         = HeartbeatFilter(enabled = true, bytesThreshold = 10000)
        val mathAcad  =
          List(appRow(mac1, 0L, "www.mathacademy.com", periodSeconds = 40, bytes = 360_000L))
        // 61 distinct infra subdomains, every one matched by the unified
        // InfraHosts list (canonical + suppressOnly).
        val infraFqdn = List(
          // gvt2.com / gvt3.com beacons (20 rows — ~36% of the prod sample)
          "beacons.gvt2.com",
          "beacons2.gvt2.com",
          "beacons3.gvt2.com",
          "beacons4.gvt2.com",
          "beacons5.gvt2.com",
          "r1---sn-abc.gvt2.com",
          "r2---sn-abc.gvt2.com",
          "r3---sn-abc.gvt2.com",
          "r4---sn-abc.gvt2.com",
          "r5---sn-abc.gvt2.com",
          "dl.gvt2.com",
          "redirector.gvt2.com",
          "update.gvt2.com",
          "beacons.gvt3.com",
          "beacons2.gvt3.com",
          "beacons3.gvt3.com",
          "beacons4.gvt3.com",
          "r1---sn-abc.gvt3.com",
          "redirector.gvt3.com",
          "update.gvt3.com",
          // Apple OS / location services / OCSP (14 rows — ~26% of the sample)
          "gsp-ssl.ls.apple.com",
          "gspe1-ssl.ls.apple.com",
          "gspe35-ssl.ls.apple.com",
          "gsp10-ssl.ls.apple.com",
          "configuration.ls.apple.com",
          "ocsp.apple.com",
          "ocsp2.apple.com",
          "crl.apple.com",
          "courier.push.apple.com",
          "1-courier.push.apple.com",
          "2-courier.push.apple.com",
          "init-p01st.push.apple.com",
          "time.apple.com",
          "gdmf.apple.com",
          // Google APIs/infra background (8 rows — ~15%)
          "clientservices.googleapis.com",
          "safebrowsingohttpgateway.googleapis.com",
          "safebrowsing.google.com",
          "b1.nel.goog",
          "b2.nel.goog",
          "ocsp.pki.goog",
          "ocsp.digicert.com",
          "captive.apple.com",
          // Analytics / SaaS telemetry (4 rows — ~7%)
          "events.launchdarkly.com",
          "cc-api-data.adobe.io",
          "app-analytics-services.com",
          "v1.app-analytics-services.com",
          // iCloud Private Relay + push/DNS infra (suppressOnly tier)
          "mask.icloud.com",
          "mask-h2.icloud.com",
          "mask-api.icloud.com",
          "a23-203-15-1.deploy.akadns.net",
          "e6858.dscx.akamaiedge.akadns.net",
          "courier.apple-dns.net",
          "apns.apple-dns.net",
          "rcs.telephony.goog",
          "mtalk.google.com",
          // Cert / connectivity probes
          "connectivitycheck.gstatic.com",
          "msftconnecttest.com",
          "www.msftconnecttest.com",
          "msftncsi.com",
          "www.msftncsi.com",
          // Misc Apple edge
          "g.aaplimg.com",
          "netcts.cdn-apple.com",
          "ess.apple.com",
        )
        // Sanity check: we built exactly the 61 infra hosts the prod sample
        // had ('Other' bucket size in the #1499 issue body). A miscount here
        // would invalidate the replay.
        val _         = assertTrue(infraFqdn.size == 61)
        val infra     = infraFqdn.zipWithIndex.map { case (h, i) =>
          // Spread evenly across the day, each window 300 s and 80 KB —
          // well above the 10 KB byte floor.
          appRow(mac1, 60L + i.toLong * 600L, h, periodSeconds = 300, bytes = 80_000L)
        }
        val rows      = mathAcad ++ infra
        // Headline daily total: 40 s genuine, every infra row suppressed.
        // (~0 minutes after floor-div; the regression would read ≥ 22.)
        assertTrue(
          Presence.totalSecondsByMac(rows, Nil, f) == Map(mac1 -> 40L),
          Presence.totalMinutesByMac(rows, Nil, f) == Map.empty[MacAddress, Int],
        )
      },
      test("an app whose host-set does NOT include the infra host still under-counts it") {
        // Control for the test above: the SAME infra row, but the app's host-set
        // does not claim gvt2.com. The asset is correctly suppressed as infra and
        // only the genuine apex window counts (60s) — attribution is keyed on the
        // app's declared host-set, not on mere co-occurrence.
        val group = "app:fooapp" -> List("fooapp.com")
        val rows  = List(
          appRow(mac1, 0L, "www.fooapp.com", periodSeconds = 60),
          appRow(mac1, 120L, "dl.gvt2.com", periodSeconds = 60),
        )
        assertTrue(
          Presence.appSecondsForProfile(rows, List(group)) == Map("app:fooapp" -> 60L),
        )
      },
    ),
    // ── #1666: phantom-engagement guard — per-app session activity anchor ─────
    //
    // A row whose host matches an active app's host-set attributes to that app
    // and bypasses the byte-floor heartbeat suppression (#1506). That is correct
    // at the ROW level (a real app's low-byte CDN poll must count when it's
    // genuinely part of a session), but at the SESSION level it lets a sparse
    // keepalive cadence — one ~80-byte poll every 60–90 s — stitch into a
    // continuous synthetic span via gap-bridging. Prima iPad accumulated 21
    // phantom Khan Academy minutes overnight this way (#1666). The fix:
    // require each stitched per-(mac, app) session to contain at least one
    // anchor row (bytes ≥ AppSessionAnchorBytes); pure-keepalive sessions are
    // dropped without breaking real usage, which always has at least one
    // substantive request above the anchor threshold.
    suite("phantom-engagement guard (#1666)")(
      test("4h of sparse low-byte polls on a real-app host produce ZERO credited seconds") {
        // The #1666 fixture: a row every 90 s for 4 hours on khanacademy.org,
        // each row 60 s wide and ~80 bytes (well below the 256-byte anchor).
        // Without the guard: gap (30 s) < effectiveGap (120 s) → every adjacent
        // pair bridges → one continuous span ≈ 14 400 s = 240 min (and even with
        // the byte floor, attribution-beats-suppression means none of these rows
        // can be classified as heartbeats). With the guard: no row clears the
        // anchor → session has no anchor → dropped → 0 s.
        val group = "app:khan" -> List("khanacademy.org", "kastatic.org", "kasandbox.org")
        // 80 bytes < AppSessionAnchorBytes (256) — no row anchors the session,
        // pinned against future threshold tuning so a bump > 80 still proves the guard.
        val rows  = (0 until 160).toList.map(i =>
          appRow(
            mac1,
            i.toLong * 90L,
            "www.khanacademy.org",
            periodSeconds = 60,
            bytes = 80L,
          ),
        )
        assertTrue(
          Presence.appSecondsForProfile(rows, List(group)) == Map.empty[String, Long],
        ) &&
        assertTrue(
          Presence.appMinutesForProfile(rows, List(group)) == Map.empty[String, Int],
        )
      },
      test("a legitimate 8-min sparse-but-real session still credits engagement") {
        // Positive control: one anchor row (50 KB article load at offset 0)
        // followed by sparse asset/poll rows of varying small byte counts every
        // ~30 s for 8 minutes. The first row anchors the session; the rest sit
        // within the idle gap of it and bridge correctly. The full span counts.
        val group  = "app:khan" -> List("khanacademy.org", "kastatic.org", "kasandbox.org")
        val anchor = appRow(mac1, 0L, "www.khanacademy.org", periodSeconds = 60, bytes = 50_000L)
        val sparse =
          (1 until 16).toList.map(i =>
            // Alternate apex and asset hosts so we also exercise the
            // cross-host bridge within the app's host-set.
            appRow(
              mac1,
              i.toLong * 30L,
              if (i % 2 == 0) "www.khanacademy.org" else "cdn.kastatic.org",
              periodSeconds = 60,
              bytes = 200L,
            ),
          )
        val rows   = anchor :: sparse
        // The stitched span runs [0, 30·15 + 60) = [0, 510): 510 s.
        assertTrue(
          Presence.appSecondsForProfile(rows, List(group)) == Map("app:khan" -> 510L),
        ) &&
        assertTrue(
          Presence.appMinutesForProfile(rows, List(group)) == Map("app:khan" -> 8),
        )
      },
      test("an anchored session retains the bridged low-byte tail (anchor is per-session)") {
        // The anchor predicate is per-session: once a substantive row anchors a
        // session, the surrounding low-byte rows within the gap still bridge.
        // Two anchors at 0 s and 240 s with a low-byte poll between them at 120 s:
        // the whole [0, 300) span counts as one session, not just the anchor windows.
        val group = "app:khan" -> List("khanacademy.org")
        val rows  = List(
          appRow(mac1, 0L, "www.khanacademy.org", periodSeconds = 60, bytes = 20_000L),
          appRow(mac1, 120L, "www.khanacademy.org", periodSeconds = 60, bytes = 80L),
          appRow(mac1, 240L, "www.khanacademy.org", periodSeconds = 60, bytes = 20_000L),
        )
        assertTrue(
          Presence.appSecondsForProfile(rows, List(group)) == Map("app:khan" -> 300L),
        )
      },
      test("two phantom stretches separated by one anchor → only the anchored session counts") {
        // Hour-long stretch of pure keepalives (00:00–01:00), then one anchor row
        // at 01:30 (gap > effectiveGap so it is its own session), then another
        // hour of pure keepalives (02:00–03:00, gap > effectiveGap from the anchor).
        // Only the anchored session (1 row, 60 s) credits; the two phantom
        // stretches are dropped.
        val group   = "app:khan" -> List("khanacademy.org")
        val phantom = (List.range(0, 60) ++ List.range(120, 180)).map(i =>
          appRow(
            mac1,
            i.toLong * 60L,
            "www.khanacademy.org",
            periodSeconds = 60,
            bytes = 80L,
          ),
        )
        val anchor  =
          appRow(mac1, 90L * 60L, "www.khanacademy.org", periodSeconds = 60, bytes = 50_000L)
        val rows    = anchor :: phantom
        assertTrue(
          Presence.appSecondsForProfile(rows, List(group)) == Map("app:khan" -> 60L),
        )
      },
    ),
    // ── #1676: phantom-suppression drop count observability ───────────────────
    //
    // The #1666 guard above silently drops un-anchored per-(mac, app) sessions.
    // appSpansForProfileWithDropCount exposes the count of sessions dropped so
    // an operator can rate-alert on threshold drift (#1676): too-aggressive ⇒
    // real sessions vanish; too-lax ⇒ phantom inflation returns.
    suite("appSpansForProfileWithDropCount (#1676)")(
      test("counts each un-anchored per-(mac, app) session as a drop") {
        // Two apps, one device:
        // - app:khan has 4 hours of sparse 80-byte polls → one un-anchored
        //   session → drop count 1, spans empty for that app.
        // - app:legit has one substantive request → spans non-empty, drop 0
        //   for that app.
        // Total drop count = 1.
        val groupKhan        = "app:khan"  -> List("khanacademy.org")
        val groupLegit       = "app:legit" -> List("legit.example")
        val phantom          = (0 until 160).toList.map(i =>
          appRow(mac1, i.toLong * 90L, "www.khanacademy.org", periodSeconds = 60, bytes = 80L),
        )
        val anchored         =
          appRow(mac1, 0L, "www.legit.example", periodSeconds = 60, bytes = 50_000L)
        val rows             = anchored :: phantom
        val (spans, dropped) =
          Presence.appSpansForProfileWithDropCount(rows, List(groupKhan, groupLegit))
        assertTrue(dropped == 1) &&
        assertTrue(!spans.contains("app:khan")) &&
        assertTrue(spans.get("app:legit").exists(_.nonEmpty))
      },
      test("an anchored session is not counted as a drop") {
        val group            = "app:khan" -> List("khanacademy.org")
        val rows             = List(
          appRow(mac1, 0L, "www.khanacademy.org", periodSeconds = 60, bytes = 50_000L),
        )
        val (spans, dropped) = Presence.appSpansForProfileWithDropCount(rows, List(group))
        assertTrue(dropped == 0) &&
        assertTrue(spans.get("app:khan").exists(_.nonEmpty))
      },
      test("drop count is per-(mac, app): two devices each with one phantom session count twice") {
        val group        = "app:khan" -> List("khanacademy.org")
        val phantom      = (mac: MacAddress) =>
          (0 until 160).toList.map(i =>
            appRow(mac, i.toLong * 90L, "www.khanacademy.org", periodSeconds = 60, bytes = 80L),
          )
        val rows         = phantom(mac1) ++ phantom(mac2)
        val (_, dropped) = Presence.appSpansForProfileWithDropCount(rows, List(group))
        assertTrue(dropped == 2)
      },
      test("appSpansForProfile delegates to the with-drop-count sibling") {
        // Single-source-of-truth: the no-drop-count alias is exactly the
        // spans projection of the sibling.
        val group      = "app:khan" -> List("khanacademy.org")
        val rows       = List(
          appRow(mac1, 0L, "www.khanacademy.org", periodSeconds = 60, bytes = 50_000L),
        )
        val (spans, _) = Presence.appSpansForProfileWithDropCount(rows, List(group))
        val aliasSpans = Presence.appSpansForProfile(rows, List(group))
        assertTrue(spans == aliasSpans)
      },
    ),
  )
}
