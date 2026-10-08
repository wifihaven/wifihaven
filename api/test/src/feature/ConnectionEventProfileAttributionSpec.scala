package wifihaven.api.feature

import doobie.*
import doobie.implicits.*
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
 * #2845 (epic #2841, design `docs/design/shared-devices.md` §6.3): the `connection_events` log and
 * series reads label and filter each event by the profile that held its device AT THE EVENT'S
 * TIMESTAMP, read from `device_profile_assignments`, not by the device's current profile.
 *
 * The backfilled case is pinned on purpose: V90 gives every pre-existing device one open row with
 * `started_at IS NULL` (open-ended start). A bare `ce.ts >= dpa.started_at` is NULL for that row,
 * so it would drop the label from almost every device's history; the NULL-safe predicate keeps it.
 */
object ConnectionEventProfileAttributionSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Transactor[Task]] {

  override val bootstrap = TestDatabase.layer

  private val Mac  = MacAddress.unsafe("aa:bb:cc:dd:28:45")
  private val Host = "video.example.com"

  // The reassignment instant: mid-hour, so the hourly rollup's bucket-start rule is observable.
  private val Day      = LocalDate.of(2026, 3, 2)
  private val Reassign = Instant.parse("2026-03-02T10:30:00Z")
  private val Before   = Instant.parse("2026-03-02T10:10:00Z") // same hour, before the move
  private val SameHour = Instant.parse("2026-03-02T10:50:00Z") // same hour, after the move
  private val After    = Instant.parse("2026-03-02T11:10:00Z") // next hour, after the move
  private val Until    = Instant.parse("2026-03-02T12:00:00Z")

  final case class Fixture(
      xa: Transactor[Task],
      events: ConnectionEventRepo,
      assignments: DeviceAssignmentRepo,
      device: DeviceId,
      kids: ProfileId,
      adults: ProfileId,
  )

  // One device on Kids, its history in the V90 backfill shape (one open row, `started_at IS NULL`),
  // and one event at each of `ts` from the household's router.
  private def fixture(ts: List[Instant]) =
    for {
      _        <- TestDatabase.cleanAndMigrate
      xa       <- ZIO.service[Transactor[Task]]
      profiles <- ZIO.serviceWithZIO[ProfileRepo](_.listAllForHousehold(HouseholdId.Default))
      kids   = profiles.find(_.name == "Kids").get.id
      adults = profiles.find(_.name == "Adults").get.id
      rid    <- ZIO.serviceWithZIO[RouterRepo](
        _.create("ce-attribution-2845", PolicyService.hashToken("et_dummy")),
      )
      device <- ZIO.serviceWithZIO[DeviceRepo](_.upsert(Mac, "Tablet", Some(kids), "192.168.1.9"))
      // Test-only: rewrite the row the upsert opened into the shape the V90 backfill wrote.
      _      <-
        sql"UPDATE device_profile_assignments SET started_at = NULL WHERE device_id = $device".update.run
          .transact(xa)
      events <- ZIO.service[ConnectionEventRepo]
      _      <- events.insertBatch(ts.map(t => event(rid, t)))
      dar    <- ZIO.service[DeviceAssignmentRepo]
    } yield Fixture(xa, events, dar, device, kids, adults)

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

  private def reassign(f: Fixture, to: Option[ProfileId], at: Instant = Reassign) =
    f.assignments.assign(
      HouseholdId.Default,
      f.device,
      to,
      at,
      AssignmentKind.Assigned,
      None,
      if (to.isDefined) AssignmentEndCause.Reassigned else AssignmentEndCause.Unassigned,
    )

  private def filter(profiles: List[ProfileId] = Nil) =
    LogFilter(
      profileIds = profiles,
      hours = 6,
      until = Some(Until),
      household = Some(HouseholdId.Default),
    )

  // (event ts, labelled profile id, labelled profile name), oldest first.
  private def logLabels(f: Fixture, profiles: List[ProfileId] = Nil) =
    f.events
      .query(filter(profiles))
      .map(_.reverse.map(r => (Instant.parse(r.ts), r.profileId, r.profileName)))

