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
import zio.json.*
import zio.test.*
import zio.test.Assertion.*

object DeviceApiSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val adminJwt = JwtConfig(secret = "test-secret-at-least-32-chars!!", expiryHours = 1)
  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, adminJwt, clock)
  private def cleanDb  = ZIO.serviceWithZIO[EmbeddedPostgres](pg =>
    TestDatabase.cleanAndMigrate.provide(ZLayer.succeed(pg)),
  )

  def spec = suite("Device API")(
    test("create and list devices") {
      for {
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        deviceRepo  <- ZIO.service[DeviceRepo]
        auth        <- makeAuth
        token       <- auth.login("admin", "changeme").map(_.token)
        profiles    <- profileRepo.listAll
        kidsId = profiles.find(_.name == "Kids").get.id
        userProfileRepo <- ZIO.service[UserProfileRepo]
        routes = DeviceRoutes.routes(auth, deviceRepo, userProfileRepo)
        body   = UpsertDeviceRequest(
          mac = MacAddress.unsafe("aa:bb:cc:dd:ee:ff"),
          name = "iPad",
          profileId = kidsId,
        ).toJson
        putReq = Request
          .put(URL.decode("/api/devices").toOption.get, Body.fromString(body))
          .addHeader(Header.Authorization.Bearer(token.value))
          .addHeader(Header.ContentType(MediaType.application.json))
        putResp <- routes.runZIO(putReq)
        getReq = Request
          .get(URL.decode("/api/devices").toOption.get)
          .addHeader(Header.Authorization.Bearer(token.value))
        getResp <- routes.runZIO(getReq)
        body2   <- getResp.body.asString
        devices <- ZIO.fromEither(body2.fromJson[List[Device]])
      } yield assertTrue(putResp.status == Status.Ok) &&
        assertTrue(devices.exists(_.mac == MacAddress.unsafe("aa:bb:cc:dd:ee:ff"))) &&
        assertTrue(devices.exists(_.name == "iPad")) &&
        assertTrue(
          devices
            .find(_.mac == MacAddress.unsafe("aa:bb:cc:dd:ee:ff"))
            .exists(_.profileName.contains("Kids")),
        ) &&
        assertTrue(!body2.contains("\"location\""))
    },
    test("MAC address is normalised (upper → lower, dashes → colons)") {
      for {
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        deviceRepo  <- ZIO.service[DeviceRepo]
        auth        <- makeAuth
        token       <- auth.login("admin", "changeme").map(_.token)
        profiles    <- profileRepo.listAll
        kidsId = profiles.find(_.name == "Kids").get.id
        userProfileRepo <- ZIO.service[UserProfileRepo]
        routes = DeviceRoutes.routes(auth, deviceRepo, userProfileRepo)
        body = UpsertDeviceRequest(MacAddress.unsafe("aa:bb:cc:dd:ee:ff"), "Laptop", kidsId).toJson
        req  = Request
          .put(URL.decode("/api/devices").toOption.get, Body.fromString(body))
          .addHeader(Header.Authorization.Bearer(token.value))
          .addHeader(Header.ContentType(MediaType.application.json))
        _      <- routes.runZIO(req)
        device <- deviceRepo.findByMac(MacAddress.unsafe("aa:bb:cc:dd:ee:ff"))
      } yield assertTrue(device.isDefined)
    },
    test("delete device") {
      for {
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        deviceRepo  <- ZIO.service[DeviceRepo]
        auth        <- makeAuth
        token       <- auth.login("admin", "changeme").map(_.token)
        profiles    <- profileRepo.listAll
        kidsId = profiles.find(_.name == "Kids").get.id
        mac    = "11:22:33:44:55:66"
        _ <- deviceRepo.upsert(MacAddress.unsafe(mac), "OldDevice", kidsId, "192.168.1.50")
        userProfileRepo <- ZIO.service[UserProfileRepo]
        routes = DeviceRoutes.routes(auth, deviceRepo, userProfileRepo)
        delReq = Request
          .delete(URL.decode(s"/api/devices/$mac").toOption.get)
          .addHeader(Header.Authorization.Bearer(token.value))
        delResp <- routes.runZIO(delReq)
        after   <- deviceRepo.findByMac(MacAddress.unsafe(mac))
      } yield assertTrue(delResp.status == Status.Ok) &&
        assertTrue(after.isEmpty)
    },
    test("updateLastSeen updates ip without losing profile assignment") {
      for {
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        deviceRepo  <- ZIO.service[DeviceRepo]
        profiles    <- profileRepo.listAll
        kidsId = profiles.find(_.name == "Kids").get.id
        mac    = "cc:dd:ee:ff:00:11"
        _      <- deviceRepo.upsert(MacAddress.unsafe(mac), "Laptop", kidsId, "192.168.1.5")
        _      <- deviceRepo.updateLastSeen(MacAddress.unsafe(mac), "192.168.1.99")
        device <- deviceRepo.findByMac(MacAddress.unsafe(mac))
      } yield assertTrue(device.exists(_.lastSeenIp.contains(IpAddress.unsafe("192.168.1.99")))) &&
        assertTrue(device.exists(_.profileId.contains(kidsId)))
    },
    test("upsert re-assigns device to a different profile and updates name") {
      // The admin PUT /api/devices owns name + profile_id.
      // last_seen_ip is router-owned and must not be overwritten by the admin upsert.
      for {
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        deviceRepo  <- ZIO.service[DeviceRepo]
        profiles    <- profileRepo.listAll
        kidsId   = profiles.find(_.name == "Kids").get.id
        adultsId = profiles.find(_.name == "Adults").get.id
        mac      = "aa:bb:cc:00:00:01"
        // First insert: assigned to Kids, router later sets last_seen_ip.
        _      <- deviceRepo.upsert(MacAddress.unsafe(mac), "Phone", kidsId, "")
        _      <- deviceRepo.updateLastSeen(MacAddress.unsafe(mac), "192.168.1.10")
        // Re-assign to Adults and rename — last_seen_ip must survive unchanged.
        _      <- deviceRepo.upsert(MacAddress.unsafe(mac), "Tablet", adultsId, "")
        device <- deviceRepo.findByMac(MacAddress.unsafe(mac))
      } yield assertTrue(device.exists(_.profileId.contains(adultsId))) &&
        assertTrue(device.exists(_.name == "Tablet")) &&
        assertTrue(device.exists(_.lastSeenIp.contains(IpAddress.unsafe("192.168.1.10"))))
    },
  ) @@ TestAspect.sequential
}
