package wifihaven.api.feature

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import wifihaven.api.JwtConfig
import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.db.TypeMeta.given
import wifihaven.api.policy.*
import wifihaven.api.routes.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.http.*
import zio.interop.catz.*
import zio.json.*
import zio.metrics.Metric
import zio.test.*

import java.time.{Instant, LocalDateTime, ZoneOffset}

/**
 * #2843 (epic #2841, design `docs/design/shared-devices.md` §5.3): every write of
 * `devices.profile_id` goes through `DeviceAssignment.assign`, which keeps the assignment history
 * in step in the same transaction; and the standing drift check on the reevaluate tick repairs any
 * device whose two stores disagree.
 *
 * Driven through the real routes and repos on embedded Postgres, with an injected TestClock so the
 * interval bounds are exact. Every test ends with the [[AssignmentInvariant]] test-pin.
 */
object DeviceAssignmentSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Transactor[Task]] {

  override val bootstrap = TestDatabase.layer

  private val jwtCfg = JwtConfig(secret = "test-secret-at-least-32-chars!!x", expiryHours = 1)
  private val T0     = LocalDateTime.of(2026, 3, 2, 9, 0)
  private def instantOf(dt: LocalDateTime): Instant = dt.toInstant(ZoneOffset.UTC)

  private val Mac = MacAddress.unsafe("aa:bb:cc:dd:ee:43")

  final case class Row(
      id: Long,
      householdId: Long,
      profileId: Long,
      startedAt: Option[Instant],
      endedAt: Option[Instant],
      kind: String,
      startedBy: Option[Long],
      endedBy: Option[Long],
      endCause: Option[String],
  )

  private def history(xa: Transactor[Task], mac: MacAddress = Mac): Task[List[Row]] =
    sql"""SELECT h.id, h.household_id, h.profile_id, h.started_at, h.ended_at, h.kind,
                 h.started_by, h.ended_by, h.end_cause
            FROM device_profile_assignments h JOIN devices d ON d.id = h.device_id
           WHERE d.mac = $mac ORDER BY h.id"""
      .query[Row]
      .to[List]
      .transact(xa)

  private def currentProfile(xa: Transactor[Task], mac: MacAddress = Mac): Task[Option[Long]] =
    sql"SELECT profile_id FROM devices WHERE mac = $mac".query[Option[Long]].unique.transact(xa)

  private def deviceId(xa: Transactor[Task], mac: MacAddress = Mac): Task[DeviceId] =
    sql"SELECT id FROM devices WHERE mac = $mac".query[DeviceId].unique.transact(xa)

  private def adminId(xa: Transactor[Task]): Task[Long] =
    sql"SELECT id FROM users WHERE username = 'admin' AND household_id = 1"
      .query[Long]
      .unique
      .transact(xa)

  // Test-only simulations of state no production writer may produce: an old image writing
  // `devices.profile_id` without history (the drift the tick repairs), and the `shared` flag, whose
  // route lands in #2848.
  private def driftProfile(xa: Transactor[Task], pid: Option[ProfileId]): Task[Unit] =
    sql"UPDATE devices SET profile_id = $pid WHERE mac = $Mac".update.run.transact(xa).unit

  private def markShared(xa: Transactor[Task]): Task[Unit] =
    sql"UPDATE devices SET shared = TRUE WHERE mac = $Mac".update.run.transact(xa).unit

  private def driftCounter: UIO[Double] =
    Metric.counter("device_assignment_drift_repaired_total").value.map(_.count)

  final case class Fixture(
      xa: Transactor[Task],
      clock: Clock.TestClock,
      devices: DeviceRepo,
      assignments: DeviceAssignmentRepo,
      routes: Routes[Any, Response],
      token: String,
      kids: ProfileId,
      adults: ProfileId,
  )

