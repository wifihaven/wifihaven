package wifihaven.api.usage

import wifihaven.api.db.*
import wifihaven.api.policy.{PolicyService, TimeStatusService}
import wifihaven.shared.HouseholdSettings
import wifihaven.shared.types.{AppId, HouseholdId, ProfileId}
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
 *     ([[wifihaven.api.policy.TimeStatusService.appDayStates]] /
 *     `Presence.patternGroupMinutesForProfile`) — same primitive, same floor-of-sum.
 *   - '''rolled+tail ⇄ live''' (EXACT): rolled + tail equals the all-live computation in seconds,
 *     because presence buckets are per-report-period granular (the agent's `usage_report_interval`,
 *     ~60s) and disjoint on either side of the watermark — the exactness argument holds at any
 *     granularity (same argument as `time_used_daily`; watermarks land in idle gaps so no session
 *     straddles).
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
      household: HouseholdId,
      now: Instant,
      date: LocalDate,
      settings: HouseholdSettings,
      profileId: ProfileId,
  ): Task[Map[AppId, Int]]

  /**
   * #1515 + #1564: the same per-app engaged minutes as [[appEngagedMinutes]], surfaced for the cap
   * read path. Keyed by the typed `apps.id` FK — the same key `app_used_daily` stores, the same key
   * the cap-group ([[wifihaven.api.policy.TimeStatusService.groupAppLimits]]) emits, and the same
   * key [[wifihaven.api.policy.TimeStatusService.appDayStatesFromMinutes]] joins on. The snapshot's
   * / `/decision`'s per-app cap reads through `perApp` on the rollup read path so a profile that
   * has rolled (the prod steady state) still caps on the real per-app aggregate instead of zero.
   * Same rolled + live-tail figure, so the rollup read and the all-live read agree by construction
   * (#1532).
   */
  def appCapMinutesByAppId(
      household: HouseholdId,
      now: Instant,
      date: LocalDate,
      settings: HouseholdSettings,
      profileId: ProfileId,
  ): Task[Map[AppId, Int]]
}

/**
 * No-op variant for test wiring / read paths that never reach the rollup branch (e.g. a
 * `TimeStatusServiceLive` over [[wifihaven.api.db.NoopTimeUsedRollupRepo]], which always
 * cache-misses to the all-live path). Both accessors return empty. Mirrors
 * [[wifihaven.api.db.NoopAppUsedRollupRepo]].
 */
object NoopAppUsedRollupService extends AppUsedRollupService {
  def appEngagedMinutes(
      household: HouseholdId,
      now: Instant,
      date: LocalDate,
      settings: HouseholdSettings,
      profileId: ProfileId,
  ): Task[Map[AppId, Int]] = ZIO.succeed(Map.empty)
  def appCapMinutesByAppId(
      household: HouseholdId,
      now: Instant,
      date: LocalDate,
      settings: HouseholdSettings,
      profileId: ProfileId,
  ): Task[Map[AppId, Int]] = ZIO.succeed(Map.empty)
}

