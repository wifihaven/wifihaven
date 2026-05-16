package familydns.api.feature

import familydns.api.db.*
import familydns.api.routes.*
import familydns.shared.*
import familydns.shared.types.*
import familydns.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.http.*
import zio.test.*

import java.time.LocalDateTime

// Tests for the /api/debug/... observability endpoints (#228). Endpoints are
// gated by an `enabled` flag (driven by FAMILYDNS_DEBUG in production) and a
// per-request loopback check.
object DebugApiSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(LocalDateTime.of(2026, 5, 11, 12, 0))

  private def cleanDb = ZIO.serviceWithZIO[EmbeddedPostgres](pg =>
    TestDatabase.cleanAndMigrate.provide(ZLayer.succeed(pg)),
  )

  private def buildRoutes(enabled: Boolean) =
    for {
      dRepo  <- ZIO.service[DeviceRepo]
      cRepo  <- ZIO.service[ConnectionEventRepo]
      tuRepo <- ZIO.service[TimeUsageRepo]
      trRepo <- ZIO.service[TrafficReportRepo]
      clock  <- ZIO.service[Clock]
    } yield DebugRoutes.routes(enabled, dRepo, cRepo, tuRepo, trRepo, clock)

  /**
   * Build a request whose Host header is loopback-y by default. zio-http's Request.remoteAddress is
   * None in test (no socket), so the loopback guard falls back to the Host header for its decision.
   */
  private def get(path: String, host: String = "localhost"): Request =
    Request
      .get(URL.decode(path).toOption.get)
      .addHeader(Header.Host(host, None))

  def spec = suite("Debug API /api/debug/*")(
    test("returns 404 for every path when enabled = false") {
      for {
        _      <- cleanDb
        routes <- buildRoutes(enabled = false)
        rDev   <- routes.runZIO(get("/api/debug/devices"))
        rEv    <- routes.runZIO(get("/api/debug/events"))
        rTu    <- routes.runZIO(get("/api/debug/time_usage"))
      } yield assertTrue(rDev.status == Status.NotFound) &&
        assertTrue(rEv.status == Status.NotFound) &&
        assertTrue(rTu.status == Status.NotFound)
    },
    test("returns 403 when Host header is non-loopback even with enabled = true") {
      for {
        _      <- cleanDb
        routes <- buildRoutes(enabled = true)
        rDev   <- routes.runZIO(get("/api/debug/devices", host = "evil.example.com"))
        rEv    <- routes.runZIO(get("/api/debug/events", host = "1.2.3.4"))
      } yield assertTrue(rDev.status == Status.Forbidden) &&
        assertTrue(rEv.status == Status.Forbidden)
    },
    test("accepts 127.0.0.1 and ::1 as loopback hosts") {
      for {
        _      <- cleanDb
        routes <- buildRoutes(enabled = true)
        r1     <- routes.runZIO(get("/api/debug/devices", host = "127.0.0.1"))
        r2     <- routes.runZIO(get("/api/debug/devices", host = "::1"))
        r3     <- routes.runZIO(get("/api/debug/devices", host = "[::1]"))
      } yield assertTrue(r1.status == Status.Ok) &&
        assertTrue(r2.status == Status.Ok) &&
        assertTrue(r3.status == Status.Ok)
    },
    test("/api/debug/devices from loopback returns 200 with seeded rows") {
      for {
        _      <- cleanDb
        pRepo  <- ZIO.service[ProfileRepo]
        sRepo  <- ZIO.service[ScheduleRepo]
        dRepo  <- ZIO.service[DeviceRepo]
        routes <- buildRoutes(enabled = true)
        kid    <- TestLayers.seedKidsProfile(pRepo, sRepo)
        _      <- TestLayers.seedDevice(dRepo, "aa:bb:cc:11:22:33", "kid-ipad", kid)
        resp   <- routes.runZIO(get("/api/debug/devices"))
        body   <- resp.body.asString
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(body.contains("aa:bb:cc:11:22:33")) &&
        assertTrue(body.contains("kid-ipad"))
    },
    test("/api/debug/events?limit=2 returns at most 2 rows") {
      for {
        _      <- cleanDb
        rRepo  <- ZIO.service[RouterRepo]
        cRepo  <- ZIO.service[ConnectionEventRepo]
        routes <- buildRoutes(enabled = true)
        rid    <- rRepo.create("debug-test", Sha256Hex.unsafe("d" * 64))
        evs = (1 to 5).toList.map(i =>
          ConnectionEventInsert(
            rid,
            Some(MacAddress.unsafe(s"aa:bb:cc:dd:ee:0$i")),
            HostId.Fqdn(Hostname.unsafe(s"site$i.example")),
            Some(IpAddress.unsafe(s"10.0.0.$i")),
            true,
            "allow",
            java.time.Instant.parse("2026-05-11T12:00:00Z"),
          ),
        )
        _    <- cRepo.insertBatch(evs)
        resp <- routes.runZIO(get("/api/debug/events?limit=2"))
        body <- resp.body.asString
      } yield assertTrue(resp.status == Status.Ok) &&
        // Crude row count: count occurrences of "\"id\":"
        assertTrue("""\"id\":""".r.findAllIn(body).length == 2)
    },
    test("/api/debug/time_usage returns 200 with today's usage rows") {
      for {
        _      <- cleanDb
        tuRepo <- ZIO.service[TimeUsageRepo]
        routes <- buildRoutes(enabled = true)
        today = java.time.LocalDate.of(2026, 5, 11)
        _    <- tuRepo.incrementSecondsAndBytes(
          MacAddress.unsafe("aa:bb:cc:11:22:33"),
          HostId.Fqdn(Hostname.unsafe("youtube.com")),
          today,
          120L,
          0L,
          0L,
        )
        resp <- routes.runZIO(get("/api/debug/time_usage"))
        body <- resp.body.asString
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(body.contains("aa:bb:cc:11:22:33")) &&
        assertTrue(body.contains("youtube.com"))
    },
  ) @@ TestAspect.sequential
}
