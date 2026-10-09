package wifihaven.api.feature

import doobie.*
import cats.syntax.all.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import wifihaven.api.db.*
import wifihaven.api.policy.PolicyService
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.testinfra.*
import zio.*
import zio.interop.catz.*
import zio.test.*

import java.time.{Instant, LocalDate}

/**
 * #2872: `/series` buckets and the hourly connection-event rollup are UTC-aligned whatever the
 * session time zone of the connection that runs them. The `date_bin` origin used to be a bare
 * `TIMESTAMP '2000-01-01 00:00:00'`, which Postgres converts to `timestamptz` in the SESSION time
 * zone: under America/Denver every daily bucket started at 07:00Z, and under a half-hour zone
 * (Asia/Kolkata, +05:30) every hourly bucket started at :30.
 *
 * Each case runs the repo on a transactor whose connections first `SET TIME ZONE` to a non-UTC
 * zone, so it fails on the old origin regardless of the JVM or server default.
 */
object ConnectionEventBucketTimeZoneSpec
    extends ZIOSpec[
      TestDatabase.AllRepos & EmbeddedPostgres & Transactor[Task],
    ] {

  override val bootstrap = TestDatabase.layer

  private val Mac   = MacAddress.unsafe("aa:bb:cc:dd:28:72")
  private val Host  = "video.example.com"
  private val Day   = LocalDate.of(2026, 3, 2)
  private val Early = Instant.parse("2026-03-02T01:10:00Z") // before 07:00Z (Denver's old origin)
  private val Late  = Instant.parse("2026-03-02T10:10:00Z") // after 07:00Z, before 10:30Z
  private val Until = Instant.parse("2026-03-02T12:00:00Z")

  private val Denver  = "America/Denver"
  private val Kolkata = "Asia/Kolkata"

  // The shared per-spec transactor, with every connection's session time zone set to `zone`.
  private def inZone(xa: Transactor[Task], zone: String): Transactor[Task] =
    xa.copy(strategy0 =
      xa.strategy.copy(before =
        Fragment.const(s"SET TIME ZONE '$zone'").update.run.void *> xa.strategy.before,
      ),
    )

  private def fixture(zone: String) =
    for {
      _   <- TestDatabase.cleanAndMigrate
      xa  <- ZIO.service[Transactor[Task]]
      rid <- ZIO.serviceWithZIO[RouterRepo](
        _.create("ce-bucket-tz-2872", PolicyService.hashToken("et_dummy")),
      )
      events = new ConnectionEventRepoLive(inZone(xa, zone))
      _ <- events.insertBatch(List(Early, Late).map(t => event(rid, t)))
    } yield (inZone(xa, zone), events)

  private def event(rid: RouterId, ts: Instant) =
    ConnectionEventInsert(
      rid,
      Some(Mac),
      HostId.Fqdn(Hostname.unsafe(Host)),
      None,
      true,
      BlockReason.fromWire("allowed"),
      ts,
    )

  private def filter(hours: Int) =
    LogFilter(hours = hours, until = Some(Until), household = Some(HouseholdId.Default))

  private def windows(rows: List[ConnectionEventAggRow]) =
    rows.map(r => (r.windowStart, r.countSucceeded)).sorted

  def spec = suite("connection-event buckets are UTC-aligned in any session time zone (#2872)")(
    test("raw /series daily buckets start at UTC midnight under America/Denver") {
      for {
        (_, events) <- fixture(Denver)
        rows        <- events.querySeries(filter(24), 86400, Set.empty)
      } yield assertTrue(windows(rows) == List(("2026-03-02T00:00:00Z", 2)))
    },
    test("raw /series hourly buckets start on the hour under Asia/Kolkata") {
      for {
        (_, events) <- fixture(Kolkata)
        rows        <- events.querySeries(filter(24), 3600, Set.empty)
      } yield assertTrue(
        windows(rows) == List(("2026-03-02T01:00:00Z", 1), ("2026-03-02T10:00:00Z", 1)),
      )
    },
    test("daily rollup /series buckets and lastSeen start at UTC midnight under America/Denver") {
      for {
        (_, events) <- fixture(Denver)
        _           <- events.rerollConnEventsDaily(Day.minusDays(1))
        rows        <- events.querySeriesRollup(filter(48), 86400, Set.empty, BucketGrain.Daily)
      } yield assertTrue(
        windows(rows) == List(("2026-03-02T00:00:00Z", 2)),
        rows.map(_.lastSeen) == List("2026-03-02T00:00:00Z"),
      )
    },
    test("the hourly rollup writer stores on-the-hour buckets under Asia/Kolkata") {
      for {
        (xa, events) <- fixture(Kolkata)
        _            <- events.rerollConnEventsHourly(Instant.parse("2026-03-02T00:00:00Z"))
        stored       <-
          sql"SELECT bucket_start FROM connection_events_hourly ORDER BY bucket_start"
            .query[Instant]
            .to[List]
            .transact(xa)
        rows         <- events.querySeriesRollup(filter(24), 3600, Set.empty, BucketGrain.Hourly)
      } yield assertTrue(
        stored == List(
          Instant.parse("2026-03-02T01:00:00Z"),
          Instant.parse("2026-03-02T10:00:00Z"),
        ),
        windows(rows) == List(("2026-03-02T01:00:00Z", 1), ("2026-03-02T10:00:00Z", 1)),
      )
    },
  ) @@ TestAspect.sequential
}
