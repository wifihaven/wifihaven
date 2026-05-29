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

import java.time.{Instant, LocalDate}

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
          profileId = Some(kidsId),
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
        body   = UpsertDeviceRequest(
          MacAddress.unsafe("aa:bb:cc:dd:ee:ff"),
          "Laptop",
          Some(kidsId),
        ).toJson
        req    = Request
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
        _ <- deviceRepo.upsert(MacAddress.unsafe(mac), "OldDevice", Some(kidsId), "192.168.1.50")
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
    test("#1154 delete device with dependent alert / usage / connection-event rows") {
      // Operator-reported: 'Remove device' failed for real devices. Real
      // devices accumulate dependent rows (a new-device alert, per-host usage,
      // connection events). Deleting one must succeed and remove the row.
      for {
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        deviceRepo  <- ZIO.service[DeviceRepo]
        alertRepo   <- ZIO.service[AlertRepo]
        usageRepo   <- ZIO.service[TimeUsageRepo]
        connRepo    <- ZIO.service[ConnectionEventRepo]
        routerRepo  <- ZIO.service[RouterRepo]
        auth        <- makeAuth
        token       <- auth.login("admin", "changeme").map(_.token)
        profiles    <- profileRepo.listAll
        kidsId = profiles.find(_.name == "Kids").get.id
        mac    = MacAddress.unsafe("aa:bb:cc:11:22:33")
        host   = HostId.Fqdn(Hostname.unsafe("youtube.com"))
        _               <- deviceRepo.upsert(mac, "Kid iPad", Some(kidsId), "192.168.1.77")
        _               <- alertRepo.raiseNewDevice(mac, Instant.parse("2026-01-01T00:00:00Z"))
        _               <- usageRepo.incrementSecondsAndBytes(
          mac,
          host,
          LocalDate.of(2026, 1, 1),
          60L,
          100L,
          200L,
        )
        routerId        <- routerRepo.create("r1", Sha256Hex.unsafe("m" * 64))
        _               <- connRepo.insertBatch(
          List(
            ConnectionEventInsert(
              routerId,
              Some(mac),
              host,
              Some(IpAddress.unsafe("1.2.3.4")),
              allowed = true,
              BlockReason.Allow,
              Instant.parse("2026-01-01T00:00:00Z"),
            ),
          ),
        )
        userProfileRepo <- ZIO.service[UserProfileRepo]
        routes  = DeviceRoutes.routes(auth, deviceRepo, userProfileRepo)
        // The SPA builds the path with encodeURIComponent(mac), so the colons
        // arrive percent-encoded (de%3Aad%3A…). The route must decode them.
        encPath = s"/api/devices/${java.net.URLEncoder.encode(mac.value, "UTF-8")}"
        delReq  = Request
          .delete(URL.decode(encPath).toOption.get)
          .addHeader(Header.Authorization.Bearer(token.value))
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
        _      <- deviceRepo.upsert(MacAddress.unsafe(mac), "Laptop", Some(kidsId), "192.168.1.5")
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
        _      <- deviceRepo.upsert(MacAddress.unsafe(mac), "Phone", Some(kidsId), "")
        _      <- deviceRepo.updateLastSeen(MacAddress.unsafe(mac), "192.168.1.10")
        // Re-assign to Adults and rename — last_seen_ip must survive unchanged.
        _      <- deviceRepo.upsert(MacAddress.unsafe(mac), "Tablet", Some(adultsId), "")
        device <- deviceRepo.findByMac(MacAddress.unsafe(mac))
      } yield assertTrue(device.exists(_.profileId.contains(adultsId))) &&
        assertTrue(device.exists(_.name == "Tablet")) &&
        assertTrue(device.exists(_.lastSeenIp.contains(IpAddress.unsafe("192.168.1.10"))))
    },
    test("#708 insert with profileId=None creates an unassigned device") {
      for {
        _               <- cleanDb
        deviceRepo      <- ZIO.service[DeviceRepo]
        auth            <- makeAuth
        token           <- auth.login("admin", "changeme").map(_.token)
        userProfileRepo <- ZIO.service[UserProfileRepo]
        routes = DeviceRoutes.routes(auth, deviceRepo, userProfileRepo)
        body   = UpsertDeviceRequest(
          mac = MacAddress.unsafe("aa:bb:cc:00:00:10"),
          name = "Lutron Bridge",
          profileId = None,
        ).toJson
        putReq = Request
          .put(URL.decode("/api/devices").toOption.get, Body.fromString(body))
          .addHeader(Header.Authorization.Bearer(token.value))
          .addHeader(Header.ContentType(MediaType.application.json))
        resp   <- routes.runZIO(putReq)
        device <- deviceRepo.findByMac(MacAddress.unsafe("aa:bb:cc:00:00:10"))
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(device.exists(_.name == "Lutron Bridge")) &&
        assertTrue(device.exists(_.profileId.isEmpty))
    },
    test("#708 update with profileId=None clears the profile assignment") {
      for {
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        deviceRepo  <- ZIO.service[DeviceRepo]
        auth        <- makeAuth
        token       <- auth.login("admin", "changeme").map(_.token)
        profiles    <- profileRepo.listAll
        kidsId = profiles.find(_.name == "Kids").get.id
        mac    = "aa:bb:cc:00:00:20"
        _               <- deviceRepo.upsert(MacAddress.unsafe(mac), "OldName", Some(kidsId), "")
        userProfileRepo <- ZIO.service[UserProfileRepo]
        routes = DeviceRoutes.routes(auth, deviceRepo, userProfileRepo)
        body   = UpsertDeviceRequest(MacAddress.unsafe(mac), "NewName", None).toJson
        putReq = Request
          .put(URL.decode("/api/devices").toOption.get, Body.fromString(body))
          .addHeader(Header.Authorization.Bearer(token.value))
          .addHeader(Header.ContentType(MediaType.application.json))
        resp   <- routes.runZIO(putReq)
        device <- deviceRepo.findByMac(MacAddress.unsafe(mac))
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(device.exists(_.name == "NewName")) &&
        assertTrue(device.exists(_.profileId.isEmpty))
    },
    test("#708 update with profileId=Some re-assigns profile and changes name") {
      for {
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        deviceRepo  <- ZIO.service[DeviceRepo]
        auth        <- makeAuth
        token       <- auth.login("admin", "changeme").map(_.token)
        profiles    <- profileRepo.listAll
        kidsId   = profiles.find(_.name == "Kids").get.id
        adultsId = profiles.find(_.name == "Adults").get.id
        mac      = "aa:bb:cc:00:00:21"
        _               <- deviceRepo.upsert(MacAddress.unsafe(mac), "OldName", Some(kidsId), "")
        userProfileRepo <- ZIO.service[UserProfileRepo]
        routes = DeviceRoutes.routes(auth, deviceRepo, userProfileRepo)
        body   = UpsertDeviceRequest(MacAddress.unsafe(mac), "NewName", Some(adultsId)).toJson
        putReq = Request
          .put(URL.decode("/api/devices").toOption.get, Body.fromString(body))
          .addHeader(Header.Authorization.Bearer(token.value))
          .addHeader(Header.ContentType(MediaType.application.json))
        resp   <- routes.runZIO(putReq)
        device <- deviceRepo.findByMac(MacAddress.unsafe(mac))
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(device.exists(_.name == "NewName")) &&
        assertTrue(device.exists(_.profileId.contains(adultsId)))
    },
    test(
      "#708 non-admin rename without profileId succeeds; supplying inaccessible profileId still 403s",
    ) {
      // The PUT /api/devices auth check is requireWriter (adult/admin role), and on
      // top of that requireProfileAccess only fires when a profileId is supplied.
      // So an adult can rename without supplying a profile (the device ends up
      // unassigned), but cannot move it to Adults if they aren't linked to Adults.
      for {
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        deviceRepo  <- ZIO.service[DeviceRepo]
        userRepo    <- ZIO.service[UserRepo]
        upRepo      <- ZIO.service[UserProfileRepo]
        auth        <- makeAuth
        profiles    <- profileRepo.listAll
        kidsId   = profiles.find(_.name == "Kids").get.id
        adultsId = profiles.find(_.name == "Adults").get.id
        mac      = "aa:bb:cc:00:00:22"
        _     <- deviceRepo.upsert(MacAddress.unsafe(mac), "OldName", Some(kidsId), "")
        // mom is an adult linked only to Kids — not Adults.
        hash  <- auth.hashPassword("pass")
        momId <- userRepo.create("mom", hash, "adult")
        _     <- userRepo.clearMustChangePassword(momId)
        _     <- upRepo.setProfilesForUser(momId, List(kidsId))
        token <- auth.login("mom", "pass").map(_.token.value)
        routes     = DeviceRoutes.routes(auth, deviceRepo, upRepo)
        // Rename only — no profileId supplied — should succeed.
        renameBody = UpsertDeviceRequest(MacAddress.unsafe(mac), "RenamedByMom", None).toJson
        renameReq  = Request
          .put(URL.decode("/api/devices").toOption.get, Body.fromString(renameBody))
          .addHeader(Header.Authorization.Bearer(token))
          .addHeader(Header.ContentType(MediaType.application.json))
        renameResp  <- routes.runZIO(renameReq)
        afterRename <- deviceRepo.findByMac(MacAddress.unsafe(mac))
        // Now try to move to Adults (mom isn't linked) — should 403.
        moveBody = UpsertDeviceRequest(MacAddress.unsafe(mac), "Moved", Some(adultsId)).toJson
        moveReq  = Request
          .put(URL.decode("/api/devices").toOption.get, Body.fromString(moveBody))
          .addHeader(Header.Authorization.Bearer(token))
          .addHeader(Header.ContentType(MediaType.application.json))
        moveResp  <- routes.runZIO(moveReq)
        afterMove <- deviceRepo.findByMac(MacAddress.unsafe(mac))
      } yield assertTrue(renameResp.status == Status.Ok) &&
        assertTrue(afterRename.exists(_.name == "RenamedByMom")) &&
        assertTrue(afterRename.exists(_.profileId.isEmpty)) &&
        assertTrue(moveResp.status == Status.Forbidden) &&
        assertTrue(afterMove.exists(_.profileId.isEmpty))
    },
  ) @@ TestAspect.sequential
}
