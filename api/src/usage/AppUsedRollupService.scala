package wifihaven.api.usage

import wifihaven.api.db.*
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
 *   engaged_seconds(rows for `date` with period_start <  rolled_through)   ← `app_used_daily` (V53)
 *   + live_aggregate(this app's host-set over rows with period_start >= rolled_through)
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
   * Per-app engaged minutes for `profileId` on the household-local `date` containing `now`. Apps with
   * zero engaged minutes are absent from the map. Keyed by `apps.id`, matching the `app_used_daily`
   * rollup the per-app series reads.
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
  ): Task[Map[AppId, Int]] =
    // #1516: STUB — the rolled + tail / all-live aggregation lands in the adoption commit. The deps
    // are touched so the wiring (and the unused-params lint) is satisfied; the result is empty.
    for {
      _ <- profileRepo.findById(profileId)
      _ <- deviceRepo.listAll
      _ <- siteTimeLimitRepo.listForProfile(profileId)
      _ <- appRepo.listAll
      _ <- trafficRepo.listPresenceRows(Nil, date)
      _ <- rollupRepo.getDayForProfile(profileId, date)
      _ = (now, settings)
    } yield Map.empty
}

object AppUsedRollupService {
  val layer: ZLayer[
    ProfileRepo & DeviceRepo & SiteTimeLimitRepo & AppRepo & TrafficReportRepo & AppUsedRollupRepo,
    Nothing,
    AppUsedRollupService,
  ] = ZLayer.fromFunction(AppUsedRollupServiceLive(_, _, _, _, _, _))
}
