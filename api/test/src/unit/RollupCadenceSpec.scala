package wifihaven.api.unit

import wifihaven.api.usage.{RollupJobs, TimeUsedRollupJob}
import zio.test.*

import java.time.Duration

/**
 * #1230: the rollup fibers re-aggregate on cadences far tighter than the reads they back require,
 * dominating DB load on the 256 MB prod Postgres. `pickTier` reads ≤24h windows from raw
 * `traffic_reports`, 24h–14d from `traffic_hourly`, >14d from `traffic_daily`, so a rolled bucket
 * is not read until it crosses its tier threshold — the only freshness requirement is "rolled at
 * least once before that". These tests pin the lowered cadences and, crucially, the self-heal
 * invariant that guards them: each fiber's lookback window must be at least its tick interval, so a
 * single missed tick is re-covered by the next one. Widening a cadence without widening the
 * lookback to match would silently drop buckets on a missed tick.
 */
object RollupCadenceSpec extends ZIOSpecDefault {

  def spec = suite("rollup cadences (#1230)")(
    test("hourly fiber ticks hourly, not every 5 minutes") {
      assertTrue(RollupJobs.HourlyInterval == Duration.ofHours(1))
    },
    test("daily fiber ticks daily, not hourly") {
      assertTrue(RollupJobs.DailyInterval == Duration.ofHours(24))
    },
    test("time_used fiber ticks every 15 minutes, not every 3") {
      assertTrue(TimeUsedRollupJob.Interval == Duration.ofMinutes(15))
    },
    test("hourly lookback covers a missed tick (lookback >= interval)") {
      assertTrue(RollupJobs.HourlyLookback.compareTo(RollupJobs.HourlyInterval) >= 0)
    },
    test("daily lookback covers a missed tick (lookback spans >= one interval)") {
      // DailyLookback is a Period of whole days; the daily interval is 24h, so the lookback must
      // span at least 2 days for a missed daily tick to self-heal on the next one.
      assertTrue(RollupJobs.DailyLookback.getDays >= 2)
    },
  )
}
