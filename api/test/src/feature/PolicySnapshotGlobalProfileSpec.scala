package wifihaven.api.feature

import wifihaven.api.JwtConfig
import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.policy.*
import wifihaven.api.routes.*
import wifihaven.shared.*
import wifihaven.shared.Clock.TestClock
import wifihaven.shared.types.*
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*
import zio.test.*

/**
 * #1771 — the global sentinel profile collapses the deleted `global_*` tables onto a single row in
 * `profiles` marked `is_global = TRUE`. `PolicyService.snapshot` unions the sentinel's resolved
 * `extraAllowed` / `extraBlocked` / `blocklistIds` into every other profile's `BlockRules`, the
 * sentinel is hidden from `GET /api/profiles`, devices cannot reference it, and writes that are
 * meaningless household-wide (schedules / time limits / paused / pauseMode / delete) are rejected
 * with 400 at the route layer.
 */
object PolicySnapshotGlobalProfileSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb  = TestDatabase.cleanAndMigrate
  private val adminJwt = JwtConfig(secret = "test-secret-at-least-32-chars!!x", expiryHours = 1)

  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, adminJwt, clock)

  private def makePs =
    for {
      pr     <- ZIO.service[ProfileRepo]
      hsr    <- ZIO.service[HouseholdSettingsRepo]
      tlr    <- ZIO.service[TimeLimitRepo]
      atlr   <- ZIO.service[AppTimeLimitRepo]
      dr     <- ZIO.service[DeviceRepo]
      blr    <- ZIO.service[BlocklistRepo]
      trRepo <- ZIO.service[TrafficReportRepo]
      er     <- ZIO.service[TimeExtensionRepo]
      ar     <- ZIO.service[AppRepo]
      clock  <- ZIO.service[Clock]
    } yield PolicyServiceLive(pr, hsr, tlr, atlr, dr, blr, trRepo, er, ar, clock): PolicyService

  private def kidsId: ZIO[ProfileRepo, Throwable, ProfileId] =
    ZIO.serviceWithZIO[ProfileRepo](_.listAll.map(_.find(_.name == "Kids").get.id))

  private def adultsId: ZIO[ProfileRepo, Throwable, ProfileId] =
    ZIO.serviceWithZIO[ProfileRepo](_.listAll.map(_.find(_.name == "Adults").get.id))

  private def globalId: ZIO[ProfileRepo, Throwable, ProfileId] =
    ZIO.serviceWithZIO[ProfileRepo](_.getGlobal.map(_.get.id))

  def spec = suite("PolicySnapshot — global sentinel profile (#1771)")(
    test("global profile's extraAllowed is unioned into every other profile's extraAllowed") {
      for {
        _      <- cleanDb
        ar     <- ZIO.service[AppRepo]
        kids   <- kidsId
        adults <- adultsId
        g      <- globalId
        _      <- TestLayers.seedAppAssignment(ar, g, "global-allow.example", AppMode.Allowed)
        _      <- TestLayers.seedAppAssignment(ar, kids, "kids-allow.example", AppMode.Allowed)
        svc    <- makePs
        snap   <- svc.snapshot
      } yield {
        val kidsEa   = snap.profiles(kids).rules.extraAllowed.map(_.value).toSet
        val adultsEa = snap.profiles(adults).rules.extraAllowed.map(_.value).toSet
        assertTrue(kidsEa.contains("global-allow.example")) &&
        assertTrue(kidsEa.contains("kids-allow.example")) &&
        assertTrue(adultsEa.contains("global-allow.example"))
      }
    },
    test("global profile's extraBlocked is unioned into every other profile's extraBlocked") {
      for {
        _    <- cleanDb
        ar   <- ZIO.service[AppRepo]
        kids <- kidsId
        g    <- globalId
        _    <- TestLayers.seedAppAssignment(ar, g, "global-block.example", AppMode.Blocked)
        svc  <- makePs
        snap <- svc.snapshot
      } yield {
        val kidsEb = snap.profiles(kids).rules.extraBlocked.map(_.value).toSet
        assertTrue(kidsEb.contains("global-block.example"))
      }
    },
    test("global profile's blockedCategories union into every other profile's blocklistIds") {
      for {
        _    <- cleanDb
        pr   <- ZIO.service[ProfileRepo]
        kids <- kidsId
        g    <- globalId
        _    <- pr.setBlockedCategories(g, List(BlocklistId.unsafe("malware")))
        svc  <- makePs
        snap <- svc.snapshot
      } yield {
        val ids = snap.profiles(kids).rules.blocklistIds.map(_.value).toSet
        assertTrue(ids.contains("malware"))
      }
    },
    test("profile with empty own rules still receives the global union") {
      for {
        _      <- cleanDb
        ar     <- ZIO.service[AppRepo]
        adults <- adultsId
        g      <- globalId
        _      <- TestLayers.seedAppAssignment(ar, g, "fleet.example", AppMode.Allowed)
        svc    <- makePs
        snap   <- svc.snapshot
      } yield {
        val ea = snap.profiles(adults).rules.extraAllowed.map(_.value).toSet
        assertTrue(ea.contains("fleet.example"))
      }
    },
    test("snapshot.global.extraAllowed includes global-profile rules + uiAllowedHosts + infra") {
      for {
        _    <- cleanDb
        ar   <- ZIO.service[AppRepo]
        g    <- globalId
        _    <- TestLayers.seedAppAssignment(ar, g, "sentinel.example", AppMode.Allowed)
        pr   <- ZIO.service[ProfileRepo]
        hsr  <- ZIO.service[HouseholdSettingsRepo]
        tlr  <- ZIO.service[TimeLimitRepo]
        atlr <- ZIO.service[AppTimeLimitRepo]
        dr   <- ZIO.service[DeviceRepo]
        blr  <- ZIO.service[BlocklistRepo]
        trr  <- ZIO.service[TrafficReportRepo]
        er   <- ZIO.service[TimeExtensionRepo]
        clk  <- ZIO.service[Clock]
        svc = PolicyServiceLive(
          pr,
          hsr,
          tlr,
          atlr,
          dr,
          blr,
          trr,
          er,
          ar,
          clk,
          uiAllowedHosts = List(Hostname.unsafe("ui.example")),
        ): PolicyService
        snap <- svc.snapshot
      } yield {
        val ea = snap.global.extraAllowed.map(_.value).toSet
        assertTrue(ea.contains("sentinel.example")) &&
        assertTrue(ea.contains("ui.example")) &&
        assertTrue(ea.intersect(PolicyService.infraAllowHosts.map(_.value).toSet).nonEmpty)
      }
    },
    test("GET /api/profiles hides the global sentinel (RoleAccessSpec invariant)") {
      for {
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        tlRepo      <- ZIO.service[TimeLimitRepo]
        upRepo      <- ZIO.service[UserProfileRepo]
        userRepoSvc <- ZIO.service[UserRepo]
        auth        <- makeAuth
        token       <- auth.login("admin", "changeme").map(_.token.value)
        routes = ProfileRoutes.routes(auth, profileRepo, tlRepo, upRepo, userRepoSvc)
        req    = Request
          .get(URL.decode("/api/profiles").toOption.get)
          .addHeader(Header.Authorization.Bearer(token))
        resp <- routes.runZIO(req)
        body <- resp.body.asString
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(!body.contains("\"name\":\"Global\""))
    },
    test("PUT /api/devices to global profile returns 400") {
      for {
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        deviceRepo  <- ZIO.service[DeviceRepo]
        upRepo      <- ZIO.service[UserProfileRepo]
        auth        <- makeAuth
        token       <- auth.login("admin", "changeme").map(_.token.value)
        g           <- globalId
        routes = DeviceRoutes.routes(auth, deviceRepo, upRepo, profileRepo)
        body   = UpsertDeviceRequest(MacAddress.unsafe("aa:bb:cc:dd:ee:01"), "x", Some(g)).toJson
        req    = Request
          .put(URL.decode("/api/devices").toOption.get, Body.fromString(body))
          .addHeader(Header.Authorization.Bearer(token))
        resp <- routes.runZIO(req)
      } yield assertTrue(resp.status == Status.BadRequest)
    },
    test("PUT /api/profiles/:id/schedules on global returns 400") {
      for {
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        tlRepo      <- ZIO.service[TimeLimitRepo]
        upRepo      <- ZIO.service[UserProfileRepo]
        userRepoSvc <- ZIO.service[UserRepo]
        nsr         <- ZIO.service[NamedScheduleRepo]
        auth        <- makeAuth
        token       <- auth.login("admin", "changeme").map(_.token.value)
        g           <- globalId
        routes = ProfileRoutes.routes(auth, profileRepo, tlRepo, upRepo, userRepoSvc, nsr)
        body   = SetProfileSchedulesRequest(Nil).toJson
        req    = Request
          .put(
            URL.decode(s"/api/profiles/${g.value}/schedules").toOption.get,
            Body.fromString(body),
          )
          .addHeader(Header.Authorization.Bearer(token))
        resp <- routes.runZIO(req)
      } yield assertTrue(resp.status == Status.BadRequest)
    },
    test("PATCH /api/profiles/:id timeLimit on global returns 400") {
      for {
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        tlRepo      <- ZIO.service[TimeLimitRepo]
        upRepo      <- ZIO.service[UserProfileRepo]
        userRepoSvc <- ZIO.service[UserRepo]
        auth        <- makeAuth
        token       <- auth.login("admin", "changeme").map(_.token.value)
        g           <- globalId
        routes = ProfileRoutes.routes(auth, profileRepo, tlRepo, upRepo, userRepoSvc)
        body   = """{"timeLimit": 60}"""
        req    = Request
          .patch(URL.decode(s"/api/profiles/${g.value}").toOption.get, Body.fromString(body))
          .addHeader(Header.Authorization.Bearer(token))
        resp <- routes.runZIO(req)
      } yield assertTrue(resp.status == Status.BadRequest)
    },
    test("PATCH /api/profiles/:id paused on global returns 400") {
      for {
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        tlRepo      <- ZIO.service[TimeLimitRepo]
        upRepo      <- ZIO.service[UserProfileRepo]
        userRepoSvc <- ZIO.service[UserRepo]
        auth        <- makeAuth
        token       <- auth.login("admin", "changeme").map(_.token.value)
        g           <- globalId
        routes = ProfileRoutes.routes(auth, profileRepo, tlRepo, upRepo, userRepoSvc)
        body   = """{"paused": true}"""
        req    = Request
          .patch(URL.decode(s"/api/profiles/${g.value}").toOption.get, Body.fromString(body))
          .addHeader(Header.Authorization.Bearer(token))
        resp <- routes.runZIO(req)
      } yield assertTrue(resp.status == Status.BadRequest)
    },
  ) @@ TestAspect.sequential
}
