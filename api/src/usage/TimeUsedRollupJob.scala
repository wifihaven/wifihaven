package wifihaven.api.usage

import wifihaven.api.db.{
  AmbientHostsRepo,
  AppTimeLimitRepo,
  AppUsedRollupRepo,
  DeviceRepo,
  HouseholdSettingsRepo,
  NoopAmbientHostsRepo,
  ProfileRepo,
  RolledAppDay,
  RolledDay,
  RollupRepo,
  TimeUsedRollupRepo,
  TrafficReportRepo,
}
import wifihaven.api.presence.AmbientGate
import wifihaven.api.metrics.AppMetrics
import wifihaven.api.policy.{PolicyService, TimeStatusService}
import wifihaven.shared.Clock
import wifihaven.shared.types.{AppId, HouseholdId, ProfileId}
import zio.*

import java.time.{Duration, Instant}

/**
 * #1160: scheduled refresh of `time_used_daily` for **today**. Each tick aggregates today's
 * presence rows (up to `now`) and upserts one row per profile carrying `(used_seconds,
 * rolled_through = now)`. The read path in `TimeStatusServiceLive` adds a live tail of buckets past
 * the watermark, so callers see real-time totals built mostly from the cached row plus a small live
 * slice.
 *
 * Past dates are intentionally not cached here — the existing live path (and the byte-rollup tables
 * from #809 for usage graphs) cover them. The hot path is `/api/time/status/summary` rendering the
 * per-profile screen-time figures (#1099); past-day reads are cold.
 *
 * Invalidation lives inline in `HouseholdSettingsRepoLive.update` — a `DELETE FROM time_used_daily`
 * + `DELETE FROM app_used_daily` in the same transaction as the settings UPDATE. It covers the
 * heartbeat filter, daily reset time, tz, the #1464 presence session-stitch knob
 * `presence_continuation_seconds`, and the #2077 ambient-gate knobs; the next tick refills with the
 * new semantics. #2553: the DELETEs are scoped to the WRITING household's profiles, which is
 * correct precisely because this job now derives each household's rollup from that household's own
 * settings — one tenant's save can no longer change what another tenant's cached rows mean.
 *
 * Multi-instance note: only one fiber should be writing each row at a time, but UPSERT keyed on
 * `(profile_id, date)` with a monotonically-advancing `rolled_through` makes redundant writes
 * idempotent semantically — two instances racing yield the same row either way. An advisory lock is
 * unnecessary for v1 (one API instance in prod); add one alongside [[RollupLockKeys]] if/when this
 * is run multi-instance.
 */
object TimeUsedRollupJob {

  /**
   * The `rollup_job` label / `rollup_runs.job` name for this job. Single-sourced so the run record
   * and the #2553 per-household skip counter can never name it two different ways.
   */
  val JobName: String = "time_used_daily"

  /**
   * Refresh cadence. The read path's tail aggregation (`TimeStatusServiceLive`, buckets with
   * `period_start >= rolled_through`) makes the screen-time figure exact regardless of cadence, so
   * this interval only bounds the tail's size — it is not a freshness knob. #1230 widened it from
   * 3m to 15m to cut DB churn on the 256 MB prod instance (#1228): the tail is roughly `Interval /
   * report-cadence` rows per profile (the cadence is the agent's `usage_report_interval`, ~60s and
   * configurable — so ~15 rows at 60s; it was ~3 back when the cadence was 5-min), a negligible
   * per-read cost for ~5× fewer recompute ticks.
   */
  val Interval: Duration = Duration.ofMinutes(15)

