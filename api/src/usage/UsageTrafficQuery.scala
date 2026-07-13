package wifihaven.api.usage

import wifihaven.api.db.{RollupRepo, RollupRow, TrafficReportRepo}
import wifihaven.shared.*
import wifihaven.shared.types.*
import zio.*

import java.time.{Duration, Instant, ZoneId}

/**
 * #1971 (S4, SPA-websocket design `docs/design/spa-websocket.md` §5.3): the ONE fetch+aggregate
 * core shared by the `GET /api/usage/traffic` aggregated path
 * ([[wifihaven.api.routes.UsageRoutes]]) and the live-edge `trafficUsage` aggregator
 * ([[wifihaven.api.routes.SpaPush]]). Both call [[aggregate]] so the streamed live-edge bucket and
 * the page's `GET` body are produced by the SAME query+rollup tiering+aggregation — they can never
 * disagree (`AGENTS.md#single-source-of- truth`; design §0.3 "zero new read-model shapes"). The S4
 * stream is the existing query re-run scoped to the head window, not a parallel read model.
 *
 * This object owns the source-tier routing (#813/#1262) that decides which physical table backs a
 * read — formerly private in `UsageRoutes`, lifted here so it isn't copied. The picker never
 * coarsens the requested bucket; the window only steers toward a finer/cheaper source.
 */
object UsageTrafficQuery {

  /**
   * Which physical table backs an aggregated read. The requested bucket is always honored at its
   * width; the source is the finer of (the bucket's correctness cap, the window's cost preference).
   */
  private enum SourceTier {
    case Raw, Hourly, Daily
  }

  private def grainToTier(g: BucketGrain): SourceTier = g match {
    case BucketGrain.Raw    => SourceTier.Raw
    case BucketGrain.Hourly => SourceTier.Hourly
    case BucketGrain.Daily  => SourceTier.Daily
  }

  private def pickTier(b: UsageTraffic.Bucket, window: Duration): SourceTier = {
    // Cap: coarsest grain that can render the bucket without losing resolution. Pref: coarseness the
    // window justifies on cost grounds — never coarsens the bucket itself. Finer of (cap, pref)
    // wins. Both inputs and the rank ordering live in `wifihaven.shared.BucketPolicy` so this picker
    // and `LogRoutes.seriesGrain` can't drift on the thresholds (#1744).
    val cap  = BucketPolicy.grainForBucket(b.code)
    val pref = BucketPolicy.windowGrain(window.toHours)
    grainToTier(if (BucketPolicy.rank(pref) <= BucketPolicy.rank(cap)) pref else cap)
  }

  // Convert rollup rows back into the shape buildAggregate consumes. The hostname is already
  // post-resolved by the rollup writer, so no extra LATERAL join is needed at read time — this is
  // the whole point of the rollup tables.
  private def asDbRows(rows: List[RollupRow]): List[TrafficUsageDbRow] =
    rows.map { r =>
      TrafficUsageDbRow(
        mac = r.mac,
        host = r.host,
        periodStart = r.bucketStart,
        periodEnd = r.bucketEnd,
        activeSeconds = r.activeSeconds,
        bytesIn = r.bytesIn,
        bytesOut = r.bytesOut,
      )
    }

  /**
   * Resolve a `(macs, profileIds)` filter to the device-mac set it selects, given the household's
   * devices. The single source of truth for "which devices does this traffic filter cover" — shared
   * by the `GET /api/usage/traffic` handler (which wraps it with per-mac `NotFound` + per-profile
   * `requireProfileReadAccess` auth) and the S4 `trafficUsage` live-edge aggregator (whose authz is
   * the upstream `visibleTo` role gate), so the two can't drift on the filter semantics. Pure:
   *   - `macs` non-empty → those devices, further narrowed to `profileIds` if also given;
   *   - else `profileIds` non-empty → devices in those profiles;
   *   - else → all devices (the "no filter" set; callers gate who may request it).
   */
  def resolveMacs(
      macs: List[MacAddress],
      profileIds: List[ProfileId],
      devices: List[Device],
  ): List[MacAddress] =
    if (macs.nonEmpty) {
      val byMac = devices.filter(d => macs.contains(d.mac))
      if (profileIds.isEmpty) byMac.map(_.mac)
      else byMac.filter(d => d.profileId.exists(profileIds.contains)).map(_.mac)
    } else if (profileIds.nonEmpty)
      devices.filter(d => d.profileId.exists(profileIds.contains)).map(_.mac)
    else devices.map(_.mac)

