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
   * Resolve a `(macs, profileIds)` filter to the device set it selects, given the household's
   * devices. The single source of truth for "which devices does this traffic filter cover" — shared
   * by the `GET /api/usage/traffic` handler (which wraps it with per-mac `NotFound` + per-profile
   * `requireProfileReadAccess` auth) and the S4 `trafficUsage` live-edge aggregator (whose authz is
   * the upstream `visibleTo` role gate), so the two can't drift on the filter semantics. Pure:
   *   - `macs` non-empty → those devices, further narrowed to `profileIds` if also given;
   *   - else `profileIds` non-empty → devices in those profiles;
   *   - else → [[MacScope.AllInHousehold]] (the "no filter" set; callers gate who may request it).
   *
   * #2708: the return type is a [[MacScope]], not a `List`. A supplied filter that selects nothing
   * is [[MacScope.NoDevices]] and a supplied-no-filter read is [[MacScope.AllInHousehold]] — two
   * distinct constructors, where an empty `List` used to have to mean both. Every caller previously
   * had to re-derive which one it was holding from the raw request; a household with zero devices
   * is the case they all got wrong.
   *
   * '''Deliberate behaviour change in the no-filter case (#2708).''' Pre-#2708 this branch returned
   * `devices.map(_.mac)`, so the read was restricted to MACs with a CURRENT `devices` row; traffic
   * whose device row had since been deleted silently vanished from the household's own usage view
   * and under-reported its totals. [[MacScope.AllInHousehold]] restricts by household instead, so
   * those rows are now included and render under the bare MAC (`UsageTraffic.buildAggregate` falls
   * back to `mac.value` / "(unassigned)"). This is a widening WITHIN one tenant and never across
   * tenants — the household predicate on every tier is what bounds it (#2313 raw, #2708 rollups),
   * and it is strictly more correct than the device-list restriction, which cannot exclude a MAC
   * shared with ANOTHER household (#2125) and so was never the isolation mechanism it looked like.
   * Pinned by `RollupHouseholdScopeSpec`'s deleted-device test.
   */
  def resolveMacs(
      macs: List[MacAddress],
      profileIds: List[ProfileId],
      devices: List[Device],
  ): MacScope =
    if (macs.nonEmpty) {
      val byMac = devices.filter(d => macs.contains(d.mac))
      MacScope.filtered(
        if (profileIds.isEmpty) byMac.map(_.mac)
        else byMac.filter(d => d.profileId.exists(profileIds.contains)).map(_.mac),
      )
    } else if (profileIds.nonEmpty)
      MacScope.filtered(devices.filter(d => d.profileId.exists(profileIds.contains)).map(_.mac))
    else MacScope.AllInHousehold

  /**
   * Fetch raw / rollup rows for `scope` over `[from, to)` at the tier the bucket+window selects,
   * then aggregate into `TrafficUsageAggregateRow`s (one per (window, grouped-column-values)).
   *
   * #2708: `scope` carries whether a filter selected nothing ([[MacScope.NoDevices]] → empty, no
   * query) or was never supplied ([[MacScope.AllInHousehold]] → every device in `household`), so
   * callers no longer hand-roll that distinction and can't get it wrong. Pure of cursor paging: the
   * `GET` path layers its keyset cursor on top of this result; the S4 aggregator passes a
   * head-bucket window and takes the rows as-is.
   */
  def aggregate(
      // #2313 (raw tier) / #2708 (rollup tiers): every tier is scoped to the caller's household —
      // "all macs" means "all macs in `household`" (not all tenants), and a shared MAC never pulls
      // another household's rows into this aggregation.
      household: HouseholdId,
      trafficRepo: TrafficReportRepo,
      rollupRepo: RollupRepo,
      scope: MacScope,
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
    val fetch: Task[List[TrafficUsageDbRow]] = scope.fold(
      ZIO.succeed(List.empty[TrafficUsageDbRow]),
    ) { macs =>
      tier match {
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
          (bucket, UsageTraffic.stepOf(bucket)) match {
            case (
                  UsageTraffic.Bucket.OneMin | UsageTraffic.Bucket.TenMin |
                  UsageTraffic.Bucket.OneHour,
                  Some(step),
                ) =>
              trafficRepo.listRawAggregatedInRange(household, macs, from, to, step.toSeconds)
            case _ =>
              trafficRepo.listRawInRange(household, macs, from, to)
          }
        case SourceTier.Hourly =>
          rollupRepo.listHourlyInRange(household, macs, from, to).map(asDbRows)
        case SourceTier.Daily  =>
          rollupRepo.listDailyInRange(household, macs, from, to).map(asDbRows)
      }
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
