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
    days    = learned.flatten
    skipped = learned.count(_.isEmpty)
    // ── What the GLOBAL writes key on when households disagree about "yesterday" ──
    //
    // The ambient baseline is deliberately ONE global host set (`ambient_host_days` is keyed
    // `(host, day)` with no household_id — docs/design/idle-traffic-discrimination.md). Only the
    // learning INPUTS are per-household. The day key is NOT: it stays a single global sequence,
    // [[globalLearnDay]] (the UTC day before `now`).
    //
    // Keying the write on each household's OWN local `yesterday` is the obvious-looking choice and
    // is WRONG, because of how the day column is read. `ambientHosts` qualifies a host on
    // `COUNT(DISTINCT day) >= ambientMinIsolatedDays` over the whole table (AmbientHostsRepo). The
    // `day` column is therefore not a fact about when a span happened — it is the bucket that
    // counts "how many separate days has this host shown up isolated". Per-household day keys make
    // ONE tick write the SAME host under two different buckets whenever two households' local dates
    // disagree, so a host shared between them (every Apple/Google background host is) accrues
    // distinct days at up to twice the real rate and crosses the operator's threshold in half the
    // configured time. It would then be ambient-gated — i.e. its spans stop counting as screen time
    // — earlier than configured, silently under-counting. A global day sequence advances exactly
    // once per calendar day no matter how many households exist, which is the semantics
    // `ambientMinIsolatedDays` was calibrated against. (Pre-#2553 the single bucket was household
    // #1's LOCAL yesterday, not UTC — same once-per-calendar-day property, different offset. The
    // cutover can therefore shift the bucket by at most a day once.)
    //
    // Each household still learns over ITS OWN local `yesterday`'s presence with ITS OWN knobs
    // ([[learnHousehold]]) — that is the #2553 fix. Only the bucket the results land in is shared.
    //
    // Counts from all households merge into that one bucket, as they did pre-#2553: `upsertDay`
    // REPLACES the count for a (host, day), so the merge must happen here or the last writer would
    // silently discard everyone else's counts. NOTE: when a household is skipped
    // ([[HouseholdTickIsolation]]) its contribution is absent from the rewritten row rather than
    // preserved — one tenant's transient failure lowers a shared count. Bounded and self-healing:
    // the qualifying read counts DISTINCT DAYS, not magnitudes, so only the `listWindow` explain
    // surface shows it, and the next successful tick (6 h) restores it.
    _       <- ZIO.when(days.nonEmpty)(
      ambientRepo.upsertDay(globalLearnDay(now), mergeCounts(days.map(_.counts))),
    )
    // Retention on a shared table must be the UNION of every household's window: pruning at a
    // short-window household's cutoff would delete days a long-window household still reads. So the
    // cutoff is the EARLIEST across households — computed from the LONGEST `ambientLearningWindowDays`
    // of the households whose settings we actually read.
    //
    // A tick with ANY skipped household does not prune at all. `days` excludes skipped households,
    // so pruning on a partial set could delete rows inside the missing household's window —
    // permanently, and precisely because of an unrelated tenant's transient failure. Skipping the
    // prune is free (the table just carries one extra cycle of rows) and self-heals next tick,
    // whereas a wrong prune is unrecoverable.
    _       <- ZIO
      .foreachDiscard(pruneCutoff(days))(ambientRepo.pruneBefore)
      .unless(skipped > 0)
    _       <- ZIO
      .logWarning(
        s"ambient_hosts prune skipped: $skipped household(s) were skipped this tick, so the " +
          "retention window is not fully known (see wifihaven_rollup_household_skipped_total)",
      )
      .when(skipped > 0)
    // The gauge is unlabelled and the ambient SET is per-household (one table, but each household
    // applies its own thresholds and window to it), so it reports the size of the UNION — "how many
    // distinct hosts are ambient-gated for at least one household". A sum would double-count the
    // hosts every household shares; picking one household's value would reintroduce exactly the
    // #2553 bug in the observability surface. Read AFTER the writes above so the gauge reflects this
    // tick, as it did pre-#2553.
    ambient <- ZIO.foreach(days)(d => currentAmbientHosts(ambientRepo, d))
    _       <- AppMetrics.setAmbientHosts(ambient.foldLeft(Set.empty[String])(_ ++ _).size)
  } yield days.flatMap(_.counts.keys).distinct.size

  /**
   * The single global bucket this tick's learning lands in: the UTC day before `now`. Deliberately
   * NOT household-local — see the rationale in [[doTick]]. It advances once per calendar day, which
   * is what the `COUNT(DISTINCT day) >= ambientMinIsolatedDays` read is calibrated against.
   */
  private[api] def globalLearnDay(now: Instant): LocalDate =
    now.atZone(java.time.ZoneOffset.UTC).toLocalDate.minusDays(1L)

  /**
   * Prune boundary: the earliest cutoff any household's window implies. `None` when no household
   * contributed (nothing to base it on).
   *
   * Each household's read is `ambientHosts(itsSettings, itsLocalToday)`, which keeps `day > today -
   * windowDays` (strict) — so its own boundary is `today - windowDays + 1`, computed from ITS local
   * today (which can differ from UTC by a day) and ITS window. The global cutoff is the earliest of
   * those, so no household loses a day it still reads.
   */
  private[usage] def pruneCutoff(days: List[LearnedDay]): Option[LocalDate] =
    days
      .map(d =>
        d.localToday.minusDays(d.settings.ambientLearningWindowDays.max(1).toLong).plusDays(1L),
      )
      .reduceOption((a, b) => if (a.isBefore(b)) a else b)

  /**
   * One household's learning result. Carries the household's resolved `settings` and `localToday`
   * so the prune boundary and the gauge read don't re-read and re-derive what this pass already
   * resolved (one settings read per household per tick, single-source).
   */
  private[usage] final case class LearnedDay(
      household: HouseholdId,
      localToday: LocalDate,
      counts: Map[String, Int],
      settings: wifihaven.shared.HouseholdSettings,
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
        HouseholdTickIsolation.ReasonSettingsRead,
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
                Some(LearnedDay(hh, today, acc.toMap, settings))
              }
            }
      }

  /**
   * This household's ambient set under its OWN thresholds/window, for the union gauge. Reuses the
   * settings and local date [[learnHousehold]] already resolved rather than re-reading them, so the
   * gauge cannot be computed from a different snapshot than the learning was.
   */
  private def currentAmbientHosts(
      ambientRepo: AmbientHostsRepo,
      d: LearnedDay,
  ): Task[Set[String]] =
    HouseholdTickIsolation.isolate(
      JobName,
      d.household,
      HouseholdTickIsolation.ReasonError,
      Set.empty[String],
    )(ambientRepo.ambientHosts(d.settings, d.localToday))
}