  /**
   * Fetch raw / rollup rows for `macs` over `[from, to)` at the tier the bucket+window selects,
   * then aggregate into `TrafficUsageAggregateRow`s (one per (window, grouped-column-values)).
   * `macs = Nil` means "all macs" at the repo level — callers that resolved a NON-EMPTY filter down
   * to zero devices must short-circuit to empty themselves (Nil here would otherwise widen to the
   * whole household). Pure of cursor paging: the `GET` path layers its keyset cursor on top of this
   * result; the S4 aggregator passes a head-bucket window and takes the rows as-is.
   */
  def aggregate(
      trafficRepo: TrafficReportRepo,
      rollupRepo: RollupRepo,
      macs: List[MacAddress],
      from: Instant,
      to: Instant,
      bucket: UsageTraffic.Bucket,
      groupBy: Set[UsageTraffic.GroupBy],
      zone: ZoneId,
      deviceByMac: Map[MacAddress, Device],
      profileNameById: Map[ProfileId, String],
      appsByHost: Map[String, List[AppMembership]],
  ): Task[List[TrafficUsageAggregateRow]] = {
    val tier                                 = pickTier(bucket, Duration.between(from, to))
    val fetch: Task[List[TrafficUsageDbRow]] = tier match {
      // #2174: a UTC-grid display bucket on the raw tier (1m / 10m / 1h) pre-aggregates in SQL —
      // one row per (mac, host, bucket) instead of one per raw report period (755k rows / 24h at
      // prod volume, ~24s). Restricted to exactly the buckets whose `floorTo` is pure UTC epoch
      // math, so the SQL floor and the Scala floor cannot disagree; the downstream aggregation —
      // groupBy fan-out, distinct counts, window assembly — is unchanged, just over far fewer
      // rows. Excluded on purpose:
      //   - `raw` has no fixed step — the row's real report period is the window (#2018);
      //   - `12h` / `1d` / `1w` floor in the HOUSEHOLD zone (`floorTo`), which a UTC epoch floor
      //     would break in non-UTC zones. They only land on the raw tier for short windows (the
      //     picker's freshness preference; their cost cap routes wide windows to the rollups), so
      //     the per-row fetch stays cheap there.
      case SourceTier.Raw    =>
        bucket match {
          case UsageTraffic.Bucket.OneMin | UsageTraffic.Bucket.TenMin |
              UsageTraffic.Bucket.OneHour =>
            // stepOf is total for fixed-step buckets; the getOrElse arm is unreachable for the
            // three matched here.
            val step = UsageTraffic.stepOf(bucket).map(_.toSeconds).getOrElse(60L)
            trafficRepo.listRawAggregatedInRange(macs, from, to, step)
          case _ =>
            trafficRepo.listRawInRange(macs, from, to)
        }
      case SourceTier.Hourly => rollupRepo.listHourlyInRange(macs, from, to).map(asDbRows)
      case SourceTier.Daily  => rollupRepo.listDailyInRange(macs, from, to).map(asDbRows)
    }
    fetch.map(rows =>
      UsageTraffic.buildAggregate(
        rows,
        bucket,
        zone,
        groupBy,
        deviceByMac,
        profileNameById,
        appsByHost,
      ),
    )
  }
}
