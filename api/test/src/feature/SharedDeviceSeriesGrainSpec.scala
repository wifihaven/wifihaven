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
 * nobody for its whole day. A profile-filtered `/series` read therefore reads the hourly rollup
 * instead, which bounds the error to the hour holding a check-in or check-out. Unfiltered reads
 * stay on the daily rollup, and so does a profile read whose window reaches past the hourly
 * rollup's retention. Grouping by profile is rejected.
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
  private val DayStart = "2026-03-02T00:00:00Z"

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
      sdr    <- ZIO.service[SharedDeviceRepo]
      in     <- sdr.checkIn(HouseholdId.Default, dev, kids, CheckIn, "admin")
      out    <- sdr.checkOut(
        HouseholdId.Default,
        dev,
        kids,
        CheckOut,
        "admin",
        AssignmentEndCause.CheckOut,
      )
      _      <- ZIO
        .fail(new Exception(s"fixture check-in/out failed: $in, $out"))
        .unless(in == CheckInOutcome.CheckedIn && out == CheckOutOutcome.CheckedOut)
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
    } yield Fixture(LogRoutes.routes(auth, events, upRepo, clock), token, kids)

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
  // rollup. (windowStart, group, succeeded) per row.
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
      rows <-
        if (resp.status != Status.Ok) ZIO.succeed(Nil)
        else
          ZIO
            .fromEither(body.fromJson[ConnectionEventSeriesPage])
            .mapError(new Exception(_))
            .map(_.rows.map(r => (r.windowStart, r.groups.values.mkString, r.countSucceeded)))
    } yield (resp.status, rows)

  def spec = suite("/series grain for profile reads of a shared device (#2873)")(
    test("a profile-filtered daily window shows the holder's events") {
      for {
        f   <- fixture
        got <- series(f, s"groupBy=device&profileId=${f.kids.value}")
      } yield assertTrue(got == (Status.Ok, List((DayStart, "family-ipad", 3))))
    },
    // Grouping by profile was removed: on hourly it was too slow on prod, on daily it mislabels.
    test("groupBy=profile is rejected") {
      for {
        f   <- fixture
        got <- series(f, "groupBy=profile")
      } yield assertTrue(got == (Status.BadRequest, Nil))
    },
    test("a read that doesn't filter by profile stays on the daily rollup") {
      for {
        f   <- fixture
        _   <- wipe("connection_events_hourly")
        got <- series(f, "groupBy=domain")
      } yield assertTrue(got == (Status.Ok, List((DayStart, Host, 3))))
    },
    // Hourly rows older than its retention are swept, so reading hourly there would drop data; the
    // daily rollup's bucket-start rule is the accepted fallback. The hourly rows are wiped and Kids'
    // span is moved to start before midnight, so only a daily read can return the events.
    test("a profile read whose window reaches past hourly retention stays on the daily rollup") {
      for {
        f   <- fixture
        _   <- wipe("connection_events_hourly")
        _   <- ZIO.serviceWithZIO[Transactor[Task]](xa =>
          sql"""UPDATE device_profile_assignments SET started_at = '2026-03-01T00:00:00Z'
                WHERE profile_id = ${f.kids}""".update.run.transact(xa),
        )
        // until = 03-03T00:00Z, now = 03-03T12:00Z: 91 days back is older than now - 90 days.
        in  <- series(f, s"groupBy=device&profileId=${f.kids.value}", hours = 24 * 91)
        // A 30-day window is inside retention, so the same filter reads the (empty) hourly rollup.
        out <- series(f, s"groupBy=device&profileId=${f.kids.value}")
      } yield assertTrue(
        in == (Status.Ok, List((DayStart, "family-ipad", 3))),
        out == (Status.Ok, Nil),
      )
    },
  ) @@ TestAspect.sequential
}
