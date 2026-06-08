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
      val hostA = HostId.Fqdn(Hostname.unsafe("example-a.com"))
      val hostB = HostId.Fqdn(Hostname.unsafe("example-b.com"))
      val rows  = List(
        TrafficUsageDbRow(mac1, hostA, base, base.plusSeconds(300), 60, 1000L, 200L),
        TrafficUsageDbRow(mac1, hostB, base, base.plusSeconds(300), 60, 1000L, 200L),
      )
      val out   = UsageTraffic.buildAggregate(
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
      // For every sub-day bucket: flooring an instant inside the bucket should
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
  )
}
