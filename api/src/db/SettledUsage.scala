package wifihaven.api.db

import cats.data.NonEmptyList
import cats.implicits.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import wifihaven.api.db.TypeMeta.given
import wifihaven.api.metrics.DbMetrics
import wifihaven.api.policy.PolicyService
import wifihaven.api.usage.TimeUsedRollupJob
import wifihaven.shared.{HeartbeatFilter, HouseholdSettings}
import wifihaven.shared.types.*
import zio.*
import zio.interop.catz.*

import java.time.{Duration, Instant, LocalDate, LocalTime, ZoneId}

/**
 * #2932 (design `docs/design/weekly-limits-and-app-groups.md` §6.4): settled past days.
 *
 * A household-local day's rows in `time_used_daily`, `app_used_daily` and `app_group_used_daily`
 * are SETTLED once `rolled_through` reaches the end of the day. Weekly usage reads settled rows for
 * the past days of a week instead of re-reading their presence, so a week costs one indexed range
 * read per table in the steady state.
 *
 * A row is only valid for the dispositions it was computed under. Every write that changes what a
 * profile's usage means calls [[SettledUsage.invalidateCurrentWeek]] (or one of its two resolving
 * forms) in the SAME transaction, so the next read recomputes the week under the new definition
 * (D7, retroactive). This object is the ONE definition of that invalidation: the assignment and
 * template-host writes here and the group / attachment CRUD (#2934) all call it.
 */
object SettledUsage {

  /**
   * How long after a day's end it becomes settleable. Until then the day is read the way today is
   * (its rolled row plus a live tail), so a report that arrives during the delay is still counted.
   * Two rollup intervals (30 min). It does not estimate the true report lag, which is unverified;
   * `budget_late_presence_rows_total` measures the reports that arrive later (design §6.4, §16).
   */
  val SettleDelay: Duration = TimeUsedRollupJob.Interval.multipliedBy(2)

  /** The instant household-local `date` ends: `dailyResetTime` on the next day. */
  def dayEnd(date: LocalDate, settings: HouseholdSettings): Instant =
    AttributionScope.dayWindow(date, settings)._2

  /** Whether `date` may be settled at `now`: [[SettleDelay]] has passed since it ended. */
  def settleable(date: LocalDate, now: Instant, settings: HouseholdSettings): Boolean =
    !now.isBefore(dayEnd(date, settings).plus(SettleDelay))

  /** What a write changed, and so which settled rows it makes stale (design §6.4). */
  enum Kinds {

    /**
     * The profile's dispositions: an assignment create, delete or mode change; an attachment create
     * or delete; a membership edit; an app's host-set changing. Each can move any app's or group's
     * minutes (the attribution set, finding 8) and the daily total, so all three tables go.
     */
    case All

    /**
     * Only which traffic the profile daily total counts: an `exemptFromDaily` edit on an assignment
     * or an attachment. App and group minutes do not change, so only `time_used_daily` goes.
     */
    case ProfileTotalOnly
  }

  /**
   * Delete the settled (and today's rolling) rows of `profiles` for the household week containing
   * `now`, from its first day through today, as `what` says. Each profile's week comes from its own
   * household's settings, read in the same transaction. Today's rows go too, so the next read of
   * today is all-live until the rollup tick refills it under the new definition. Rows of earlier
   * weeks are kept: no budget reads them, and the per-app history views do. Returns the number of
   * rows deleted.
   */
  def invalidateCurrentWeek(
      profiles: List[ProfileId],
      what: Kinds,
      now: Instant,
  ): ConnectionIO[Int] =
    NonEmptyList.fromList(profiles.distinct) match {
      case None      => 0.pure[ConnectionIO]
      case Some(nel) =>
        (fr"""SELECT p.id, p.household_id, hs.daily_reset_time, hs.daily_reset_tz, hs.week_start_day
                FROM profiles p
                JOIN household_settings hs ON hs.household_id = p.household_id
               WHERE""" ++ Fragments.in(fr"p.id", nel))
          .query[(ProfileId, HouseholdId, LocalTime, ZoneId, Int)]
          .to[List]
          .flatTap(rows => rows.map(_._2).distinct.sortBy(_.value).traverse_(lockHousehold))
          .flatMap(_.traverse { case (pid, _, reset, tz, weekStartDay) =>
            val settings =
              HouseholdSettings(reset, tz, HeartbeatFilter.Off, weekStartDay = weekStartDay)
            val from     = PolicyService.householdWeekStart(now, settings)
            val today    = PolicyService.householdLocalDate(now, settings)
            val week     = fr"profile_id = $pid AND date BETWEEN $from AND $today"
            val total    = (fr"DELETE FROM time_used_daily WHERE" ++ week).update.run
            what match {
              case Kinds.ProfileTotalOnly => total
              case Kinds.All              =>
                (
                  total,
                  (fr"DELETE FROM app_used_daily WHERE" ++ week).update.run,
                  (fr"DELETE FROM app_group_used_daily WHERE" ++ week).update.run,
                ).mapN(_ + _ + _)
            }
          })
          .map(_.sum)
    }

