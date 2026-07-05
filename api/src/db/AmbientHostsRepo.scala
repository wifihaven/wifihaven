package wifihaven.api.db

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import wifihaven.api.metrics.DbMetrics
import wifihaven.api.presence.AmbientGate
import wifihaven.shared.HouseholdSettings
import zio.*
import zio.interop.catz.*

import java.time.LocalDate

// #2077: persistence for the isolation-learned ambient-host baseline
// (docs/design/idle-traffic-discrimination.md, migration V63). One row per
// (host, local day) on which the host appeared in an isolated device span
// (Presence.isolatedSpanHosts). The AMBIENT SET at read time is the hosts with
// >= ambient_min_isolated_days distinct days inside the trailing
// ambient_learning_window_days; the daily learner (AmbientLearnJob) writes one
// day per run and prunes days older than the window, so the table stays bounded
// at (distinct background hosts) x (window days).

/** One host's learning state, for the `GET /api/presence/ambient-hosts` explain surface. */
final case class AmbientHostRow(
    host: String,
    isolatedDays: Int,
    lastIsolatedDay: LocalDate,
    isolatedSpanCount: Int,
)

trait AmbientHostsRepo {

  /**
   * Upsert the learner's result for one local `day`: one row per host with its isolated-span count
   * for that day. Re-running the learner for the same day replaces the count (idempotent).
   */
  def upsertDay(day: LocalDate, counts: Map[String, Int]): Task[Int]

  /** Delete rows with `day < cutoff` (the learner's window prune). Returns rows removed. */
  def pruneBefore(cutoff: LocalDate): Task[Int]

  /**
   * The learned ambient host set per the thresholds in `settings`, evaluated against the trailing
   * window ending at `today` (host qualifies with >= `ambientMinIsolatedDays` distinct days with
   * `day > today - ambientLearningWindowDays`).
   */
  def ambientHosts(settings: HouseholdSettings, today: LocalDate): Task[Set[String]]

  /**
   * The [[AmbientGate]] for the request at hand: `Off` without touching the DB when the master
   * switch is disabled, else the learned set under `enabled = true`. The single assembly point
   * every gating call site loads through, so enablement and the set cannot be paired up two ways.
   */
  def gateFor(settings: HouseholdSettings, today: LocalDate): Task[AmbientGate]

  /**
   * Every host in the trailing window with its distinct-day count — INCLUDING below-threshold
   * candidates — for the explain surface, ordered by days desc then host.
   */
  def listWindow(settings: HouseholdSettings, today: LocalDate): Task[List[AmbientHostRow]]
}

/**
 * #2077: inert stand-in for test constructions that don't exercise the ambient gate (mirrors
 * `NoopTimeUsedRollupRepo` / `NoopNamedScheduleRepo`). `gateFor` returns `Off`, so gating call
 * sites become the identity.
 */
object NoopAmbientHostsRepo extends AmbientHostsRepo {
  def upsertDay(day: LocalDate, counts: Map[String, Int]): Task[Int] = ZIO.succeed(0)
  def pruneBefore(cutoff: LocalDate): Task[Int]                      = ZIO.succeed(0)
  def ambientHosts(settings: HouseholdSettings, today: LocalDate): Task[Set[String]]        =
    ZIO.succeed(Set.empty)
  def gateFor(settings: HouseholdSettings, today: LocalDate): Task[AmbientGate]             =
    ZIO.succeed(AmbientGate.Off)
  def listWindow(settings: HouseholdSettings, today: LocalDate): Task[List[AmbientHostRow]] =
    ZIO.succeed(Nil)
}

class AmbientHostsRepoLive(xa: Transactor[Task]) extends AmbientHostsRepo {

  def upsertDay(day: LocalDate, counts: Map[String, Int]): Task[Int] =
    DbMetrics.timed("ambientHosts.upsertDay") {
      val sql  = """INSERT INTO ambient_host_days (host, day, isolated_span_count)
                   VALUES (?, ?, ?)
                   ON CONFLICT (host, day)
                   DO UPDATE SET isolated_span_count = EXCLUDED.isolated_span_count"""
      val rows = counts.toList.map { case (h, c) => (h, day, c) }
      Update[(String, LocalDate, Int)](sql).updateMany(rows).transact(xa)
    }

  def pruneBefore(cutoff: LocalDate): Task[Int] =
    DbMetrics.timed("ambientHosts.pruneBefore")(
      sql"DELETE FROM ambient_host_days WHERE day < $cutoff".update.run.transact(xa),
    )

  private def windowStart(settings: HouseholdSettings, today: LocalDate): LocalDate =
    today.minusDays(settings.ambientLearningWindowDays.max(1).toLong)

  def ambientHosts(settings: HouseholdSettings, today: LocalDate): Task[Set[String]] =
    DbMetrics.timed("ambientHosts.ambientHosts")(
      sql"""SELECT host FROM ambient_host_days
            WHERE day > ${windowStart(settings, today)}
            GROUP BY host
            HAVING COUNT(DISTINCT day) >= ${settings.ambientMinIsolatedDays.max(1)}"""
        .query[String]
        .to[Set]
        .transact(xa),
    )

  def gateFor(settings: HouseholdSettings, today: LocalDate): Task[AmbientGate] =
    if (!settings.ambientGateEnabled) ZIO.succeed(AmbientGate.Off)
    else ambientHosts(settings, today).map(hosts => AmbientGate(enabled = true, hosts))

  def listWindow(settings: HouseholdSettings, today: LocalDate): Task[List[AmbientHostRow]] =
    DbMetrics.timed("ambientHosts.listWindow")(
      sql"""SELECT host, COUNT(DISTINCT day)::int, MAX(day), SUM(isolated_span_count)::int
            FROM ambient_host_days
            WHERE day > ${windowStart(settings, today)}
            GROUP BY host
            ORDER BY COUNT(DISTINCT day) DESC, host"""
        .query[AmbientHostRow]
        .to[List]
        .transact(xa),
    )
}
