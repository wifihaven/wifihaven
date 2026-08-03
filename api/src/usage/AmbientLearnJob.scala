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

import java.time.{Duration, Instant, LocalDate, ZoneOffset}

/**
 * #2077: the ambient-host learner (docs/design/idle-traffic-discrimination.md). Each tick
 * recomputes the isolated-span host counts for the previous day — one day LABEL, derived in UTC and
 * shared by every household, matched against each household's own local `traffic_reports.date` (see
 * `learnDay` in `doTick`) — via [[Presence.isolatedSpanHosts]], upserts one `ambient_host_days` row
 * per (host, day), prunes rows that aged out of the learning window, and refreshes the
 * `presence_ambient_hosts` gauge.
 *
 * #2553 — the baseline stays GLOBAL, deliberately. `ambient_host_days` carries no `household_id`:
 * it answers "is this host a machine-generated background beacon?", which is a property of the HOST
 * on the public internet, not of a household. Every household's isolated spans are evidence for the
 * same question, and keying the table per household would starve the signal exactly where it
 * matters most — a three-device household would take weeks of its own idle nights to clear
 * `ambient_min_isolated_days`, while the merged set identifies an Apple push endpoint on day one.
 * The learning INPUTS (isolation knob, heartbeat filter, session-stitch knob, per-profile app
 * attribution) and the READ thresholds (`ambient_min_isolated_days`,
 * `ambient_learning_window_days`, `ambient_gate_enabled`) are per household, so a household still
 * decides what counts as isolated and what qualifies as ambient for ITS OWN screen time. The
 * residual coupling — one household's traffic contributing days for a host another household reads
 * — is the point of a shared popularity signal, and it can only ever SUPPRESS attribution for a
 * host that habitually appears alone; if prod evidence ever shows a genuinely-engaged host learned
 * ambient from another tenant, the fix is a household-keyed table behind its own migration, not an
 * implicit change here.
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
  ): Task[Int] = {
    // #2553: which day to learn is a household-independent LABEL, derived in UTC — not a UTC time
    // window. `traffic_reports.date` is stamped at ingest with the reporting household's OWN local
    // date (`RouterIngestService.handleUsage` → `PolicyService.householdLocalDate`), and
    // `listPresenceRows` filters on that column, so each household still learns over its own local
    // day; only the label picking WHICH local day is shared.
    //
    // It has to be shared because the baseline is one global `(host, day)` table (see the object
    // doc) and `upsertDay` REPLACES the count for a `(host, day)` row. Per-household labels would
    // have a household whose local midnight is ahead write label D at one tick, and a household
    // behind it write the same label D at a later tick — the second write dropping the first
    // household's contribution. (Households sharing a label within ONE tick are safe: their counts
    // merge before the single write below.) A shared label makes every household's contribution to
    // a day land in the same write, and re-learning a day is idempotent, so at the 6-hour cadence
    // each label converges to its complete value however far a household sits from UTC.
    val learnDay = LocalDate.ofInstant(now, ZoneOffset.UTC).minusDays(1L)
    for {
      // #2257: this is a genuinely all-tenant batch — enumerate households explicitly and union each
      // one's scoped read, rather than a cross-tenant `listAll` that a request path could also grab.
      // #2313: the presence read AND the profile-group learning run PER HOUSEHOLD — `traffic_reports`
      // is router_id-keyed and the same MAC can exist in >1 household (post-V74), so a global read +
      // global grouping would let one household's traffic on a shared MAC seed the ambient baseline
      // from another household's device. Each household learns over only its own scoped presence; the
      // per-host counts merge afterward (the baseline is a single global host set). Identical for
      // distinct MACs.
      // #2553: the learning INPUTS are per household too — the isolation knob, the heartbeat filter
      // and the session-stitch knob now come from each household's own settings row instead of the
      // operator household's.
      households <- profileRepo.distinctHouseholds
      perHh      <- ZIO.foreach(households) { hh =>
        for {
          // Fails loud for a household with no settings row (#2386 / no-dark-by-default) — see the
          // matching note in `TimeUsedRollupJob.doTick`.
          settings <- hs.getForHousehold(hh)
          profiles <- profileRepo.listAllForHousehold(hh)
          devices  <- deviceRepo.listAllForHousehold(hh)
          atlsP <- ZIO.foreach(profiles)(p => appTimeLimitRepo.listForProfile(p.id).map(p.id -> _))
          presence <- trafficRepo.listPresenceRows(hh, devices.map(_.mac), learnDay)
        } yield {
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
          // The prune horizon and the gauge are per-household reads of the same global table, so each
          // household carries its own local `today` and window alongside its counts.
          (settings, PolicyService.householdLocalDate(now, settings), acc.toMap)
        }
      }
      // Merge each household's per-host isolated-span counts into the single global baseline.
      counts = perHh.foldLeft(Map.empty[String, Int]) { case (acc, (_, _, hhCounts)) =>
        hhCounts.foldLeft(acc) { case (a, (h, c)) => a.updated(h, a.getOrElse(h, 0) + c) }
      }
      _          <- ambientRepo.upsertDay(learnDay, counts)
      // Prune exactly the days the window reads exclude: the reads keep
      // `day > today - windowDays` (strict), so everything at or before that
      // boundary is dead weight — delete `day < boundary + 1`.
      // #2553: the window is per-household, so the cutoff is the EARLIEST any household still needs —
      // pruning on one household's shorter window would truncate another's read window. With no
      // households there is nothing to keep the table for, but nothing was written either, so skip.
      _          <- ZIO
        .foreach(perHh.map { case (settings, today, _) =>
          today.minusDays(settings.ambientLearningWindowDays.max(1).toLong).plusDays(1L)
        }.minOption)(ambientRepo.pruneBefore)
      // The gauge is the union of what each household's own thresholds resolve to over the shared
      // baseline — "hosts learned ambient for somebody" — since the thresholds are per household but
      // the table is not.
      ambient    <- ZIO
        .foreach(perHh) { case (settings, today, _) => ambientRepo.ambientHosts(settings, today) }
        .map(_.foldLeft(Set.empty[String])(_ ++ _))
      _          <- AppMetrics.setAmbientHosts(ambient.size)
    } yield counts.size
  }
}
