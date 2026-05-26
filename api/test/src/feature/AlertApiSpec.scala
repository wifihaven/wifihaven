package wifihaven.api.feature

import wifihaven.api.JwtConfig
import wifihaven.api.auth.*
import wifihaven.api.db.*
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

import java.time.Instant

/**
 * Replaces the old DeviceAlertApiSpec — same coverage, against the unified `/api/alerts` endpoints.
 * The schema (V33) supports a second `access_request` kind too, but that's #960's territory; this
 * spec only exercises the new_device path.
 */
object AlertApiSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val jwtCfg = JwtConfig(secret = "test-secret-at-least-32-chars!!", expiryHours = 1)

  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwtCfg, clock)

  private def cleanDb = ZIO.serviceWithZIO[EmbeddedPostgres](pg =>
    TestDatabase.cleanAndMigrate.provide(ZLayer.succeed(pg)),
  )

  private val mac1 = MacAddress.unsafe("aa:bb:cc:11:22:33")
  private val mac2 = MacAddress.unsafe("aa:bb:cc:44:55:66")
  private val now  = Instant.parse("2026-05-07T14:00:00Z")

  private def seedTwoNewDeviceAlerts(dRepo: DeviceRepo, aRepo: AlertRepo): Task[Unit] =
    for {
      _ <- dRepo.upsertUnknown(mac1, "device-112233", None, now)
      _ <- dRepo.upsertUnknown(mac2, "device-445566", None, now)
      _ <- aRepo.raiseNewDevice(mac1, now)
      _ <- aRepo.raiseNewDevice(mac2, now)
    } yield ()

  private def getAlerts(routes: Routes[Any, Response], token: JwtToken, path: String) =
    routes.runZIO(
      Request
        .get(URL.decode(path).toOption.get)
        .addHeader(Header.Authorization.Bearer(token.value)),
    )

  private def postAlertAction(
      routes: Routes[Any, Response],
      path: String,
      token: JwtToken,
  ) =
    routes.runZIO(
      Request
        .post(URL.decode(path).toOption.get, Body.fromString(""))
        .addHeader(Header.Authorization.Bearer(token.value)),
    )

  def spec = suite("Alerts API (new_device kind, formerly /api/device-alerts)")(
    test("GET /api/alerts returns only pending rows by default") {
      for {
        _     <- cleanDb
        dRepo <- ZIO.service[DeviceRepo]
        aRepo <- ZIO.service[AlertRepo]
        auth  <- makeAuth
        clock <- ZIO.service[Clock]
        _     <- seedTwoNewDeviceAlerts(dRepo, aRepo)
        all   <- aRepo.list(includeAll = false)
        _     <- aRepo.decide(all.head.id, AlertStatus.Approved, now, "admin", None)
        token <- auth.login("admin", "changeme").map(_.token)
        routes = AlertRoutes.routes(auth, aRepo, clock)
        resp   <- getAlerts(routes, token, "/api/alerts")
        body   <- resp.body.asString
        alerts <- ZIO.fromEither(body.fromJson[List[Alert]])
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(alerts.size == 1) &&
        assertTrue(alerts.forall(_.status == AlertStatus.Pending))
    },
    test("GET /api/alerts?all=true returns decided rows too") {
      for {
        _     <- cleanDb
        dRepo <- ZIO.service[DeviceRepo]
        aRepo <- ZIO.service[AlertRepo]
        auth  <- makeAuth
        clock <- ZIO.service[Clock]
        _     <- seedTwoNewDeviceAlerts(dRepo, aRepo)
        all   <- aRepo.list(includeAll = false)
        _     <- aRepo.decide(all.head.id, AlertStatus.Approved, now, "admin", None)
        token <- auth.login("admin", "changeme").map(_.token)
        routes = AlertRoutes.routes(auth, aRepo, clock)
        resp   <- getAlerts(routes, token, "/api/alerts?all=true")
        body   <- resp.body.asString
        alerts <- ZIO.fromEither(body.fromJson[List[Alert]])
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(alerts.size == 2)
    },
    test("raiseNewDevice is idempotent on (mac, kind=new_device)") {
      for {
        _     <- cleanDb
        dRepo <- ZIO.service[DeviceRepo]
        aRepo <- ZIO.service[AlertRepo]
        _     <- dRepo.upsertUnknown(mac1, "device-112233", None, now)
        _     <- aRepo.raiseNewDevice(mac1, now)
        _     <- aRepo.raiseNewDevice(mac1, now.plusSeconds(60))
        xs    <- aRepo.list(includeAll = true)
      } yield assertTrue(xs.size == 1) &&
        assertTrue(xs.head.kind == AlertKind.NewDevice) &&
        assertTrue(xs.head.mac == mac1)
    },
    test("POST /api/alerts/{id}/approve transitions a pending row to approved") {
      for {
        _     <- cleanDb
        dRepo <- ZIO.service[DeviceRepo]
        aRepo <- ZIO.service[AlertRepo]
        auth  <- makeAuth
        clock <- ZIO.service[Clock]
        _     <- dRepo.upsertUnknown(mac1, "device-112233", None, now)
        _     <- aRepo.raiseNewDevice(mac1, now)
        all   <- aRepo.list(includeAll = false)
        token <- auth.login("admin", "changeme").map(_.token)
        routes = AlertRoutes.routes(auth, aRepo, clock)
        resp  <- postAlertAction(routes, s"/api/alerts/${all.head.id.value}/approve", token)
        body  <- resp.body.asString
        alert <- ZIO.fromEither(body.fromJson[Alert])
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(alert.status == AlertStatus.Approved)
    },
    test("POST /api/alerts/{id}/deny transitions a pending row to denied") {
      for {
        _     <- cleanDb
        dRepo <- ZIO.service[DeviceRepo]
        aRepo <- ZIO.service[AlertRepo]
        auth  <- makeAuth
        clock <- ZIO.service[Clock]
        _     <- dRepo.upsertUnknown(mac1, "device-112233", None, now)
        _     <- aRepo.raiseNewDevice(mac1, now)
        all   <- aRepo.list(includeAll = false)
        token <- auth.login("admin", "changeme").map(_.token)
        routes = AlertRoutes.routes(auth, aRepo, clock)
        resp  <- postAlertAction(routes, s"/api/alerts/${all.head.id.value}/deny", token)
        body  <- resp.body.asString
        alert <- ZIO.fromEither(body.fromJson[Alert])
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(alert.status == AlertStatus.Denied)
    },
    test("approve on an already-decided row is a conflict") {
      for {
        _     <- cleanDb
        dRepo <- ZIO.service[DeviceRepo]
        aRepo <- ZIO.service[AlertRepo]
        auth  <- makeAuth
        clock <- ZIO.service[Clock]
        _     <- dRepo.upsertUnknown(mac1, "device-112233", None, now)
        _     <- aRepo.raiseNewDevice(mac1, now)
        all   <- aRepo.list(includeAll = false)
        _     <- aRepo.decide(all.head.id, AlertStatus.Approved, now, "admin", None)
        token <- auth.login("admin", "changeme").map(_.token)
        routes = AlertRoutes.routes(auth, aRepo, clock)
        resp <- postAlertAction(routes, s"/api/alerts/${all.head.id.value}/approve", token)
      } yield assertTrue(resp.status == Status.Conflict)
    },
  ) @@ TestAspect.sequential
}
