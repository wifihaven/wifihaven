package familydns.api.feature

import familydns.api.JwtConfig
import familydns.api.auth.*
import familydns.api.db.*
import familydns.api.routes.*
import familydns.shared.*
import familydns.shared.Clock.TestClock
import familydns.testinfra.*
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
          mac = "aa:bb:cc:dd:ee:ff",
          name = "iPad",
          profileId = kidsId,
        ).toJson
        putReq = Request
          .put(URL.decode("/api/devices").toOption.get, Body.fromString(body))
          .addHeader(Header.Authorization.Bearer(token))
          .addHeader(Header.ContentType(MediaType.application.json))
        putResp <- routes.runZIO(putReq)
        getReq = Request
          .get(URL.decode("/api/devices").toOption.get)
          .addHeader(Header.Authorization.Bearer(token))
        getResp <- routes.runZIO(getReq)
        body2   <- getResp.body.asString
        devices <- ZIO.fromEither(body2.fromJson[List[Device]])
      } yield assertTrue(putResp.status == Status.Ok) &&
        assertTrue(devices.exists(_.mac == "aa:bb:cc:dd:ee:ff")) &&
        assertTrue(devices.exists(_.name == "iPad")) &&
        assertTrue(
          devices.find(_.mac == "aa:bb:cc:dd:ee:ff").exists(_.profileName.contains("Kids")),
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
        body   = UpsertDeviceRequest("AA-BB-CC-DD-EE-FF", "Laptop", kidsId).toJson
        req    = Request
          .put(URL.decode("/api/devices").toOption.get, Body.fromString(body))
          .addHeader(Header.Authorization.Bearer(token))
          .addHeader(Header.ContentType(MediaType.application.json))
        _      <- routes.runZIO(req)
        device <- deviceRepo.findByMac("aa:bb:cc:dd:ee:ff")
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
        _               <- deviceRepo.upsert(mac, "OldDevice", kidsId, "192.168.1.50")
        userProfileRepo <- ZIO.service[UserProfileRepo]
        routes = DeviceRoutes.routes(auth, deviceRepo, userProfileRepo)
        delReq = Request
          .delete(URL.decode(s"/api/devices/$mac").toOption.get)
          .addHeader(Header.Authorization.Bearer(token))
        delResp <- routes.runZIO(delReq)
        after   <- deviceRepo.findByMac(mac)
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
        _      <- deviceRepo.upsert(mac, "Laptop", kidsId, "192.168.1.5")
        _      <- deviceRepo.updateLastSeen(mac, "192.168.1.99")
        device <- deviceRepo.findByMac(mac)
      } yield assertTrue(device.exists(_.lastSeenIp.contains("192.168.1.99"))) &&
        assertTrue(device.exists(_.profileId.contains(kidsId)))
    },
    test("upsert updates last_seen_ip without losing profile assignment") {
      for {
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        deviceRepo  <- ZIO.service[DeviceRepo]
        auth        <- makeAuth
        token       <- auth.login("admin", "changeme").map(_.token)
        profiles    <- profileRepo.listAll
        kidsId = profiles.find(_.name == "Kids").get.id
        mac    = "aa:bb:cc:00:00:01"
        _      <- deviceRepo.upsert(mac, "Phone", kidsId, "192.168.1.10")
        // Upsert again with different IP (simulating DHCP lease change)
        _      <- deviceRepo.upsert(mac, "Phone", kidsId, "192.168.1.20")
        device <- deviceRepo.findByMac(mac)
      } yield assertTrue(device.exists(_.lastSeenIp.contains("192.168.1.20"))) &&
        assertTrue(device.exists(_.profileId.contains(kidsId)))
    },
  ) @@ TestAspect.sequential
}
