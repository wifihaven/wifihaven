package familydns.api.feature

import familydns.api.db.*
import familydns.api.routes.*
import familydns.shared.*
import familydns.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*
import zio.test.*

import java.time.{Instant, LocalDate, OffsetDateTime}
import java.time.format.DateTimeFormatter
import java.util.UUID

private object SeenParser:
  // Postgres `::TEXT` on TIMESTAMPTZ produces "2026-05-07 08:05:00-06". Normalize the
  // separator and pad a 2-digit numeric offset to ±HH:00 so OffsetDateTime can parse it.
  private val fmt                   = DateTimeFormatter.ISO_OFFSET_DATE_TIME
  def toInstant(s: String): Instant =
    val withT  = s.replace(' ', 'T')
    val padded =
      if withT.matches(""".*[+-]\d{2}$""") then withT + ":00"
      else withT
    OffsetDateTime.parse(padded, fmt).toInstant

object RouterIngestSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres] {

  override val bootstrap = TestDatabase.layer

  private def cleanDb = ZIO.serviceWithZIO[EmbeddedPostgres](pg =>
    TestDatabase.cleanAndMigrate.provide(ZLayer.succeed(pg)),
  )

  private def seedRouter(rRepo: RouterRepo): Task[(UUID, String)] =
    val token = "ROUTER_TOKEN_PLAIN"
    val hash  = RouterAuth.sha256Hex(token)
    for
      id <- rRepo.create("test-router", "ENROLL_HASH")
      _  <- rRepo.completeEnrollment(id, hash)
    yield (id, token)

  private def buildRoutes =
    for
      rRepo <- ZIO.service[RouterRepo]
      tRepo <- ZIO.service[TrafficReportRepo]
      tu    <- ZIO.service[TimeUsageRepo]
      dRepo <- ZIO.service[DeviceRepo]
      cRepo <- ZIO.service[ConnectionEventRepo]
      auth = new RouterAuthLive(rRepo)
    yield RouterIngestRoutes.routes(auth, rRepo, tRepo, tu, dRepo, cRepo)

  private def post(
      routes: Routes[Any, Response],
      path: String,
      body: String,
      token: Option[String],
  ) =
    val base = Request
      .post(URL.decode(path).toOption.get, Body.fromString(body))
      .addHeader(Header.ContentType(MediaType.application.json))
    val req  = token.fold(base)(t => base.addHeader(Header.Authorization.Bearer(t)))
    routes.runZIO(req)

  // Fixed test instants
  private val periodStart = Instant.parse("2026-05-07T14:00:00Z")
  private val periodEnd   = Instant.parse("2026-05-07T14:05:00Z")
  private val testDate    = LocalDate.of(2026, 5, 7)

  private val knownMac   = "aa:bb:cc:11:22:33"
  private val unknownMac = "aa:bb:cc:99:99:99"

  private def seedKnownDevice(dRepo: DeviceRepo, profileRepo: ProfileRepo): Task[Unit] =
    for
      pid <- profileRepo.create("Kids", List("adult"))
      _   <- dRepo.upsert(knownMac, "kid-ipad", pid, "192.168.1.10", "home")
    yield ()