  /**
   * Fiber loop entry point. Errors are caught and recorded in `rollup_runs`; the fiber never dies.
   */
  def loop(
      rollup: TimeUsedRollupRepo,
      appRollup: AppUsedRollupRepo,
      runs: RollupRepo,
      profileRepo: ProfileRepo,
      deviceRepo: DeviceRepo,
      appTimeLimitRepo: AppTimeLimitRepo,
      trafficRepo: TrafficReportRepo,
      hs: HouseholdSettingsRepo,
      clock: Clock,
      ambientRepo: AmbientHostsRepo = NoopAmbientHostsRepo,
  ): UIO[Unit] =
    runOnce(
      runs,
      clock,
      now =>
        doTick(
          rollup,
          appRollup,
          profileRepo,
          deviceRepo,
          appTimeLimitRepo,
          trafficRepo,
          hs,
          now,
          ambientRepo,
        ),
    )
      .repeat(Schedule.fixed(Interval))
      .unit

  /**
   * Single tick body, exposed for the test that exercises the rollup end-to-end without going
   * through the fiber loop. Computes today (per `now`) directly from the repos and persists the
   * resulting (used_seconds, rolled_through = `now`) rows. Returns the count of profiles rolled.
   */
  def oneTickForTest(
      rollup: TimeUsedRollupRepo,
      appRollup: AppUsedRollupRepo,
      profileRepo: ProfileRepo,
      deviceRepo: DeviceRepo,
      appTimeLimitRepo: AppTimeLimitRepo,
      trafficRepo: TrafficReportRepo,
      hs: HouseholdSettingsRepo,
      now: Instant,
      ambientRepo: AmbientHostsRepo = NoopAmbientHostsRepo,
  ): Task[Int] =
    doTick(
      rollup,
      appRollup,
      profileRepo,
      deviceRepo,
      appTimeLimitRepo,
      trafficRepo,
      hs,
      now,
      ambientRepo,
    )

  // ── internals ──────────────────────────────────────────────────────────────

  // `private[api]` with an injected tick `body` so the shutdown-handling spec can
  // drive a single tick that fails (or never completes) without standing up the
  // full repo graph or the forever-looping fiber. Production wiring in `loop`
  // passes `doTick`.
  private[api] def runOnce(
      runs: RollupRepo,
      clock: Clock,
      body: Instant => Task[Int],
  ): UIO[Unit] =
    for {
      started  <- clock.instant
      result   <- clock.instant.flatMap(body).either
      finished <- clock.instant
      _        <- result match {
        case Right(n)                                  =>
          ZIO.logInfo(s"rollup time_used_daily tick ok rows=$n") *>
            runs.recordRun(JobName, started, finished, "ok", None, n).ignore
        case Left(e) if RollupShutdown.isPoolClosed(e) =>
          // #1247: pool closed out from under a mid-flight tick during shutdown.
          // Benign — don't log ERROR or record a bogus error run.
          ZIO.logDebug("rollup time_used_daily tick aborted (pool closed during shutdown)")
        case Left(e)                                   =>
          ZIO.logErrorCause("rollup time_used_daily tick failed", Cause.fail(e)) *>
            runs
              .recordRun(
                JobName,
                started,
                finished,
                "error",
                Some(e.getMessage.take(500)),
                0,
              )
              .ignore
      }
    } yield ()

