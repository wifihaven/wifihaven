package wifihaven.api.db

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import wifihaven.api.db.TypeMeta.given
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

  /** Cached rows keyed by profile for `date`. Missing profiles signal a cache miss. */
  def getDayMap(date: LocalDate): Task[Map[ProfileId, RolledDay]]

  /**
   * Drop every cached row. Called from `HouseholdSettingsRepoLive.update` so any change to the
   * heartbeat filter or the daily-reset boundary forces the next rollup tick to refill from first
   * principles. The table is bounded (one row per profile per active date), so the DELETE is cheap.
   */
  def deleteAll: Task[Unit]
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
  def getDayMap(date: LocalDate): Task[Map[ProfileId, RolledDay]] = ZIO.succeed(Map.empty)
  def deleteAll: Task[Unit]                                       = ZIO.unit
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

  def getDayMap(date: LocalDate): Task[Map[ProfileId, RolledDay]] =
    sql"""SELECT profile_id, used_seconds, rolled_through
          FROM time_used_daily WHERE date=$date"""
      .query[(ProfileId, Long, Instant)]
      .map { case (p, s, t) => p -> RolledDay(s, t) }
      .to[List]
      .transact(xa)
      .map(_.toMap)

  def deleteAll: Task[Unit] =
    sql"DELETE FROM time_used_daily".update.run.transact(xa).unit
}
