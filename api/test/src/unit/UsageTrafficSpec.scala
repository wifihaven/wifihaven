package wifihaven.api.unit

import wifihaven.api.usage.{TrafficUsageDbRow, UsageTraffic}
import wifihaven.shared.types.*
import zio.test.*

import java.time.{Duration, Instant, ZoneOffset}

/**
 * #901: pin Traffic Usage aggregation stride. Regression for "10m bucket shows window starts 20
 * minutes apart, not 10". Tests use a deterministic raw-row layout (router cadence of 5 min,
 * period_starts aligned to UTC minute boundaries) so the floor + group step is unambiguous.
 */
object UsageTrafficSpec extends ZIOSpecDefault {

  private val UTC  = ZoneOffset.UTC
  private val mac1 = MacAddress.unsafe("aa:bb:cc:dd:ee:01")
  private val host = HostId.Fqdn(Hostname.unsafe("youtube.com"))

  // Anchor at an arbitrary 10m + 12h + 1d aligned instant so every sub-day floor
  // produces the same epoch base.
  private val base = Instant.parse("2026-05-13T00:00:00Z")

  private def row(periodStart: Instant): TrafficUsageDbRow =
    TrafficUsageDbRow(
      mac = mac1,
      host = host,
      periodStart = periodStart,
      periodEnd = periodStart.plusSeconds(300),
      activeSeconds = 60,
      bytesIn = 1000L,
      bytesOut = 200L,
    )

