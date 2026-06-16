package wifihaven.shared

// #1262: on the usage-series endpoints, bucket width and time range are
// INDEPENDENT client choices — the range must never coarsen the bucket. The
// only range-independent decision is which storage grain can render a given
// bucket width without losing resolution:
//
//   - sub-hour buckets (raw / 1m / 10m) need the raw 5-min rows
//   - 1h / 12h buckets can be summed from the hourly rollup
//   - 1d / 1w buckets can be summed from the daily rollup
//
// Both /api/usage/traffic and (once #1265 lands its connection-events rollups)
// /api/connection-events/series classify their requested bucket through here,
// so the two can't disagree about which tier serves a width. This is the
// single shared mapping that keeps them from drifting — and it is keyed on the
// bucket, not the window.
enum BucketGrain(val wire: String) {
  case Raw    extends BucketGrain("raw")
  case Hourly extends BucketGrain("hourly")
  case Daily  extends BucketGrain("daily")
}

object BucketGrain {
  def fromWire(s: String): Option[BucketGrain] = values.find(_.wire == s)
}

object BucketPolicy {

  /**
   * Canonical list of bucket codes the API understands. Lives next to `grainForBucket` so the SPA
   * can read both pieces from a single API surface (#1743) without re-listing buckets out-of-band.
   */
  val allBuckets: List[String] = List("raw", "1m", "10m", "1h", "12h", "1d", "1w")

  /**
   * Coarsest storage grain that can render `bucketCode` without losing resolution. Keyed on the
   * requested bucket width alone — the time range is irrelevant. Unknown/finer codes fall back to
   * Raw (the only grain that can serve arbitrary fine widths).
   */
  def grainForBucket(bucketCode: String): BucketGrain = bucketCode match {
    case "1h" | "12h" => BucketGrain.Hourly
    case "1d" | "1w"  => BucketGrain.Daily
    case _            => BucketGrain.Raw
  }

  /**
   * Canonical bucket → grain mapping the API emits to the SPA (#1743). Built from `allBuckets` and
   * `grainForBucket` so this stays the only place the classification is defined.
   */
  val bucketTiers: Map[String, BucketGrain] =
    allBuckets.map(b => b -> grainForBucket(b)).toMap
}
