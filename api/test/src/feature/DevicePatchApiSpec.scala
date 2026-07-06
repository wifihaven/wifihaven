package wifihaven.api.feature

import wifihaven.api.JwtConfig
import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.routes.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.shared.Clock.TestClock
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.http.*
import zio.test.*

// #996: PATCH /api/devices/:mac — partial, field-scoped updates with
// symmetric read/write shapes (web client posts Partial<Device>).
object DevicePatchApiSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val jwtCfg   = JwtConfig(secret = "test-secret-at-least-32-chars!!x", expiryHours = 1)
  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwtCfg, clock)
  private val cleanDb  = TestDatabase.cleanAndMigrate

  private def url(p: String) = URL.decode(p).toOption.get

  private val Mac = MacAddress.unsafe("aa:bb:cc:dd:ee:01")

  private def setupDevice(initialName: String = "OldName", profileName: String = "Kids") =
    for {
      _           <- cleanDb
      profileRepo <- ZIO.service[ProfileRepo]
      deviceRepo  <- ZIO.service[DeviceRepo]
      profiles    <- profileRepo.listAll
      pid = profiles.find(_.name == profileName).get.id
      _ <- deviceRepo.upsert(Mac, initialName, Some(pid), "")
    } yield pid

  private def routesAndToken =
    for {
      auth            <- makeAuth
      deviceRepo      <- ZIO.service[DeviceRepo]
      profileRepo     <- ZIO.service[ProfileRepo]
      userProfileRepo <- ZIO.service[UserProfileRepo]
      token           <- auth.login("admin", "changeme").map(_.token.value)
    } yield (DeviceRoutes.routes(auth, deviceRepo, userProfileRepo, profileRepo), token)

  private def patch(routes: Routes[Any, Response], token: String, body: String) =
    routes.runZIO(
      Request
        .patch(url(s"/api/devices/${Mac.value}"), Body.fromString(body))
        .addHeader(Header.Authorization.Bearer(token))
        .addHeader(Header.ContentType(MediaType.application.json)),
    )

  def spec = suite("PATCH /api/devices/:mac (#996)")(
    test("single-field patch updates only that field") {
      for {
        pid          <- setupDevice()
        (routes, tk) <- routesAndToken
        resp         <- patch(routes, tk, """{"name":"NewName"}""")
        deviceRepo   <- ZIO.service[DeviceRepo]
        after        <- deviceRepo.findByMac(Mac)
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(after.exists(_.name == "NewName")) &&
        assertTrue(after.exists(_.profileId.contains(pid)))
    },
    test("multi-field patch updates all supplied fields") {
      for {
        _           <- setupDevice()
        profileRepo <- ZIO.service[ProfileRepo]
        profiles    <- profileRepo.listAll
        adultsId = profiles.find(_.name == "Adults").get.id
        (routes, tk) <- routesAndToken
        resp         <- patch(routes, tk, s"""{"name":"Tablet","profileId":${adultsId.value}}""")
        deviceRepo   <- ZIO.service[DeviceRepo]
        after        <- deviceRepo.findByMac(Mac)
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(after.exists(_.name == "Tablet")) &&
        assertTrue(after.exists(_.profileId.contains(adultsId)))
    },
    test("absent field preserves existing value") {
      for {
        pid          <- setupDevice(initialName = "Keep")
        (routes, tk) <- routesAndToken
        // Empty patch — change nothing.
        resp         <- patch(routes, tk, "{}")
        deviceRepo   <- ZIO.service[DeviceRepo]
        after        <- deviceRepo.findByMac(Mac)
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(after.exists(_.name == "Keep")) &&
        assertTrue(after.exists(_.profileId.contains(pid)))
    },
    test("null on nullable profileId clears the assignment") {
      for {
        _            <- setupDevice()
        (routes, tk) <- routesAndToken
        resp         <- patch(routes, tk, """{"profileId":null}""")
        deviceRepo   <- ZIO.service[DeviceRepo]
        after        <- deviceRepo.findByMac(Mac)
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(after.exists(_.profileId.isEmpty))
    },
    test("null on non-nullable name returns 400") {
      for {
        _            <- setupDevice()
        (routes, tk) <- routesAndToken
        resp         <- patch(routes, tk, """{"name":null}""")
      } yield assertTrue(resp.status == Status.BadRequest)
    },
    test("404 when device is missing") {
      for {
        _            <- cleanDb
        (routes, tk) <- routesAndToken
        resp         <- patch(routes, tk, """{"name":"X"}""")
      } yield assertTrue(resp.status == Status.NotFound)
    },
    test("401 without token") {
      for {
        _      <- setupDevice()
        routes <- routesAndToken.map(_._1)
        resp   <- routes.runZIO(
          Request
            .patch(url(s"/api/devices/${Mac.value}"), Body.fromString("""{"name":"X"}"""))
            .addHeader(Header.ContentType(MediaType.application.json)),
        )
      } yield assertTrue(resp.status == Status.Unauthorized)
    },
    test("403 when adult tries to move device to a profile they don't have access to") {
      for {
        _           <- setupDevice()
        profileRepo <- ZIO.service[ProfileRepo]
        userRepo    <- ZIO.service[UserRepo]
        upRepo      <- ZIO.service[UserProfileRepo]
        deviceRepo  <- ZIO.service[DeviceRepo]
        auth        <- makeAuth
        profiles    <- profileRepo.listAll
        kidsId   = profiles.find(_.name == "Kids").get.id
        adultsId = profiles.find(_.name == "Adults").get.id
        hash  <- auth.hashPassword("pass")
        momId <- userRepo.create("mom", hash, "adult")
        _     <- userRepo.clearMustChangePassword(momId)
        _     <- upRepo.setProfilesForUser(momId, List(kidsId))
        token <- auth.login("mom", "pass").map(_.token.value)
        routes = DeviceRoutes.routes(auth, deviceRepo, upRepo, profileRepo)
        resp  <- routes.runZIO(
          Request
            .patch(
              url(s"/api/devices/${Mac.value}"),
              Body.fromString(s"""{"profileId":${adultsId.value}}"""),
            )
            .addHeader(Header.Authorization.Bearer(token))
            .addHeader(Header.ContentType(MediaType.application.json)),
        )
        after <- deviceRepo.findByMac(Mac)
      } yield assertTrue(resp.status == Status.Forbidden) &&
        assertTrue(after.exists(_.profileId.contains(kidsId)))
    },
  ) @@ TestAspect.sequential
}
