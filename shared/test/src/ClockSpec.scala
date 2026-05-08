package familydns.shared

import zio.{Clock as _, *}
import zio.test.*
import zio.test.Assertion.*

import java.time.{LocalDate, LocalDateTime, LocalTime}

object ClockSpec extends ZIOSpecDefault {

  def spec = suite("Clock")(
    suite("LiveClock")(
      test("now returns a datetime close to system time") {
        for {
          t1 <- Clock.now.provide(Clock.live)
          t2 = LocalDateTime.now()
        } yield assertTrue(t1.isAfter(t2.minusSeconds(2)) && t1.isBefore(t2.plusSeconds(2)))
      },
      test("today returns current date") {
        for d <- Clock.today.provide(Clock.live)
        yield assertTrue(d == LocalDate.now())
      },
    ),
    suite("TestClock")(
      test("starts at given datetime") {
        val dt = LocalDateTime.of(2025, 1, 6, 14, 0, 0)
        for now <- Clock.now.provide(Clock.TestClock.make(dt))
        yield assertTrue(now == dt)
      },
      test("advance moves clock forward") {
        val dt = LocalDateTime.of(2025, 1, 6, 14, 0, 0)
        for {
          ref <- Ref.make(dt)
          tc = new Clock.TestClock(ref)
          _   <- tc.advance(java.time.Duration.ofHours(2))
          now <- tc.now
        } yield assertTrue(now == dt.plusHours(2))
      },
      test("setTo replaces datetime") {
        val dt  = LocalDateTime.of(2025, 1, 6, 14, 0, 0)
        val dt2 = LocalDateTime.of(2025, 6, 15, 9, 30, 0)
        for {
          ref <- Ref.make(dt)
          tc = new Clock.TestClock(ref)
          _   <- tc.setTo(dt2)
          now <- tc.now
        } yield assertTrue(now == dt2)
      },
      test("setTime preserves date") {
        val dt = LocalDateTime.of(2025, 1, 6, 14, 0, 0)
        for {
          ref <- Ref.make(dt)
          tc = new Clock.TestClock(ref)
          _   <- tc.setTime(LocalTime.of(21, 30))
          now <- tc.now
        } yield assertTrue(now.toLocalDate == LocalDate.of(2025, 1, 6)) &&
          assertTrue(now.toLocalTime == LocalTime.of(21, 30))
      },
      test("setDate preserves time") {
        val dt = LocalDateTime.of(2025, 1, 6, 14, 0, 0)
        for {
          ref <- Ref.make(dt)
          tc = new Clock.TestClock(ref)
          _   <- tc.setDate(LocalDate.of(2025, 6, 15))
          now <- tc.now
        } yield assertTrue(now.toLocalDate == LocalDate.of(2025, 6, 15)) &&
          assertTrue(now.toLocalTime == LocalTime.of(14, 0))
      },
      test("today returns date component of current datetime") {
        val dt = LocalDateTime.of(2025, 3, 15, 22, 0, 0)
        for today <- Clock.today.provide(Clock.TestClock.make(dt))
        yield assertTrue(today == LocalDate.of(2025, 3, 15))
      },
      test("standard test fixtures have expected values") {
        for {
          afternoon <- Clock.now.provide(Clock.TestClock.make(Clock.TestClock.schoolDayAfternoon))
          bedtime   <- Clock.now.provide(Clock.TestClock.make(Clock.TestClock.bedtime))
          early     <- Clock.now.provide(Clock.TestClock.make(Clock.TestClock.earlyMorning))
        } yield assertTrue(afternoon.toLocalTime == LocalTime.of(14, 0)) &&
          assertTrue(bedtime.toLocalTime == LocalTime.of(21, 30)) &&
          assertTrue(early.toLocalTime == LocalTime.of(6, 0))
      },
    ),
  )
}