  override def spec = suite("UsageTraffic")(
    test("floorTo TenMin aligns to 10-minute UTC boundaries") {
      val cases = List(
        Instant.parse("2026-05-13T21:10:00Z") -> Instant.parse("2026-05-13T21:10:00Z"),
        Instant.parse("2026-05-13T21:10:42Z") -> Instant.parse("2026-05-13T21:10:00Z"),
        Instant.parse("2026-05-13T21:15:00Z") -> Instant.parse("2026-05-13T21:10:00Z"),
        Instant.parse("2026-05-13T21:19:59Z") -> Instant.parse("2026-05-13T21:10:00Z"),
        Instant.parse("2026-05-13T21:20:00Z") -> Instant.parse("2026-05-13T21:20:00Z"),
        Instant.parse("2026-05-13T21:25:00Z") -> Instant.parse("2026-05-13T21:20:00Z"),
        Instant.parse("2026-05-13T21:30:00Z") -> Instant.parse("2026-05-13T21:30:00Z"),
      )
      assertTrue(cases.forall { case (in, exp) =>
        UsageTraffic.floorTo(in, UsageTraffic.Bucket.TenMin, UTC) == exp
      })
    },
    test("buildAggregate(TenMin) emits one row per 10m window for 5-min-stride input") {
      // Twelve raw rows spaced 5 minutes apart → six contiguous 10-minute windows.
      val starts = (0 until 12).map(i => base.plusSeconds(i * 300L)).toList
      val rows   = starts.map(row)
      val out    = UsageTraffic.buildAggregate(
        rows = rows,
        bucket = UsageTraffic.Bucket.TenMin,
        zone = UTC,
        groupBy = Set(UsageTraffic.GroupBy.Domain),
        deviceByMac = Map.empty,
        profileNameById = Map.empty,
      )

      val windowStarts = out.map(_.windowStart).distinct
      val expected     = (0 until 6).map(i => base.plusSeconds(i * 600L).toString).toList

      // Stride between consecutive windows must be exactly 10 minutes — never 20.
      val sortedAsc      = windowStarts.map(Instant.parse).sorted
      val pairwiseDeltas = sortedAsc
        .sliding(2)
        .collect { case List(a, b) =>
          Duration.between(a, b).toMinutes
        }
        .toList

      assertTrue(out.length == 6) &&
      assertTrue(windowStarts.sorted == expected.sorted) &&
      assertTrue(pairwiseDeltas == List.fill(5)(10L))
    },
    test("buildAggregate(TenMin) windowEnd is windowStart + 10 minutes") {
      val rows = List(row(base), row(base.plusSeconds(120L)), row(base.plusSeconds(540L)))
      val out  = UsageTraffic.buildAggregate(
        rows = rows,
        bucket = UsageTraffic.Bucket.TenMin,
        zone = UTC,
        groupBy = Set(UsageTraffic.GroupBy.Domain),
        deviceByMac = Map.empty,
        profileNameById = Map.empty,
      )
      assertTrue(out.length == 1) &&
      assertTrue(out.head.windowStart == base.toString) &&
      assertTrue(out.head.windowEnd == base.plusSeconds(600L).toString)
    },
    test("#1526: unmatched hosts each become their own single-host app (no 'Other' bucket)") {
      // Two distinct hosts, neither in any registered app. Under the old model
      // both rows would collapse into one synthetic `__other__` row; under the
      // app-focused model each unmatched host is its own single-host app, so
      // grouping by app must produce two distinct rows keyed by host.
      val hostA    = HostId.Fqdn(Hostname.unsafe("example-a.com"))
      val hostB    = HostId.Fqdn(Hostname.unsafe("example-b.com"))
      val rows     = List(
        TrafficUsageDbRow(mac1, hostA, base, base.plusSeconds(300), 60, 1000L, 200L),
        TrafficUsageDbRow(mac1, hostB, base, base.plusSeconds(300), 60, 1000L, 200L),
      )
      val out      = UsageTraffic.buildAggregate(
        rows = rows,
        bucket = UsageTraffic.Bucket.TenMin,
        zone = UTC,
        groupBy = Set(UsageTraffic.GroupBy.App),
        deviceByMac = Map.empty,
        profileNameById = Map.empty,
        appsByHost = Map.empty, // no apps registered
      )
      val appSlugs = out.flatMap(_.groups.get("app")).toSet
      // Two unmatched hosts → two single-host apps, NOT one shared bucket.
      assertTrue(out.length == 2) &&
      assertTrue(appSlugs.size == 2) &&
      assertTrue(!appSlugs.contains("__other__")) &&
      assertTrue(appSlugs == Set(hostA.value, hostB.value))
    },
    test("floorTo and stepOf agree for each sub-day bucket (no 2× stride anywhere)") {
      // For every sub-day display bucket: flooring an instant inside the bucket should
      // never produce a window wider than `stepOf(bucket)` — i.e. `start +
      // step` must be strictly after the original instant.
      val sample  = Instant.parse("2026-05-13T07:23:17Z")
      val buckets = List(
        UsageTraffic.Bucket.OneMin,
        UsageTraffic.Bucket.TenMin,
        UsageTraffic.Bucket.OneHour,
      )
      assertTrue(buckets.forall { b =>
        val start = UsageTraffic.floorTo(sample, b, UTC)
        val step  = UsageTraffic.stepOf(b)
        !start.isAfter(sample) && start.plus(step).isAfter(sample)
      })
    },
    // #2018/#2020 PINNING: `raw` must derive its window from the row's REAL report period
    // `[periodStart, periodEnd)` (the agent's `usage_report_interval`, data-derived) — NEVER a
    // synthetic fixed grid. Seed two DIFFERENT, non-5-min-aligned periods (37 s and 90 s) and
    // assert the aggregated raw window equals each row's actual period and the derived B/s equals
    // bytes / span. This FAILS the moment anyone re-hardcodes a fixed raw window (e.g. `% 300` /
    // `ofMinutes(5)`), which is exactly how #2018 regressed.
    test(
      "#2018: raw window equals the row's real report period (37 s and 90 s), B/s = bytes/span",
    ) {
      // Periods deliberately NOT aligned to any 5-min boundary so a synthetic floor would diverge.
      val p1Start = Instant.parse("2026-05-13T00:01:13Z")
      val p1End   = p1Start.plusSeconds(37)
      val p2Start = Instant.parse("2026-05-13T00:07:41Z")
      val p2End   = p2Start.plusSeconds(90)

      val row1 = TrafficUsageDbRow(mac1, host, p1Start, p1End, 37, 3700L, 740L)
      val row2 = TrafficUsageDbRow(mac1, host, p2Start, p2End, 90, 9000L, 1800L)

      def aggOf(r: TrafficUsageDbRow) = UsageTraffic
        .buildAggregate(
          rows = List(r),
          bucket = UsageTraffic.Bucket.Raw,
          zone = UTC,
          groupBy = Set(UsageTraffic.GroupBy.Domain),
          deviceByMac = Map.empty,
          profileNameById = Map.empty,
        )
        .head

      val a1 = aggOf(row1)
      val a2 = aggOf(row2)

      def spanSeconds(windowStart: String, windowEnd: String): Long =
        Duration.between(Instant.parse(windowStart), Instant.parse(windowEnd)).getSeconds

      assertTrue(a1.windowStart == p1Start.toString) &&
      assertTrue(a1.windowEnd == p1End.toString) &&
      assertTrue(spanSeconds(a1.windowStart, a1.windowEnd) == 37L) &&
      // derived rate = (bytesIn + bytesOut) / span
      assertTrue(
        (a1.totalBytesIn + a1.totalBytesOut) / spanSeconds(a1.windowStart, a1.windowEnd) == 120L,
      ) &&
      assertTrue(a2.windowStart == p2Start.toString) &&
      assertTrue(a2.windowEnd == p2End.toString) &&
      assertTrue(spanSeconds(a2.windowStart, a2.windowEnd) == 90L) &&
      assertTrue(
        (a2.totalBytesIn + a2.totalBytesOut) / spanSeconds(a2.windowStart, a2.windowEnd) == 120L,
      )
    },
  )
}
