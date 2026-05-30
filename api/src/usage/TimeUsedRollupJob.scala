package wifihaven.api.usage

import wifihaven.api.db.{
  AppRepo,
  DeviceRepo,
  HouseholdSettingsRepo,
  ProfileRepo,
  RolledAppDay,
  RolledDay,
  RollupRepo,
  SiteTimeLimitRepo,
  TimeUsedAppRollupRepo,
  TimeUsedRollupRepo,
  TrafficReportRepo,
}
import wifihaven.api.policy.{PolicyService, TimeStatusService}
import wifihaven.shared.Clock
import wifihaven.shared.types.{AppId, ProfileId}
import wifihaven.shared.types.Hostname
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
   * Refresh cadence. The tail aggregation handles freshness between ticks; this just bounds the
   * tail's size so reads don't drift toward a full-day live aggregation as the day wears on.
   */
  val Interval: Duration = Duration.ofMinutes(3)

  /**
   * Fiber loop entry point. Errors are caught and recorded in `rollup_runs`; the fiber never dies.
   */
  def loop(
      rollup: TimeUsedRollupRepo,
      appRollup: TimeUsedAppRollupRepo,
      runs: RollupRepo,
      profileRepo: ProfileRepo,
      deviceRepo: DeviceRepo,
      siteTimeLimitRepo: SiteTimeLimitRepo,
      trafficRepo: TrafficReportRepo,
      appRepo: AppRepo,
      hs: HouseholdSettingsRepo,
      clock: Clock,
  ): UIO[Unit] =
    runOnce(
      rollup,
      appRollup,
      runs,
      profileRepo,
      deviceRepo,
      siteTimeLimitRepo,
      trafficRepo,
      appRepo,
      hs,
      clock,
    ).repeat(Schedule.fixed(Interval)).unit

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
  ): Task[Int] = doTick(
    rollup,
    NoopAppRollup,
    profileRepo,
    deviceRepo,
    siteTimeLimitRepo,
    trafficRepo,
    NoopAppRepo,
    hs,
    now,
  )

  /** #1167: end-to-end tick including the per-app sub-bucket write. */
  def oneTickForTest(
      rollup: TimeUsedRollupRepo,
      appRollup: TimeUsedAppRollupRepo,
      profileRepo: ProfileRepo,
      deviceRepo: DeviceRepo,
      siteTimeLimitRepo: SiteTimeLimitRepo,
      trafficRepo: TrafficReportRepo,
      appRepo: AppRepo,
      hs: HouseholdSettingsRepo,
      now: Instant,
  ): Task[Int] = doTick(
    rollup,
    appRollup,
    profileRepo,
    deviceRepo,
    siteTimeLimitRepo,
    trafficRepo,
    appRepo,
    hs,
    now,
  )

  private object NoopAppRollup extends TimeUsedAppRollupRepo {
    def upsertDay(p: ProfileId, a: AppId, d: java.time.LocalDate, r: RolledAppDay): Task[Unit] =
      ZIO.unit
    def upsertBatch(
        d: java.time.LocalDate,
        rows: Map[(ProfileId, AppId), RolledAppDay],
    ): Task[Int] = ZIO.succeed(0)
    def getDayForProfile(
        p: ProfileId,
        d: java.time.LocalDate,
    ): Task[Map[AppId, RolledAppDay]] = ZIO.succeed(Map.empty)
    def getDayMap(d: java.time.LocalDate): Task[Map[(ProfileId, AppId), RolledAppDay]]         =
      ZIO.succeed(Map.empty)
    def deleteAll: Task[Unit] = ZIO.unit
  }

  // Test-only AppRepo that returns no apps — used by the legacy 7-arg
  // `oneTickForTest` so the existing TimeUsedRollupSpec keeps compiling
  // without needing an AppRepo fixture; the per-app branch becomes a no-op.
  private object NoopAppRepo extends AppRepo {
    import wifihaven.shared.*
    import wifihaven.shared.types.*
    def listAll                            = ZIO.succeed(Nil)
    def findById(id: AppId)                = ZIO.succeed(None)
    def findBySlug(slug: String)           = ZIO.succeed(None)
    def findByTemplateId(t: AppTemplateId) = ZIO.succeed(None)
    def create(n: String, s: String, t: Option[AppTemplateId], i: Option[String], it: IconType) =
      ZIO.fail(new UnsupportedOperationException("NoopAppRepo"))
    def update(a: App)                           = ZIO.unit
    def delete(id: AppId)                        = ZIO.unit
    def setHosts(id: AppId, h: List[Hostname])   = ZIO.unit
    def getHosts(id: AppId)                      = ZIO.succeed(Nil)
    def listAllHostMappings                      = ZIO.succeed(Nil)
    def upsertAssignment(
        a: AppId,
        p: ProfileId,
        m: AppMode,
        d: Option[Int],
        e: Boolean,
    ) = ZIO.fail(new UnsupportedOperationException("NoopAppRepo"))
    def deleteAssignment(a: AppId, p: ProfileId) = ZIO.unit
    def listAssignmentsForApp(a: AppId)          = ZIO.succeed(Nil)
    def listAssignmentsForProfile(p: ProfileId)  = ZIO.succeed(Nil)
  }

  // ── internals ──────────────────────────────────────────────────────────────

  private def runOnce(
      rollup: TimeUsedRollupRepo,
      appRollup: TimeUsedAppRollupRepo,
      runs: RollupRepo,
      profileRepo: ProfileRepo,
      deviceRepo: DeviceRepo,
      siteTimeLimitRepo: SiteTimeLimitRepo,
      trafficRepo: TrafficReportRepo,
      appRepo: AppRepo,
      hs: HouseholdSettingsRepo,
      clock: Clock,
  ): UIO[Unit] =
    for {
      started  <- clock.instant
      result   <-
        clock.instant
          .flatMap(now =>
            doTick(
              rollup,
              appRollup,
              profileRepo,
              deviceRepo,
              siteTimeLimitRepo,
              trafficRepo,
              appRepo,
              hs,
              now,
            ),
          )
          .either
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
      appRollup: TimeUsedAppRollupRepo,
      profileRepo: ProfileRepo,
      deviceRepo: DeviceRepo,
      siteTimeLimitRepo: SiteTimeLimitRepo,
      trafficRepo: TrafficReportRepo,
      appRepo: AppRepo,
      hs: HouseholdSettingsRepo,
      now: Instant,
  ): Task[Int] = for {
    settings <- hs.get
    today = PolicyService.householdLocalDate(now, settings)
    profiles <- profileRepo.listAll
    devices  <- deviceRepo.listAll
    stlsP    <- ZIO.foreach(profiles)(p => siteTimeLimitRepo.listForProfile(p.id).map(p.id -> _))
    presence <- trafficRepo.listPresenceRows(devices.map(_.mac), today)
    mappings <- appRepo.listAllHostMappings
    perProfile: Map[ProfileId, RolledDay]         = {
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
    // #1167: same tick, same watermark — sub-bucket rows duplicate the parent
    // profile's `rolled_through` so the read path can compose rolled+tail with
    // a single-key lookup per (profile, app). Empty mappings (no apps configured)
    // means the per-app upsert is a no-op.
    perApp: Map[(ProfileId, AppId), RolledAppDay] = {
      val appHosts: Map[AppId, List[Hostname]] =
        mappings.groupBy(_.appId).view.mapValues(_.map(_.host)).toMap
      val devsByP                              =
        devices.groupBy(_.profileId).collect { case (Some(pid), devs) => pid -> devs }
      profiles.iterator.flatMap { p =>
        val devs       = devsByP.getOrElse(p.id, Nil)
        val macSet     = devs.map(_.mac).toSet
        val pres       = presence.filter(r => macSet.contains(r.mac))
        val perAppSecs =
          TimeStatusService.usedSecondsByApp(devs, appHosts, pres, settings)
        appHosts.keys.iterator.map { aid =>
          (p.id, aid) -> RolledAppDay(perAppSecs.getOrElse(aid, 0L), now)
        }
      }.toMap
    }
    n <- rollup.upsertBatch(today, perProfile)
    _ <- appRollup.upsertBatch(today, perApp)
  } yield n
}