  private def fixture
      : ZIO[TestDatabase.AllRepos & EmbeddedPostgres & Transactor[Task], Throwable, Fixture] =
    for {
      _   <- TestDatabase.cleanAndMigrate
      xa  <- ZIO.service[Transactor[Task]]
      ref <- Ref.make(T0)
      clk = new Clock.TestClock(ref)
      ur       <- ZIO.service[UserRepo]
      upr      <- ZIO.service[UserProfileRepo]
      pr       <- ZIO.service[ProfileRepo]
      profiles <- pr.listAllForHousehold(HouseholdId.Default)
      auth    = AuthServiceLive(ur, jwtCfg, clk)
      devices = new DeviceRepoLive(xa, clk)
      token <- auth.login("admin", "changeme").map(_.token.value)
    } yield Fixture(
      xa,
      clk,
      devices,
      new DeviceAssignmentRepoLive(xa),
      DeviceRoutes.routes(auth, devices, upr, pr),
      token,
      profiles.find(_.name == "Kids").get.id,
      profiles.find(_.name == "Adults").get.id,
    )

  private def url(p: String) = URL.decode(p).toOption.get

  private def put(f: Fixture, pid: Option[ProfileId], name: String = "Tablet") =
    f.routes.runZIO(
      Request
        .put(url("/api/devices"), Body.fromString(UpsertDeviceRequest(Mac, name, pid).toJson))
        .addHeader(Header.Authorization.Bearer(f.token))
        .addHeader(Header.ContentType(MediaType.application.json)),
    )

  private def patch(f: Fixture, body: String) =
    f.routes.runZIO(
      Request
        .patch(url(s"/api/devices/${Mac.value}"), Body.fromString(body))
        .addHeader(Header.Authorization.Bearer(f.token))
        .addHeader(Header.ContentType(MediaType.application.json)),
    )

  // A PolicyService with the snapshot cache on, so `reevaluate` runs the per-household tick, and
  // the drift check wired the way `PolicyService.layer` wires it.
  private def policy(f: Fixture) =
    for {
      pr   <- ZIO.service[ProfileRepo]
      hsr  <- ZIO.service[HouseholdSettingsRepo]
      tlr  <- ZIO.service[TimeLimitRepo]
      atlr <- ZIO.service[AppTimeLimitRepo]
      blr  <- ZIO.service[BlocklistRepo]
      trr  <- ZIO.service[TrafficReportRepo]
      er   <- ZIO.service[TimeExtensionRepo]
      ar   <- ZIO.service[AppRepo]
      nsr  <- ZIO.service[NamedScheduleRepo]
      tss = new TimeStatusServiceLive(
        pr,
        tlr,
        atlr,
        f.devices,
        trr,
        er,
        NoopTimeUsedRollupRepo,
        nsr,
      )
    } yield new PolicyServiceLive(
      pr,
      hsr,
      tlr,
      atlr,
      f.devices,
      blr,
      trr,
      er,
      ar,
      tss,
      f.clock,
      namedScheduleRepo = nsr,
      cacheEnabled = true,
      repairAssignmentDrift = f.assignments.repairDrift,
    )

  private def pinned(f: Fixture, result: TestResult): Task[TestResult] =
    AssignmentInvariant.violations(f.xa).map(vs => result && assertTrue(vs == Nil))

