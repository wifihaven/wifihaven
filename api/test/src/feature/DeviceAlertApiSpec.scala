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

import java.time.Instant

object DeviceAlertApiSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

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

  private def seedAlerts(dRepo: DeviceRepo, aRepo: DeviceAlertRepo): Task[Unit] =
    for {
      _ <- dRepo.upsertUnknown(mac1, "device-112233", None, now)
      _ <- dRepo.upsertUnknown(mac2, "device-445566", None, now)
      _ <- aRepo.raise(mac1, now)
      _ <- aRepo.raise(mac2, now)
    } yield ()

  def spec = suite("Device-alert API (#711)")(
    test("GET /api/device-alerts returns only pending alerts by default") {
      for {
        _     <- cleanDb
        dRepo <- ZIO.service[DeviceRepo]
        aRepo <- ZIO.service[DeviceAlertRepo]
        auth  <- makeAuth
        clock <- ZIO.service[Clock]
        _     <- seedAlerts(dRepo, aRepo)
        all   <- aRepo.listAll(includeDismissed = false)
        _     <- aRepo.dismiss(all.head.id, now)
        token <- auth.login("admin", "changeme").map(_.token)
        routes = DeviceAlertRoutes.routes(auth, aRepo, clock)
        req    = Request
          .get(URL.decode("/api/device-alerts").toOption.get)
          .addHeader(Header.Authorization.Bearer(token.value))
        resp   <- routes.runZIO(req)
        body   <- resp.body.asString
        alerts <- ZIO.fromEither(body.fromJson[List[DeviceAlert]])
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(alerts.size == 1) &&
        assertTrue(alerts.forall(_.dismissedAt.isEmpty))
    },
    test("GET /api/device-alerts?dismissed=true returns all alerts") {
      for {
        _     <- cleanDb
        dRepo <- ZIO.service[DeviceRepo]
        aRepo <- ZIO.service[DeviceAlertRepo]
        auth  <- makeAuth
        clock <- ZIO.service[Clock]
        _     <- seedAlerts(dRepo, aRepo)
        all   <- aRepo.listAll(includeDismissed = false)
        _     <- aRepo.dismiss(all.head.id, now)
        token <- auth.login("admin", "changeme").map(_.token)
        routes = DeviceAlertRoutes.routes(auth, aRepo, clock)
        req    = Request
          .get(URL.decode("/api/device-alerts?dismissed=true").toOption.get)
          .addHeader(Header.Authorization.Bearer(token.value))
        resp   <- routes.runZIO(req)
        body   <- resp.body.asString
        alerts <- ZIO.fromEither(body.fromJson[List[DeviceAlert]])
      } yield assertTrue(alerts.size == 2)
    },
    test("POST /api/device-alerts/{id}/dismiss flips dismissed_at") {
      for {
        _     <- cleanDb
        dRepo <- ZIO.service[DeviceRepo]
        aRepo <- ZIO.service[DeviceAlertRepo]
        auth  <- makeAuth
        clock <- ZIO.service[Clock]
        _     <- seedAlerts(dRepo, aRepo)
        all   <- aRepo.listAll(includeDismissed = false)
        token <- auth.login("admin", "changeme").map(_.token)
        routes = DeviceAlertRoutes.routes(auth, aRepo, clock)
        req    = Request
          .post(
            URL.decode(s"/api/device-alerts/${all.head.id.value}/dismiss").toOption.get,
            Body.empty,
          )
          .addHeader(Header.Authorization.Bearer(token.value))
        resp    <- routes.runZIO(req)
        pending <- aRepo.listAll(includeDismissed = false)
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(pending.size == 1)
    },
    test("POST dismiss for unknown id returns 404") {
      for {
        _     <- cleanDb
        aRepo <- ZIO.service[DeviceAlertRepo]
        auth  <- makeAuth
        clock <- ZIO.service[Clock]
        token <- auth.login("admin", "changeme").map(_.token)
        routes = DeviceAlertRoutes.routes(auth, aRepo, clock)
        req    = Request
          .post(URL.decode("/api/device-alerts/99999/dismiss").toOption.get, Body.empty)
          .addHeader(Header.Authorization.Bearer(token.value))
        resp <- routes.runZIO(req)
      } yield assertTrue(resp.status == Status.NotFound)
    },
    test("GET without auth → 401") {
      for {
        _     <- cleanDb
        aRepo <- ZIO.service[DeviceAlertRepo]
        auth  <- makeAuth
        clock <- ZIO.service[Clock]
        routes = DeviceAlertRoutes.routes(auth, aRepo, clock)
        req    = Request.get(URL.decode("/api/device-alerts").toOption.get)
        resp <- routes.runZIO(req)
      } yield assertTrue(resp.status == Status.Unauthorized)
    },
  ) @@ TestAspect.sequential
}
