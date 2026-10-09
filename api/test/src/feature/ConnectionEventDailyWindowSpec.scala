package wifihaven.api.feature

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import wifihaven.api.db.*
import wifihaven.api.db.TypeMeta.given
import wifihaven.api.policy.PolicyService
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.testinfra.*
import zio.*
import zio.interop.catz.*
import zio.test.*

import java.time.{Instant, LocalDate}

/**
 * #2874: the daily-rollup `/series` window selects the `connection_events_daily` rows whose UTC
 * midnight lies in `(anchor - hours, anchor]`. These cases pin which dates that admits at each
 * window edge, so the predicate can compare `cer.date` directly (and range-scan
 * `idx_ce_daily_date`) without changing the result.
 *
 * Each date gets its own hostname and the series is grouped by domain, so the assertion is the set
 * of dates the window admitted. `windowStart` is not asserted: it follows date_bin's origin in the
 * session time zone (#2872).
 */
object ConnectionEventDailyWindowSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Transactor[Task]] {

  override val bootstrap = TestDatabase.layer

  private val Mac   = "aa:bb:cc:dd:28:74"
  private val Dates = (1 to 7).map(d => LocalDate.of(2026, 3, d)).toList

  private def host(d: LocalDate) = s"d-$d.example.com"

  // One daily row per date in `Dates`, for one router.
  private val seed =
    for {
      _   <- TestDatabase.cleanAndMigrate
      xa  <- ZIO.service[Transactor[Task]]
      rid <- ZIO.serviceWithZIO[RouterRepo](
        _.create("ce-daily-window-2874", PolicyService.hashToken("et_dummy")),
      )
      _   <- ZIO.foreachDiscard(Dates)(d =>
        sql"""INSERT INTO connection_events_daily (router_id, mac, hostname, date, count_succeeded)
              VALUES ($rid, $Mac, ${host(d)}, $d, 1)""".update.run.transact(xa),
      )
    } yield ()

  // The hostnames (one per date) the daily series admits for a window ending at `until`.
  private def admitted(until: String, hours: Int) =
    ZIO
      .serviceWithZIO[ConnectionEventRepo](
        _.querySeriesRollup(
          LogFilter(
            hours = hours,
            until = Some(Instant.parse(until)),
            household = Some(HouseholdId.Default),
          ),
          86400,
          Set("domain"),
          BucketGrain.Daily,
        ),
      )
      .map(_.flatMap(_.groups.get("domain")).toSet)

  private def hosts(days: Int*) = days.map(d => host(LocalDate.of(2026, 3, d))).toSet

  def spec = suite("daily-rollup /series window edges (#2874)")(
    test("anchor at UTC midnight: the anchor's date is in, the lower bound's date is out") {
      for {
        _   <- seed
        got <- admitted("2026-03-05T00:00:00Z", 48)
      } yield assertTrue(got == hosts(4, 5))
    },
    test("lower bound exactly at a UTC midnight excludes that date; one hour wider includes it") {
      for {
        _      <- seed
        at     <- admitted("2026-03-05T10:00:00Z", 34) // lower = 03-04T00:00Z
        before <- admitted("2026-03-05T10:00:00Z", 35) // lower = 03-03T23:00Z
      } yield assertTrue(at == hosts(5), before == hosts(4, 5))
    },
    test("anchor one second before midnight excludes the next date") {
      for {
        _   <- seed
        got <- admitted("2026-03-04T23:59:59Z", 24) // lower = 03-03T23:59:59Z
      } yield assertTrue(got == hosts(4))
    },
    test("a multi-day window admits every date in (anchor - hours, anchor]") {
      for {
        _   <- seed
        got <- admitted("2026-03-06T00:00:00Z", 120) // lower = 03-01T00:00Z
      } yield assertTrue(got == hosts(2, 3, 4, 5, 6))
    },
    test("a sub-day window containing no UTC midnight admits nothing") {
      for {
        _   <- seed
        got <- admitted("2026-03-05T20:00:00Z", 6)
      } yield assertTrue(got.isEmpty)
    },
  ) @@ TestAspect.sequential
}
