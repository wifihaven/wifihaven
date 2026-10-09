package wifihaven.api.feature

import doobie.*
import doobie.implicits.*
import wifihaven.api.JwtConfig
import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.policy.*
import wifihaven.api.routes.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.shared.Clock.TestClock
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.http.*
import zio.interop.catz.*
import zio.json.*
import zio.test.*

import java.time.ZoneOffset

/**
 * #2848 (epic #2841, design `docs/design/shared-devices.md` §9, §13, §15 Q1–Q5a): the shared-device
 * routes, driven through the real routes and repos on embedded Postgres.
 *
 *   - `GET /api/shared-devices`: every shared device in the caller's household with its holder.
 *   - `POST /api/shared-devices/{mac}/check-in {profileId}`: a child on a linked profile, an
 *     adult/admin on any household profile; 409 `held` / `profile_blocked` / `not_shared`, 403
 *     `not_linked`, 404 for another household's MAC or profile.
 *   - `POST /api/shared-devices/{mac}/check-out`: `check_out` by a user linked to the holder,
 *     `forced` by an adult/admin on someone else's check-in.
 *   - `shared` on `PATCH /api/devices/{mac}`, and the 409 `device_shared` guard on a non-null
 *     `profileId` for a shared device on both `PATCH` and `PUT /api/devices`.
 *
 * Each mutation must reach the router: the tests read the snapshot the router would apply.
 */
object SharedDeviceApiSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val jwtCfg  = JwtConfig(secret = "test-secret-at-least-32-chars!!x", expiryHours = 1)
  private val cleanDb = TestDatabase.cleanAndMigrate
  private val Now     = TestClock.schoolDayAfternoon.toInstant(ZoneOffset.UTC)

  private val SharedMac = MacAddress.unsafe("aa:bb:cc:00:48:01")
  private val PlainMac  = MacAddress.unsafe("aa:bb:cc:00:48:02")

  private def url(p: String) = URL.decode(p).toOption.get

  /** Everything a test needs: the two route tables, tokens per role, and the seeded ids. */
  final case class Fx(
      shared: Routes[Any, Response],
      devices: Routes[Any, Response],
      changes: Ref[List[HouseholdId]],
      kid1: ProfileId,
      kid2: ProfileId,
      admin: String,
      adult: String,
      child1: String,
      child2: String,
  )

  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwtCfg, clock)

  private def userToken(
      auth: AuthService,
      username: String,
      role: String,
      linked: List[ProfileId],
  ) =
    for {
      ur   <- ZIO.service[UserRepo]
      upr  <- ZIO.service[UserProfileRepo]
      hash <- auth.hashPassword("pass")
      id   <- ur.create(username, hash, role)
      _    <- ur.clearMustChangePassword(id)
      _    <- upr.setProfilesForUser(id, linked)
      tok  <- auth.login(username, "pass").mapError(e => new RuntimeException(s"login: $e"))
    } yield tok.token.value

  private def markShared(mac: MacAddress) =
    ZIO.serviceWithZIO[Transactor[Task]] { xa =>
      sql"UPDATE devices SET shared = true WHERE mac = ${mac.value}".update.run.transact(xa).unit
    }

  /**
   * A shared, checked-out device (`SharedMac`), a plain device on kid1 (`PlainMac`), two child
   * profiles, a child linked to each, and an adult linked to neither.
   */
  private def setup =
    for {
      _       <- cleanDb
      pr      <- ZIO.service[ProfileRepo]
      dr      <- ZIO.service[DeviceRepo]
      sdr     <- ZIO.service[SharedDeviceRepo]
      upr     <- ZIO.service[UserProfileRepo]
      hsr     <- ZIO.service[HouseholdSettingsRepo]
      clock   <- ZIO.service[Clock]
      ts      <- TestLayers.timeStatusService
      auth    <- makeAuth
      kid1    <- pr.create("Kid1", Nil)
      kid2    <- pr.create("Kid2", Nil)
      _       <- dr.upsertUnknown(SharedMac, "family-mac", None, Now)
      _       <- markShared(SharedMac)
      _       <- dr.upsert(PlainMac, "kid1-phone", Some(kid1), "")
      admin   <- auth
        .login("admin", "changeme")
        .map(_.token.value)
        .mapError(e => new RuntimeException(s"login: $e"))
      adult   <- userToken(auth, "mom", "adult", Nil)
      child1  <- userToken(auth, "kid1", "child", List(kid1))
      child2  <- userToken(auth, "kid2", "child", List(kid2))
      changes <- Ref.make(List.empty[HouseholdId])
      onChange = (hh: HouseholdId) => changes.update(hh :: _)
      shared   = SharedDeviceRoutes.routes(auth, dr, sdr, upr, pr, hsr, ts, clock, onChange)
      devices  = DeviceRoutes.routes(auth, dr, upr, pr, onChange)
    } yield Fx(shared, devices, changes, kid1, kid2, admin, adult, child1, child2)

  private def send(routes: Routes[Any, Response], req: Request, token: String) =
    routes.runZIO(
      req
        .addHeader(Header.Authorization.Bearer(token))
        .addHeader(Header.ContentType(MediaType.application.json)),
    )

  private def checkIn(fx: Fx, token: String, pid: ProfileId, mac: MacAddress = SharedMac) =
    send(
      fx.shared,
      Request.post(
        url(s"/api/shared-devices/${mac.value}/check-in"),
        Body.fromString(s"""{"profileId":${pid.value}}"""),
      ),
      token,
    )

  private def checkOut(fx: Fx, token: String, mac: MacAddress = SharedMac) =
    send(
      fx.shared,
      Request.post(url(s"/api/shared-devices/${mac.value}/check-out"), Body.empty),
      token,
    )

  private def list(fx: Fx, token: String) =
    send(fx.shared, Request.get(url("/api/shared-devices")), token).flatMap(r =>
      r.body.asString.map(b => (r.status, b.fromJson[List[SharedDevice]])),
    )

  private def patchDevice(fx: Fx, token: String, mac: MacAddress, body: String) =
    send(fx.devices, Request.patch(url(s"/api/devices/${mac.value}"), Body.fromString(body)), token)

  private def errorOf(r: Response): Task[String] = r.body.asString

  private def device(mac: MacAddress) =
    ZIO.serviceWithZIO[DeviceRepo](_.findByMac(mac)).someOrFailException

  /** `(kind, profile_id, end_cause)` of every history row for `mac`, oldest first. */
  private def history(mac: MacAddress) =
    ZIO.serviceWithZIO[Transactor[Task]] { xa =>
      sql"""SELECT h.kind, h.profile_id, h.end_cause
              FROM device_profile_assignments h JOIN devices d ON d.id = h.device_id
             WHERE d.mac = ${mac.value} ORDER BY h.id"""
        .query[(String, Long, Option[String])]
        .to[List]
        .transact(xa)
    }

  private def snapshotDevice(mac: MacAddress) =
    for {
      pr   <- ZIO.service[ProfileRepo]
      hsr  <- ZIO.service[HouseholdSettingsRepo]
      tlr  <- ZIO.service[TimeLimitRepo]
      atlr <- ZIO.service[AppTimeLimitRepo]
      dr   <- ZIO.service[DeviceRepo]
      blr  <- ZIO.service[BlocklistRepo]
      trr  <- ZIO.service[TrafficReportRepo]
      er   <- ZIO.service[TimeExtensionRepo]
      ar   <- ZIO.service[AppRepo]
      nsr  <- ZIO.service[NamedScheduleRepo]
      clk  <- ZIO.service[Clock]
      ps = PolicyServiceLive(pr, hsr, tlr, atlr, dr, blr, trr, er, ar, clk, namedScheduleRepo = nsr)
      snap <- ps.snapshot
    } yield snap.devices.get(mac)

  private def isCheckedOut(dp: Option[DevicePolicy]): Boolean =
    dp.exists(d =>
      d.profileId.isEmpty &&
        d.rules.exists(r => r.blocked && r.blockReason.contains(MacBlockReason.CheckedOut)),
    )

  def spec = suite("Shared-device API (#2848)")(
    suite("GET /api/shared-devices")(
      test("lists only shared devices; a checked-out device has no holder") {
        for {
          fx           <- setup
          (status, ls) <- list(fx, fx.admin)
        } yield assertTrue(
          status == Status.Ok,
          ls.map(_.map(_.mac)) == Right(List(SharedMac)),
          ls.toOption.flatMap(_.headOption).exists(_.holder.isEmpty),
        )
      },
      test("a child sees every shared device, with the holder, the user and since-when") {
        for {
          fx           <- setup
          _            <- checkIn(fx, fx.child2, fx.kid2)
          (status, ls) <- list(fx, fx.child1)
          holder = ls.toOption.flatMap(_.headOption).flatMap(_.holder)
        } yield assertTrue(
          status == Status.Ok,
          holder.map(_.profileId).contains(fx.kid2),
          holder.map(_.profileName).contains("Kid2"),
          holder.flatMap(_.checkedInBy).contains("kid2"),
          holder.map(_.since).contains(Now.toString),
        )
      },
      test("401 without a token") {
        for {
          fx   <- setup
          resp <- fx.shared.runZIO(Request.get(url("/api/shared-devices")))
        } yield assertTrue(resp.status == Status.Unauthorized)
      },
    ),
    suite("POST /api/shared-devices/{mac}/check-in")(
      test("a child checks in on a linked profile: the snapshot resolves to the holder") {
        for {
          fx      <- setup
          resp    <- checkIn(fx, fx.child1, fx.kid1)
          dev     <- device(SharedMac)
          hist    <- history(SharedMac)
          snap    <- snapshotDevice(SharedMac)
          changes <- fx.changes.get
        } yield assertTrue(
          resp.status == Status.Ok,
          dev.profileId.contains(fx.kid1),
          hist == List(("check_in", fx.kid1.value, None)),
          snap.exists(d => d.profileId.contains(fx.kid1) && d.rules.isEmpty),
          changes == List(HouseholdId.Default),
        )
      },
      test("a child on a profile they are not linked to: 403 not_linked, nothing written") {
        for {
          fx   <- setup
          resp <- checkIn(fx, fx.child1, fx.kid2)
          body <- errorOf(resp)
          dev  <- device(SharedMac)
          ch   <- fx.changes.get
        } yield assertTrue(
          resp.status == Status.Forbidden,
          body.contains("not_linked"),
          dev.profileId.isEmpty,
          ch.isEmpty,
        )
      },
      test("an adult linked to no profile may check in on any household profile") {
        for {
          fx   <- setup
          resp <- checkIn(fx, fx.adult, fx.kid2)
          dev  <- device(SharedMac)
        } yield assertTrue(resp.status == Status.Ok, dev.profileId.contains(fx.kid2))
      },
      test("an already-held device: 409 held, the holder is unchanged") {
        for {
          fx   <- setup
          _    <- checkIn(fx, fx.child1, fx.kid1)
          resp <- checkIn(fx, fx.child2, fx.kid2)
          body <- errorOf(resp)
          dev  <- device(SharedMac)
        } yield assertTrue(
          resp.status == Status.Conflict,
          body.contains("\"held\""),
          dev.profileId.contains(fx.kid1),
        )
      },
      test("a paused profile: 409 profile_blocked with the reason") {
        for {
          fx   <- setup
          _    <- ZIO.serviceWithZIO[ProfileRepo](_.setPaused(fx.kid1, true))
          resp <- checkIn(fx, fx.child1, fx.kid1)
          body <- errorOf(resp)
          dev  <- device(SharedMac)
        } yield assertTrue(
          resp.status == Status.Conflict,
          body.contains("profile_blocked"),
          body.contains("Paused"),
          dev.profileId.isEmpty,
        )
      },
      test("a profile in a schedule block: 409 profile_blocked (Schedule)") {
        for {
          fx   <- setup
          nsr  <- ZIO.service[NamedScheduleRepo]
          // 13:00–15:00 every day covers the fixture clock's Monday 14:00.
          sid  <- nsr.create(
            "Homework",
            None,
            List(
              ScheduleWindow(
                List("mon", "tue", "wed", "thu", "fri", "sat", "sun"),
                java.time.LocalTime.of(13, 0),
                java.time.LocalTime.of(15, 0),
                java.time.ZoneId.of("UTC"),
              ),
            ),
          )
          _    <- nsr.setProfileBlockSchedules(fx.kid1, List(sid))
          resp <- checkIn(fx, fx.child1, fx.kid1)
          body <- errorOf(resp)
          dev  <- device(SharedMac)
        } yield assertTrue(
          resp.status == Status.Conflict,
          body.contains("profile_blocked"),
          body.contains("Schedule"),
          dev.profileId.isEmpty,
        )
      },
      test("a default-deny profile is a baseline, not a block: check-in succeeds") {
        for {
          fx   <- setup
          _    <- ZIO.serviceWithZIO[ProfileRepo](_.setDefaultDeny(fx.kid1, true))
          resp <- checkIn(fx, fx.child1, fx.kid1)
        } yield assertTrue(resp.status == Status.Ok)
      },
      test("a device that is not shared: 409 not_shared, its assignment untouched") {
        for {
          fx   <- setup
          resp <- checkIn(fx, fx.admin, fx.kid2, PlainMac)
          body <- errorOf(resp)
          dev  <- device(PlainMac)
        } yield assertTrue(
          resp.status == Status.Conflict,
          body.contains("not_shared"),
          dev.profileId.contains(fx.kid1),
        )
      },
      test("an unknown MAC: 404") {
        for {
          fx   <- setup
          resp <- checkIn(fx, fx.admin, fx.kid1, MacAddress.unsafe("aa:bb:cc:00:48:99"))
        } yield assertTrue(resp.status == Status.NotFound)
      },
      test("another household's MAC and another household's profile both 404") {
        for {
          fx    <- setup
          two   <- TestLayers.seedTwoHouseholds(
            MacAddress.unsafe("aa:bb:cc:00:48:a1"),
            MacAddress.unsafe("aa:bb:cc:00:48:b1"),
          )
          mac   <- checkIn(fx, fx.admin, fx.kid1, two.macB)
          prof  <- checkIn(fx, fx.admin, two.profileB)
          devB  <- ZIO.serviceWithZIO[DeviceRepo](_.findByMacInHousehold(two.macB, two.hhB))
          devSh <- device(SharedMac)
        } yield assertTrue(
          mac.status == Status.NotFound,
          prof.status == Status.NotFound,
          devB.exists(_.profileId.contains(two.profileB)),
          devSh.profileId.isEmpty,
        )
      },
    ),
    suite("POST /api/shared-devices/{mac}/check-out")(
      test("the holder checks out: check_out, and the snapshot is CheckedOut again") {
        for {
          fx   <- setup
          _    <- checkIn(fx, fx.child1, fx.kid1)
          resp <- checkOut(fx, fx.child1)
          dev  <- device(SharedMac)
          hist <- history(SharedMac)
          snap <- snapshotDevice(SharedMac)
          ch   <- fx.changes.get
        } yield assertTrue(
          resp.status == Status.Ok,
          dev.profileId.isEmpty,
          hist == List(("check_in", fx.kid1.value, Some("check_out"))),
          isCheckedOut(snap),
          ch.size == 2,
        )
      },
      test("another child: 403 not_linked, the check-in stays open") {
        for {
          fx   <- setup
          _    <- checkIn(fx, fx.child1, fx.kid1)
          resp <- checkOut(fx, fx.child2)
          body <- errorOf(resp)
          dev  <- device(SharedMac)
        } yield assertTrue(
          resp.status == Status.Forbidden,
          body.contains("not_linked"),
          dev.profileId.contains(fx.kid1),
        )
      },
      test("an adult on someone else's check-in: forced") {
        for {
          fx   <- setup
          _    <- checkIn(fx, fx.child1, fx.kid1)
          resp <- checkOut(fx, fx.adult)
          hist <- history(SharedMac)
        } yield assertTrue(
          resp.status == Status.Ok,
          hist == List(("check_in", fx.kid1.value, Some("forced"))),
        )
      },
      test("nobody holds the device: 409 not_held") {
        for {
          fx   <- setup
          resp <- checkOut(fx, fx.admin)
          body <- errorOf(resp)
        } yield assertTrue(resp.status == Status.Conflict, body.contains("not_held"))
      },
    ),
    suite("PATCH /api/devices/{mac} {shared} and the device_shared guard")(
      test("making a device shared closes its assigned row (made_shared) and checks it out") {
        for {
          fx   <- setup
          resp <- patchDevice(fx, fx.admin, PlainMac, """{"shared":true}""")
          dev  <- device(PlainMac)
          hist <- history(PlainMac)
          snap <- snapshotDevice(PlainMac)
        } yield assertTrue(
          resp.status == Status.Ok,
          dev.shared,
          dev.profileId.isEmpty,
          hist == List(("assigned", fx.kid1.value, Some("made_shared"))),
          isCheckedOut(snap),
        )
      },
      test("{shared:true, profileId:P}: 409 device_shared, nothing changes") {
        for {
          fx   <- setup
          resp <- patchDevice(
            fx,
            fx.admin,
            PlainMac,
            s"""{"shared":true,"profileId":${fx.kid2.value}}""",
          )
          body <- errorOf(resp)
          dev  <- device(PlainMac)
        } yield assertTrue(
          resp.status == Status.Conflict,
          body.contains("device_shared"),
          !dev.shared,
          dev.profileId.contains(fx.kid1),
        )
      },
      test("{shared:false, profileId:P} closes the check-in (unshared) and assigns P") {
        for {
          fx   <- setup
          _    <- checkIn(fx, fx.child1, fx.kid1)
          resp <- patchDevice(
            fx,
            fx.admin,
            SharedMac,
            s"""{"shared":false,"profileId":${fx.kid2.value}}""",
          )
          dev  <- device(SharedMac)
          hist <- history(SharedMac)
        } yield assertTrue(
          resp.status == Status.Ok,
          !dev.shared,
          dev.profileId.contains(fx.kid2),
          hist == List(
            ("check_in", fx.kid1.value, Some("unshared")),
            ("assigned", fx.kid2.value, None),
          ),
        )
      },
      test("a non-null profileId on a shared device: 409 device_shared on PATCH") {
        for {
          fx   <- setup
          resp <- patchDevice(fx, fx.admin, SharedMac, s"""{"profileId":${fx.kid1.value}}""")
          body <- errorOf(resp)
          dev  <- device(SharedMac)
        } yield assertTrue(
          resp.status == Status.Conflict,
          body.contains("device_shared"),
          dev.profileId.isEmpty,
        )
      },
      test("a non-null profileId on a shared device: 409 device_shared on PUT") {
        for {
          fx   <- setup
          resp <- send(
            fx.devices,
            Request.put(
              url("/api/devices"),
              Body.fromString(
                s"""{"mac":"${SharedMac.value}","name":"family-mac","profileId":${fx.kid1.value}}""",
              ),
            ),
            fx.admin,
          )
          body <- errorOf(resp)
          dev  <- device(SharedMac)
        } yield assertTrue(
          resp.status == Status.Conflict,
          body.contains("device_shared"),
          dev.profileId.isEmpty,
        )
      },
      test("a rename of a checked-in shared device leaves the check-in alone") {
        for {
          fx   <- setup
          _    <- checkIn(fx, fx.child1, fx.kid1)
          resp <- patchDevice(fx, fx.admin, SharedMac, """{"name":"Family Mac"}""")
          dev  <- device(SharedMac)
          hist <- history(SharedMac)
        } yield assertTrue(
          resp.status == Status.Ok,
          dev.name == "Family Mac",
          dev.profileId.contains(fx.kid1),
          hist == List(("check_in", fx.kid1.value, None)),
        )
      },
      test("an adult not linked to the holder can still turn sharing off") {
        for {
          fx   <- setup
          _    <- checkIn(fx, fx.child1, fx.kid1)
          resp <- patchDevice(fx, fx.adult, SharedMac, """{"shared":false}""")
          dev  <- device(SharedMac)
        } yield assertTrue(resp.status == Status.Ok, !dev.shared, dev.profileId.isEmpty)
      },
      test("a child cannot change sharing: 403") {
        for {
          fx   <- setup
          resp <- patchDevice(fx, fx.child1, PlainMac, """{"shared":true}""")
          dev  <- device(PlainMac)
        } yield assertTrue(resp.status == Status.Forbidden, !dev.shared)
      },
    ),
  ) @@ TestAspect.sequential
}