  /**
   * The per-household lock that orders an invalidation against a refill's write (held to the end of
   * the caller's transaction). Every invalidation takes it before deleting, and
   * [[SettledUsageRepo.writeSettled]] takes it before checking its fingerprint, so a refill either
   * writes before an invalidating write commits (whose delete then removes its rows) or sees that
   * write's changes and does not write. Households are locked in id order.
   */
  def lockHousehold(household: HouseholdId): ConnectionIO[Unit] = {
    val key = (0x2932L << 32) | household.value
    sql"SELECT pg_advisory_xact_lock($key)".query[Unit].unique
  }

  /**
   * [[invalidateCurrentWeek]] for every profile whose dispositions include `app`: the profiles that
   * assign it, and the profiles with a group attached that has it as a member. For an app-level
   * write (a host-set change, a merge) that moves those profiles' minutes.
   */
  def invalidateForApp(app: AppId, now: Instant): ConnectionIO[Int] =
    sql"""SELECT profile_id FROM app_policy_assignments WHERE app_id = $app
          UNION
          SELECT pag.profile_id
            FROM profile_app_groups pag
            JOIN app_group_members m ON m.group_id = pag.group_id
           WHERE m.app_id = $app"""
      .query[ProfileId]
      .to[List]
      .flatMap(invalidateCurrentWeek(_, Kinds.All, now))

  /**
   * [[invalidateCurrentWeek]] for every profile the group is attached to: the call a membership
   * edit makes (design §6.4, test 19). An attachment's own create or delete passes its one profile
   * to [[invalidateCurrentWeek]] instead.
   */
  def invalidateForGroup(group: AppGroupId, now: Instant): ConnectionIO[Int] =
    sql"SELECT profile_id FROM profile_app_groups WHERE group_id = $group"
      .query[ProfileId]
      .to[List]
      .flatMap(invalidateCurrentWeek(_, Kinds.All, now))
}

/**
 * #2932: one profile-day's usage in seconds, as the settle pass writes it: the exempt-filtered
 * profile total and every budget's seconds, zeros included, so a settled day is recognisable.
 */
final case class DayUsage(
    totalSeconds: Long,
    apps: Map[AppId, Long],
    groups: Map[AppGroupId, Long],
)

object DayUsage {
  val zero: DayUsage = DayUsage(0L, Map.empty, Map.empty)
}

/** #2932: the rolled rows of one profile-day across the three tables, as read. */
final case class RolledProfileDay(
    total: Option[RolledDay],
    apps: Map[AppId, RolledAppDay],
    groups: Map[AppGroupId, RolledAppDay],
)

object RolledProfileDay {
  val empty: RolledProfileDay = RolledProfileDay(None, Map.empty, Map.empty)
}

/** #2932 (design §6.4): reads and writes of settled past days. */
trait SettledUsageRepo {

  /**
   * Every rolled row of `profiles` (all in `household`) dated `from`..`to` inclusive, across
   * `time_used_daily`, `app_used_daily` and `app_group_used_daily`. One range read per table, each
   * served by its `(profile_id, …, date)` primary key and bounded by the household.
   */
  def readRange(
      household: HouseholdId,
      profiles: List[ProfileId],
      from: LocalDate,
      to: LocalDate,
  ): Task[Map[(ProfileId, LocalDate), RolledProfileDay]]

  /**
   * Upsert `date` as settled for each profile in `rows`: its total and every budget, each with
   * `rolled_through = settledThrough` (the day's end). One transaction, under
   * [[SettledUsage.lockHousehold]], and only when the household's [[fingerprint]] still equals
   * `computedUnder`, the value read before the rows were computed. Returns whether it wrote.
   */
  def writeSettled(
      household: HouseholdId,
      date: LocalDate,
      rows: Map[ProfileId, DayUsage],
      settledThrough: Instant,
      computedUnder: String,
  ): Task[Boolean]

