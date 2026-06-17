package wifihaven.shared

import zio.test.*

// #1262: the shared bucket->storage-grain classification both usage-series
// endpoints route through. Keyed on the requested bucket alone — the time
// range is never an input — so the two endpoints can't disagree about which
// tier serves a width.
object BucketPolicySpec extends ZIOSpecDefault {

  private def grain(code: String, expected: BucketGrain) =
    test(s"$code -> $expected") {
      assertTrue(BucketPolicy.grainForBucket(code) == expected)
    }

  private def window(hours: Long, expected: BucketGrain) =
    test(s"${hours}h -> $expected") {
      assertTrue(BucketPolicy.windowGrain(hours) == expected)
    }

  def spec = suite("BucketPolicy")(
    suite("grainForBucket")(
      grain("raw", BucketGrain.Raw),
      grain("1m", BucketGrain.Raw),
      grain("10m", BucketGrain.Raw),
      grain("1h", BucketGrain.Hourly),
      grain("12h", BucketGrain.Hourly),
      grain("1d", BucketGrain.Daily),
      grain("1w", BucketGrain.Daily),
      // Unknown/finer codes fall back to Raw — the only grain that can serve an
      // arbitrary fine width.
      grain("5m", BucketGrain.Raw),
      grain("", BucketGrain.Raw),
    ),
    // #1744: the cost-preference half of the tier picker — both
    // /api/usage/traffic and /api/connection-events/series route through here.
    // Boundary cases pin the thresholds; both adjacent grains exercised.
    suite("windowGrain")(
      window(1L, BucketGrain.Raw),
      window(24L, BucketGrain.Raw),
      window(25L, BucketGrain.Hourly),
      window(14L * 24L, BucketGrain.Hourly),
      window(14L * 24L + 1L, BucketGrain.Daily),
      window(365L * 24L, BucketGrain.Daily),
    ),
    suite("rank")(
      test("monotone fine→coarse") {
        assertTrue(
          BucketPolicy.rank(BucketGrain.Raw) < BucketPolicy.rank(BucketGrain.Hourly),
          BucketPolicy.rank(BucketGrain.Hourly) < BucketPolicy.rank(BucketGrain.Daily),
        )
      },
    ),
  )
}
