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
enum BucketGrain {
  case Raw, Hourly, Daily
}

object BucketPolicy {

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
   * Coarseness the window justifies on cost grounds — the cost-preference input to the per-endpoint
   * tier picker. Keyed on the requested window width alone; the bucket width is a separate input
   * (see [[grainForBucket]]) and the picker takes the finer of (cap, pref). Both the traffic-series
   * (`UsageRoutes.windowTier`) and connection-events-series (`LogRoutes.seriesGrain`) endpoints
   * route through this so they can't drift on the thresholds (#1744 / #1532).
   */
  def windowGrain(windowHours: Long): BucketGrain =
    if (windowHours <= 24) BucketGrain.Raw
    else if (windowHours <= 14 * 24) BucketGrain.Hourly
    else BucketGrain.Daily

  /** Total ordering on [[BucketGrain]] from finest (Raw) to coarsest (Daily). */
  def rank(g: BucketGrain): Int = g match {
    case BucketGrain.Raw    => 0
    case BucketGrain.Hourly => 1
    case BucketGrain.Daily  => 2
  }
}
