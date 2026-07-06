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
import java.util.UUID

/**
 * #1122: synthesized firewall-drop events (from the openwrt agent's nflog tail) land in
 * connection_events and are visible through the standard logs read paths.
 *
 * The router never gets a separate ingest endpoint or a parallel table; the existing
 * `connection_attempt` event type already supports `allowed=false` + an arbitrary reason string.
 * This spec posts synthesized payloads for each MacBlockReason value and asserts they round-trip
 * through `/api/logs?mac=...&blocked=true` and `/api/connection-events/series?bucket=1h`.
 */
object BlockedMacEventIngestSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val jwtCfg   = JwtConfig(secret = "test-secret-at-least-32-chars!!x", expiryHours = 1)
  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwtCfg, clock)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private val mac = MacAddress.unsafe("aa:bb:cc:11:22:33")

  private def seedRouter: ZIO[RouterRepo, Throwable, (RouterId, String)] =
    ZIO.serviceWithZIO[RouterRepo] { rRepo =>
      val token = "ROUTER_TOKEN_PLAIN"
      val hash  = Sha256Hex.unsafe(RouterAuth.sha256Hex(token))
      for {
        id <- rRepo.create("home", Sha256Hex.unsafe("a" * 64))
        _  <- rRepo.completeEnrollment(id, hash)
      } yield (id, token)
    }

  private def buildIngestRoutes =
    for {
      rRepo <- ZIO.service[RouterRepo]
      tRepo <- ZIO.service[TrafficReportRepo]
      tu    <- ZIO.service[TimeUsageRepo]
      dRepo <- ZIO.service[DeviceRepo]
      cRepo <- ZIO.service[ConnectionEventRepo]
      aRepo <- ZIO.service[AlertRepo]
      hsr   <- ZIO.service[HouseholdSettingsRepo]
      auth = new RouterAuthLive(rRepo)
    } yield RouterIngestRoutes.routes(auth, rRepo, tRepo, tu, dRepo, cRepo, aRepo, hsr)

  private def buildLogRoutes(auth: AuthService) =
    for {
      cRepo  <- ZIO.service[ConnectionEventRepo]
      upRepo <- ZIO.service[UserProfileRepo]
    } yield LogRoutes.routes(auth, cRepo, upRepo)

  private def post(
      routes: Routes[Any, Response],
      path: String,
      body: String,
      token: String,
  ) =
    routes.runZIO(
      Request
        .post(URL.decode(path).toOption.get, Body.fromString(body))
        .addHeader(Header.ContentType(MediaType.application.json))
        .addHeader(Header.Authorization.Bearer(token)),
    )

  private def get(routes: Routes[Any, Response], path: String, token: String) =
    routes.runZIO(
      Request
        .get(URL.decode(path).toOption.get)
        .addHeader(Header.Authorization.Bearer(token)),
    )

  private def mkBlockedEv(reason: String, dstIp: String, ts: Instant): RouterEvent =
    RouterEvent(
      "connection_attempt",
      mac = Some(mac),
      // host is an IPv4-tagged HostId when the agent has no DNS attribution
      // for the dropped packet (the common case — nflog gives us the IP, not
      // a hostname). The API's #720 backfill closes the attribution loop
      // when a paired fqdn event lands in the same window.
      host = Some(HostId.IPv4(IpAddress.unsafe(dstIp))),
      destIp = Some(IpAddress.unsafe(dstIp)),
      allowed = Some(false),
      reason = Some(reason),
      ts = ts.toString,
      eventId = Some(UUID.randomUUID()),
    )

  // Anchor event timestamps to "just now" so the default /api/logs?hours=24
  // window includes them regardless of the test Clock injected via bootstrap.
  private def recentTs: Instant = Instant.now().minusSeconds(60)

  def spec = suite("BlockedMacEventIngest (#1122)")(
    test(
      "each MacBlockReason synthesized by the agent round-trips through /api/logs?blocked=true",
    ) {
      val ts0 = recentTs
      for {
        _         <- cleanDb
        (id, tk)  <- seedRouter
        ingest    <- buildIngestRoutes
        auth      <- makeAuth
        adminTok  <- auth.login("admin", "changeme").map(_.token.value)
        logRoutes <- buildLogRoutes(auth)
        evs  = List(
          mkBlockedEv("Paused", "1.1.1.1", ts0),
          mkBlockedEv("Schedule", "1.1.1.2", ts0.plusSeconds(1)),
          mkBlockedEv("TimeLimit", "1.1.1.3", ts0.plusSeconds(2)),
          mkBlockedEv("Manual", "1.1.1.4", ts0.plusSeconds(3)),
          mkBlockedEv("Unmanaged", "1.1.1.5", ts0.plusSeconds(4)),
        )
        body = RouterEventsRequest(id, evs).toJson
        ingestResp <- post(ingest, "/api/router/events", body, tk)
        logsResp   <- get(logRoutes, s"/api/logs?mac=${mac.value}&blocked=true&hours=168", adminTok)
        logsBody   <- logsResp.body.asString
        page       <- ZIO.fromEither(logsBody.fromJson[QueryLogPage])
        reasons = page.rows.map(_.reason).toSet
      } yield assertTrue(ingestResp.status == Status.Ok) &&
        assertTrue(page.rows.size == 5) &&
        assertTrue(page.rows.forall(_.blocked)) &&
        assertTrue(
          reasons == Set[BlockReason](
            MacBlockReason.Paused,
            MacBlockReason.Schedule,
            MacBlockReason.TimeLimit,
            MacBlockReason.Manual,
            MacBlockReason.Unmanaged,
          ),
        )
    },
    test("synthesized blocked-MAC events appear in /api/connection-events/series with bucket=1h") {
      val ts0 = recentTs
      for {
        _         <- cleanDb
        (id, tk)  <- seedRouter
        ingest    <- buildIngestRoutes
        auth      <- makeAuth
        adminTok  <- auth.login("admin", "changeme").map(_.token.value)
        logRoutes <- buildLogRoutes(auth)
        evs  = List(
          mkBlockedEv("Paused", "1.1.1.1", ts0),
          mkBlockedEv("Schedule", "1.1.1.2", ts0.plusSeconds(1)),
        )
        body = RouterEventsRequest(id, evs).toJson
        _        <- post(ingest, "/api/router/events", body, tk)
        // hours=24 keeps this on the raw tier (#1265): a freshly-ingested,
        // not-yet-rerolled event is only visible on the live connection_events
        // table. A wider window would route to the hourly rollup (rollup-lag by
        // design — that path is covered in LogApiSpec). This test is about
        // ingest visibility, so it reads the fresh path.
        resp     <- get(
          logRoutes,
          s"/api/connection-events/series?bucket=1h&blocked=true&hours=24",
          adminTok,
        )
        respBody <- resp.body.asString
        page     <- ZIO.fromEither(respBody.fromJson[ConnectionEventSeriesPage])
      } yield assertTrue(resp.status == Status.Ok) &&
        // Both events are in the same hour window so they collapse to one row
        // with countBlocked == 2 (no groupBy).
        assertTrue(page.rows.exists(r => r.countBlocked == 2 && r.countSucceeded == 0))
    },
    test("ingest tolerates an `Unmanaged` reason string (forward-compat with #1122 router)") {
      // Pre-#1122 agents emit `Manual` for the default-block path; #1122 agents
      // emit `Unmanaged`. The API normalizes the wire reason into a typed
      // BlockReason on ingest (#962), so `Unmanaged` must map to
      // MacBlockReason.Unmanaged without ingest rejection.
      val ts = recentTs
      for {
        _         <- cleanDb
        (id, tk)  <- seedRouter
        ingest    <- buildIngestRoutes
        auth      <- makeAuth
        adminTok  <- auth.login("admin", "changeme").map(_.token.value)
        logRoutes <- buildLogRoutes(auth)
        body = RouterEventsRequest(id, List(mkBlockedEv("Unmanaged", "9.9.9.9", ts))).toJson
        resp <- post(ingest, "/api/router/events", body, tk)
        logs <- get(logRoutes, s"/api/logs?mac=${mac.value}&blocked=true&hours=168", adminTok)
        lb   <- logs.body.asString
        page <- ZIO.fromEither(lb.fromJson[QueryLogPage])
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(page.rows.size == 1) &&
        assertTrue(page.rows.head.reason == MacBlockReason.Unmanaged)
    },
  ) @@ TestAspect.sequential
}