  // The cached row covers presence buckets with `period_start < rolled_through`. Setting
  // `rolled_through = now` makes the read path's tail-load filter (`period_start >= rolled_through`)
  // pick up any bucket that lands after this tick — including buckets that finish during the tick
  // itself but arrive at the DB after the snapshot read. The double-counting risk is bounded by the
  // report-period granularity (the agent's `usage_report_interval`, ~60s); the next tick re-rolls
  // with a fresh `now` and supersedes the row.
  private def doTick(
      rollup: TimeUsedRollupRepo,
      appRollup: AppUsedRollupRepo,
      profileRepo: ProfileRepo,
      deviceRepo: DeviceRepo,
      appTimeLimitRepo: AppTimeLimitRepo,
      trafficRepo: TrafficReportRepo,
      hs: HouseholdSettingsRepo,
      now: Instant,
      ambientRepo: AmbientHostsRepo,
  ): Task[Int] = for {
    // #2257: all-tenant rollup batch — enumerate households explicitly and union each one's scoped
    // read (no cross-tenant `listAll` a request path could also reach).
    // #2313: the presence read is PER-HOUSEHOLD (`traffic_reports` is router_id-keyed, and the same
    // MAC can exist in >1 household post-V74), and `computeRolls` runs per household over only that
    // household's profiles/devices/scoped presence — so a shared MAC's minutes can never leak across
    // tenants into the rollup the enforcement read consumes.
    // #2553: the SETTINGS are per-household too. `settings` fixes the day key (`today`), selects the
    // presence rows for that day, and supplies the heartbeat / session-stitch / ambient knobs that
    // define an active minute — so reading it once per tick applied household #1's timezone and
    // filters to every tenant. Because `today` is now per household, the rolls can no longer be
    // unioned into ONE `upsertBatch`: each household upserts under its OWN local date, inside the
    // loop.
    households <- profileRepo.distinctHouseholds
    perHh      <- ZIO.foreach(households)(hh =>
      rollHousehold(
        rollup,
        appRollup,
        profileRepo,
        deviceRepo,
        appTimeLimitRepo,
        trafficRepo,
        hs,
        now,
        ambientRepo,
        hh,
      ),
    )
    totals = perHh.foldLeft((0, 0, 0)) { case ((accN, accDropped, accAmb), (n, dropped, amb)) =>
      (accN + n, accDropped + dropped, accAmb + amb)
    }
    // #1676: each tick emits the count of per-(mac, app) sessions silently
    // dropped by the #1666 anchor-row guard so an operator can rate-alert on
    // threshold drift. Summed across profiles — AND, since #2553, across
    // households — since the metric is unlabelled (a per-household label would
    // breach the cardinality firewall).
    _ <- AppMetrics.recordAppSessionsDropped(totals._2)
    // #2077: same cadence for the ambient anchor gate's dropped-span watchdog —
    // a sustained rise with flat screen-time means the learner is eating real
    // sessions; a flat zero with returning phantom means it is too lax.
    _ <- AppMetrics.recordAmbientSpansDropped(totals._3)
  } yield totals._1

  /**
   * One household's slice: its own settings → its own day key → its own gated rolls → its own
   * upserts. Returns `(rows upserted, app-sessions dropped, ambient spans dropped)` so the caller
   * can emit the two unlabelled watchdog counters as single cross-household sums.
   *
   * Failures are isolated per household ([[HouseholdTickIsolation]]) rather than aborting the whole
   * tick: `getForHousehold` fails loud for an unprovisioned household (#2386), and one such tenant
   * must not stop screen-time accounting for every other tenant. A skip is logged at ERROR and
   * metered with an attributable reason — never silently dropped.
   */
  private def rollHousehold(
      rollup: TimeUsedRollupRepo,
      appRollup: AppUsedRollupRepo,
      profileRepo: ProfileRepo,
      deviceRepo: DeviceRepo,
      appTimeLimitRepo: AppTimeLimitRepo,
      trafficRepo: TrafficReportRepo,
      hs: HouseholdSettingsRepo,
      now: Instant,
      ambientRepo: AmbientHostsRepo,
      hh: HouseholdId,
  ): Task[(Int, Int, Int)] =
    HouseholdTickIsolation
      .isolate(
        JobName,
        hh,
        HouseholdTickIsolation.ReasonSettingsRead,
        Option.empty[
          wifihaven.shared.HouseholdSettings,
        ],
      )(hs.getForHousehold(hh).map(Some(_)))
      .flatMap {
        case None           => ZIO.succeed((0, 0, 0))
        case Some(settings) =>
          HouseholdTickIsolation.isolate(
            JobName,
            hh,
            HouseholdTickIsolation.ReasonError,
            (0, 0, 0),
          ) {
            val today = PolicyService.householdLocalDate(now, settings)
            for {
              ambient  <- ambientRepo.gateFor(settings, today)
              profiles <- profileRepo.listAllForHousehold(hh)
              devices  <- deviceRepo.listAllForHousehold(hh)
              atlsP    <- ZIO.foreach(profiles)(p =>
                appTimeLimitRepo.listForProfile(p.id).map(p.id -> _),
              )
              presence <- trafficRepo.listPresenceRows(hh, devices.map(_.mac), today)
              rolls = computeRolls(profiles, devices, atlsP.toMap, presence, settings, now, ambient)
              n <- rollup.upsertBatch(today, rolls._1)
              _ <- appRollup.upsertBatch(today, rolls._2)
            } yield (n, rolls._3, rolls._4)
          }
      }

