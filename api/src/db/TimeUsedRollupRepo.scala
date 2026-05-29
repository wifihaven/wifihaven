package wifihaven.api.db

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import wifihaven.api.db.TypeMeta.given
import wifihaven.shared.types.*
import zio.*
import zio.interop.catz.*

import java.time.LocalDate

// #1160: persistent rollup of `TimeStatusService.dayStateAll(...).usedMinutes`,
// keyed by (profile, household-local date). Read by the /api/time/status/summary
// route (and any other consumer that wants the cached total) instead of the
// per-request raw aggregation. Invalidation is wholesale — see the V43 migration
// header and HouseholdSettingsRepoLive.update for the trigger.
trait TimeUsedRollupRepo {

  /** Upsert the cached `usedMinutes` for one profile/date. */
  def upsertDay(profileId: ProfileId, date: LocalDate, usedMinutes: Int): Task[Unit]

  /**
   * Batched upsert — one row per profile for `date`, all in a single transaction.
   *
   * Returns the count of rows touched.
   */
  def upsertBatch(date: LocalDate, perProfile: Map[ProfileId, Int]): Task[Int]

  /** Cached `usedMinutes` for a single profile/date, or None if not yet rolled. */
  def getDayForProfile(profileId: ProfileId, date: LocalDate): Task[Option[Int]]

  /** Cached `usedMinutes` keyed by profile for `date`. Missing profiles signal a cache miss. */
  def getDayMap(date: LocalDate): Task[Map[ProfileId, Int]]

  /**
   * Drop every cached row. Called from `HouseholdSettingsRepoLive.update` so any change to the
   * household-local day boundary or the heartbeat filter forces a fresh re-roll. Cheap — the table
   * is bounded by retention × profiles.
   */
  def deleteAll: Task[Unit]
}

/**
 * No-op variant for test wiring that doesn't need cache behaviour (e.g., the `PolicyService.apply`
 * factory used by the snapshot specs — those exercise today, which is always live). All reads miss
 * and all writes are dropped; semantically equivalent to no rollup.
 */
object NoopTimeUsedRollupRepo extends TimeUsedRollupRepo {
  def upsertDay(profileId: ProfileId, date: LocalDate, usedMinutes: Int): Task[Unit] = ZIO.unit
  def upsertBatch(date: LocalDate, perProfile: Map[ProfileId, Int]): Task[Int]   = ZIO.succeed(0)
  def getDayForProfile(profileId: ProfileId, date: LocalDate): Task[Option[Int]] = ZIO.none
  def getDayMap(date: LocalDate): Task[Map[ProfileId, Int]] = ZIO.succeed(Map.empty)
  def deleteAll: Task[Unit]                                 = ZIO.unit
}

class TimeUsedRollupRepoLive(xa: Transactor[Task]) extends TimeUsedRollupRepo {

  def upsertDay(profileId: ProfileId, date: LocalDate, usedMinutes: Int): Task[Unit] =
    sql"""INSERT INTO time_used_daily (profile_id, date, used_minutes, rolled_at)
          VALUES ($profileId, $date, $usedMinutes, NOW())
          ON CONFLICT (profile_id, date) DO UPDATE
            SET used_minutes = EXCLUDED.used_minutes,
                rolled_at    = EXCLUDED.rolled_at""".update.run.transact(xa).unit

  def upsertBatch(date: LocalDate, perProfile: Map[ProfileId, Int]): Task[Int] =
    if perProfile.isEmpty then ZIO.succeed(0)
    else {
      val sql  = "INSERT INTO time_used_daily (profile_id, date, used_minutes, rolled_at) " +
        "VALUES (?, ?, ?, NOW()) " +
        "ON CONFLICT (profile_id, date) DO UPDATE " +
        "SET used_minutes = EXCLUDED.used_minutes, rolled_at = EXCLUDED.rolled_at"
      val rows = perProfile.toList.map { case (pid, used) => (pid, date, used) }
      Update[(ProfileId, LocalDate, Int)](sql).updateMany(rows).transact(xa)
    }

  def getDayForProfile(profileId: ProfileId, date: LocalDate): Task[Option[Int]] =
    sql"SELECT used_minutes FROM time_used_daily WHERE profile_id=$profileId AND date=$date"
      .query[Int]
      .option
      .transact(xa)

  def getDayMap(date: LocalDate): Task[Map[ProfileId, Int]] =
    sql"SELECT profile_id, used_minutes FROM time_used_daily WHERE date=$date"
      .query[(ProfileId, Int)]
      .to[List]
      .transact(xa)
      .map(_.toMap)

  def deleteAll: Task[Unit] =
    sql"DELETE FROM time_used_daily".update.run.transact(xa).unit
}
