package wifihaven.api.db

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import wifihaven.shared.types.*

import java.time.LocalDate

/**
 * Design `docs/design/weekly-limits-and-app-groups.md` §6.4 "Invalidation": the ONE definition of
 * how a write that changes a profile's dispositions drops that profile's settled usage rows.
 *
 * Past days of `app_used_daily` / `app_group_used_daily` / `time_used_daily` are read as settled
 * once their `rolled_through` reaches the end of the day, and a settled row is only valid for the
 * dispositions it was computed under. Every write that changes them — an assignment create, delete
 * or mode change; an attachment create or delete; a group membership edit — also changes the
 * attribution set, so it must delete the profile's current-week rows IN THE SAME TRANSACTION as the
 * write. Callers compose the returned [[ConnectionIO]] into their own transaction; there is no
 * variant that runs on its own transactor, so a write cannot be made and forget the drop.
 *
 * The exempt flag is the one narrower case: it changes what the profile daily TOTAL counts (the
 * rollup writes `time_used_daily` through the exempt-filtered `usedSecondsForProfile`) but no app
 * or group minutes, so [[Kinds.ProfileTotalOnly]] drops only `time_used_daily`. A limit edit
 * changes no usage at all and calls nothing.
 */
object SettledUsage {

  /** Which settled tables a write must drop. */
  enum Kinds {

    /** Assignment create / delete / mode change, attachment create / delete, membership edit. */
    case All

    /** An exempt-from-daily edit: only the profile daily total moves. */
    case ProfileTotalOnly
  }

  /**
   * The oldest `date` a write drops. A week is at most seven household-local dates and a
   * household-local date is within a day of the wall-clock UTC date (reset time + zone offset), so
   * `today - 8` is a superset of the current week for every household. Dropping a day too many only
   * costs one recompute (design §6.4: "at most six days per affected profile, once"), so the
   * approximation errs toward correctness; the exact week start (`householdWeekStart`) is the
   * budget primitive's, and this takes the date rather than recomputing it.
   */
  def weekFloor(today: LocalDate): LocalDate = today.minusDays(8)

  /** Drop `profiles`' settled rows of `kinds` on or after `from`. Returns rows deleted. */
  def invalidate(
      profiles: List[ProfileId],
      kinds: Kinds,
      from: LocalDate,
  ): ConnectionIO[Int] =
    if (profiles.isEmpty) doobie.free.connection.pure(0)
    else {
      val ids = profiles.map(_.value).toArray
      val tt  =
        sql"DELETE FROM time_used_daily WHERE profile_id = ANY($ids) AND date >= $from".update.run
      kinds match {
        case Kinds.ProfileTotalOnly => tt
        case Kinds.All              =>
          for {
            a <-
              sql"DELETE FROM app_used_daily WHERE profile_id = ANY($ids) AND date >= $from".update.run
            g <-
              sql"DELETE FROM app_group_used_daily WHERE profile_id = ANY($ids) AND date >= $from".update.run
            t <- tt
          } yield a + g + t
      }
    }
}