  // Pure: collapse one presence batch into both the per-profile total roll (`time_used_daily`) and
  // the per-(profile, app) engaged roll (`app_used_daily`), stamped with the same `now` watermark so
  // the two compose identically (rolled + tail). The per-app figure derives from the SINGLE per-app
  // primitive (`TimeStatusService.appSecondsByApp` → `Presence.appSecondsForProfile`), keyed on the
  // typed `apps.id` FK [[wifihaven.shared.AppTimeLimit.appId]] carried straight from the repo join
  // (#1564) — no slugToAppId lookup. Only apps with engaged activity get a row (zero-activity apps
  // are absent).
  private def computeRolls(
      profiles: List[wifihaven.shared.Profile],
      devices: List[wifihaven.shared.Device],
      atlMap: Map[ProfileId, List[wifihaven.shared.AppTimeLimit]],
      presence: List[wifihaven.api.presence.PresenceRow],
      settings: wifihaven.shared.HouseholdSettings,
      now: Instant,
      ambient: AmbientGate = AmbientGate.Off,
  ): (Map[ProfileId, RolledDay], Map[(ProfileId, AppId), RolledAppDay], Int, Int) = {
    val devsByP                                                                         =
      devices.groupBy(_.profileId).collect { case (Some(pid), devs) => pid -> devs }
    // #2077: gate each profile's slice ONCE per tick (the write side of the rollup is the
    // canonical active-minute definition), accumulating the dropped-span watchdog count.
    // Precomputed per profile so the two consumers below (per-profile total, per-app roll)
    // share one gating pass and the drop count isn't double-counted.
    val gatedByProfile: Map[ProfileId, (List[wifihaven.api.presence.PresenceRow], Int)] =
      profiles.iterator.map { p =>
        val mac    = devsByP.getOrElse(p.id, Nil).map(_.mac).toSet
        val scoped = presence.filter(r => mac.contains(r.mac))
        p.id -> TimeStatusService.gatedPresenceWithDropCount(
          atlMap.getOrElse(p.id, Nil),
          scoped,
          settings,
          ambient,
        )
      }.toMap
    val ambientDropped                                                                  =
      gatedByProfile.valuesIterator.map(_._2).sum
    def presFor(pid: ProfileId): List[wifihaven.api.presence.PresenceRow]               =
      gatedByProfile.get(pid).map(_._1).getOrElse(Nil)
    val perProfile    = profiles.iterator.map { p =>
      val secs = TimeStatusService.usedSecondsForProfile(
        p,
        devsByP.getOrElse(p.id, Nil),
        atlMap.getOrElse(p.id, Nil),
        presFor(p.id),
        settings,
      )
      p.id -> RolledDay(secs, now)
    }.toMap
    // #1676: each profile's per-app fold also yields a dropped-session count
    // from the #1666 anchor-row guard. The metric is unlabelled, so we sum
    // across profiles and the caller emits the total.
    val perAppEntries = profiles.iterator.map { p =>
      val (secsByApp, dropped) = TimeStatusService.appSecondsByAppWithDropCount(
        p,
        atlMap.getOrElse(p.id, Nil),
        presFor(p.id),
        settings,
      )
      val rows                 = secsByApp.iterator.map { case (appId, secs) =>
        (p.id, appId) -> RolledAppDay(secs, now)
      }.toMap
      (rows, dropped)
    }.toList
    val perApp = perAppEntries.foldLeft(Map.empty[(ProfileId, AppId), RolledAppDay])(_ ++ _._1)
    val droppedTotal = perAppEntries.foldLeft(0)(_ + _._2)
    (perProfile, perApp, droppedTotal, ambientDropped)
  }
}
