package wifihaven.api.db

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import wifihaven.api.db.TypeMeta.given
import wifihaven.api.metrics.DbMetrics
import wifihaven.shared.types.*
import zio.*
import zio.interop.catz.*

import java.time.{Instant, LocalDate}

// #1160: per-(profile, today) cache of `usedSeconds`. The row covers presence
// buckets on `date` with `period_start < rolled_through`; readers add a live
// aggregation of the remaining buckets (period_start >= rolled_through) to
// recover an exact, real-time `usedMinutes`. Stored as seconds (not minutes)
// so the rollup + tail decomposition is exact — truncation happens once at
// read time. See `TimeStatusServiceLive` for the read path and the V43
// migration header for invalidation semantics.

/** Watermarked rollup row: aggregated seconds and the upper bound (`exclusive`) it covers. */
final case class RolledDay(usedSeconds: Long, rolledThrough: Instant)

trait TimeUsedRollupRepo {

  /** Upsert one profile's rolled (seconds, watermark) for `date`. */
  def upsertDay(profileId: ProfileId, date: LocalDate, row: RolledDay): Task[Unit]

  /** Batched upsert — one row per profile for `date`. Returns the count of rows touched. */
  def upsertBatch(date: LocalDate, perProfile: Map[ProfileId, RolledDay]): Task[Int]

  /** Cached row for a single profile/date, or None if not yet rolled. */
  def getDayForProfile(profileId: ProfileId, date: LocalDate): Task[Option[RolledDay]]

  /**
   * Cached rows keyed by profile for `date`, restricted to `household`'s profiles. Missing profiles
   * signal a cache miss.
   *
   * #2264 (follow-up to #2257, epic #2085/#622): scoped via the `profiles.household_id` join
   * (index-backed by `idx_profiles_household`, V65). Replaces the all-tenant `getDayMap`, which
   * read every household's `time_used_daily` rows into memory inside the household-scoped
   * `TimeStatusService.dayStateAll` batch — the third read of this class (not named in #2264; found
   * by the #2563 audit). No all-tenant variant — see `ProfileRepo.distinctHouseholds`.
   */
  def getDayMapForHousehold(
      household: HouseholdId,
      date: LocalDate,
  ): Task[Map[ProfileId, RolledDay]]
}

/**
 * No-op variant for test wiring that doesn't exercise the cache (e.g., the `PolicyService.apply`
 * factory used by snapshot specs). Reads miss; writes are dropped.
 */
object NoopTimeUsedRollupRepo extends TimeUsedRollupRepo {
  def upsertDay(profileId: ProfileId, date: LocalDate, row: RolledDay): Task[Unit]     = ZIO.unit
  def upsertBatch(date: LocalDate, perProfile: Map[ProfileId, RolledDay]): Task[Int]   =
    ZIO.succeed(0)
  def getDayForProfile(profileId: ProfileId, date: LocalDate): Task[Option[RolledDay]] = ZIO.none
  def getDayMapForHousehold(
      household: HouseholdId,
      date: LocalDate,
  ): Task[Map[ProfileId, RolledDay]] = ZIO.succeed(Map.empty)
}

class TimeUsedRollupRepoLive(xa: Transactor[Task]) extends TimeUsedRollupRepo {

  def upsertDay(profileId: ProfileId, date: LocalDate, row: RolledDay): Task[Unit] =
    sql"""INSERT INTO time_used_daily (profile_id, date, used_seconds, rolled_through, rolled_at)
          VALUES ($profileId, $date, ${row.usedSeconds}, ${row.rolledThrough}, NOW())
          ON CONFLICT (profile_id, date) DO UPDATE
            SET used_seconds   = EXCLUDED.used_seconds,
                rolled_through = EXCLUDED.rolled_through,
                rolled_at      = EXCLUDED.rolled_at""".update.run.transact(xa).unit

  def upsertBatch(date: LocalDate, perProfile: Map[ProfileId, RolledDay]): Task[Int] =
    if perProfile.isEmpty then ZIO.succeed(0)
    else {
      val sql  =
        "INSERT INTO time_used_daily (profile_id, date, used_seconds, rolled_through, rolled_at) " +
          "VALUES (?, ?, ?, ?, NOW()) " +
          "ON CONFLICT (profile_id, date) DO UPDATE " +
          "SET used_seconds = EXCLUDED.used_seconds, " +
          "    rolled_through = EXCLUDED.rolled_through, " +
          "    rolled_at = EXCLUDED.rolled_at"
      val rows = perProfile.toList.map { case (pid, r) =>
        (pid, date, r.usedSeconds, r.rolledThrough)
      }
      Update[(ProfileId, LocalDate, Long, Instant)](sql).updateMany(rows).transact(xa)
    }

  def getDayForProfile(profileId: ProfileId, date: LocalDate): Task[Option[RolledDay]] =
    sql"""SELECT used_seconds, rolled_through
          FROM time_used_daily WHERE profile_id=$profileId AND date=$date"""
      .query[(Long, Instant)]
      .map { case (s, t) => RolledDay(s, t) }
      .option
      .transact(xa)

  // #2264: household-scoped via the `profiles.household_id` join (idx_profiles_household, V65).
  // Timed like its two sibling `dayStateAll` reads so the added join is visible on the
  // `db_query_duration_seconds` by-op p95 panel (the missing-index leading indicator, #809).
  def getDayMapForHousehold(
      household: HouseholdId,
      date: LocalDate,
  ): Task[Map[ProfileId, RolledDay]] =
    DbMetrics.timed("timeUsedRollup.getDayMapForHousehold")(
      (fr"""SELECT tud.profile_id, tud.used_seconds, tud.rolled_through
            FROM time_used_daily tud
            JOIN profiles p ON p.id = tud.profile_id
            WHERE tud.date = $date AND """ ++
        SqlFragments.householdEq(household, "p.household_id"))
        .query[(ProfileId, Long, Instant)]
        .map { case (p, s, t) => p -> RolledDay(s, t) }
        .to[List]
        .transact(xa)
        .map(_.toMap),
    )
}
