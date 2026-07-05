package wifihaven.api.usage

import wifihaven.api.db.{
  AmbientHostsRepo,
  AppTimeLimitRepo,
  DeviceRepo,
  HouseholdSettingsRepo,
  ProfileRepo,
  RollupRepo,
  TrafficReportRepo,
}
import wifihaven.api.metrics.AppMetrics
import wifihaven.api.policy.{PolicyService, TimeStatusService}
import wifihaven.api.presence.Presence
import wifihaven.shared.Clock
import zio.*

import java.time.{Duration, Instant}

/**
 * #2077: the ambient-host learner (docs/design/idle-traffic-discrimination.md). Each tick
 * recomputes the isolated-span host counts for the PREVIOUS household-local day — the last complete
 * day — via [[Presence.isolatedSpanHosts]], upserts one `ambient_host_days` row per (host, day),
 * prunes rows that aged out of the learning window, and refreshes the `presence_ambient_hosts`
 * gauge.
 *
 * Learning runs regardless of `ambient_gate_enabled` so the operator can inspect the would-be
 * ambient set (`GET /api/presence/ambient-hosts`) before flipping the gate on. Recomputing the same
 * day is idempotent (upsert replaces the count), so the 6-hour cadence just bounds staleness after
 * restarts / tz changes; one run per day does the real work.
 *
 * Per-device app-attribution context comes from the device's profile assignments — the same
 * [[TimeStatusService.appHostPatterns]]-derived contract the gate uses — so a host on an active
 * app's host-set can never be learned ambient from that profile's devices. Devices with no profile
 * learn with no app context (their background is still background).
 */
object AmbientLearnJob {

  val Interval: Duration = Duration.ofHours(6)

  def loop(
      ambientRepo: AmbientHostsRepo,
      runs: RollupRepo,
      profileRepo: ProfileRepo,
      deviceRepo: DeviceRepo,
      appTimeLimitRepo: AppTimeLimitRepo,
      trafficRepo: TrafficReportRepo,
      hs: HouseholdSettingsRepo,
      clock: Clock,
  ): UIO[Unit] =
    runOnce(
      runs,
      clock,
      now => doTick(ambientRepo, profileRepo, deviceRepo, appTimeLimitRepo, trafficRepo, hs, now),
    )
      .repeat(Schedule.fixed(Interval))
      .unit

  /** Single tick body, exposed for the feature spec (no fiber loop). Returns hosts learned. */
  def oneTickForTest(
      ambientRepo: AmbientHostsRepo,
      profileRepo: ProfileRepo,
      deviceRepo: DeviceRepo,
      appTimeLimitRepo: AppTimeLimitRepo,
      trafficRepo: TrafficReportRepo,
      hs: HouseholdSettingsRepo,
      now: Instant,
  ): Task[Int] =
    doTick(ambientRepo, profileRepo, deviceRepo, appTimeLimitRepo, trafficRepo, hs, now)

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
          ZIO.logInfo(s"ambient_hosts learn tick ok hosts=$n") *>
            runs.recordRun("ambient_hosts", started, finished, "ok", None, n).ignore
        case Left(e) if RollupShutdown.isPoolClosed(e) =>
          ZIO.logDebug("ambient_hosts learn tick aborted (pool closed during shutdown)")
        case Left(e)                                   =>
          ZIO.logErrorCause("ambient_hosts learn tick failed", Cause.fail(e)) *>
            runs
              .recordRun(
                "ambient_hosts",
                started,
                finished,
                "error",
                Some(e.getMessage.take(500)),
                0,
              )
              .ignore
      }
    } yield ()

  private def doTick(
      ambientRepo: AmbientHostsRepo,
      profileRepo: ProfileRepo,
      deviceRepo: DeviceRepo,
      appTimeLimitRepo: AppTimeLimitRepo,
      trafficRepo: TrafficReportRepo,
      hs: HouseholdSettingsRepo,
      now: Instant,
  ): Task[Int] = for {
    settings <- hs.get
    today     = PolicyService.householdLocalDate(now, settings)
    yesterday = today.minusDays(1L)
    profiles <- profileRepo.listAll
    devices  <- deviceRepo.listAll
    atlsP    <- ZIO.foreach(profiles)(p => appTimeLimitRepo.listForProfile(p.id).map(p.id -> _))
    presence <- trafficRepo.listPresenceRows(devices.map(_.mac), yesterday)
    counts = {
      val atlMap  = atlsP.toMap
      val devsByP = devices.groupBy(_.profileId)
      // Learn per profile-group so each device's spans see its own profile's app-attribution
      // context; devices with no profile learn with none. Counts merge across groups (the
      // baseline is household-wide).
      val acc     = scala.collection.mutable.Map.empty[String, Int]
      devsByP.foreach { case (pidOpt, devs) =>
        val macSet  = devs.map(_.mac).toSet
        val appPats = pidOpt.map(pid => atlMap.getOrElse(pid, Nil)).getOrElse(Nil)
        val learned = Presence.isolatedSpanHosts(
          presence.filter(r => macSet.contains(r.mac)),
          settings.ambientIsolationMaxHosts,
          settings.heartbeatFilter,
          settings.presenceContinuationSeconds,
          TimeStatusService.appHostPatterns(appPats),
        )
        learned.foreach { case (h, c) =>
          acc.updateWith(h.value)(prev => Some(prev.getOrElse(0) + c))
        }
      }
      acc.toMap
    }
    _ <- ambientRepo.upsertDay(yesterday, counts)
    // Prune exactly the days the window reads exclude: the reads keep
    // `day > today - windowDays` (strict), so everything at or before that
    // boundary is dead weight — delete `day < boundary + 1`.
    _       <- ambientRepo.pruneBefore(
      today.minusDays(settings.ambientLearningWindowDays.max(1).toLong).plusDays(1L),
    )
    ambient <- ambientRepo.ambientHosts(settings, today)
    _       <- AppMetrics.setAmbientHosts(ambient.size)
  } yield counts.size
}
