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
import wifihaven.shared.types.HouseholdId
import zio.*

import java.time.{Duration, Instant, LocalDate}

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

  /**
   * The `rollup_job` label / `rollup_runs.job` name for this job. Single-sourced so the run record
   * and the #2553 per-household skip counter can never name it two different ways.
   */
  val JobName: String = "ambient_hosts"

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
            runs.recordRun(JobName, started, finished, "ok", None, n).ignore
        case Left(e) if RollupShutdown.isPoolClosed(e) =>
          ZIO.logDebug("ambient_hosts learn tick aborted (pool closed during shutdown)")
        case Left(e)                                   =>
          ZIO.logErrorCause("ambient_hosts learn tick failed", Cause.fail(e)) *>
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

  private def doTick(
      ambientRepo: AmbientHostsRepo,
      profileRepo: ProfileRepo,
      deviceRepo: DeviceRepo,
      appTimeLimitRepo: AppTimeLimitRepo,
      trafficRepo: TrafficReportRepo,
      hs: HouseholdSettingsRepo,
      now: Instant,
  ): Task[Int] = for {
    // #2257: this is a genuinely all-tenant batch — enumerate households explicitly and union each
    // one's scoped read, rather than a cross-tenant `listAll` that a request path could also grab.
    // #2313: the presence read AND the profile-group learning run PER HOUSEHOLD — `traffic_reports`
    // is router_id-keyed and the same MAC can exist in >1 household (post-V74), so a global read +
    // global grouping would let one household's traffic on a shared MAC seed the ambient baseline
    // from another household's device. Each household learns over only its own scoped presence; the
    // per-host counts merge afterward (the baseline is a single global host set). Identical for
    // distinct MACs.
    // #2553: the SETTINGS are per-household too — they fix the day key (`yesterday`) and supply
    // `ambientIsolationMaxHosts` / `heartbeatFilter` / `presenceContinuationSeconds` to the
    // isolated-span learner, so reading them once per tick learned every tenant's baseline under
    // household #1's timezone and thresholds.
    households <- profileRepo.distinctHouseholds
    learned    <- ZIO.foreach(households)(hh =>
      learnHousehold(profileRepo, deviceRepo, appTimeLimitRepo, trafficRepo, hs, now, hh),
    )
    days = learned.flatten
    // ── What the GLOBAL writes key on when households disagree about "yesterday" ──
    //
    // The ambient baseline is deliberately ONE global host set (`ambient_host_days` is keyed
    // `(host, day)` with no household_id — design: idle-traffic-discrimination.md). Only the
    // learning INPUTS and the day key are per-household, so the two global writes need an explicit
    // rule rather than an arbitrary household's value:
    //
    //   upsertDay — keyed on EACH household's OWN `yesterday`, grouped and MERGED first. `upsertDay`
    //     REPLACES the count for a (host, day), so two households that share a day key must be
    //     summed into one call; calling it twice would silently discard the first household's
    //     counts. Households whose local yesterday differs simply write different day rows, which
    //     is the honest representation — a span really did happen on that household's day.
    //
    //   pruneBefore — the EARLIEST (most generous) cutoff across households, not the writer's own.
    //     Retention on a shared table must be the UNION of every household's window: pruning at a
    //     short-window household's cutoff would delete days a long-window household still reads.
    //     Extra retained days cost nothing — each household's read (`ambientHosts(settings, today)`)
    //     re-filters to its own window — and the table stays bounded by the LONGEST window. With no
    //     households there is no basis for a cutoff, so nothing is pruned.
    _       <- ZIO.foreachDiscard(days.groupBy(_.day)) { case (day, forDay) =>
      ambientRepo.upsertDay(day, mergeCounts(forDay.map(_.counts)))
    }
    _       <- ZIO.foreachDiscard(
      days.map(_.pruneCutoff).reduceOption((a, b) => if (a.isBefore(b)) a else b),
    )(ambientRepo.pruneBefore)
    // The gauge is unlabelled and the ambient SET is now per-household (same table, each
    // household's own thresholds and window), so it reports the size of the UNION — "how many
    // distinct hosts are ambient-gated for at least one household". A sum would double-count the
    // hosts every household shares; picking one household's value would reintroduce exactly the
    // #2553 bug in the observability surface. Read AFTER the upsert/prune above so the gauge
    // reflects this tick's writes, as it did pre-#2553.
    ambient <- ZIO.foreach(days.map(_.household).distinct)(hh =>
      currentAmbientHosts(ambientRepo, hs, now, hh),
    )
    _       <- AppMetrics.setAmbientHosts(ambient.foldLeft(Set.empty[String])(_ ++ _).size)
  } yield days.flatMap(_.counts.keys).distinct.size

  /** One household's learning result: its own day key, counts, and window cutoff. */
  private final case class LearnedDay(
      household: HouseholdId,
      day: LocalDate,
      counts: Map[String, Int],
      pruneCutoff: LocalDate,
  )

  private def mergeCounts(all: List[Map[String, Int]]): Map[String, Int] =
    all.foldLeft(Map.empty[String, Int]) { (acc, m) =>
      m.foldLeft(acc) { case (a, (h, c)) => a.updated(h, a.getOrElse(h, 0) + c) }
    }

  /**
   * One household's slice: its own settings → its own `yesterday` → isolated-span host counts over
   * only its own scoped presence, gated by its own knobs. `None` when the household was skipped;
   * see [[HouseholdTickIsolation]] for why a skip beats failing the whole tick.
   */
  private def learnHousehold(
      profileRepo: ProfileRepo,
      deviceRepo: DeviceRepo,
      appTimeLimitRepo: AppTimeLimitRepo,
      trafficRepo: TrafficReportRepo,
      hs: HouseholdSettingsRepo,
      now: Instant,
      hh: HouseholdId,
  ): Task[Option[LearnedDay]] =
    HouseholdTickIsolation
      .isolate(
        JobName,
        hh,
        HouseholdTickIsolation.ReasonSettingsMissing,
        Option.empty[
          wifihaven.shared.HouseholdSettings,
        ],
      )(hs.getForHousehold(hh).map(Some(_)))
      .flatMap {
        case None           => ZIO.none
        case Some(settings) =>
          HouseholdTickIsolation
            .isolate(JobName, hh, HouseholdTickIsolation.ReasonError, Option.empty[LearnedDay]) {
              val today     = PolicyService.householdLocalDate(now, settings)
              val yesterday = today.minusDays(1L)
              for {
                profiles <- profileRepo.listAllForHousehold(hh)
                devices  <- deviceRepo.listAllForHousehold(hh)
                atlsP    <- ZIO.foreach(profiles)(p =>
                  appTimeLimitRepo.listForProfile(p.id).map(p.id -> _),
                )
                presence <- trafficRepo.listPresenceRows(hh, devices.map(_.mac), yesterday)
              } yield {
                val atlMap  = atlsP.toMap
                val devsByP = devices.groupBy(_.profileId)
                // Learn per profile-group so each device's spans see its own profile's
                // app-attribution context; devices with no profile learn with none. Counts merge
                // across groups (the baseline is household-wide).
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
                Some(
                  LearnedDay(
                    hh,
                    yesterday,
                    acc.toMap,
                    // Prune exactly the days the window reads exclude: the reads keep
                    // `day > today - windowDays` (strict), so everything at or before that boundary
                    // is dead weight — delete `day < boundary + 1`.
                    today
                      .minusDays(settings.ambientLearningWindowDays.max(1).toLong)
                      .plusDays(1L),
                  ),
                )
              }
            }
      }

  /** This household's ambient set under its OWN thresholds/window, for the union gauge. */
  private def currentAmbientHosts(
      ambientRepo: AmbientHostsRepo,
      hs: HouseholdSettingsRepo,
      now: Instant,
      hh: HouseholdId,
  ): Task[Set[String]] =
    HouseholdTickIsolation.isolate(
      JobName,
      hh,
      HouseholdTickIsolation.ReasonError,
      Set.empty[String],
    )(
      hs.getForHousehold(hh)
        .flatMap(s => ambientRepo.ambientHosts(s, PolicyService.householdLocalDate(now, s))),
    )
}
