package wifihaven.api.feature

import wifihaven.api.JwtConfig
import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.routes.*
import wifihaven.api.usage.RetentionSweepJob
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.shared.Clock.TestClock
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import doobie.*
import doobie.implicits.*
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*
import zio.test.*
import zio.interop.catz.*

import java.time.Instant

/**
 * #1103: MAC-level packet drops surface. Verifies POST ingest auth + idempotency, negative-delta
 * rejection, the read endpoint's auth + filtering, and that the shared daily retention sweep
 * includes mac_drops.
 */
object MacDropsApiSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val jwtCfg   = JwtConfig(secret = "test-secret-at-least-32-chars!!", expiryHours = 1)
  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwtCfg, clock)
  private def cleanDb  = ZIO.serviceWithZIO[EmbeddedPostgres](pg =>
    TestDatabase.cleanAndMigrate.provide(ZLayer.succeed(pg)),
  )

  private def seedRouter(rRepo: RouterRepo): Task[(RouterId, String)] = {
    val token = "ROUTER_TOKEN_PLAIN"
    val hash  = Sha256Hex.unsafe(RouterAuth.sha256Hex(token))
    for {
      id <- rRepo.create("test-router", Sha256Hex.unsafe("m" * 64))
      _  <- rRepo.completeEnrollment(id, hash)
    } yield (id, token)
  }

  private def buildIngestRoutes =
    for {
      rRepo  <- ZIO.service[RouterRepo]
      tRepo  <- ZIO.service[TrafficReportRepo]
      tu     <- ZIO.service[TimeUsageRepo]
      dRepo  <- ZIO.service[DeviceRepo]
      cRepo  <- ZIO.service[ConnectionEventRepo]
      aRepo  <- ZIO.service[AlertRepo]
      hsr    <- ZIO.service[HouseholdSettingsRepo]
      mdRepo <- ZIO.service[MacDropsRepo]
      auth = new RouterAuthLive(rRepo)
    } yield RouterIngestRoutes.routes(auth, rRepo, tRepo, tu, dRepo, cRepo, aRepo, hsr, mdRepo)

  private def macA = MacAddress.unsafe("aa:bb:cc:dd:ee:ff")
  private def macB = MacAddress.unsafe("11:22:33:44:55:66")

  // Anchor `periodEnd` to a stable, non-now instant so the /series window
  // queries (which use `until` as the right edge) are reproducible.
  private val anchorEnd   = Instant.parse("2026-05-07T14:00:00Z")
  private val anchorStart = anchorEnd.minusSeconds(60)

  private def reportJson(routerId: RouterId, drops: List[MacDropRecord]): String =
    MacDropsReport(routerId, drops).toJson

  private def post(routes: Routes[Any, Response], path: String, body: String, token: String) =
    routes.runZIO(
      Request
        .post(URL.decode(path).toOption.get, Body.fromString(body))
        .addHeader(Header.Authorization.Bearer(token))
        .addHeader(Header.ContentType(MediaType.application.json)),
    )

  private def get(routes: Routes[Any, Response], path: String, token: String) =
    routes.runZIO(
      Request
        .get(URL.decode(path).toOption.get)
        .addHeader(Header.Authorization.Bearer(token)),
    )

  def spec = suite("MAC drops API (#1103)")(
    test("POST /api/router/mac-drops persists rows on valid router token") {
      for {
        _              <- cleanDb
        rRepo          <- ZIO.service[RouterRepo]
        (routerId, tk) <- seedRouter(rRepo)
        mdRepo         <- ZIO.service[MacDropsRepo]
        routes         <- buildIngestRoutes
        body = reportJson(
          routerId,
          List(
            MacDropRecord(
              macA,
              MacBlockReason.Paused,
              7L,
              1024L,
              anchorStart.toString,
              anchorEnd.toString,
            ),
            MacDropRecord(
              macB,
              MacBlockReason.TimeLimit,
              3L,
              256L,
              anchorStart.toString,
              anchorEnd.toString,
            ),
          ),
        )
        resp <- post(routes, "/api/router/mac-drops", body, tk)
        rows <- mdRepo.querySeries(
          MacDropsSeriesFilter(hours = 1, until = Some(anchorEnd.plusSeconds(1))),
          3600,
        )
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(rows.length == 2) &&
        assertTrue(
          rows.exists(r => r.mac == macA && r.reason == MacBlockReason.Paused && r.packets == 7L),
        ) &&
        assertTrue(
          rows.exists(r => r.mac == macB && r.reason == MacBlockReason.TimeLimit && r.bytes == 256L),
        )
    },
    test("POST /api/router/mac-drops is idempotent on (router, mac, reason, periodEnd)") {
      for {
        _              <- cleanDb
        rRepo          <- ZIO.service[RouterRepo]
        (routerId, tk) <- seedRouter(rRepo)
        mdRepo         <- ZIO.service[MacDropsRepo]
        routes         <- buildIngestRoutes
        body = reportJson(
          routerId,
          List(
            MacDropRecord(
              macA,
              MacBlockReason.Paused,
              5L,
              100L,
              anchorStart.toString,
              anchorEnd.toString,
            ),
          ),
        )
        r1   <- post(routes, "/api/router/mac-drops", body, tk)
        r2   <- post(routes, "/api/router/mac-drops", body, tk)
        rows <- mdRepo.querySeries(
          MacDropsSeriesFilter(hours = 1, until = Some(anchorEnd.plusSeconds(1))),
          3600,
        )
      } yield assertTrue(r1.status == Status.Ok) &&
        assertTrue(r2.status == Status.Ok) &&
        // Replay collapsed on the unique key; only one row persisted.
        assertTrue(rows.count(_.mac == macA) == 1) &&
        assertTrue(rows.head.packets == 5L)
    },
    test("POST /api/router/mac-drops rejects negative packets/bytes with 400") {
      for {
        _              <- cleanDb
        rRepo          <- ZIO.service[RouterRepo]
        (routerId, tk) <- seedRouter(rRepo)
        mdRepo         <- ZIO.service[MacDropsRepo]
        routes         <- buildIngestRoutes
        body = reportJson(
          routerId,
          List(
            MacDropRecord(
              macA,
              MacBlockReason.Paused,
              -5L,
              0L,
              anchorStart.toString,
              anchorEnd.toString,
            ),
          ),
        )
        resp <- post(routes, "/api/router/mac-drops", body, tk)
        rows <- mdRepo.querySeries(
          MacDropsSeriesFilter(hours = 1, until = Some(anchorEnd.plusSeconds(1))),
          3600,
        )
      } yield assertTrue(resp.status == Status.BadRequest) &&
        assertTrue(rows.isEmpty)
    },
    test("POST /api/router/mac-drops rejects mismatched router_id") {
      for {
        _       <- cleanDb
        rRepo   <- ZIO.service[RouterRepo]
        (_, tk) <- seedRouter(rRepo)
        routes  <- buildIngestRoutes
        otherRouterId = RouterId(java.util.UUID.randomUUID())
        body          = reportJson(
          otherRouterId,
          List(
            MacDropRecord(
              macA,
              MacBlockReason.Paused,
              1L,
              1L,
              anchorStart.toString,
              anchorEnd.toString,
            ),
          ),
        )
        resp <- post(routes, "/api/router/mac-drops", body, tk)
      } yield assertTrue(resp.status == Status.BadRequest)
    },
    test("POST /api/router/mac-drops rejects missing bearer with 401") {
      for {
        _      <- cleanDb
        rRepo  <- ZIO.service[RouterRepo]
        (_, _) <- seedRouter(rRepo)
        routes <- buildIngestRoutes
        req = Request.post(URL.decode("/api/router/mac-drops").toOption.get, Body.fromString("{}"))
        resp <- routes.runZIO(req)
      } yield assertTrue(resp.status == Status.Unauthorized)
    },
    test("GET /api/mac-drops/series filters by mac and reason and buckets by hour") {
      for {
        _             <- cleanDb
        rRepo         <- ZIO.service[RouterRepo]
        (routerId, _) <- seedRouter(rRepo)
        mdRepo        <- ZIO.service[MacDropsRepo]
        auth          <- makeAuth
        token         <- auth.login("admin", "changeme").map(_.token.value)
        _             <- mdRepo.insertBatch(
          List(
            MacDropInsert(
              routerId,
              macA,
              MacBlockReason.Paused,
              10L,
              1000L,
              anchorStart,
              anchorEnd,
            ),
            MacDropInsert(
              routerId,
              macA,
              MacBlockReason.TimeLimit,
              5L,
              500L,
              anchorStart,
              anchorEnd,
            ),
            MacDropInsert(routerId, macB, MacBlockReason.Paused, 8L, 800L, anchorStart, anchorEnd),
          ),
        )
        routes = MacDropsRoutes.routes(auth, mdRepo)
        until  = anchorEnd.plusSeconds(1).toString
        all   <- get(routes, s"/api/mac-drops/series?bucket=1h&hours=2&until=$until", token)
        body  <- all.body.asString
        page  <- ZIO.fromEither(body.fromJson[MacDropsSeriesPage])
        // Filter to macA only
        a     <- get(
          routes,
          s"/api/mac-drops/series?bucket=1h&hours=2&mac=${macA.value}&until=$until",
          token,
        )
        aBody <- a.body.asString
        aPage <- ZIO.fromEither(aBody.fromJson[MacDropsSeriesPage])
        // Filter to reason=Paused only
        p     <- get(
          routes,
          s"/api/mac-drops/series?bucket=1h&hours=2&reason=Paused&until=$until",
          token,
        )
        pBody <- p.body.asString
        pPage <- ZIO.fromEither(pBody.fromJson[MacDropsSeriesPage])
      } yield assertTrue(all.status == Status.Ok) &&
        assertTrue(page.rows.length == 3) &&
        assertTrue(aPage.rows.length == 2) &&
        assertTrue(aPage.rows.forall(_.mac == macA)) &&
        assertTrue(pPage.rows.length == 2) &&
        assertTrue(pPage.rows.forall(_.reason == MacBlockReason.Paused))
    },
    test("GET /api/mac-drops/series rejects child viewer with 403") {
      for {
        _       <- cleanDb
        ur      <- ZIO.service[UserRepo]
        auth    <- makeAuth
        kidHash <- auth.hashPassword("kidpw")
        _       <- ur.create("kid", kidHash, "child")
        // Need to clear must_change_password to log in normally
        _       <- ZIO.serviceWithZIO[EmbeddedPostgres] { pg =>
          ZIO.attemptBlocking {
            val conn = pg.getPostgresDatabase.getConnection
            try
              conn
                .createStatement()
                .execute(
                  "UPDATE users SET must_change_password=false WHERE username='kid'",
                )
            finally conn.close()
          }
        }
        kidTok  <- auth.login("kid", "kidpw").map(_.token.value)
        mdRepo  <- ZIO.service[MacDropsRepo]
        routes = MacDropsRoutes.routes(auth, mdRepo)
        resp <- get(routes, "/api/mac-drops/series?bucket=1h&hours=24", kidTok)
      } yield assertTrue(resp.status == Status.Forbidden)
    },
    test("Retention sweep removes mac_drops older than 30 days") {
      for {
        _             <- cleanDb
        rRepo         <- ZIO.service[RouterRepo]
        (routerId, _) <- seedRouter(rRepo)
        mdRepo        <- ZIO.service[MacDropsRepo]
        xa            <- ZIO.service[Transactor[Task]]
        oldPe = Instant.now().minusSeconds(60L * 60 * 24 * 35)
        newPe = Instant.now().minusSeconds(60L * 60)
        _      <- mdRepo.insertBatch(
          List(
            MacDropInsert(
              routerId,
              macA,
              MacBlockReason.Paused,
              1L,
              1L,
              oldPe.minusSeconds(60),
              oldPe,
            ),
            MacDropInsert(
              routerId,
              macA,
              MacBlockReason.Paused,
              2L,
              2L,
              newPe.minusSeconds(60),
              newPe,
            ),
          ),
        )
        _      <- RetentionSweepJob.sweepOnce(xa)
        remain <- sql"SELECT COUNT(*)::INT FROM mac_drops".query[Int].unique.transact(xa)
      } yield assertTrue(remain == 1)
    },
  ) @@ TestAspect.sequential
}
