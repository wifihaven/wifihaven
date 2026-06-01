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

  def spec = suite("BucketPolicy.grainForBucket")(
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
  )
}
