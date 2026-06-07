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
 *   - Migration skips any profile that has ALREADY been migrated, keyed off a durable fact: the
 *     existence of the migrated `named_schedules` row (its deterministic "Migrated from …"
 *     description), NOT the mutable profile→schedule attachment. Keying off the attachment was the
 *     #1538 resurrection bug — detaching the schedule made the guard re-migrate the still-retained
 *     legacy row on the next boot. The legacy `schedules` rows are left intact — PolicyService
 *     unions them with the named windows (identical → same result), and they keep the
 *     pre-`schedule_id` image enforcing correctly on a rollback. Their removal is a later
 *     deprecation-window migration (#1485).
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

  // TODO(#1485): once the legacy `schedules` table is dropped (gated ~Jun 2026, post-soak),
  // `legacy.listForProfile` has nothing left to read and this whole migrate pass becomes a
  // permanent no-op — remove it (and its callers) then. It must not be invoked against a
  // non-existent table, so coordinate the removal with that migration.
  private def migrateProfiles(
      named: NamedScheduleRepo,
      legacy: ScheduleRepo,
      profiles: ProfileRepo,
  ): Task[Int] =
    for {
      ps              <- profiles.listAll
      // #1538: anchor idempotency to a DURABLE fact — the migrated `named_schedules` row exists —
      // NOT to the (mutable) attachment. The old guard skipped only when the profile CURRENTLY had
      // a block-mode named schedule attached, so an operator detaching the schedule (deleting the
      // profile_schedule_rules row) made the guard read "not migrated" while the legacy `schedules`
      // row was still retained for rollback safety. The next boot then re-migrated that retained
      // row and resurrected the removed schedule. The migrated named_schedules row survives a
      // profile-level detach, so its presence is the correct one-shot signal. Snapshot all
      // descriptions once — each profile is migrated at most once per run and profile names are
      // distinct markers, so the in-loop creates can't affect another profile's guard.
      migratedMarkers <- named.listAll.map(_.flatMap(_.description).toSet)
      migrated        <- ZIO.foldLeft(ps)(0) { (acc, p) =>
        val alreadyMigrated = migratedMarkers.contains(migratedDescription(p.name))
        named.blockScheduleIdsForProfile(p.id).flatMap { attached =>
          // Skip if it's currently attached OR a migrated row already exists for this profile.
          // (Attachment is kept as a belt-and-braces signal — e.g. a profile renamed after
          // migration, whose marker no longer matches its current name, still won't re-migrate
          // while it remains attached.)
          if attached.nonEmpty || alreadyMigrated then ZIO.succeed(acc)
          else
            legacy.listForProfile(p.id).flatMap {
              case Nil  => ZIO.succeed(acc)
              case rows =>
                val windows = rows.map(s => ScheduleWindow(s.days, s.startLocal, s.endLocal, s.tz))
                for {
                  name <- uniqueName(named, s"${p.name} schedule")
                  id   <- named.create(name, Some(migratedDescription(p.name)), windows)
                  _    <- named.setProfileBlockSchedules(p.id, List(id))
                } yield acc + 1
            }
        }
      }
    } yield migrated

  // #1069: the deterministic description stamped on the named schedule a profile's legacy
  // `schedules` rows migrate into. #1538 keys idempotency off the *existence* of this row, so the
  // marker is the durable "this profile was already migrated" fact.
  private def migratedDescription(profileName: String): String =
    s"Migrated from $profileName's schedule"

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
