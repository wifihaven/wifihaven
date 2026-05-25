package wifihaven.api.unit

import wifihaven.api.policy.PolicyService
import wifihaven.shared.{HeartbeatFilter, HouseholdSettings, Schedule}
import wifihaven.shared.types.*
import zio.test.*

import java.time.{Instant, LocalTime, ZoneId, ZonedDateTime}

/**
 * #334: timezone-aware schedule evaluation + daily-reset Instant computation.
 *
 * The pure functions under test do all timezone math against `Instant.now()` projected into the
 * per-row IANA zone. Tests pin DST behavior (spring-forward + fall-back) and cross-midnight windows
 * since those are the bug-prone cases.
 */
object SchedulingSpec extends ZIOSpecDefault {

  private val LA  = ZoneId.of("America/Los_Angeles")
  private val NY  = ZoneId.of("America/New_York")
  private val UTC = ZoneId.of("UTC")

  /** Build an Instant from a wall-clock time in a given zone. */
  private def instantAt(
      year: Int,
      month: Int,
      day: Int,
      hour: Int,
      min: Int,
      zone: ZoneId,
  ): Instant =
    ZonedDateTime.of(year, month, day, hour, min, 0, 0, zone).toInstant

  private val allDays = List("mon", "tue", "wed", "thu", "fri", "sat", "sun")

  private def schedule(
      tz: ZoneId,
      startLocal: LocalTime,
      endLocal: LocalTime,
      days: List[String] = allDays,
  ): Schedule =
    Schedule(
      id = ScheduleId(1L),
      profileId = ProfileId(1L),
      name = "test",
      days = days,
      startLocal = startLocal,
      endLocal = endLocal,
      tz = tz,
    )