  def spec = suite("Router ingest /api/router/*")(
    // ── auth ─────────────────────────────────────────────────────────────────
    test("missing bearer returns 401") {
      for
        _      <- cleanDb
        routes <- buildRoutes
        body = UsageReport(UUID.randomUUID(), periodStart.toString, periodEnd.toString, Nil).toJson
        resp <- post(routes, "/api/router/usage", body, None)
      yield assertTrue(resp.status == Status.Unauthorized)
    },
    test("invalid bearer returns 401") {
      for
        _      <- cleanDb
        routes <- buildRoutes
        body = UsageReport(UUID.randomUUID(), periodStart.toString, periodEnd.toString, Nil).toJson
        resp <- post(routes, "/api/router/usage", body, Some("not-a-real-token"))
      yield assertTrue(resp.status == Status.Unauthorized)
    },
    test("valid bearer with empty records returns 200") {
      for
        _        <- cleanDb
        rRepo    <- ZIO.service[RouterRepo]
        routes   <- buildRoutes
        (id, tk) <- seedRouter(rRepo)
        body = UsageReport(id, periodStart.toString, periodEnd.toString, Nil).toJson
        resp <- post(routes, "/api/router/usage", body, Some(tk))
      yield assertTrue(resp.status == Status.Ok)
    },

    // ── usage ────────────────────────────────────────────────────────────────
    test(
      "usage: posting same period+mac+hostname twice does not double-count seconds_used or bytes",
    ) {
      for
        _        <- cleanDb
        rRepo    <- ZIO.service[RouterRepo]
        pRepo    <- ZIO.service[ProfileRepo]
        dRepo    <- ZIO.service[DeviceRepo]
        tu       <- ZIO.service[TimeUsageRepo]
        tRepo    <- ZIO.service[TrafficReportRepo]
        routes   <- buildRoutes
        _        <- seedKnownDevice(dRepo, pRepo)
        (id, tk) <- seedRouter(rRepo)
        rec  = UsageRecord(knownMac, Some("192.168.1.10"), "youtube.com", 240L, 1000L, 500L)
        body = UsageReport(id, periodStart.toString, periodEnd.toString, List(rec)).toJson
        r1   <- post(routes, "/api/router/usage", body, Some(tk))
        r2   <- post(routes, "/api/router/usage", body, Some(tk))
        sb   <- tu.getSecondsAndBytes(knownMac, "youtube.com", testDate)
        rows <- tRepo.listForRouter(id, 100)
      yield assertTrue(r1.status == Status.Ok) &&
        assertTrue(r2.status == Status.Ok) &&
        assertTrue(sb == ((240L, 1000L, 500L))) &&
        assertTrue(rows.size == 1)
    },
    test(
      "usage: bytes accumulate across multiple records in one POST (different hostnames same mac)",
    ) {
      for
        _        <- cleanDb
        rRepo    <- ZIO.service[RouterRepo]
        pRepo    <- ZIO.service[ProfileRepo]
        dRepo    <- ZIO.service[DeviceRepo]
        tu       <- ZIO.service[TimeUsageRepo]
        routes   <- buildRoutes
        _        <- seedKnownDevice(dRepo, pRepo)
        (id, tk) <- seedRouter(rRepo)
        recs = List(
          UsageRecord(knownMac, None, "youtube.com", 60L, 100L, 50L),
          UsageRecord(knownMac, None, "google.com", 30L, 200L, 10L),
        )
        body = UsageReport(id, periodStart.toString, periodEnd.toString, recs).toJson
        resp <- post(routes, "/api/router/usage", body, Some(tk))
        yt   <- tu.getSecondsAndBytes(knownMac, "youtube.com", testDate)
        gg   <- tu.getSecondsAndBytes(knownMac, "google.com", testDate)
      yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(yt == ((60L, 100L, 50L))) &&
        assertTrue(gg == ((30L, 200L, 10L)))
    },
    test("usage: seconds add across distinct periods for same (mac, hostname)") {
      for
        _        <- cleanDb
        rRepo    <- ZIO.service[RouterRepo]
        pRepo    <- ZIO.service[ProfileRepo]
        dRepo    <- ZIO.service[DeviceRepo]
        tu       <- ZIO.service[TimeUsageRepo]
        routes   <- buildRoutes
        _        <- seedKnownDevice(dRepo, pRepo)
        (id, tk) <- seedRouter(rRepo)
        rec = UsageRecord(knownMac, None, "youtube.com", 120L, 1L, 1L)
        b1  = UsageReport(id, "2026-05-07T14:00:00Z", "2026-05-07T14:05:00Z", List(rec)).toJson
        b2  = UsageReport(id, "2026-05-07T14:05:00Z", "2026-05-07T14:10:00Z", List(rec)).toJson
        _  <- post(routes, "/api/router/usage", b1, Some(tk))
        _  <- post(routes, "/api/router/usage", b2, Some(tk))
        sb <- tu.getSecondsAndBytes(knownMac, "youtube.com", testDate)
      yield assertTrue(sb == ((240L, 2L, 2L)))
    },
    test("usage: known mac last_seen_at is set to period_end and last_seen_ip to record.ip") {
      for
        _        <- cleanDb
        rRepo    <- ZIO.service[RouterRepo]
        pRepo    <- ZIO.service[ProfileRepo]
        dRepo    <- ZIO.service[DeviceRepo]
        routes   <- buildRoutes
        _        <- seedKnownDevice(dRepo, pRepo)
        (id, tk) <- seedRouter(rRepo)
        rec  = UsageRecord(knownMac, Some("192.168.1.42"), "youtube.com", 60L, 1L, 1L)
        body = UsageReport(id, periodStart.toString, periodEnd.toString, List(rec)).toJson
        _ <- post(routes, "/api/router/usage", body, Some(tk))
        d <- dRepo.findByMac(knownMac)
      yield assertTrue(d.exists(_.lastSeenIp.contains("192.168.1.42"))) &&
        assertTrue(d.flatMap(_.lastSeenAt).map(SeenParser.toInstant).contains(periodEnd))
    },
    test("usage: unknown mac in records does NOT create a device row (events does that)") {
      for
        _        <- cleanDb
        rRepo    <- ZIO.service[RouterRepo]
        dRepo    <- ZIO.service[DeviceRepo]
        routes   <- buildRoutes
        (id, tk) <- seedRouter(rRepo)
        rec  = UsageRecord(unknownMac, Some("192.168.1.99"), "ads.example.com", 10L, 1L, 1L)
        body = UsageReport(id, periodStart.toString, periodEnd.toString, List(rec)).toJson
        resp <- post(routes, "/api/router/usage", body, Some(tk))
        d    <- dRepo.findByMac(unknownMac)
      yield assertTrue(resp.status == Status.Ok) && assertTrue(d.isEmpty)
    },

    // ── events ───────────────────────────────────────────────────────────────
    test("events: connection_attempt batch is recorded with allowed/reason") {
      for
        _        <- cleanDb
        rRepo    <- ZIO.service[RouterRepo]
        cRepo    <- ZIO.service[ConnectionEventRepo]
        routes   <- buildRoutes
        (id, tk) <- seedRouter(rRepo)
        evs  = List(
          RouterEvent(
            "connection_attempt",
            mac = Some(knownMac),
            hostname = Some("youtube.com"),
            destIp = Some("1.2.3.4"),
            allowed = Some(false),
            reason = Some("category:adult"),
            ts = "2026-05-07T14:01:00Z",
          ),
          RouterEvent(
            "connection_attempt",
            mac = Some(knownMac),
            hostname = Some("khanacademy.org"),
            destIp = Some("5.6.7.8"),
            allowed = Some(true),
            reason = Some("allow"),
            ts = "2026-05-07T14:01:01Z",
          ),
        )
        body = RouterEventsRequest(id, evs).toJson
        resp <- post(routes, "/api/router/events", body, Some(tk))
        rows <- cRepo.listForRouter(id, 100)
      yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(rows.size == 2) &&
        assertTrue(rows.exists(r => r.hostname == "youtube.com" && !r.allowed)) &&
        assertTrue(rows.exists(r => r.hostname == "khanacademy.org" && r.allowed))
    },
    test("events: dhcp_lease for known mac updates devices.last_seen_*") {
      for
        _        <- cleanDb
        rRepo    <- ZIO.service[RouterRepo]
        pRepo    <- ZIO.service[ProfileRepo]
        dRepo    <- ZIO.service[DeviceRepo]
        routes   <- buildRoutes
        _        <- seedKnownDevice(dRepo, pRepo)
        (id, tk) <- seedRouter(rRepo)
        ev   = RouterEvent(
          "dhcp_lease",
          mac = Some(knownMac),
          ip = Some("192.168.1.77"),
          hostname = Some("kid-ipad"),
          ts = "2026-05-07T14:01:13Z",
        )
        body = RouterEventsRequest(id, List(ev)).toJson
        _ <- post(routes, "/api/router/events", body, Some(tk))
        d <- dRepo.findByMac(knownMac)
      yield assertTrue(d.exists(_.lastSeenIp.contains("192.168.1.77"))) &&
        assertTrue(
          d.flatMap(_.lastSeenAt)
            .map(SeenParser.toInstant)
            .contains(Instant.parse("2026-05-07T14:01:13Z")),
        )
    },
    test("events: dhcp_lease for unknown mac upserts a device row with NULL profile_id") {
      for
        _        <- cleanDb
        rRepo    <- ZIO.service[RouterRepo]
        dRepo    <- ZIO.service[DeviceRepo]
        routes   <- buildRoutes
        (id, tk) <- seedRouter(rRepo)
        ev   = RouterEvent(
          "dhcp_lease",
          mac = Some(unknownMac),
          ip = Some("192.168.1.55"),
          hostname = Some("mystery-laptop"),
          ts = "2026-05-07T14:02:00Z",
        )
        body = RouterEventsRequest(id, List(ev)).toJson
        _ <- post(routes, "/api/router/events", body, Some(tk))
        d <- dRepo.findByMac(unknownMac)
      yield assertTrue(d.exists(_.name == "mystery-laptop")) &&
        // unassigned profile means profileId == 0 (the route DAO maps NULL → 0)
        assertTrue(d.exists(_.profileId == 0L)) &&
        assertTrue(d.exists(_.lastSeenIp.contains("192.168.1.55")))
    },
    test("events: dhcp_lease for the same unknown mac twice is idempotent (no duplicate row)") {
      for
        _        <- cleanDb
        rRepo    <- ZIO.service[RouterRepo]
        dRepo    <- ZIO.service[DeviceRepo]
        routes   <- buildRoutes
        (id, tk) <- seedRouter(rRepo)
        ev   = RouterEvent(
          "dhcp_lease",
          mac = Some(unknownMac),
          ip = Some("192.168.1.55"),
          hostname = Some("mystery-laptop"),
          ts = "2026-05-07T14:02:00Z",
        )
        body = RouterEventsRequest(id, List(ev)).toJson
        _   <- post(routes, "/api/router/events", body, Some(tk))
        _   <- post(routes, "/api/router/events", body, Some(tk))
        all <- dRepo.listAll
      yield assertTrue(all.count(_.mac == unknownMac) == 1)
    },
    test("events: first_seen_mac creates an unknown-device row when missing") {
      for
        _        <- cleanDb
        rRepo    <- ZIO.service[RouterRepo]
        dRepo    <- ZIO.service[DeviceRepo]
        routes   <- buildRoutes
        (id, tk) <- seedRouter(rRepo)
        ev   = RouterEvent(
          "first_seen_mac",
          mac = Some("aa:bb:cc:dd:ee:01"),
          ip = Some("192.168.1.61"),
          hostname = None,
          ts = "2026-05-07T14:03:00Z",
        )
        body = RouterEventsRequest(id, List(ev)).toJson
        _ <- post(routes, "/api/router/events", body, Some(tk))
        d <- dRepo.findByMac("aa:bb:cc:dd:ee:01")
      yield assertTrue(d.isDefined) &&
        assertTrue(d.exists(_.profileId == 0L)) &&
        assertTrue(d.exists(_.lastSeenIp.contains("192.168.1.61")))
    },
  ) @@ TestAspect.sequential
}
