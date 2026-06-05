package wifihaven.api

import wifihaven.api.db.*
import wifihaven.shared.*
import zio.*

import java.time.{LocalTime, ZoneId}

/** Outcome of the boot-time named-schedule seed/migrate pass. Logged at startup. */
final case class ScheduleSeedSummary(migrated: Int, seededDefaults: Int)

/**
 * #1069: one-time, idempotent boot step that (1) migrates each profile's legacy per-profile
 * `schedules` rows into the household-scoped `named_schedules` model and links the profile to it,
 * and (2) seeds a default starter set of schedules on a brand-new household.
 *
 * Idempotency:
 *   - Migration skips any profile that already references a named schedule (`schedule_id` set), so
 *     a re-run is a no-op once a profile has been migrated. The legacy `schedules` rows are left
 *     intact — PolicyService unions them with the named windows (identical → same result), and they
 *     keep the pre-`schedule_id` image enforcing correctly on a rollback. Their removal is a later
 *     deprecation-window migration.
 *   - Default seeding is gated on the `named_schedules` table being empty, and runs *before*
 *     migration so a brand-new install (whose V1-seeded Kids/Bedtime legacy schedule would
 *     otherwise make the table non-empty after migration) still gets the starter set. The defaults
 *     are unlinked reusable templates — seeding them changes no enforcement until an operator
 *     references one — so re-seeding after a full delete is low-harm; a durable "seeded once"
 *     marker (household_settings column) is a follow-up once a migration can ship for it.
 */
object ScheduleSeeder {

  private val AllDays                       = List("mon", "tue", "wed", "thu", "fri", "sat", "sun")
  private val Weekdays                      = List("mon", "tue", "wed", "thu", "fri")
  private val Weekend                       = List("sat", "sun")
  private def at(h: Int, m: Int): LocalTime = LocalTime.of(h, m)

  // (name, description, windows-as-(days, start, end)). Times are wall-clock in the household zone.
  private val Defaults: List[(String, String, List[(List[String], LocalTime, LocalTime)])] = List(
    ("Bedtime", "Daily overnight downtime", List((AllDays, at(20, 0), at(7, 0)))),
    ("School hours", "Weekday school day", List((Weekdays, at(8, 0), at(15, 0)))),
    ("Afternoon", "Weekday after school", List((Weekdays, at(15, 0), at(18, 0)))),
    ("Weekend mornings", "Saturday & Sunday mornings", List((Weekend, at(6, 0), at(10, 0)))),
  )

  def seedAndMigrate(
      named: NamedScheduleRepo,
      legacy: ScheduleRepo,
      profiles: ProfileRepo,
      tz: ZoneId,
  ): Task[ScheduleSeedSummary] =
    for {
      // Seed defaults first, gated on an empty table, so a fresh install's V1-seeded legacy
      // schedule (which migration turns into a named schedule) doesn't suppress the starter set.
      seeded   <- seedDefaults(named, tz)
      migrated <- migrateProfiles(named, legacy, profiles)
    } yield ScheduleSeedSummary(migrated, seeded)

  private def migrateProfiles(
      named: NamedScheduleRepo,
      legacy: ScheduleRepo,
      profiles: ProfileRepo,
  ): Task[Int] =
    profiles.listAll.flatMap { ps =>
      ZIO.foldLeft(ps)(0) { (acc, p) =>
        named.blockScheduleIdsForProfile(p.id).flatMap { existing =>
          if existing.nonEmpty then ZIO.succeed(acc) // already migrated
          else
            legacy.listForProfile(p.id).flatMap {
              case Nil  => ZIO.succeed(acc)
              case rows =>
                val windows = rows.map(s => ScheduleWindow(s.days, s.startLocal, s.endLocal, s.tz))
                for {
                  name <- uniqueName(named, s"${p.name} schedule")
                  id   <- named.create(name, Some(s"Migrated from ${p.name}'s schedule"), windows)
                  _    <- named.setProfileBlockSchedules(p.id, List(id))
                } yield acc + 1
            }
        }
      }
    }

  // First free "<base>", then "<base> (2)", "<base> (3)", … honouring named_schedules.name UNIQUE.
  private def uniqueName(named: NamedScheduleRepo, base: String): Task[String] = {
    def loop(candidate: String, n: Int): Task[String] =
      named.findByName(candidate).flatMap {
        case None    => ZIO.succeed(candidate)
        case Some(_) => loop(s"$base (${n + 1})", n + 1)
      }
    loop(base, 1)
  }

  private def seedDefaults(named: NamedScheduleRepo, tz: ZoneId): Task[Int] =
    named.listAll.flatMap { existing =>
      if existing.nonEmpty then ZIO.succeed(0)
      else
        ZIO
          .foreachDiscard(Defaults) { case (name, desc, windows) =>
            named.create(
              name,
              Some(desc),
              windows.map { case (days, start, end) => ScheduleWindow(days, start, end, tz) },
            )
          }
          .as(Defaults.size)
    }
}