class AppUsedRollupServiceLive(
    profileRepo: ProfileRepo,
    deviceRepo: DeviceRepo,
    appTimeLimitRepo: AppTimeLimitRepo,
    trafficRepo: TrafficReportRepo,
    rollupRepo: AppUsedRollupRepo,
    // #2077: the ambient anchor gate input; Noop (gate Off) keeps test constructions inert.
    ambientRepo: AmbientHostsRepo = NoopAmbientHostsRepo,
) extends AppUsedRollupService {

  def appEngagedMinutes(
      household: HouseholdId,
      now: Instant,
      date: LocalDate,
      settings: HouseholdSettings,
      profileId: ProfileId,
  ): Task[Map[AppId, Int]] =
    aggregate(household, now, date, settings, profileId)

  def appCapMinutesByAppId(
      household: HouseholdId,
      now: Instant,
      date: LocalDate,
      settings: HouseholdSettings,
      profileId: ProfileId,
  ): Task[Map[AppId, Int]] =
    aggregate(household, now, date, settings, profileId)

  // Single read path keyed on apps.id end-to-end (#1564). When the per-app rollup is non-empty,
  // add its engaged seconds to a live aggregation of the TAIL past the shared watermark
  // (period_start >= rolled_through). When it is empty (cache miss / past date), the watermark is
  // absent so the whole day is aggregated live and the rolled contribution is zero — i.e. the
  // all-live path. Either way the floor-of-sum to minutes happens once at the end, so the result is
  // byte-identical to a full live aggregation (the watermark lands in an idle gap, so no engaged
  // span straddles it — same exactness argument as `time_used_daily`). The cap and series consumers
  // both read this AppId-keyed map directly; no slug round-trip.
  private def aggregate(
      household: HouseholdId,
      now: Instant,
      date: LocalDate,
      settings: HouseholdSettings,
      profileId: ProfileId,
  ): Task[Map[AppId, Int]] = {
    val today   = PolicyService.householdLocalDate(now, settings)
    val rolledZ =
      if (date == today) rollupRepo.getDayForProfile(profileId, date)
      else ZIO.succeed(Map.empty[AppId, RolledAppDay])
    rolledZ.flatMap { rolled =>
      profileRepo.findById(profileId).flatMap {
        case None    => ZIO.succeed(Map.empty[AppId, Int])
        case Some(p) =>
          appTimeLimitRepo.listForProfile(profileId).flatMap {
            // #2652: a profile with NO app assignments has no per-app minutes to compute — the live
            // aggregation below is empty by construction (`ProfileAppDispositions.from(Nil)` yields
            // no cap groups, so `appSecondsByApp` returns an empty map whatever presence rows it is
            // handed). Skipping the presence read is therefore output-identical, and it is the
            // difference between a whole-day `traffic_reports` scan and no DB touch at all: on the
            // `rolled.isEmpty` branch below the fallback is `listPresenceRows` for the ENTIRE day,
            // and `app_used_daily` is empty for exactly these profiles (no assignments ⇒ nothing to
            // roll). Prod 2026-08-08: three of the household's profiles have zero rows in
            // `app_policy_assignments` and were scanning 191k / 136k / 8k rows per read to build an
            // empty map — the dominant cost of `GET /api/blocked` (#2652).
            //
            // `rolled` can still be non-empty here (an assignment removed after the last rollup
            // tick), so it is projected exactly as the full path would — this is a skipped READ, not
            // a skipped result.
            case Nil  => ZIO.succeed(minutesFrom(rolled, Map.empty))
            case atls =>
              for {
                devices <- deviceRepo.listForProfile(profileId)
                macs = devices.map(_.mac)
                raw     <- rolled.values.iterator.map(_.rolledThrough).minOption match {
                  case Some(watermark) =>
                    trafficRepo.listPresenceRowsSince(household, macs, date, watermark)
                  case None            => trafficRepo.listPresenceRows(household, macs, date)
                }
                // #2077: gate the live slice the same way the rollup write path gates its input, so
                // rolled + tail compose over one active-minute definition. Gating only the slice can
                // transiently drop an ambient-only tail of a session whose anchor is already rolled
                // — bounded (self-heals on the next whole-day tick) and only ever removes minutes.
                ambient <- ambientRepo.gateFor(settings, today)
                presence = TimeStatusService.gatedPresence(atls, raw, settings, ambient)
              } yield
              // #1676: the dropped-session counter is emitted from the
              // periodic TimeUsedRollupJob tick (one clean cadence), NOT from
              // this hot read path — re-emitting on every status read would
              // inflate the series with read frequency instead of reflecting
              // data state, defeating rate-alerting.
              minutesFrom(rolled, TimeStatusService.appSecondsByApp(p, atls, presence, settings))
          }
      }
    }
  }

  /**
   * #2652: the single `rolled + live ⇒ whole minutes` projection, so the skip-the-read branch above
   * and the full path cannot shape their result differently. Floor-to-minutes happens once, on the
   * sum, exactly as before; a zero-minute app is omitted.
   */
  private def minutesFrom(
      rolled: Map[AppId, RolledAppDay],
      liveSecs: Map[AppId, Long],
  ): Map[AppId, Int] =
    (rolled.keySet ++ liveSecs.keySet).iterator.flatMap { id =>
      val secs = rolled.get(id).map(_.engagedSeconds).getOrElse(0L) + liveSecs.getOrElse(id, 0L)
      val mins = (secs / 60L).toInt
      if mins != 0 then Some(id -> mins) else None
    }.toMap
}

object AppUsedRollupService {
  val layer: ZLayer[
    ProfileRepo & DeviceRepo & AppTimeLimitRepo & TrafficReportRepo & AppUsedRollupRepo &
      AmbientHostsRepo,
    Nothing,
    AppUsedRollupService,
  ] = ZLayer.fromFunction(AppUsedRollupServiceLive(_, _, _, _, _, _))
}
