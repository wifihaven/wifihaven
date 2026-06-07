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
    val today = PolicyService.householdLocalDate(now, settings)
    if (date == today)
      rollupRepo.getDayForProfile(profileId, date).flatMap { rolled =>
        // Empty ⇒ cache miss for this profile (no app rolled yet) ⇒ all-live, mirroring how the
        // `time_used_daily` read treats a missing row.
        if rolled.isEmpty then liveMinutes(date, settings, profileId)
        else rolledPlusTailMinutes(date, settings, profileId, rolled)
      }
    else liveMinutes(date, settings, profileId)
  }

  /** Full live aggregation over the whole day — the cache-miss / past-date path. */
  private def liveMinutes(
      date: LocalDate,
      settings: HouseholdSettings,
      profileId: ProfileId,
  ): Task[Map[AppId, Int]] =
    profileRepo.findById(profileId).flatMap {
      case None    => ZIO.succeed(Map.empty)
      case Some(p) =>
        for {
          stls    <- siteTimeLimitRepo.listForProfile(profileId)
          devices <- deviceRepo.listAll.map(_.filter(_.profileId.contains(profileId)))
          apps    <- appRepo.listAll
          pres    <- trafficRepo.listPresenceRows(devices.map(_.mac), date)
        } yield toMinutes(
          TimeStatusService.appSecondsByApp(p, stls, slugToAppId(apps), pres, settings),
        )
    }

  // Rolled engaged seconds (period_start < rolled_through) + a live aggregation of this app's
  // host-set over the buckets the rollup hasn't yet absorbed (period_start >= rolled_through). The
  // sum floors to minutes once at the end (floor-of-sum), so the result is byte-identical to the
  // all-live computation — the watermark lands in an idle gap, so no engaged span straddles it
  // (same exactness argument as `time_used_daily`).
  private def rolledPlusTailMinutes(
      date: LocalDate,
      settings: HouseholdSettings,
      profileId: ProfileId,
      rolled: Map[AppId, RolledAppDay],
  ): Task[Map[AppId, Int]] =
    profileRepo.findById(profileId).flatMap {
      case None    => ZIO.succeed(Map.empty)
      case Some(p) =>
        val watermark = rolled.values.iterator.map(_.rolledThrough).min
        for {
          stls    <- siteTimeLimitRepo.listForProfile(profileId)
          devices <- deviceRepo.listAll.map(_.filter(_.profileId.contains(profileId)))
          apps    <- appRepo.listAll
          tail    <- trafficRepo.listPresenceRowsSince(devices.map(_.mac), date, watermark)
        } yield {
          val tailSecs =
            TimeStatusService.appSecondsByApp(p, stls, slugToAppId(apps), tail, settings)
          (rolled.keySet ++ tailSecs.keySet).iterator.flatMap { id =>
            val secs =
              rolled.get(id).map(_.engagedSeconds).getOrElse(0L) + tailSecs.getOrElse(id, 0L)
            val mins = (secs / 60L).toInt
            if mins != 0 then Some(id -> mins) else None
          }.toMap
        }
    }

  private def slugToAppId(apps: List[wifihaven.shared.App]): Map[String, AppId] =
    apps.map(a => a.slug -> a.id).toMap

  private def toMinutes(secs: Map[AppId, Long]): Map[AppId, Int] =
    secs.view.mapValues(s => (s / 60L).toInt).filter(_._2 != 0).toMap
}

object AppUsedRollupService {
  val layer: ZLayer[
    ProfileRepo & DeviceRepo & SiteTimeLimitRepo & AppRepo & TrafficReportRepo & AppUsedRollupRepo,
    Nothing,
    AppUsedRollupService,
  ] = ZLayer.fromFunction(AppUsedRollupServiceLive(_, _, _, _, _, _))
}
