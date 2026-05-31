package wifihaven.api.usage

import wifihaven.api.db.{
  DeviceRepo,
  HouseholdSettingsRepo,
  ProfileRepo,
  RolledDay,
  RollupRepo,
  SiteTimeLimitRepo,
  TimeUsedRollupRepo,
  TrafficReportRepo,
}
import wifihaven.api.policy.{PolicyService, TimeStatusService}
import wifihaven.shared.Clock
import wifihaven.shared.types.ProfileId
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
 * Invalidation is wholesale via `TimeUsedRollupRepo.deleteAll` (called from
 * `HouseholdSettingsRepoLive.update` — covers the heartbeat filter, daily reset time, and tz). The
 * next tick refills.
 *
 * Multi-instance note: only one fiber should be writing each row at a time, but UPSERT keyed on
 * `(profile_id, date)` with a monotonically-advancing `rolled_through` makes redundant writes
 * idempotent semantically — two instances racing yield the same row either way. An advisory lock is
 * unnecessary for v1 (one API instance in prod); add one alongside [[RollupLockKeys]] if/when this
 * is run multi-instance.
 */
object TimeUsedRollupJob {

  /**
   * Refresh cadence. The read path's tail aggregation (`TimeStatusServiceLive`, buckets with
   * `period_start >= rolled_through`) makes the screen-time figure exact regardless of cadence, so
   * this interval only bounds the tail's size — it is not a freshness knob. #1230 widened it from
   * 3m to 15m to cut DB churn on the 256 MB prod instance (#1228): at 5-min bucket granularity the
   * tail tops out at ~3 rows per profile, a negligible per-read cost for ~5× fewer recompute ticks.
   */
  val Interval: Duration = Duration.ofMinutes(15)

  /**
   * Fiber loop entry point. Errors are caught and recorded in `rollup_runs`; the fiber never dies.
   */
  def loop(
      rollup: TimeUsedRollupRepo,
      runs: RollupRepo,
      profileRepo: ProfileRepo,
      deviceRepo: DeviceRepo,
      siteTimeLimitRepo: SiteTimeLimitRepo,
      trafficRepo: TrafficReportRepo,
      hs: HouseholdSettingsRepo,
      clock: Clock,
  ): UIO[Unit] =
    runOnce(
      runs,
      clock,
      now => doTick(rollup, profileRepo, deviceRepo, siteTimeLimitRepo, trafficRepo, hs, now),
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
      profileRepo: ProfileRepo,
      deviceRepo: DeviceRepo,
      siteTimeLimitRepo: SiteTimeLimitRepo,
      trafficRepo: TrafficReportRepo,
      hs: HouseholdSettingsRepo,
      now: Instant,
  ): Task[Int] = doTick(rollup, profileRepo, deviceRepo, siteTimeLimitRepo, trafficRepo, hs, now)

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
        case Right(n) =>
          ZIO.logInfo(s"rollup time_used_daily tick ok rows=$n") *>
            runs.recordRun("time_used_daily", started, finished, "ok", None, n).ignore
        case Left(e)  =>
          ZIO.logErrorCause("rollup time_used_daily tick failed", Cause.fail(e)) *>
            runs
              .recordRun(
                "time_used_daily",
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
  // itself but arrive at the DB after the snapshot read. The double-counting risk is bounded by
  // bucket granularity (5 min); the next tick re-rolls with a fresh `now` and supersedes the row.
  private def doTick(
      rollup: TimeUsedRollupRepo,
      profileRepo: ProfileRepo,
      deviceRepo: DeviceRepo,
      siteTimeLimitRepo: SiteTimeLimitRepo,
      trafficRepo: TrafficReportRepo,
      hs: HouseholdSettingsRepo,
      now: Instant,
  ): Task[Int] = for {
    settings <- hs.get
    today = PolicyService.householdLocalDate(now, settings)
    profiles <- profileRepo.listAll
    devices  <- deviceRepo.listAll
    stlsP    <- ZIO.foreach(profiles)(p => siteTimeLimitRepo.listForProfile(p.id).map(p.id -> _))
    presence <- trafficRepo.listPresenceRows(devices.map(_.mac), today)
    perProfile: Map[ProfileId, RolledDay] = {
      val stlMap  = stlsP.toMap
      val devsByP =
        devices.groupBy(_.profileId).collect { case (Some(pid), devs) => pid -> devs }
      profiles.iterator.map { p =>
        val devs = devsByP.getOrElse(p.id, Nil)
        val mac  = devs.map(_.mac).toSet
        val pres = presence.filter(r => mac.contains(r.mac))
        val secs = TimeStatusService.usedSecondsForProfile(
          p,
          devs,
          stlMap.getOrElse(p.id, Nil),
          pres,
          settings,
        )
        p.id -> RolledDay(secs, now)
      }.toMap
    }
    n <- rollup.upsertBatch(today, perProfile)
  } yield n
}