  /**
   * Whether `date` is settled for any of `household`'s profiles (a `time_used_daily` row with
   * `rolled_through >= dayEnd`). The late-presence meter's test.
   */
  def anySettled(household: HouseholdId, date: LocalDate, dayEnd: Instant): Task[Boolean]

  /**
   * A value that changes whenever anything the settled rows depend on changes for `household`: its
   * settings row, its assignments, attachments and memberships (each row's `xmin`, so an update,
   * insert or delete all show), and the global host-set version. The refill reads it before its
   * live compute and again inside its write; a difference means a write changed them meanwhile.
   */
  def fingerprint(household: HouseholdId): Task[String]
}

/**
 * Test wiring: reads find nothing and writes are dropped, so every past day is computed live on
 * each read. The numbers are the same as with the real repo; only the caching is missing.
 */
object NoopSettledUsageRepo extends SettledUsageRepo {
  def readRange(
      household: HouseholdId,
      profiles: List[ProfileId],
      from: LocalDate,
      to: LocalDate,
  ): Task[Map[(ProfileId, LocalDate), RolledProfileDay]] = ZIO.succeed(Map.empty)
  def writeSettled(
      household: HouseholdId,
      date: LocalDate,
      rows: Map[ProfileId, DayUsage],
      settledThrough: Instant,
      computedUnder: String,
  ): Task[Boolean] = ZIO.succeed(false)
  def anySettled(household: HouseholdId, date: LocalDate, dayEnd: Instant): Task[Boolean] =
    ZIO.succeed(false)
  def fingerprint(household: HouseholdId): Task[String] = ZIO.succeed("")
}

class SettledUsageRepoLive(xa: Transactor[Task]) extends SettledUsageRepo {

  def readRange(
      household: HouseholdId,
      profiles: List[ProfileId],
      from: LocalDate,
      to: LocalDate,
  ): Task[Map[(ProfileId, LocalDate), RolledProfileDay]] =
    NonEmptyList.fromList(profiles.distinct) match {
      case None      => ZIO.succeed(Map.empty)
      case Some(nel) =>
        val ownProfiles =
          fr"AND profile_id IN (SELECT id FROM profiles WHERE household_id = $household)"
        val totals      =
          (fr"SELECT profile_id, date, used_seconds, rolled_through FROM time_used_daily WHERE" ++
            Fragments.in(fr"profile_id", nel) ++
            fr"AND date BETWEEN $from AND $to" ++ ownProfiles)
            .query[(ProfileId, LocalDate, Long, Instant)]
            .to[List]
        val apps        =
          (fr"SELECT profile_id, date, app_id, engaged_seconds, rolled_through FROM app_used_daily WHERE" ++
            Fragments.in(fr"profile_id", nel) ++
            fr"AND date BETWEEN $from AND $to" ++ ownProfiles)
            .query[(ProfileId, LocalDate, AppId, Long, Instant)]
            .to[List]
        val groups      =
          (fr"""SELECT profile_id, date, group_id, engaged_seconds, rolled_through
                  FROM app_group_used_daily
                 WHERE household_id = $household AND""" ++
            Fragments.in(fr"profile_id", nel) ++ fr"AND date BETWEEN $from AND $to")
            .query[(ProfileId, LocalDate, AppGroupId, Long, Instant)]
            .to[List]
        DbMetrics
          .timed("settledUsage.readRange")((totals, apps, groups).tupled.transact(xa))
          .map { case (ts, as, gs) =>
            val keys = ts.map(r => (r._1, r._2)) ++ as.map(r => (r._1, r._2)) ++
              gs.map(r => (r._1, r._2))
            val tMap = ts.map(r => (r._1, r._2) -> RolledDay(r._3, r._4)).toMap
            val aMap = as.groupMap(r => (r._1, r._2))(r => r._3 -> RolledAppDay(r._4, r._5))
            val gMap = gs.groupMap(r => (r._1, r._2))(r => r._3 -> RolledAppDay(r._4, r._5))
            keys.distinct.map { k =>
              k -> RolledProfileDay(
                tMap.get(k),
                aMap.getOrElse(k, Nil).toMap,
                gMap.getOrElse(k, Nil).toMap,
              )
            }.toMap
          }
    }