  def spec = suite("Scheduling")(
    suite("scheduleActiveAt — basic")(
      test("simple in-window, same-day window") {
        val s = schedule(LA, LocalTime.of(9, 0), LocalTime.of(17, 0))
        // 2026-05-13 (Wed) 12:00 PDT
        val t = instantAt(2026, 5, 13, 12, 0, LA)
        assertTrue(PolicyService.scheduleActiveAt(s, t))
      },
      test("just before window starts → not active") {
        val s = schedule(LA, LocalTime.of(9, 0), LocalTime.of(17, 0))
        val t = instantAt(2026, 5, 13, 8, 59, LA)
        assertTrue(!PolicyService.scheduleActiveAt(s, t))
      },
      test("right at window end → not active (end is exclusive)") {
        val s = schedule(LA, LocalTime.of(9, 0), LocalTime.of(17, 0))
        val t = instantAt(2026, 5, 13, 17, 0, LA)
        assertTrue(!PolicyService.scheduleActiveAt(s, t))
      },
      test("day-of-week filter excludes other days") {
        val s = schedule(LA, LocalTime.of(9, 0), LocalTime.of(17, 0), days = List("mon"))
        // Wednesday
        val t = instantAt(2026, 5, 13, 12, 0, LA)
        assertTrue(!PolicyService.scheduleActiveAt(s, t))
      },
    ),
    suite("scheduleActiveAt — cross-midnight (overnight)")(
      test("23:30 → 06:00 NY: in-window at 23:30 NY") {
        val s = schedule(NY, LocalTime.of(23, 30), LocalTime.of(6, 0))
        val t = instantAt(2026, 5, 13, 23, 30, NY)
        assertTrue(PolicyService.scheduleActiveAt(s, t))
      },
      test("23:30 → 06:00 NY: in-window at 02:00 NY (next day, prev-day's window)") {
        val s = schedule(NY, LocalTime.of(23, 30), LocalTime.of(6, 0))
        // 2026-05-14 02:00 NY is the tail of the schedule that started 2026-05-13 23:30
        val t = instantAt(2026, 5, 14, 2, 0, NY)
        assertTrue(PolicyService.scheduleActiveAt(s, t))
      },
      test("23:30 → 06:00 NY: out-of-window at 06:01 NY") {
        val s = schedule(NY, LocalTime.of(23, 30), LocalTime.of(6, 0))
        val t = instantAt(2026, 5, 14, 6, 1, NY)
        assertTrue(!PolicyService.scheduleActiveAt(s, t))
      },
      test("23:30 → 06:00 NY with days=[mon]: tue 02:00 still in-window (mon's tail)") {
        val s = schedule(NY, LocalTime.of(23, 30), LocalTime.of(6, 0), days = List("mon"))
        // 2026-05-11 is Monday; 2026-05-12 02:00 is Tuesday morning, tail of Monday's window
        val t = instantAt(2026, 5, 12, 2, 0, NY)
        assertTrue(PolicyService.scheduleActiveAt(s, t))
      },
      test("23:30 → 06:00 NY with days=[mon]: tue 23:30 not in-window (tue not in days)") {
        val s = schedule(NY, LocalTime.of(23, 30), LocalTime.of(6, 0), days = List("mon"))
        val t = instantAt(2026, 5, 12, 23, 30, NY)
        assertTrue(!PolicyService.scheduleActiveAt(s, t))
      },
    ),
    suite("scheduleActiveAt — DST")(
      test(
        "21:00 → 07:00 LA across spring-forward 2026-03-08 → next morning still in-window at 06:30 PDT",
      ) {
        val s = schedule(LA, LocalTime.of(21, 0), LocalTime.of(7, 0))
        // Spring-forward in LA happens 2026-03-08 02:00 PST → 03:00 PDT.
        // Window starts 2026-03-07 21:00 PST, runs through 2026-03-08 07:00 PDT.
        // 2026-03-08 06:30 PDT must be in-window even though wall-clock skipped 02:00–02:59.
        val t = instantAt(2026, 3, 8, 6, 30, LA)
        assertTrue(PolicyService.scheduleActiveAt(s, t))
      },
      test("21:00 → 07:00 LA across spring-forward → 07:01 PDT out-of-window") {
        val s = schedule(LA, LocalTime.of(21, 0), LocalTime.of(7, 0))
        val t = instantAt(2026, 3, 8, 7, 1, LA)
        assertTrue(!PolicyService.scheduleActiveAt(s, t))
      },
      test("21:00 → 07:00 LA across fall-back 2026-11-01 → both 01:30 instants are in-window") {
        val s          = schedule(LA, LocalTime.of(21, 0), LocalTime.of(7, 0))
        // 2026-11-01 02:00 PDT → 01:00 PST; the wall-clock 01:30 happens twice.
        // First 01:30 = 2026-11-01 08:30Z (PDT), second = 2026-11-01 09:30Z (PST). Both must be in-window.
        val firstHalf  = Instant.parse("2026-11-01T08:30:00Z")
        val secondHalf = Instant.parse("2026-11-01T09:30:00Z")
        assertTrue(
          PolicyService.scheduleActiveAt(s, firstHalf),
          PolicyService.scheduleActiveAt(s, secondHalf),
        )
      },
      test("21:00 → 07:00 LA: 21:00 PDT on 2026-03-07 (start day) is in-window") {
        val s = schedule(LA, LocalTime.of(21, 0), LocalTime.of(7, 0))
        val t = instantAt(2026, 3, 7, 21, 0, LA)
        assertTrue(PolicyService.scheduleActiveAt(s, t))
      },
    ),
    suite("scheduleActiveAt — per-row tz isolation")(
      test("two schedules, different tz, same Instant: each evaluated against its own tz") {
        // 2026-05-13 23:00 NY = 2026-05-13 20:00 LA
        val t   = instantAt(2026, 5, 13, 23, 0, NY)
        // Schedule A: 22:00–02:00 NY → 23:00 NY is in-window
        val sNy = schedule(NY, LocalTime.of(22, 0), LocalTime.of(2, 0))
        // Schedule B: 22:00–02:00 LA → 20:00 LA is NOT in-window
        val sLa = schedule(LA, LocalTime.of(22, 0), LocalTime.of(2, 0))
        assertTrue(
          PolicyService.scheduleActiveAt(sNy, t),
          !PolicyService.scheduleActiveAt(sLa, t),
        )
      },
    ),
    suite("nextDailyResetAfter")(
      test("00:00 LA in standard time (PST, Jan 15 2026) → 08:00Z next day") {
        val settings = HouseholdSettings(LocalTime.of(0, 0), LA, HeartbeatFilter.Off)
        val now      = Instant.parse("2026-01-15T20:00:00Z") // Jan 15 12:00 PST
        // Next 00:00 PST = 2026-01-16 00:00 PST = 2026-01-16 08:00Z
        val expected = Instant.parse("2026-01-16T08:00:00Z")
        assertTrue(PolicyService.nextDailyResetAfter(settings, now) == expected)
      },
      test("00:00 LA in daylight time (PDT, Jun 15 2026) → 07:00Z next day") {
        val settings = HouseholdSettings(LocalTime.of(0, 0), LA, HeartbeatFilter.Off)
        val now      = Instant.parse("2026-06-15T19:00:00Z") // Jun 15 12:00 PDT
        // Next 00:00 PDT = 2026-06-16 00:00 PDT = 2026-06-16 07:00Z
        val expected = Instant.parse("2026-06-16T07:00:00Z")
        assertTrue(PolicyService.nextDailyResetAfter(settings, now) == expected)
      },
      test("at exactly the reset Instant: returns NEXT day's reset (strict-after)") {
        val settings = HouseholdSettings(LocalTime.of(0, 0), UTC, HeartbeatFilter.Off)
        val now      = Instant.parse("2026-05-13T00:00:00Z")
        val expected = Instant.parse("2026-05-14T00:00:00Z")
        assertTrue(PolicyService.nextDailyResetAfter(settings, now) == expected)
      },
      test("reset later in the day, before reset → today's reset") {
        val settings = HouseholdSettings(LocalTime.of(3, 0), UTC, HeartbeatFilter.Off)
        val now      = Instant.parse("2026-05-13T01:00:00Z")
        val expected = Instant.parse("2026-05-13T03:00:00Z")
        assertTrue(PolicyService.nextDailyResetAfter(settings, now) == expected)
      },
    ),
    suite("householdLocalDate — #1010")(
      test("default UTC household: calendar date is the UTC date") {
        val settings = HouseholdSettings(LocalTime.of(0, 0), UTC, HeartbeatFilter.Off)
        val now      = Instant.parse("2026-05-13T15:30:00Z")
        assertTrue(
          PolicyService.householdLocalDate(now, settings) == java.time.LocalDate.of(2026, 5, 13),
        )
      },
      test("LA household at 20:00 PDT: bucket is the local date (still yesterday in UTC)") {
        val settings = HouseholdSettings(LocalTime.of(0, 0), LA, HeartbeatFilter.Off)
        // 2026-05-07 20:00 PDT = 2026-05-08 03:00 UTC. UTC date has rolled but LA hasn't.
        val now      = Instant.parse("2026-05-08T03:00:00Z")
        assertTrue(
          PolicyService.householdLocalDate(now, settings) == java.time.LocalDate.of(2026, 5, 7),
        )
      },
      test("LA household at 22:00 PT: still 'today' in LA, even though UTC says tomorrow") {
        val settings = HouseholdSettings(LocalTime.of(0, 0), LA, HeartbeatFilter.Off)
        // 22:00 PDT on 2026-05-13 = 05:00 UTC 2026-05-14. The kid in LA is still on 2026-05-13.
        val now      = Instant.parse("2026-05-14T05:00:00Z")
        assertTrue(
          PolicyService.householdLocalDate(now, settings) == java.time.LocalDate.of(2026, 5, 13),
        )
      },
      test("LA household one minute past local midnight: bucket flips to the new day") {
        val settings = HouseholdSettings(LocalTime.of(0, 0), LA, HeartbeatFilter.Off)
        // 00:01 PDT 2026-05-14 = 07:01 UTC 2026-05-14
        val now      = Instant.parse("2026-05-14T07:01:00Z")
        assertTrue(
          PolicyService.householdLocalDate(now, settings) == java.time.LocalDate.of(2026, 5, 14),
        )
      },
      test("non-midnight reset_time: 02:00 local before reset stays on prior day's bucket") {
        // Reset at 04:00 LA. At 03:30 LA the day-bucket should still be the previous date.
        val settings = HouseholdSettings(LocalTime.of(4, 0), LA, HeartbeatFilter.Off)
        val now      = instantAt(2026, 5, 14, 3, 30, LA)
        assertTrue(
          PolicyService.householdLocalDate(now, settings) == java.time.LocalDate.of(2026, 5, 13),
        )
      },
      test("non-midnight reset_time: just after reset flips to the new bucket") {
        val settings = HouseholdSettings(LocalTime.of(4, 0), LA, HeartbeatFilter.Off)
        val now      = instantAt(2026, 5, 14, 4, 1, LA)
        assertTrue(
          PolicyService.householdLocalDate(now, settings) == java.time.LocalDate.of(2026, 5, 14),
        )
      },
      test("DST spring-forward: 06:30 LA on 2026-03-08 is still that calendar date") {
        val settings = HouseholdSettings(LocalTime.of(0, 0), LA, HeartbeatFilter.Off)
        val now      = instantAt(2026, 3, 8, 6, 30, LA)
        assertTrue(
          PolicyService.householdLocalDate(now, settings) == java.time.LocalDate.of(2026, 3, 8),
        )
      },
    ),
  )
}
