package wifihaven.api.policy

import wifihaven.shared.*
import wifihaven.shared.types.*
import zio.test.*

import java.time.{Instant, LocalTime, ZoneId, ZonedDateTime}

/**
 * #1379: per-app schedule evaluation — the pure server-side collapse of an app's (schedule, mode)
 * rules into the existing `extraAllowed` / `extraBlocked` host buckets. These unit tests pin the
 * schedule-boundary behaviour ([[PolicyService.windowActiveAt]] reuses the DST-correct
 * [[PolicyService.scheduleActiveAt]]), the effective-disposition resolution
 * ([[PolicyService.expandAppDispositions]] — window overrides base mode, allowed beats blocked),
 * and the daily-cap carve gate ([[PolicyService.dailyCapExhausted]]). No wire shape is touched —
 * the only outputs are the two host lists.
 */
object PerAppScheduleSpec extends ZIOSpecDefault {

  private val UTC = ZoneId.of("UTC")
  private val LA  = ZoneId.of("America/Los_Angeles")
  private val NY  = ZoneId.of("America/New_York")

  private val allDays = List("mon", "tue", "wed", "thu", "fri", "sat", "sun")

  private def instantAt(
      year: Int,
      month: Int,
      day: Int,
      hour: Int,
      min: Int,
      zone: ZoneId,
  ): Instant =
    ZonedDateTime.of(year, month, day, hour, min, 0, 0, zone).toInstant

  private def win(
      start: LocalTime,
      end: LocalTime,
      days: List[String] = allDays,
      tz: ZoneId = UTC,
  ): ScheduleWindow =
    ScheduleWindow(days, start, end, tz)

  // The single app under test, host = "app.example.com", appId = 10.
  private val appHost                                = "app.example.com"
  private val appId                                  = AppId(10L)
  private val hostsByApp: Map[AppId, List[Hostname]] =
    Map(appId -> List(Hostname.unsafe(appHost)))

  private def assign(
      mode: AppMode,
      exempt: Boolean = true,
      id: Long = 1L,
  ): AppPolicyAssignment =
    AppPolicyAssignment(AppPolicyAssignmentId(id), appId, ProfileId(1L), mode, None, exempt)

  /** Run the disposition fold for a single assignment with the given rules. */
  private def expand(
      a: AppPolicyAssignment,
      rules: List[(AppScheduleMode, ScheduleWindow)],
      capExhausted: Boolean,
      now: Instant,
  ): (Set[String], Set[String]) = {
    val (allowed, blocked) = PolicyService.expandAppDispositions(
      assigns = List(a),
      hostsByApp = hostsByApp,
      schedWindows = Map(a.id -> rules),
      capExhausted = capExhausted,
      now = now,
    )
    (allowed.map(_.value).toSet, blocked.map(_.value).toSet)
  }

  // A weekday-bedtime UTC window: 21:00 → 07:00 (overnight wrap), all days.
  private val bedtime   = win(LocalTime.of(21, 0), LocalTime.of(7, 0))
  // Wed 2026-05-13 22:30 UTC — inside bedtime.
  private val atBedtime = instantAt(2026, 5, 13, 22, 30, UTC)
  // Wed 2026-05-13 12:00 UTC — outside bedtime.
  private val atNoon    = instantAt(2026, 5, 13, 12, 0, UTC)