  def writeSettled(
      household: HouseholdId,
      date: LocalDate,
      rows: Map[ProfileId, DayUsage],
      settledThrough: Instant,
      computedUnder: String,
  ): Task[Boolean] =
    if rows.isEmpty then ZIO.succeed(false)
    else {
      val totals  = rows.toList.map { case (p, u) => (p, date, u.totalSeconds, settledThrough) }
      val apps    = rows.toList.flatMap { case (p, u) =>
        u.apps.toList.map { case (a, s) => (p, a, date, s, settledThrough) }
      }
      val groups  = rows.toList.flatMap { case (p, u) =>
        u.groups.toList.map { case (g, s) => (household, p, g, date, s, settledThrough) }
      }
      val prog    = for {
        _ <- Update[(ProfileId, LocalDate, Long, Instant)](
          """INSERT INTO time_used_daily (profile_id, date, used_seconds, rolled_through, rolled_at)
             VALUES (?, ?, ?, ?, NOW())
             ON CONFLICT (profile_id, date) DO UPDATE
               SET used_seconds = EXCLUDED.used_seconds,
                   rolled_through = EXCLUDED.rolled_through,
                   rolled_at = EXCLUDED.rolled_at""",
        ).updateMany(totals)
        _ <- Update[(ProfileId, AppId, LocalDate, Long, Instant)](
          """INSERT INTO app_used_daily (profile_id, app_id, date, engaged_seconds, rolled_through, rolled_at)
             VALUES (?, ?, ?, ?, ?, NOW())
             ON CONFLICT (profile_id, app_id, date) DO UPDATE
               SET engaged_seconds = EXCLUDED.engaged_seconds,
                   rolled_through = EXCLUDED.rolled_through,
                   rolled_at = EXCLUDED.rolled_at""",
        ).updateMany(apps)
        _ <- Update[(HouseholdId, ProfileId, AppGroupId, LocalDate, Long, Instant)](
          """INSERT INTO app_group_used_daily
               (household_id, profile_id, group_id, date, engaged_seconds, rolled_through, rolled_at)
             VALUES (?, ?, ?, ?, ?, ?, NOW())
             ON CONFLICT (profile_id, group_id, date) DO UPDATE
               SET engaged_seconds = EXCLUDED.engaged_seconds,
                   rolled_through = EXCLUDED.rolled_through,
                   rolled_at = EXCLUDED.rolled_at""",
        ).updateMany(groups)
      } yield ()
      val guarded = for {
        _       <- SettledUsage.lockHousehold(household)
        current <- fingerprintQ(household)
        ok = current == computedUnder
        _ <- prog.whenA(ok)
      } yield ok
      DbMetrics.timed("settledUsage.writeSettled")(guarded.transact(xa))
    }

  def anySettled(household: HouseholdId, date: LocalDate, dayEnd: Instant): Task[Boolean] =
    DbMetrics.timed("settledUsage.anySettled")(
      sql"""SELECT EXISTS (
              SELECT 1 FROM time_used_daily tud
               JOIN profiles p ON p.id = tud.profile_id
              WHERE p.household_id = $household
                AND tud.date = $date
                AND tud.rolled_through >= $dayEnd)"""
        .query[Boolean]
        .unique
        .transact(xa),
    )

  def fingerprint(household: HouseholdId): Task[String] =
    DbMetrics.timed("settledUsage.fingerprint")(fingerprintQ(household).transact(xa))

  private def fingerprintQ(household: HouseholdId): ConnectionIO[String] =
    sql"""SELECT md5(concat_ws('|',
                (SELECT xmin::text FROM household_settings WHERE household_id = $household),
                (SELECT string_agg(apa.id::text || ':' || apa.xmin::text, ',' ORDER BY apa.id)
                   FROM app_policy_assignments apa
                   JOIN profiles p ON p.id = apa.profile_id
                  WHERE p.household_id = $household),
                (SELECT string_agg(pag.id::text || ':' || pag.xmin::text, ',' ORDER BY pag.id)
                   FROM profile_app_groups pag WHERE pag.household_id = $household),
                (SELECT string_agg(m.group_id::text || ':' || m.app_id::text || ':' || m.xmin::text,
                                   ',' ORDER BY m.group_id, m.app_id)
                   FROM app_group_members m WHERE m.household_id = $household),
                (SELECT version::text FROM app_hosts_version)))"""
      .query[String]
      .unique
}