  def spec = suite("DeviceAssignment (#2843)")(
    suite("routes write history through the primitive")(
      test("PUT with a profile opens one assigned row at the clock instant, by the caller") {
        for {
          f     <- fixture
          resp  <- put(f, Some(f.kids))
          rows  <- history(f.xa)
          admin <- adminId(f.xa)
          opened = rows.map(r => (r.profileId, r.startedAt, r.endedAt, r.kind, r.startedBy))
          res    = assertTrue(
            resp.status == Status.Ok,
            opened == List((f.kids.value, Some(instantOf(T0)), None, "assigned", Some(admin))),
            rows.map(_.householdId) == List(1L),
          )
          out <- pinned(f, res)
        } yield out
      },
      test("PUT without a profile writes no history") {
        for {
          f    <- fixture
          _    <- put(f, None)
          rows <- history(f.xa)
          out  <- pinned(f, assertTrue(rows.isEmpty))
        } yield out
      },
      test("PATCH to another profile closes the open row as reassigned and opens the new one") {
        for {
          f     <- fixture
          _     <- put(f, Some(f.kids))
          _     <- f.clock.advance(java.time.Duration.ofHours(2))
          resp  <- patch(f, s"""{"profileId":${f.adults.value}}""")
          rows  <- history(f.xa)
          admin <- adminId(f.xa)
          t1  = instantOf(T0.plusHours(2))
          res = assertTrue(
            resp.status == Status.Ok,
            rows.map(r => (r.profileId, r.startedAt, r.endedAt, r.endCause, r.endedBy)) == List(
              (f.kids.value, Some(instantOf(T0)), Some(t1), Some("reassigned"), Some(admin)),
              (f.adults.value, Some(t1), None, None, None),
            ),
          )
          out <- pinned(f, res)
        } yield out
      },
      test("PATCH profileId null closes the open row as unassigned and clears devices.profile_id") {
        for {
          f    <- fixture
          _    <- put(f, Some(f.kids))
          _    <- f.clock.advance(java.time.Duration.ofMinutes(30))
          _    <- patch(f, """{"profileId":null}""")
          rows <- history(f.xa)
          cur  <- currentProfile(f.xa)
          res = assertTrue(
            cur.isEmpty,
            rows.map(r => (r.endedAt, r.endCause)) ==
              List((Some(instantOf(T0.plusMinutes(30))), Some("unassigned"))),
          )
          out <- pinned(f, res)
        } yield out
      },
      test("a rename and a same-profile PUT leave history untouched") {
        for {
          f      <- fixture
          _      <- put(f, Some(f.kids))
          before <- history(f.xa)
          _      <- f.clock.advance(java.time.Duration.ofMinutes(5))
          _      <- patch(f, """{"name":"Renamed"}""")
          _      <- put(f, Some(f.kids), name = "Again")
          after  <- history(f.xa)
          out    <- pinned(f, assertTrue(after == before))
        } yield out
      },
      test("seeding through DeviceRepo.upsert goes through the primitive too") {
        for {
          f    <- fixture
          _    <- f.devices.upsert(Mac, "Seeded", Some(f.kids), "")
          rows <- history(f.xa)
          out  <- pinned(
            f,
            assertTrue(rows.map(r => (r.profileId, r.startedBy)) == List((f.kids.value, None))),
          )
        } yield out
      },
    ),
    suite("the primitive")(
      test("refuses an assigned row on a shared device and leaves both stores unchanged") {
        for {
          f      <- fixture
          _      <- put(f, None)
          _      <- markShared(f.xa)
          id     <- deviceId(f.xa)
          result <- f.assignments
            .assign(
              HouseholdId.Default,
              id,
              Some(f.kids),
              instantOf(T0),
              AssignmentKind.Assigned,
              None,
              AssignmentEndCause.Reassigned,
            )
            .either
          rows   <- history(f.xa)
          cur    <- currentProfile(f.xa)
          res = assertTrue(
            result.left.exists(_.isInstanceOf[SharedDeviceAssignmentRefused]),
            rows.isEmpty,
            cur.isEmpty,
          )
          out <- pinned(f, res)
        } yield out
      },
      test("opens a check_in row on a shared device") {
        for {
          f    <- fixture
          _    <- put(f, None)
          _    <- markShared(f.xa)
          id   <- deviceId(f.xa)
          _    <- f.assignments.assign(
            HouseholdId.Default,
            id,
            Some(f.kids),
            instantOf(T0),
            AssignmentKind.CheckIn,
            None,
            AssignmentEndCause.CheckOut,
          )
          rows <- history(f.xa)
          cur  <- currentProfile(f.xa)
          res = assertTrue(rows.map(_.kind) == List("check_in"), cur.contains(f.kids.value))
          out <- pinned(f, res)
        } yield out
      },
      test("refuses a device outside the caller's household") {
        for {
          f      <- fixture
          _      <- put(f, None)
          id     <- deviceId(f.xa)
          result <- f.assignments
            .assign(
              HouseholdId(999L),
              id,
              Some(f.kids),
              instantOf(T0),
              AssignmentKind.Assigned,
              None,
              AssignmentEndCause.Reassigned,
            )
            .either
          rows   <- history(f.xa)
        } yield assertTrue(result.isLeft, rows.isEmpty)
      },
      test(
        "a clock behind the open row's start never produces an inverted or overlapping interval",
      ) {
        for {
          f    <- fixture
          _    <- put(f, Some(f.kids))
          _    <- f.clock.setTo(T0.minusMinutes(10))
          _    <- patch(f, s"""{"profileId":${f.adults.value}}""")
          rows <- history(f.xa)
          res = assertTrue(
            rows.map(r => (r.startedAt, r.endedAt)) == List(
              (Some(instantOf(T0)), Some(instantOf(T0))),
              (Some(instantOf(T0)), None),
            ),
          )
          out <- pinned(f, res)
        } yield out
      },
    ),
    suite("standing drift check on the reevaluate tick")(
      test(
        "a non-shared device whose profile_id moved without history is closed and reopened at the tick",
      ) {
        for {
          f      <- fixture
          _      <- put(f, Some(f.kids))
          _      <- driftProfile(f.xa, Some(f.adults))
          _      <- f.clock.advance(java.time.Duration.ofHours(1))
          svc    <- policy(f)
          before <- driftCounter
          _      <- svc.reevaluate
          mid    <- driftCounter
          _      <- svc.reevaluate
          after  <- driftCounter
          rows   <- history(f.xa)
          t1  = instantOf(T0.plusHours(1))
          res = assertTrue(
            mid - before == 1.0,
            after == mid,
            rows.map(r => (r.profileId, r.startedAt, r.endedAt, r.endCause, r.kind)) == List(
              (f.kids.value, Some(instantOf(T0)), Some(t1), Some("reassigned"), "assigned"),
              (f.adults.value, Some(t1), None, None, "assigned"),
            ),
          )
          out <- pinned(f, res)
        } yield out
      },
      test("a device cleared without history has its open row closed as unassigned") {
        for {
          f    <- fixture
          _    <- put(f, Some(f.kids))
          _    <- driftProfile(f.xa, None)
          svc  <- policy(f)
          _    <- svc.reevaluate
          rows <- history(f.xa)
          out  <- pinned(f, assertTrue(rows.map(_.endCause) == List(Some("unassigned"))))
        } yield out
      },
      test("a device assigned without history gets an open assigned row") {
        for {
          f    <- fixture
          _    <- put(f, None)
          _    <- driftProfile(f.xa, Some(f.kids))
          svc  <- policy(f)
          _    <- svc.reevaluate
          rows <- history(f.xa)
          out  <- pinned(
            f,
            assertTrue(rows.map(r => (r.profileId, r.endedAt)) == List((f.kids.value, None))),
          )
        } yield out
      },
      test("a shared device with an open check-in has profile_id restored from the check-in") {
        for {
          f      <- fixture
          _      <- put(f, None)
          _      <- markShared(f.xa)
          id     <- deviceId(f.xa)
          _      <- f.assignments.assign(
            HouseholdId.Default,
            id,
            Some(f.kids),
            instantOf(T0),
            AssignmentKind.CheckIn,
            None,
            AssignmentEndCause.CheckOut,
          )
          before <- history(f.xa)
          _      <- driftProfile(f.xa, Some(f.adults))
          svc    <- policy(f)
          _      <- svc.reevaluate
          after  <- history(f.xa)
          cur    <- currentProfile(f.xa)
          out    <- pinned(f, assertTrue(cur.contains(f.kids.value), after == before))
        } yield out
      },
      test(
        "a shared device with a profile_id and no check-in is cleared, never given an assigned row",
      ) {
        for {
          f    <- fixture
          _    <- put(f, None)
          _    <- markShared(f.xa)
          _    <- driftProfile(f.xa, Some(f.kids))
          svc  <- policy(f)
          _    <- svc.reevaluate
          rows <- history(f.xa)
          cur  <- currentProfile(f.xa)
          out  <- pinned(f, assertTrue(cur.isEmpty, rows.isEmpty))
        } yield out
      },
      test(
        "a shared device still holding an assigned row has it closed as unassigned and is cleared",
      ) {
        for {
          f    <- fixture
          _    <- put(f, Some(f.kids))
          _    <- markShared(f.xa)
          svc  <- policy(f)
          _    <- svc.reevaluate
          rows <- history(f.xa)
          cur  <- currentProfile(f.xa)
          out  <- pinned(
            f,
            assertTrue(
              cur.isEmpty,
              rows.map(r => (r.kind, r.endCause)) == List(("assigned", Some("unassigned"))),
            ),
          )
        } yield out
      },
      test("a household with no drift repairs nothing") {
        for {
          f      <- fixture
          _      <- put(f, Some(f.kids))
          svc    <- policy(f)
          before <- driftCounter
          _      <- svc.reevaluate
          after  <- driftCounter
          out    <- pinned(f, assertTrue(after == before))
        } yield out
      },
    ),
  ) @@ TestAspect.sequential
}
