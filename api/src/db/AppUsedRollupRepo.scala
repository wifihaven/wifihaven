package wifihaven.api.db

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import wifihaven.api.db.TypeMeta.given
import wifihaven.shared.types.*
import zio.*
import zio.interop.catz.*

import java.time.{Instant, LocalDate}

// #1516 (sub-issue of #1510): per-(profile, app, date) cache of app-aggregated,
// gap-bridged *engaged* seconds — the engaged-time counterpart of V43's
// per-(profile, date) `time_used_daily`, sliced by app. Backs the `app_used_daily`
// table (V53). A row covers the presence buckets on `date` with
// `period_start < rolled_through`; readers add a live aggregation of this app's
// host-set over the remaining buckets (`period_start >= rolled_through`) to recover
// an exact, real-time per-app engaged figure. Stored as seconds (mirroring
// `time_used_daily.used_seconds`) so the rolled + tail decomposition stays exact —
// truncation to minutes happens once at read time. See `AppUsedRollupService` for
// the read path and the V53 migration header for the keying / invalidation rationale.

/** Watermarked per-app rollup row: aggregated engaged seconds and the upper bound (`exclusive`) it covers. */
final case class RolledAppDay(engagedSeconds: Long, rolledThrough: Instant)

trait AppUsedRollupRepo {

  /** Upsert one (profile, app) rolled (engaged seconds, watermark) for `date`. */
  def upsertDay(profileId: ProfileId, appId: AppId, date: LocalDate, row: RolledAppDay): Task[Unit]

  /**
   * Batched upsert — one row per (profile, app) for `date`. The whole tick shares one `rolled_through`
   * watermark, so callers pass a single `(profile, app) -> RolledAppDay`. Returns the count of rows
   * touched.
   */
  def upsertBatch(date: LocalDate, rows: Map[(ProfileId, AppId), RolledAppDay]): Task[Int]

  /**
   * Cached rows for a single profile/date, keyed by app. An EMPTY map signals a cache miss for the
   * profile (no app had engaged activity in the rolled window) and steers the read path to the
   * all-live fallback, mirroring how `time_used_daily` treats a missing row.
   */
  def getDayForProfile(profileId: ProfileId, date: LocalDate): Task[Map[AppId, RolledAppDay]]

  /**
   * Drop every cached row. Called from `HouseholdSettingsRepoLive.update` alongside the
   * `time_used_daily` invalidation so any change to the heartbeat filter, the daily-reset boundary,
   * or the presence session-stitch knob forces the next rollup tick to refill from first principles.
   * The table is bounded (one row per profile × app × active date), so the DELETE is cheap.
   */
  def deleteAll: Task[Unit]
}

/**
 * No-op variant for test wiring / read paths that don't exercise the cache. Reads miss (empty map);
 * writes are dropped. Mirrors [[NoopTimeUsedRollupRepo]].
 */
object NoopAppUsedRollupRepo extends AppUsedRollupRepo {
  def upsertDay(profileId: ProfileId, appId: AppId, date: LocalDate, row: RolledAppDay): Task[Unit] =
    ZIO.unit
  def upsertBatch(date: LocalDate, rows: Map[(ProfileId, AppId), RolledAppDay]): Task[Int]         =
    ZIO.succeed(0)
  def getDayForProfile(profileId: ProfileId, date: LocalDate): Task[Map[AppId, RolledAppDay]]      =
    ZIO.succeed(Map.empty)
  def deleteAll: Task[Unit]                                                                        =
    ZIO.unit
}

class AppUsedRollupRepoLive(xa: Transactor[Task]) extends AppUsedRollupRepo {

  def upsertDay(
      profileId: ProfileId,
      appId: AppId,
      date: LocalDate,
      row: RolledAppDay,
  ): Task[Unit] =
    sql"""INSERT INTO app_used_daily (profile_id, app_id, date, engaged_seconds, rolled_through, rolled_at)
          VALUES ($profileId, $appId, $date, ${row.engagedSeconds}, ${row.rolledThrough}, NOW())
          ON CONFLICT (profile_id, app_id, date) DO UPDATE
            SET engaged_seconds = EXCLUDED.engaged_seconds,
                rolled_through  = EXCLUDED.rolled_through,
                rolled_at       = EXCLUDED.rolled_at""".update.run.transact(xa).unit

  def upsertBatch(date: LocalDate, rows: Map[(ProfileId, AppId), RolledAppDay]): Task[Int] =
    if rows.isEmpty then ZIO.succeed(0)
    else {
      val sql      =
        "INSERT INTO app_used_daily (profile_id, app_id, date, engaged_seconds, rolled_through, rolled_at) " +
          "VALUES (?, ?, ?, ?, ?, NOW()) " +
          "ON CONFLICT (profile_id, app_id, date) DO UPDATE " +
          "SET engaged_seconds = EXCLUDED.engaged_seconds, " +
          "    rolled_through  = EXCLUDED.rolled_through, " +
          "    rolled_at       = EXCLUDED.rolled_at"
      val batch    = rows.toList.map { case ((pid, aid), r) =>
        (pid, aid, date, r.engagedSeconds, r.rolledThrough)
      }
      Update[(ProfileId, AppId, LocalDate, Long, Instant)](sql).updateMany(batch).transact(xa)
    }

  def getDayForProfile(profileId: ProfileId, date: LocalDate): Task[Map[AppId, RolledAppDay]] =
    sql"""SELECT app_id, engaged_seconds, rolled_through
          FROM app_used_daily WHERE profile_id=$profileId AND date=$date"""
      .query[(AppId, Long, Instant)]
      .map { case (a, s, t) => a -> RolledAppDay(s, t) }
      .to[List]
      .transact(xa)
      .map(_.toMap)

  def deleteAll: Task[Unit] =
    sql"DELETE FROM app_used_daily".update.run.transact(xa).unit
}
