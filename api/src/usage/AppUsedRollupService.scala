package wifihaven.api.usage

import wifihaven.api.db.*
import wifihaven.api.policy.{PolicyService, TimeStatusService}
import wifihaven.shared.HouseholdSettings
import wifihaven.shared.types.{AppId, ProfileId}
import zio.*

import java.time.{Instant, LocalDate}

/**
 * #1516 (sub-issue of #1510): the read accessor for per-(profile, app, date) **engaged minutes** —
 * the gap-bridged, cross-host, cap-relevant per-app figure. It is the per-app counterpart of the
 * `time_used_daily` read path in [[wifihaven.api.policy.TimeStatusServiceLive]] and composes the
 * SAME rolled + live-tail way:
 *
 * engaged_seconds(rows for `date` with period_start < rolled_through) ← `app_used_daily` (V53) +
 * live_aggregate(this app's host-set over rows with period_start >= rolled_through)
 *
 * floored to minutes once at the end (floor-of-sum, never floor-then-sum). For today it reads the
 * rollup + a small tail; on a cache miss (no app rolled for the profile yet) or for past dates it
 * falls through to the all-live path, so the result is identical to a full live aggregation.
 *
 * ── Reconciliation invariant (the #1517 graph series will rely on this) ─────────────────────────
 * Every per-app number is derived from the ONE per-app primitive
 * [[TimeStatusService.appSecondsByApp]] → [[wifihaven.api.presence.Presence.appSecondsForProfile]],
 * so for a profile + date:
 *
 *   - '''rollup ⇄ cap''' (EXACT): this accessor's per-app minutes equal the per-app cap aggregate
 *     ([[wifihaven.api.policy.TimeStatusService.siteDayStates]] /
 *     `Presence.patternGroupMinutesForProfile`) — same primitive, same floor-of-sum.
 *   - '''rolled+tail ⇄ live''' (EXACT): rolled + tail equals the all-live computation in seconds,
 *     because presence buckets are 5-min granular and disjoint on either side of the watermark
 *     (same argument as `time_used_daily`; watermarks land in idle gaps so no session straddles).
 *   - '''per-app sum ⇄ profile total''' (RESTRICTED equality): `Σ_app engagedSeconds(app)` equals
 *     the profile daily total ([[TimeStatusService.usedSecondsForProfile]]) ONLY when every counted
 *     second belongs to a single, non-exempt-from-daily app and the apps do not overlap in
 *     wall-clock per device (and, cross-device, under `Sum`). In general the per-app sum may exceed
 *     the profile total: (a) `exempt_from_daily` apps add to the per-app series but NOT the profile
 *     total, (b) apps overlapping in the same window are deduped in the total but counted once per
 *     app, and (c) non-app traffic adds to the total but to no app row. The headline daily total
 *     remains `time_used_daily`; this accessor is the per-app decomposition, not a partition of it.
 */
trait AppUsedRollupService {

  /**
   * Per-app engaged minutes for `profileId` on the household-local `date` containing `now`. Apps
   * with zero engaged minutes are absent from the map. Keyed by `apps.id`, matching the
   * `app_used_daily` rollup the per-app series reads.
   */
  def appEngagedMinutes(
      now: Instant,
      date: LocalDate,
      settings: HouseholdSettings,
      profileId: ProfileId,
  ): Task[Map[AppId, Int]]
}

class AppUsedRollupServiceLive(
    profileRepo: ProfileRepo,
    deviceRepo: DeviceRepo,
    siteTimeLimitRepo: SiteTimeLimitRepo,
    appRepo: AppRepo,
    trafficRepo: TrafficReportRepo,
    rollupRepo: AppUsedRollupRepo,
) extends AppUsedRollupService {

  def appEngagedMinutes(
      now: Instant,
      date: LocalDate,
      settings: HouseholdSettings,
      profileId: ProfileId,
  ): Task[Map[AppId, Int]] = {
    val today  = PolicyService.householdLocalDate(now, settings)
    // Only today reads the rollup; past dates ignore it (rollup is today-only, like
    // `time_used_daily`). An empty rolled map — cache miss, or a past date — collapses `aggregate`
    // to a full-day all-live computation.
    val rolled =
      if (date == today) rollupRepo.getDayForProfile(profileId, date)
      else ZIO.succeed(Map.empty[AppId, RolledAppDay])
    rolled.flatMap(aggregate(date, settings, profileId, _))
  }

  // Single read path for both cases. When `rolled` is non-empty, add its engaged seconds to a live
  // aggregation of the TAIL past the shared watermark (period_start >= rolled_through). When it is
  // empty (cache miss / past date), the watermark is absent so the whole day is aggregated live and
  // the rolled contribution is zero — i.e. the all-live path. Either way the floor-of-sum to minutes
  // happens once at the end, so the result is byte-identical to a full live aggregation (the
  // watermark lands in an idle gap, so no engaged span straddles it — same exactness argument as
  // `time_used_daily`).
  private def aggregate(
      date: LocalDate,
      settings: HouseholdSettings,
      profileId: ProfileId,
      rolled: Map[AppId, RolledAppDay],
  ): Task[Map[AppId, Int]] =
    profileRepo.findById(profileId).flatMap {
      case None    => ZIO.succeed(Map.empty)
      case Some(p) =>
        for {
          stls    <- siteTimeLimitRepo.listForProfile(profileId)
          devices <- deviceRepo.listAll.map(_.filter(_.profileId.contains(profileId)))
          apps    <- appRepo.listAll
          macs = devices.map(_.mac)
          presence <- rolled.values.iterator.map(_.rolledThrough).minOption match {
            case Some(watermark) => trafficRepo.listPresenceRowsSince(macs, date, watermark)
            case None            => trafficRepo.listPresenceRows(macs, date)
          }
        } yield {
          val liveSecs =
            TimeStatusService.appSecondsByApp(
              p,
              stls,
              TimeStatusService.slugToAppId(apps),
              presence,
              settings,
            )
          (rolled.keySet ++ liveSecs.keySet).iterator.flatMap { id =>
            val secs =
              rolled.get(id).map(_.engagedSeconds).getOrElse(0L) + liveSecs.getOrElse(id, 0L)
            val mins = (secs / 60L).toInt
            if mins != 0 then Some(id -> mins) else None
          }.toMap
        }
    }
}

object AppUsedRollupService {
  val layer: ZLayer[
    ProfileRepo & DeviceRepo & SiteTimeLimitRepo & AppRepo & TrafficReportRepo & AppUsedRollupRepo,
    Nothing,
    AppUsedRollupService,
  ] = ZLayer.fromFunction(AppUsedRollupServiceLive(_, _, _, _, _, _))
}