  def spec = suite("per-app schedules (#1379)")(
    suite("windowActiveAt — boundaries reuse scheduleActiveAt")(
      test("exact start is inclusive (active)") {
        val w = win(LocalTime.of(9, 0), LocalTime.of(17, 0))
        assertTrue(PolicyService.windowActiveAt(w, instantAt(2026, 5, 13, 9, 0, UTC)))
      },
      test("exact end is exclusive (inactive)") {
        val w = win(LocalTime.of(9, 0), LocalTime.of(17, 0))
        assertTrue(!PolicyService.windowActiveAt(w, instantAt(2026, 5, 13, 17, 0, UTC)))
      },
      test("one minute before start → inactive") {
        val w = win(LocalTime.of(9, 0), LocalTime.of(17, 0))
        assertTrue(!PolicyService.windowActiveAt(w, instantAt(2026, 5, 13, 8, 59, UTC)))
      },
      test("day-of-week membership excludes other days") {
        val w = win(LocalTime.of(9, 0), LocalTime.of(17, 0), days = List("mon"))
        // 2026-05-13 is a Wednesday.
        assertTrue(!PolicyService.windowActiveAt(w, instantAt(2026, 5, 13, 12, 0, UTC)))
      },
      test("overnight wrap: tail of the prior day is active") {
        val w = win(LocalTime.of(23, 30), LocalTime.of(6, 0), tz = NY)
        // 2026-05-14 02:00 NY is the tail of the window that started 2026-05-13 23:30 NY.
        assertTrue(PolicyService.windowActiveAt(w, instantAt(2026, 5, 14, 2, 0, NY)))
      },
      test("DST spring-forward: 21:00→07:00 LA active at 06:30 PDT the next morning") {
        val w = win(LocalTime.of(21, 0), LocalTime.of(7, 0), tz = LA)
        // Spring-forward 2026-03-08 02:00 PST → 03:00 PDT; 06:30 PDT must still be in-window.
        assertTrue(PolicyService.windowActiveAt(w, instantAt(2026, 3, 8, 6, 30, LA)))
      },
    ),
    suite("effective disposition — window overrides base mode")(
      test("allowed_during active over base Blocked → host carved into extraAllowed only") {
        val (ea, eb) =
          expand(
            assign(AppMode.Blocked),
            List(AppScheduleMode.AllowedDuring -> bedtime),
            false,
            atBedtime,
          )
        assertTrue(ea == Set(appHost)) && assertTrue(eb.isEmpty)
      },
      test("allowed_during INACTIVE → falls back to base Blocked") {
        val (ea, eb) =
          expand(
            assign(AppMode.Blocked),
            List(AppScheduleMode.AllowedDuring -> bedtime),
            false,
            atNoon,
          )
        assertTrue(ea.isEmpty) && assertTrue(eb == Set(appHost))
      },
      test("blocked_during active over base Allowed → host in extraBlocked only (row 3)") {
        val w        = win(LocalTime.of(9, 0), LocalTime.of(17, 0))
        val (ea, eb) =
          expand(assign(AppMode.Allowed), List(AppScheduleMode.BlockedDuring -> w), false, atNoon)
        assertTrue(eb == Set(appHost)) && assertTrue(ea.isEmpty)
      },
      test("blocked_during INACTIVE on base Allowed → falls back to extraAllowed") {
        val w        = win(LocalTime.of(9, 0), LocalTime.of(17, 0))
        val (ea, eb) =
          expand(
            assign(AppMode.Allowed),
            List(AppScheduleMode.BlockedDuring -> w),
            false,
            atBedtime,
          )
        assertTrue(ea == Set(appHost)) && assertTrue(eb.isEmpty)
      },
      test("no rules → pure base mode (Blocked)") {
        val (ea, eb) = expand(assign(AppMode.Blocked), Nil, false, atBedtime)
        assertTrue(ea.isEmpty) && assertTrue(eb == Set(appHost))
      },
      test("TimeLimited base with no active window contributes to neither bucket") {
        val (ea, eb) = expand(assign(AppMode.TimeLimited), Nil, false, atBedtime)
        assertTrue(ea.isEmpty) && assertTrue(eb.isEmpty)
      },
    ),
    suite("allowed_during beats blocked_during on simultaneous overlap")(
      test("both windows active → AllowedDuring wins, host in extraAllowed only") {
        val rules    = List(
          AppScheduleMode.AllowedDuring -> bedtime,
          AppScheduleMode.BlockedDuring -> win(LocalTime.of(21, 0), LocalTime.of(7, 0)),
        )
        val (ea, eb) = expand(assign(AppMode.Blocked), rules, false, atBedtime)
        assertTrue(ea == Set(appHost)) && assertTrue(eb.isEmpty)
      },
    ),
    suite("multi-window named schedule — active iff ANY window active")(
      test("second window active → allowed_during fires") {
        // Two windows on the rule (school hours + evening); evaluate during the evening one.
        val rules    = List(
          AppScheduleMode.AllowedDuring -> win(LocalTime.of(8, 0), LocalTime.of(15, 0)),
          AppScheduleMode.AllowedDuring -> win(LocalTime.of(20, 0), LocalTime.of(23, 0)),
        )
        val now      = instantAt(2026, 5, 13, 21, 0, UTC)
        val (ea, eb) = expand(assign(AppMode.Blocked), rules, false, now)
        assertTrue(ea == Set(appHost)) && assertTrue(eb.isEmpty)
      },
      test("neither window active → base mode") {
        val rules    = List(
          AppScheduleMode.AllowedDuring -> win(LocalTime.of(8, 0), LocalTime.of(15, 0)),
          AppScheduleMode.AllowedDuring -> win(LocalTime.of(20, 0), LocalTime.of(23, 0)),
        )
        val now      = instantAt(2026, 5, 13, 17, 0, UTC) // 5pm — between both windows
        val (ea, eb) = expand(assign(AppMode.Blocked), rules, false, now)
        assertTrue(ea.isEmpty) && assertTrue(eb == Set(appHost))
      },
    ),
    suite("daily-cap carve gate — allowed_during yields to the cap unless exempt")(
      test("exempt + cap exhausted → still carved (9a)") {
        val (ea, _) = expand(
          assign(AppMode.Blocked, exempt = true),
          List(AppScheduleMode.AllowedDuring -> bedtime),
          capExhausted = true,
          atBedtime,
        )
        assertTrue(ea == Set(appHost))
      },
      test("NON-exempt + cap exhausted → NOT carved (9b): neither bucket") {
        val (ea, eb) = expand(
          assign(AppMode.Blocked, exempt = false),
          List(AppScheduleMode.AllowedDuring -> bedtime),
          capExhausted = true,
          atBedtime,
        )
        assertTrue(ea.isEmpty) && assertTrue(eb.isEmpty)
      },
      test("NON-exempt + cap NOT exhausted → carved") {
        val (ea, _) = expand(
          assign(AppMode.Blocked, exempt = false),
          List(AppScheduleMode.AllowedDuring -> bedtime),
          capExhausted = false,
          atBedtime,
        )
        assertTrue(ea == Set(appHost))
      },
      test("exempt + cap NOT exhausted → carved") {
        val (ea, _) = expand(
          assign(AppMode.Blocked, exempt = true),
          List(AppScheduleMode.AllowedDuring -> bedtime),
          capExhausted = false,
          atBedtime,
        )
        assertTrue(ea == Set(appHost))
      },
      test("cap gate does NOT touch blocked_during (always drops)") {
        val w        = win(LocalTime.of(9, 0), LocalTime.of(17, 0))
        val (ea, eb) = expand(
          assign(AppMode.Allowed, exempt = false),
          List(AppScheduleMode.BlockedDuring -> w),
          capExhausted = true,
          atNoon,
        )
        assertTrue(eb == Set(appHost)) && assertTrue(ea.isEmpty)
      },
    ),
    suite("dailyCapExhausted — keyed off raw ProfileDayState, not blockReason")(
      test("used >= limit + extensions → exhausted") {
        val st = ProfileDayState(
          ProfileId(1L),
          java.time.LocalDate.of(2026, 5, 13),
          Some(30),
          usedMinutes = 35,
          extensionMinutes = 0,
          remainingMinutes = Some(0),
          blocked = true,
          blockReason =
            Some(MacBlockReason.Schedule), // higher-precedence reason; gate must ignore it
          perApp = Nil,
        )
        assertTrue(PolicyService.dailyCapExhausted(st))
      },
      test("extensions push used below the effective cap → not exhausted") {
        val st = ProfileDayState(
          ProfileId(1L),
          java.time.LocalDate.of(2026, 5, 13),
          Some(30),
          usedMinutes = 35,
          extensionMinutes = 10, // effective cap = 40 > 35
          remainingMinutes = Some(5),
          blocked = false,
          blockReason = None,
          perApp = Nil,
        )
        assertTrue(!PolicyService.dailyCapExhausted(st))
      },
      test("no daily limit set → never exhausted") {
        val st = ProfileDayState(
          ProfileId(1L),
          java.time.LocalDate.of(2026, 5, 13),
          None,
          usedMinutes = 999,
          extensionMinutes = 0,
          remainingMinutes = None,
          blocked = false,
          blockReason = None,
          perApp = Nil,
        )
        assertTrue(!PolicyService.dailyCapExhausted(st))
      },
    ),
  )
}
