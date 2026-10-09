package wifihaven.api.feature

import doobie.*
import doobie.implicits.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import wifihaven.api.JwtConfig
import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.db.TypeMeta.given
import wifihaven.api.routes.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.testinfra.*
import zio.{Clock as _, *}
import zio.http.*
import zio.interop.catz.*
import zio.json.*
import zio.test.*

import java.time.{Instant, LocalDate, LocalDateTime, ZoneOffset}

/**
 * #2873 (epic #2841): a shared device is usually checked out overnight, so the daily rollup's
 * bucket-start label (the holder at 00:00 UTC, design `docs/design/shared-devices.md` §6.3) is
 * `(unassigned)` for its whole day. A `/series` read that groups or filters by profile therefore
 * reads the hourly rollup instead, which bounds the label error to the hour holding a check-in or
 * check-out. Reads that don't touch profile stay on the daily rollup, and so does a profile read
 * whose window reaches past the hourly rollup's retention.
 *
 * Each test wipes the raw table after the rerolls, and some wipe one rollup too, so the result
 * shows which table served the read.
 */
object SharedDeviceSeriesGrainSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]] {

  // "Now" for the route's retention check: the day after the events.
  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(LocalDateTime.of(2026, 3, 3, 12, 0))

  private val jwtCfg = JwtConfig(secret = "test-secret-at-least-32-chars!!x", expiryHours = 1)

  private val Mac      = MacAddress.unsafe("aa:bb:cc:dd:28:73")
  private val Host     = "homework.example.com"
  private val Day      = LocalDate.of(2026, 3, 2)
  private val CheckIn  = Instant.parse("2026-03-02T09:00:00Z")
  private val CheckOut = Instant.parse("2026-03-02T17:00:00Z")
  private val Events = List("09:10", "12:00", "16:50").map(t => Instant.parse(s"2026-03-02T$t:00Z"))
  private val Until  = "2026-03-03T00:00:00Z"

  final case class Fixture(routes: Routes[Any, Response], token: String, kids: ProfileId)

  // A shared device with no holder at 00:00 UTC, held by Kids 09:00–17:00, and three events in that
  // span. Both rollups are built and the raw table is wiped.
  private val fixture =
    for {
      _        <- TestDatabase.cleanAndMigrate
      xa       <- ZIO.service[Transactor[Task]]
      profiles <- ZIO.serviceWithZIO[ProfileRepo](_.listAllForHousehold(HouseholdId.Default))
      kids = profiles.find(_.name == "Kids").get.id
      rid    <- ZIO.serviceWithZIO[RouterRepo] { r =>
        r.create("home", Sha256Hex.unsafe("e" * 64))
          .tap(id => r.completeEnrollment(id, Sha256Hex.unsafe("f" * 64)))
      }
      dev    <- ZIO.serviceWithZIO[DeviceRepo](
        _.upsertUnknown(Mac, "family-ipad", None, Instant.parse("2026-03-01T12:00:00Z")),
      )
      _      <- sql"UPDATE devices SET shared = true WHERE id = $dev".update.run.transact(xa)
      dar    <- ZIO.service[DeviceAssignmentRepo]
      _      <- dar.assign(
        HouseholdId.Default,
        dev,
        Some(kids),
        CheckIn,
        AssignmentKind.CheckIn,
        None,
        AssignmentEndCause.Reassigned,
      )
      _      <- dar.assign(
        HouseholdId.Default,
        dev,
        None,
        CheckOut,
        AssignmentKind.CheckIn,
        None,
        AssignmentEndCause.CheckOut,
      )
      events <- ZIO.service[ConnectionEventRepo]
      _      <- events.insertBatch(Events.map(ts => event(rid, ts)))
      _      <- events.rerollConnEventsHourly(Day.atStartOfDay(ZoneOffset.UTC).toInstant)
      _      <- events.rerollConnEventsDaily(Day.minusDays(1))
      _      <- sql"DELETE FROM connection_events".update.run.transact(xa)
      upRepo <- ZIO.service[UserProfileRepo]
      auth   <- for {
        ur    <- ZIO.service[UserRepo]
        clock <- ZIO.service[Clock]
      } yield AuthServiceLive(ur, jwtCfg, clock)
      clock  <- ZIO.service[Clock]
      token  <- auth.login("admin", "changeme").map(_.token.value)
    } yield Fixture(LogRoutes.routes(auth, events, upRepo), token, kids)

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

  private def wipe(table: String) =
    ZIO.serviceWithZIO[Transactor[Task]](xa =>
      (fr"DELETE FROM" ++ Fragment.const(table)).update.run.transact(xa),
    )

  // A 30-day daily-bucket read (the SPA's aggregated band), so the window alone picks the daily
  // rollup. (group, succeeded) per row; `windowStart` follows date_bin's origin in the session
  // time zone (#2872), so it is not asserted.
  private def series(f: Fixture, query: String, hours: Int = 24 * 30) =
    for {
      resp <- f.routes.runZIO(
        Request
          .get(
            URL
              .decode(s"/api/connection-events/series?bucket=1d&hours=$hours&until=$Until&$query")
              .toOption
              .get,
          )
          .addHeader(Header.Authorization.Bearer(f.token)),
      )
      body <- resp.body.asString
      page <- ZIO.fromEither(body.fromJson[ConnectionEventSeriesPage]).mapError(new Exception(_))
    } yield (resp.status, page.rows.map(r => (r.groups.values.mkString, r.countSucceeded)))

  def spec = suite("/series grain for profile reads of a shared device (#2873)")(
    test("a profile-filtered daily window shows the holder's events") {
      for {
        f   <- fixture
        got <- series(f, s"groupBy=profile&profileId=${f.kids.value}")
      } yield assertTrue(got == (Status.Ok, List(("Kids", 3))))
    },
    test("a profile-grouped daily window labels the held span with the holder, not (unassigned)") {
      for {
        f   <- fixture
        got <- series(f, "groupBy=profile")
      } yield assertTrue(got == (Status.Ok, List(("Kids", 3))))
    },
    test("a read that doesn't group or filter by profile stays on the daily rollup") {
      for {
        f   <- fixture
        _   <- wipe("connection_events_hourly")
        got <- series(f, "groupBy=domain")
      } yield assertTrue(got == (Status.Ok, List((Host, 3))))
    },
    // Hourly rows older than its retention are swept, so reading hourly there would drop data. The
    // daily rollup's bucket-start label is the accepted fallback.
    test("a profile read whose window reaches past hourly retention stays on the daily rollup") {
      for {
        f   <- fixture
        _   <- wipe("connection_events_hourly")
        // until = 03-03T00:00Z, now = 03-03T12:00Z: 91 days back is older than now - 90 days.
        got <- series(f, "groupBy=profile", hours = 24 * 91)
      } yield assertTrue(got == (Status.Ok, List(("(unassigned)", 3))))
    },
  ) @@ TestAspect.sequential
}