  private def seriesByProfile(rows: List[ConnectionEventAggRow]) =
    rows
      .map(r => (r.windowStart, r.groups.getOrElse("profile", ""), r.countSucceeded))
      .sortBy(r => (r._1, r._2))

  def spec = suite("connection_events profile label at event time (#2845)")(
    test("a never-reassigned device (backfilled NULL-start row) is labelled and filterable") {
      for {
        f        <- fixture(List(Before, After))
        all      <- logLabels(f)
        filtered <- logLabels(f, List(f.kids))
      } yield assertTrue(
        all == List((Before, Some(f.kids), Some("Kids")), (After, Some(f.kids), Some("Kids"))),
        filtered.map(_._1) == List(Before, After),
      )
    },
    test("/api/logs labels an event by the profile that held the device at its timestamp") {
      for {
        f      <- fixture(List(Before, After))
        _      <- reassign(f, Some(f.adults))
        all    <- logLabels(f)
        kids   <- logLabels(f, List(f.kids))
        adults <- logLabels(f, List(f.adults))
      } yield assertTrue(
        all == List((Before, Some(f.kids), Some("Kids")), (After, Some(f.adults), Some("Adults"))),
        kids.map(_._1) == List(Before),
        adults.map(_._1) == List(After),
      )
    },
    test("an event before the device's first assignment, or after it is unassigned, has no label") {
      for {
        f   <- fixture(List(Before, After))
        // Give the device a bounded first row: unassign at 10:00, then assign Adults at 10:30.
        _   <- reassign(f, None, Instant.parse("2026-03-02T10:00:00Z"))
        _   <- reassign(f, Some(f.adults))
        all <- logLabels(f)
      } yield assertTrue(
        all == List((Before, None, None), (After, Some(f.adults), Some("Adults"))),
      )
    },
    test("raw /series groups and filters by the profile at event time") {
      for {
        f      <- fixture(List(Before, After))
        _      <- reassign(f, Some(f.adults))
        rows   <- f.events.querySeries(filter(), 3600, Set("profile"))
        adults <- f.events.querySeries(filter(List(f.adults)), 3600, Set("profile"))
      } yield assertTrue(
        seriesByProfile(rows) == List(
          ("2026-03-02T10:00:00Z", "Kids", 1),
          ("2026-03-02T11:00:00Z", "Adults", 1),
        ),
        seriesByProfile(adults) == List(("2026-03-02T11:00:00Z", "Adults", 1)),
      )
    },
    test("hourly rollup attributes a straddling bucket by its start") {
      for {
        f    <- fixture(List(Before, SameHour, After))
        _    <- reassign(f, Some(f.adults))
        _    <- f.events.rerollConnEventsHourly(Instant.parse("2026-03-02T08:00:00Z"))
        rows <- f.events.querySeriesRollup(filter(), 3600, Set("profile"), BucketGrain.Hourly)
        kids <- f.events
          .querySeriesRollup(filter(List(f.kids)), 3600, Set("profile"), BucketGrain.Hourly)
      } yield assertTrue(
        // The 10:00 bucket holds an event from after the 10:30 move, and is still Kids'.
        seriesByProfile(rows) == List(
          ("2026-03-02T10:00:00Z", "Kids", 2),
          ("2026-03-02T11:00:00Z", "Adults", 1),
        ),
        seriesByProfile(kids) == List(("2026-03-02T10:00:00Z", "Kids", 2)),
      )
    },
    test("daily rollup attributes the day to the profile that held the device at midnight") {
      for {
        f    <- fixture(List(Before, After))
        _    <- reassign(f, Some(f.adults))
        _    <- f.events.rerollConnEventsDaily(Day.minusDays(1))
        rows <- f.events.querySeriesRollup(
          filter().copy(hours = 48),
          86400,
          Set("profile"),
          BucketGrain.Daily,
        )
        // Profile and count only: the re-binned `windowStart` follows date_bin's origin in the session
        // time zone, which is not what this test is about.
      } yield assertTrue(seriesByProfile(rows).map(r => (r._2, r._3)) == List(("Kids", 2)))
    },
  ) @@ TestAspect.sequential
}
