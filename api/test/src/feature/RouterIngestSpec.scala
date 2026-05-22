package wifihaven.api.feature

import wifihaven.api.db.*
import wifihaven.api.policy.*
import wifihaven.api.routes.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*
import zio.test.*

import java.time.{Instant, LocalDate, LocalDateTime, OffsetDateTime}
import java.time.format.DateTimeFormatter
import java.util.UUID

private object SeenParser {
  // Postgres `::TEXT` on TIMESTAMPTZ produces "2026-05-07 08:05:00-06". Normalize the
  // separator and pad a 2-digit numeric offset to ±HH:00 so OffsetDateTime can parse it.
  private val fmt                   = DateTimeFormatter.ISO_OFFSET_DATE_TIME
  def toInstant(s: String): Instant = {
    val withT  = s.replace(' ', 'T')
    val padded =
      if withT.matches(""".*[+-]\d{2}$""") then withT + ":00"
      else withT
    OffsetDateTime.parse(padded, fmt).toInstant
  }
}

object RouterIngestSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  // Pin Clock to 2026-05-07 14:00 so PolicyService.snapshot's `today` matches
  // the period_end date used by the ingest fixtures below.
  private val testClockAt: LocalDateTime = LocalDateTime.of(2026, 5, 7, 14, 0, 0)

  override val bootstrap = TestDatabase.layer ++ TestLayers.withClock(testClockAt)

  private def cleanDb = ZIO.serviceWithZIO[EmbeddedPostgres](pg =>
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

  private def buildRoutes =
    for {
      rRepo <- ZIO.service[RouterRepo]
      tRepo <- ZIO.service[TrafficReportRepo]
      tu    <- ZIO.service[TimeUsageRepo]
      dRepo <- ZIO.service[DeviceRepo]
      cRepo <- ZIO.service[ConnectionEventRepo]
      auth = new RouterAuthLive(rRepo)
    } yield RouterIngestRoutes.routes(auth, rRepo, tRepo, tu, dRepo, cRepo)

  private def makePolicyService =
    for {
      pr     <- ZIO.service[ProfileRepo]
      sr     <- ZIO.service[ScheduleRepo]
      hsr    <- ZIO.service[HouseholdSettingsRepo]
      tlr    <- ZIO.service[TimeLimitRepo]
      stlr   <- ZIO.service[SiteTimeLimitRepo]
      dr     <- ZIO.service[DeviceRepo]
      blr    <- ZIO.service[BlocklistRepo]
      trRepo <- ZIO.service[TrafficReportRepo]
      er     <- ZIO.service[TimeExtensionRepo]
      clock  <- ZIO.service[Clock]
    } yield (new PolicyServiceLive(
      pr,
      sr,
      hsr,
      tlr,
      stlr,
      dr,
      blr,
      trRepo,
      er,
      clock,
    )): PolicyService

  private def post(
      routes: Routes[Any, Response],
      path: String,
      body: String,
      token: Option[String],
  ) = {
    val base = Request
      .post(URL.decode(path).toOption.get, Body.fromString(body))
      .addHeader(Header.ContentType(MediaType.application.json))
    val req  = token.fold(base)(t => base.addHeader(Header.Authorization.Bearer(t)))
    routes.runZIO(req)
  }

  // Fixed test instants
  private val periodStart = Instant.parse("2026-05-07T14:00:00Z")
  private val periodEnd   = Instant.parse("2026-05-07T14:05:00Z")
  private val testDate    = LocalDate.of(2026, 5, 7)

  private val knownMac   = "aa:bb:cc:11:22:33"
  private val unknownMac = "aa:bb:cc:99:99:99"

  private def seedKnownDevice(dRepo: DeviceRepo, profileRepo: ProfileRepo): Task[Unit] =
    for {
      pid <- profileRepo.create("Kids", List(BlocklistId.unsafe("adult")))
      _   <- dRepo.upsert(MacAddress.unsafe(knownMac), "kid-ipad", Some(pid), "192.168.1.10")
    } yield ()

  def spec = suite("Router ingest /api/router/*")(
    // ── auth ─────────────────────────────────────────────────────────────────
    test("missing bearer returns 401") {
      for {
        _      <- cleanDb
        routes <- buildRoutes
        body = UsageReport(
          RouterId(UUID.randomUUID()),
          periodStart.toString,
          periodEnd.toString,
          Nil,
        ).toJson
        resp <- post(routes, "/api/router/usage", body, None)
      } yield assertTrue(resp.status == Status.Unauthorized)
    },
    test("invalid bearer returns 401") {
      for {
        _      <- cleanDb
        routes <- buildRoutes
        body = UsageReport(
          RouterId(UUID.randomUUID()),
          periodStart.toString,
          periodEnd.toString,
          Nil,
        ).toJson
        resp <- post(routes, "/api/router/usage", body, Some("not-a-real-token"))
      } yield assertTrue(resp.status == Status.Unauthorized)
    },
    test("valid bearer with empty records returns 200") {
      for {
        _        <- cleanDb
        rRepo    <- ZIO.service[RouterRepo]
        routes   <- buildRoutes
        (id, tk) <- seedRouter(rRepo)
        body = UsageReport(id, periodStart.toString, periodEnd.toString, Nil).toJson
        resp <- post(routes, "/api/router/usage", body, Some(tk))
      } yield assertTrue(resp.status == Status.Ok)
    },

    // ── usage ────────────────────────────────────────────────────────────────
    test(
      "usage: posting same period+mac+hostname twice does not double-count seconds_used or bytes",
    ) {
      for {
        _        <- cleanDb
        rRepo    <- ZIO.service[RouterRepo]
        pRepo    <- ZIO.service[ProfileRepo]
        dRepo    <- ZIO.service[DeviceRepo]
        tu       <- ZIO.service[TimeUsageRepo]
        tRepo    <- ZIO.service[TrafficReportRepo]
        routes   <- buildRoutes
        _        <- seedKnownDevice(dRepo, pRepo)
        (id, tk) <- seedRouter(rRepo)
        rec  = UsageRecord(
          MacAddress.unsafe(knownMac),
          Some(IpAddress.unsafe("192.168.1.10")),
          HostId.Fqdn(Hostname.unsafe("youtube.com")),
          240L,
          1000L,
          500L,
        )
        body = UsageReport(id, periodStart.toString, periodEnd.toString, List(rec)).toJson
        r1   <- post(routes, "/api/router/usage", body, Some(tk))
        r2   <- post(routes, "/api/router/usage", body, Some(tk))
        sb   <- tu.getSecondsAndBytes(
          MacAddress.unsafe(knownMac),
          HostId.Fqdn(Hostname.unsafe("youtube.com")),
          testDate,
        )
        rows <- tRepo.listForRouter(id, 100)
      } yield assertTrue(r1.status == Status.Ok) &&
        assertTrue(r2.status == Status.Ok) &&
        assertTrue(sb == ((240L, 1000L, 500L))) &&
        assertTrue(rows.size == 1)
    },
    test(
      "usage: bytes accumulate across multiple records in one POST (different hostnames same mac)",
    ) {
      for {
        _        <- cleanDb
        rRepo    <- ZIO.service[RouterRepo]
        pRepo    <- ZIO.service[ProfileRepo]
        dRepo    <- ZIO.service[DeviceRepo]
        tu       <- ZIO.service[TimeUsageRepo]
        routes   <- buildRoutes
        _        <- seedKnownDevice(dRepo, pRepo)
        (id, tk) <- seedRouter(rRepo)
        recs = List(
          UsageRecord(
            MacAddress.unsafe(knownMac),
            None,
            HostId.Fqdn(Hostname.unsafe("youtube.com")),
            60L,
            100L,
            50L,
          ),
          UsageRecord(
            MacAddress.unsafe(knownMac),
            None,
            HostId.Fqdn(Hostname.unsafe("google.com")),
            30L,
            200L,
            10L,
          ),
        )
        body = UsageReport(id, periodStart.toString, periodEnd.toString, recs).toJson
        resp <- post(routes, "/api/router/usage", body, Some(tk))
        yt   <- tu.getSecondsAndBytes(
          MacAddress.unsafe(knownMac),
          HostId.Fqdn(Hostname.unsafe("youtube.com")),
          testDate,
        )
        gg   <- tu.getSecondsAndBytes(
          MacAddress.unsafe(knownMac),
          HostId.Fqdn(Hostname.unsafe("google.com")),
          testDate,
        )
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(yt == ((60L, 100L, 50L))) &&
        assertTrue(gg == ((30L, 200L, 10L)))
    },
    test(
      "usage: multiple records with same (mac, hostname) in one POST count seconds_used once",
    ) {
      // The agent emits one record per (mac, dst_ip) — many dst_ips can map
      // to the same hostname (especially the "unknown" bucket before nft_set
      // resolution), and activeSeconds is the bucket duration, not a sum.
      // The combined (mac, hostname) row should advance by one bucket of
      // seconds, but bytes should sum across the per-flow records.
      for {
        _        <- cleanDb
        rRepo    <- ZIO.service[RouterRepo]
        pRepo    <- ZIO.service[ProfileRepo]
        dRepo    <- ZIO.service[DeviceRepo]
        tu       <- ZIO.service[TimeUsageRepo]
        routes   <- buildRoutes
        _        <- seedKnownDevice(dRepo, pRepo)
        (id, tk) <- seedRouter(rRepo)
        recs = List(
          UsageRecord(
            MacAddress.unsafe(knownMac),
            None,
            HostId.Fqdn(Hostname.unsafe("unknown")),
            300L,
            100L,
            50L,
          ),
          UsageRecord(
            MacAddress.unsafe(knownMac),
            None,
            HostId.Fqdn(Hostname.unsafe("unknown")),
            300L,
            200L,
            10L,
          ),
          UsageRecord(
            MacAddress.unsafe(knownMac),
            None,
            HostId.Fqdn(Hostname.unsafe("unknown")),
            300L,
            50L,
            0L,
          ),
        )
        body = UsageReport(id, periodStart.toString, periodEnd.toString, recs).toJson
        resp <- post(routes, "/api/router/usage", body, Some(tk))
        sb   <- tu.getSecondsAndBytes(
          MacAddress.unsafe(knownMac),
          HostId.Fqdn(Hostname.unsafe("unknown")),
          testDate,
        )
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(sb == ((300L, 350L, 60L)))
    },
    test("usage: #715 — applyDelta writes byte-share-weighted proportional_seconds per host") {
      // Two hosts in the same batch with an 80/20 byte split. seconds_used is
      // bucket-max for each host (= the full bucket duration the agent saw on
      // that host), but proportional_seconds splits the bucket by byte share so
      // the heavy host gets most of the wall-clock attribution.
      for {
        _        <- cleanDb
        rRepo    <- ZIO.service[RouterRepo]
        pRepo    <- ZIO.service[ProfileRepo]
        dRepo    <- ZIO.service[DeviceRepo]
        tu       <- ZIO.service[TimeUsageRepo]
        routes   <- buildRoutes
        _        <- seedKnownDevice(dRepo, pRepo)
        (id, tk) <- seedRouter(rRepo)
        recs = List(
          // youtube: 300s bucket, 800 bytes
          UsageRecord(
            MacAddress.unsafe(knownMac),
            None,
            HostId.Fqdn(Hostname.unsafe("youtube.com")),
            300L,
            500L,
            300L,
          ),
          // icloud: 300s bucket, 200 bytes
          UsageRecord(
            MacAddress.unsafe(knownMac),
            None,
            HostId.Fqdn(Hostname.unsafe("icloud.com")),
            300L,
            150L,
            50L,
          ),
        )
        body = UsageReport(id, periodStart.toString, periodEnd.toString, recs).toJson
        resp   <- post(routes, "/api/router/usage", body, Some(tk))
        // Bucket-presence is unchanged: each host still credits the full 300s.
        ytSb   <- tu.getSecondsAndBytes(
          MacAddress.unsafe(knownMac),
          HostId.Fqdn(Hostname.unsafe("youtube.com")),
          testDate,
        )
        icSb   <- tu.getSecondsAndBytes(
          MacAddress.unsafe(knownMac),
          HostId.Fqdn(Hostname.unsafe("icloud.com")),
          testDate,
        )
        ytProp <- tu.getProportionalSeconds(
          MacAddress.unsafe(knownMac),
          HostId.Fqdn(Hostname.unsafe("youtube.com")),
          testDate,
        )
        icProp <- tu.getProportionalSeconds(
          MacAddress.unsafe(knownMac),
          HostId.Fqdn(Hostname.unsafe("icloud.com")),
          testDate,
        )
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(ytSb._1 == 300L && icSb._1 == 300L) &&
        // bucket=300s. youtube share = 800/1000 → 240s. icloud share = 200/1000 → 60s.
        assertTrue(ytProp == 240L) &&
        assertTrue(icProp == 60L) &&
        // The two proportional values reconcile back to the bucket wall-clock duration.
        assertTrue(ytProp + icProp == 300L)
    },
    test("usage: #715 — single-host batches credit full bucket to proportional_seconds") {
      // No competing hosts → byte share is 100% → proportional_seconds equals
      // seconds_used. Guarantees we don't regress for the common case.
      for {
        _        <- cleanDb
        rRepo    <- ZIO.service[RouterRepo]
        pRepo    <- ZIO.service[ProfileRepo]
        dRepo    <- ZIO.service[DeviceRepo]
        tu       <- ZIO.service[TimeUsageRepo]
        routes   <- buildRoutes
        _        <- seedKnownDevice(dRepo, pRepo)
        (id, tk) <- seedRouter(rRepo)
        rec  = UsageRecord(
          MacAddress.unsafe(knownMac),
          None,
          HostId.Fqdn(Hostname.unsafe("youtube.com")),
          300L,
          1000L,
          500L,
        )
        body = UsageReport(id, periodStart.toString, periodEnd.toString, List(rec)).toJson
        _    <- post(routes, "/api/router/usage", body, Some(tk))
        sb   <- tu.getSecondsAndBytes(
          MacAddress.unsafe(knownMac),
          HostId.Fqdn(Hostname.unsafe("youtube.com")),
          testDate,
        )
        prop <- tu.getProportionalSeconds(
          MacAddress.unsafe(knownMac),
          HostId.Fqdn(Hostname.unsafe("youtube.com")),
          testDate,
        )
      } yield assertTrue(sb._1 == 300L) && assertTrue(prop == 300L)
    },
    test("usage: seconds add across distinct periods for same (mac, hostname)") {
      for {
        _        <- cleanDb
        rRepo    <- ZIO.service[RouterRepo]
        pRepo    <- ZIO.service[ProfileRepo]
        dRepo    <- ZIO.service[DeviceRepo]
        tu       <- ZIO.service[TimeUsageRepo]
        routes   <- buildRoutes
        _        <- seedKnownDevice(dRepo, pRepo)
        (id, tk) <- seedRouter(rRepo)
        rec = UsageRecord(
          MacAddress.unsafe(knownMac),
          None,
          HostId.Fqdn(Hostname.unsafe("youtube.com")),
          120L,
          1L,
          1L,
        )
        b1  = UsageReport(id, "2026-05-07T14:00:00Z", "2026-05-07T14:05:00Z", List(rec)).toJson
        b2  = UsageReport(id, "2026-05-07T14:05:00Z", "2026-05-07T14:10:00Z", List(rec)).toJson
        _  <- post(routes, "/api/router/usage", b1, Some(tk))
        _  <- post(routes, "/api/router/usage", b2, Some(tk))
        sb <- tu.getSecondsAndBytes(
          MacAddress.unsafe(knownMac),
          HostId.Fqdn(Hostname.unsafe("youtube.com")),
          testDate,
        )
      } yield assertTrue(sb == ((240L, 2L, 2L)))
    },
    test("usage: known mac last_seen_at is set to period_end and last_seen_ip to record.ip") {
      for {
        _        <- cleanDb
        rRepo    <- ZIO.service[RouterRepo]
        pRepo    <- ZIO.service[ProfileRepo]
        dRepo    <- ZIO.service[DeviceRepo]
        routes   <- buildRoutes
        _        <- seedKnownDevice(dRepo, pRepo)
        (id, tk) <- seedRouter(rRepo)
        rec  = UsageRecord(
          MacAddress.unsafe(knownMac),
          Some(IpAddress.unsafe("192.168.1.42")),
          HostId.Fqdn(Hostname.unsafe("youtube.com")),
          60L,
          1L,
          1L,
        )
        body = UsageReport(id, periodStart.toString, periodEnd.toString, List(rec)).toJson
        _ <- post(routes, "/api/router/usage", body, Some(tk))
        d <- dRepo.findByMac(MacAddress.unsafe(knownMac))
      } yield assertTrue(d.exists(_.lastSeenIp.contains(IpAddress.unsafe("192.168.1.42")))) &&
        assertTrue(d.flatMap(_.lastSeenAt).map(SeenParser.toInstant).contains(periodEnd))
    },
    test("usage: unknown mac in records does NOT create a device row (events does that)") {
      for {
        _        <- cleanDb
        rRepo    <- ZIO.service[RouterRepo]
        dRepo    <- ZIO.service[DeviceRepo]
        routes   <- buildRoutes
        (id, tk) <- seedRouter(rRepo)
        rec  = UsageRecord(
          MacAddress.unsafe(unknownMac),
          Some(IpAddress.unsafe("192.168.1.99")),
          HostId.Fqdn(Hostname.unsafe("ads.example.com")),
          10L,
          1L,
          1L,
        )
        body = UsageReport(id, periodStart.toString, periodEnd.toString, List(rec)).toJson
        resp <- post(routes, "/api/router/usage", body, Some(tk))
        d    <- dRepo.findByMac(MacAddress.unsafe(unknownMac))
      } yield assertTrue(resp.status == Status.Ok) && assertTrue(d.isEmpty)
    },

    // ── events ───────────────────────────────────────────────────────────────
    test("events: connection_attempt batch is recorded with allowed/reason") {
      for {
        _        <- cleanDb
        rRepo    <- ZIO.service[RouterRepo]
        cRepo    <- ZIO.service[ConnectionEventRepo]
        routes   <- buildRoutes
        (id, tk) <- seedRouter(rRepo)
        evs  = List(
          RouterEvent(
            "connection_attempt",
            mac = Some(MacAddress.unsafe(knownMac)),
            host = Some(HostId.Fqdn(Hostname.unsafe("youtube.com"))),
            destIp = Some(IpAddress.unsafe("1.2.3.4")),
            allowed = Some(false),
            reason = Some("category:adult"),
            ts = "2026-05-07T14:01:00Z",
          ),
          RouterEvent(
            "connection_attempt",
            mac = Some(MacAddress.unsafe(knownMac)),
            host = Some(HostId.Fqdn(Hostname.unsafe("khanacademy.org"))),
            destIp = Some(IpAddress.unsafe("5.6.7.8")),
            allowed = Some(true),
            reason = Some("allow"),
            ts = "2026-05-07T14:01:01Z",
          ),
        )
        body = RouterEventsRequest(id, evs).toJson
        resp <- post(routes, "/api/router/events", body, Some(tk))
        rows <- cRepo.listForRouter(id, 100)
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(rows.size == 2) &&
        assertTrue(
          rows.exists(r => r.host == HostId.Fqdn(Hostname.unsafe("youtube.com")) && !r.allowed),
        ) &&
        assertTrue(
          rows.exists(r => r.host == HostId.Fqdn(Hostname.unsafe("khanacademy.org")) && r.allowed),
        )
    },
    // ── #338: connection_events idempotency on retry-queue replay ────────────
    test("events: identical connection_attempt batch POSTed twice inserts once") {
      val eid1 = UUID.randomUUID()
      val eid2 = UUID.randomUUID()
      for {
        _        <- cleanDb
        rRepo    <- ZIO.service[RouterRepo]
        cRepo    <- ZIO.service[ConnectionEventRepo]
        routes   <- buildRoutes
        (id, tk) <- seedRouter(rRepo)
        evs  = List(
          RouterEvent(
            "connection_attempt",
            mac = Some(MacAddress.unsafe(knownMac)),
            host = Some(HostId.Fqdn(Hostname.unsafe("youtube.com"))),
            destIp = Some(IpAddress.unsafe("1.2.3.4")),
            allowed = Some(false),
            reason = Some("blocked"),
            ts = "2026-05-07T14:01:00Z",
            eventId = Some(eid1),
          ),
          RouterEvent(
            "connection_attempt",
            mac = Some(MacAddress.unsafe(knownMac)),
            host = Some(HostId.Fqdn(Hostname.unsafe("khanacademy.org"))),
            destIp = Some(IpAddress.unsafe("5.6.7.8")),
            allowed = Some(true),
            reason = Some("allow"),
            ts = "2026-05-07T14:01:01Z",
            eventId = Some(eid2),
          ),
        )
        body = RouterEventsRequest(id, evs).toJson
        r1   <- post(routes, "/api/router/events", body, Some(tk))
        r2   <- post(routes, "/api/router/events", body, Some(tk))
        rows <- cRepo.listForRouter(id, 100)
      } yield assertTrue(r1.status == Status.Ok) &&
        assertTrue(r2.status == Status.Ok) &&
        assertTrue(rows.size == 2)
    },
    test("events: mixed batch (some seen, some new) inserts only the new ones") {
      val eidOld                                    = UUID.randomUUID()
      val eidNew                                    = UUID.randomUUID()
      def mkEv(host: String, ts: String, eid: UUID) = RouterEvent(
        "connection_attempt",
        mac = Some(MacAddress.unsafe(knownMac)),
        host = Some(HostId.Fqdn(Hostname.unsafe(host))),
        destIp = Some(IpAddress.unsafe("1.2.3.4")),
        allowed = Some(true),
        reason = Some("allow"),
        ts = ts,
        eventId = Some(eid),
      )
      for {
        _        <- cleanDb
        rRepo    <- ZIO.service[RouterRepo]
        cRepo    <- ZIO.service[ConnectionEventRepo]
        routes   <- buildRoutes
        (id, tk) <- seedRouter(rRepo)
        first  = RouterEventsRequest(
          id,
          List(mkEv("youtube.com", "2026-05-07T14:01:00Z", eidOld)),
        ).toJson
        second = RouterEventsRequest(
          id,
          List(
            mkEv("youtube.com", "2026-05-07T14:01:00Z", eidOld), // duplicate
            mkEv("khanacademy.org", "2026-05-07T14:01:01Z", eidNew),
          ),
        ).toJson
        _ <- post(routes, "/api/router/events", first, Some(tk))
        _    <- post(routes, "/api/router/events", second, Some(tk))
        rows <- cRepo.listForRouter(id, 100)
      } yield assertTrue(rows.size == 2) &&
        assertTrue(rows.exists(_.host == HostId.Fqdn(Hostname.unsafe("youtube.com")))) &&
        assertTrue(rows.exists(_.host == HostId.Fqdn(Hostname.unsafe("khanacademy.org"))))
    },
    test("events: older agent without eventId inserts (server-side default UUID)") {
      // Forward-compat: agents predating #338 omit eventId; the API must still
      // accept the batch and insert via gen_random_uuid() DEFAULT. Same batch
      // twice DOES duplicate in this fallback mode — there's no client key to
      // dedup on — but the goal here is just "no rejection".
      for {
        _        <- cleanDb
        rRepo    <- ZIO.service[RouterRepo]
        cRepo    <- ZIO.service[ConnectionEventRepo]
        routes   <- buildRoutes
        (id, tk) <- seedRouter(rRepo)
        ev   = RouterEvent(
          "connection_attempt",
          mac = Some(MacAddress.unsafe(knownMac)),
          host = Some(HostId.Fqdn(Hostname.unsafe("example.com"))),
          destIp = Some(IpAddress.unsafe("9.9.9.9")),
          allowed = Some(true),
          reason = Some("allow"),
          ts = "2026-05-07T14:01:00Z",
          // eventId left as None (default) — older-agent path
        )
        body = RouterEventsRequest(id, List(ev)).toJson
        r1   <- post(routes, "/api/router/events", body, Some(tk))
        rows <- cRepo.listForRouter(id, 100)
      } yield assertTrue(r1.status == Status.Ok) && assertTrue(rows.size == 1)
    },
    test("events: dhcp_lease for known mac updates devices.last_seen_*") {
      for {
        _        <- cleanDb
        rRepo    <- ZIO.service[RouterRepo]
        pRepo    <- ZIO.service[ProfileRepo]
        dRepo    <- ZIO.service[DeviceRepo]
        routes   <- buildRoutes
        _        <- seedKnownDevice(dRepo, pRepo)
        (id, tk) <- seedRouter(rRepo)
        ev   = RouterEvent(
          "dhcp_lease",
          mac = Some(MacAddress.unsafe(knownMac)),
          ip = Some(IpAddress.unsafe("192.168.1.77")),
          hostname = Some(Hostname.unsafe("kid-ipad")),
          ts = "2026-05-07T14:01:13Z",
        )
        body = RouterEventsRequest(id, List(ev)).toJson
        _ <- post(routes, "/api/router/events", body, Some(tk))
        d <- dRepo.findByMac(MacAddress.unsafe(knownMac))
      } yield assertTrue(d.exists(_.lastSeenIp.contains(IpAddress.unsafe("192.168.1.77")))) &&
        assertTrue(
          d.flatMap(_.lastSeenAt)
            .map(SeenParser.toInstant)
            .contains(Instant.parse("2026-05-07T14:01:13Z")),
        )
    },
    test("events: dhcp_lease for unknown mac upserts a device row with NULL profile_id") {
      for {
        _        <- cleanDb
        rRepo    <- ZIO.service[RouterRepo]
        dRepo    <- ZIO.service[DeviceRepo]
        routes   <- buildRoutes
        (id, tk) <- seedRouter(rRepo)
        ev   = RouterEvent(
          "dhcp_lease",
          mac = Some(MacAddress.unsafe(unknownMac)),
          ip = Some(IpAddress.unsafe("192.168.1.55")),
          hostname = Some(Hostname.unsafe("mystery-laptop")),
          ts = "2026-05-07T14:02:00Z",
        )
        body = RouterEventsRequest(id, List(ev)).toJson
        _ <- post(routes, "/api/router/events", body, Some(tk))
        d <- dRepo.findByMac(MacAddress.unsafe(unknownMac))
      } yield assertTrue(d.exists(_.name == "mystery-laptop")) &&
        assertTrue(d.exists(_.profileId.isEmpty)) &&
        assertTrue(d.exists(_.lastSeenIp.contains(IpAddress.unsafe("192.168.1.55"))))
    },
    test("events: dhcp_lease for the same unknown mac twice is idempotent (no duplicate row)") {
      for {
        _        <- cleanDb
        rRepo    <- ZIO.service[RouterRepo]
        dRepo    <- ZIO.service[DeviceRepo]
        routes   <- buildRoutes
        (id, tk) <- seedRouter(rRepo)
        ev   = RouterEvent(
          "dhcp_lease",
          mac = Some(MacAddress.unsafe(unknownMac)),
          ip = Some(IpAddress.unsafe("192.168.1.55")),
          hostname = Some(Hostname.unsafe("mystery-laptop")),
          ts = "2026-05-07T14:02:00Z",
        )
        body = RouterEventsRequest(id, List(ev)).toJson
        _   <- post(routes, "/api/router/events", body, Some(tk))
        _   <- post(routes, "/api/router/events", body, Some(tk))
        all <- dRepo.listAll
      } yield assertTrue(all.count(_.mac == MacAddress.unsafe(unknownMac)) == 1)
    },
    // ── ingest → policy round-trip (pins #135 fix) ───────────────────────────
    test("usage→policy: 90 active_seconds is reflected as timeUsedToday >= dailyMinutes") {
      // Pins the snapshotAll fix in #135: before the fix, snapshotAll read only
      // the legacy minutes_used column and ignored seconds_used (written by the
      // OpenWRT /api/router/usage path), so timeUsedToday.totalMinutes was 0
      // and the agent never saw the daily limit being exceeded.
      for {
        _        <- cleanDb
        rRepo    <- ZIO.service[RouterRepo]
        pRepo    <- ZIO.service[ProfileRepo]
        dRepo    <- ZIO.service[DeviceRepo]
        tlr      <- ZIO.service[TimeLimitRepo]
        ber      <- ZIO.service[BlockEventRepo]
        ingest   <- buildRoutes
        ps       <- makePolicyService
        // 1. Profile with dailyMinutes = 1
        pid      <- pRepo.create("Kids", List.empty)
        _        <- tlr.upsert(pid, 1)
        // 2. Device assigned to that profile
        _        <- dRepo.upsert(MacAddress.unsafe(knownMac), "kid-ipad", Some(pid), "192.168.1.10")
        // 3. Router (RouterAuth.sha256Hex == PolicyService.hashToken, so the
        //    same bearer authenticates against both ingest and policy routes).
        (id, tk) <- seedRouter(rRepo)
        policy = RouterRoutes.routes(rRepo, ps, RouterAuthLive(rRepo), ber)
        // 4. POST /api/router/usage with 90 active seconds. Bytes are well above the
        //    default heartbeat filter threshold (#789: 10 KB) so the row isn't dropped.
        rec    = UsageRecord(
          MacAddress.unsafe(knownMac),
          Some(IpAddress.unsafe("192.168.1.42")),
          HostId.Fqdn(Hostname.unsafe("youtube.com")),
          90L,
          100_000L,
          100_000L,
        )
        body   = UsageReport(id, periodStart.toString, periodEnd.toString, List(rec)).toJson
        ingestResp <- post(ingest, "/api/router/usage", body, Some(tk))
        // 5. GET /api/router/policy
        polResp    <- policy.runZIO(
          Request
            .get(URL.decode("/api/router/policy").toOption.get)
            .addHeader(Header.Authorization.Bearer(tk)),
        )
        polBody    <- polResp.body.asString
        snap       <- ZIO.fromEither(polBody.fromJson[PolicySnapshot])
        kp = snap.profiles(pid)
        // 6. Verify last_seen_ip update
        d <- dRepo.findByMac(MacAddress.unsafe(knownMac))
      } yield assertTrue(ingestResp.status == Status.Ok) &&
        assertTrue(polResp.status == Status.Ok) &&
        // #354: dailyMinutes / timeUsedToday no longer ship on the wire —
        // their effect is collapsed into BlockRules.blocked. With 90s of
        // active time against a 1-minute limit, the profile is blocked.
        assertTrue(kp.rules.blocked) &&
        assertTrue(kp.rules.blockReason.contains(MacBlockReason.TimeLimit)) &&
        assertTrue(d.exists(_.lastSeenIp.contains(IpAddress.unsafe("192.168.1.42"))))
    },
    test("events: accepts the raw JSON shape the OpenWRT Lua agent emits (regression for #215)") {
      // Verbatim payload shape produced by openwrt/files/usr/lib/lua/wifihaven/conntrack.lua
      // build_event + jsonc.stringify({ routerId, events }). Hand-rolled rather than going
      // through RouterEventsRequest.toJson so that any drift in case-class field names
      // (snake_case → camelCase regressions etc.) is caught by deserialization here.
      for {
        _        <- cleanDb
        rRepo    <- ZIO.service[RouterRepo]
        cRepo    <- ZIO.service[ConnectionEventRepo]
        routes   <- buildRoutes
        (id, tk) <- seedRouter(rRepo)
        rawBody = s"""{
          "routerId":"$id",
          "events":[
            {"type":"connection_attempt","mac":"$knownMac","host":{"type":"fqdn","value":"youtube.com"},
             "destIp":"1.2.3.4","allowed":false,"reason":"category:adult",
             "ts":"2026-05-07T14:01:14Z"}
          ]
        }"""
        resp <- post(routes, "/api/router/events", rawBody, Some(tk))
        rows <- cRepo.listForRouter(id, 100)
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(rows.size == 1) &&
        assertTrue(
          rows.exists(r => r.host == HostId.Fqdn(Hostname.unsafe("youtube.com")) && !r.allowed),
        )
    },
    test("events: first_seen_mac creates an unknown-device row when missing") {
      for {
        _        <- cleanDb
        rRepo    <- ZIO.service[RouterRepo]
        dRepo    <- ZIO.service[DeviceRepo]
        routes   <- buildRoutes
        (id, tk) <- seedRouter(rRepo)
        ev   = RouterEvent(
          "first_seen_mac",
          mac = Some(MacAddress.unsafe("aa:bb:cc:dd:ee:01")),
          ip = Some(IpAddress.unsafe("192.168.1.61")),
          hostname = None,
          ts = "2026-05-07T14:03:00Z",
        )
        body = RouterEventsRequest(id, List(ev)).toJson
        _ <- post(routes, "/api/router/events", body, Some(tk))
        d <- dRepo.findByMac(MacAddress.unsafe("aa:bb:cc:dd:ee:01"))
      } yield assertTrue(d.isDefined) &&
        assertTrue(d.exists(_.profileId.isEmpty)) &&
        assertTrue(d.exists(_.lastSeenIp.contains(IpAddress.unsafe("192.168.1.61")))) &&
        // #249: when no hostname is provided we now generate a disambiguable
        // placeholder name from the MAC instead of the literal string "unknown".
        assertTrue(d.exists(_.name == "device-ddee01"))
    },

    // ── #249: late-arriving DHCP lease renames auto-generated devices ────────
    test("events: dhcp_lease renames a device whose name is literal 'unknown' (legacy rows)") {
      for {
        _        <- cleanDb
        rRepo    <- ZIO.service[RouterRepo]
        dRepo    <- ZIO.service[DeviceRepo]
        routes   <- buildRoutes
        (id, tk) <- seedRouter(rRepo)
        // Pre-existing row with the legacy "unknown" name (e.g. created before
        // the device-XXYYZZ change shipped).
        _        <- dRepo.upsertUnknown(
          MacAddress.unsafe(unknownMac),
          "unknown",
          Some(IpAddress.unsafe("192.168.1.55")),
          periodStart,
        )
        ev   = RouterEvent(
          "dhcp_lease",
          mac = Some(MacAddress.unsafe(unknownMac)),
          ip = Some(IpAddress.unsafe("192.168.1.55")),
          hostname = Some(Hostname.unsafe("kid-phone")),
          ts = "2026-05-07T14:02:30Z",
        )
        body = RouterEventsRequest(id, List(ev)).toJson
        _ <- post(routes, "/api/router/events", body, Some(tk))
        d <- dRepo.findByMac(MacAddress.unsafe(unknownMac))
      } yield assertTrue(d.exists(_.name == "kid-phone"))
    },
    test("events: dhcp_lease renames a device whose name matches device-XXYYZZ") {
      for {
        _        <- cleanDb
        rRepo    <- ZIO.service[RouterRepo]
        dRepo    <- ZIO.service[DeviceRepo]
        routes   <- buildRoutes
        (id, tk) <- seedRouter(rRepo)
        _        <- dRepo.upsertUnknown(
          MacAddress.unsafe(unknownMac),
          "device-999999",
          Some(IpAddress.unsafe("192.168.1.55")),
          periodStart,
        )
        ev   = RouterEvent(
          "dhcp_lease",
          mac = Some(MacAddress.unsafe(unknownMac)),
          ip = Some(IpAddress.unsafe("192.168.1.55")),
          hostname = Some(Hostname.unsafe("kid-phone")),
          ts = "2026-05-07T14:02:30Z",
        )
        body = RouterEventsRequest(id, List(ev)).toJson
        _ <- post(routes, "/api/router/events", body, Some(tk))
        d <- dRepo.findByMac(MacAddress.unsafe(unknownMac))
      } yield assertTrue(d.exists(_.name == "kid-phone"))
    },
    // ── #415: older agents may still POST clockSkewSeconds; API discards it ──
    test(
      "usage: an older agent's POST containing clockSkewSeconds is accepted (field is discarded)",
    ) {
      // Per #376 deprecation policy, the API must keep accepting older
      // wire-format fields during the rollout window so a stale agent doesn't
      // start 400-ing the moment the API is upgraded. The field is silently
      // dropped — the column was removed in V14 (#415).
      for {
        _        <- cleanDb
        rRepo    <- ZIO.service[RouterRepo]
        routes   <- buildRoutes
        (id, tk) <- seedRouter(rRepo)
        rawBody =
          s"""{"routerId":"$id","periodStart":"${periodStart.toString}","periodEnd":"${periodEnd.toString}","records":[],"clockSkewSeconds":120}"""
        resp <- post(routes, "/api/router/usage", rawBody, Some(tk))
      } yield assertTrue(resp.status == Status.Ok)
    },
    test("events: dhcp_lease does NOT clobber an admin-set device name") {
      for {
        _        <- cleanDb
        rRepo    <- ZIO.service[RouterRepo]
        pRepo    <- ZIO.service[ProfileRepo]
        dRepo    <- ZIO.service[DeviceRepo]
        routes   <- buildRoutes
        _        <- seedKnownDevice(dRepo, pRepo) // creates knownMac with name "kid-ipad"
        (id, tk) <- seedRouter(rRepo)
        // dnsmasq reports a generic hostname like "android-1234" — must not overwrite
        // the curated name the admin chose in the UI.
        ev   = RouterEvent(
          "dhcp_lease",
          mac = Some(MacAddress.unsafe(knownMac)),
          ip = Some(IpAddress.unsafe("192.168.1.10")),
          hostname = Some(Hostname.unsafe("android-1234")),
          ts = "2026-05-07T14:02:30Z",
        )
        body = RouterEventsRequest(id, List(ev)).toJson
        _ <- post(routes, "/api/router/events", body, Some(tk))
        d <- dRepo.findByMac(MacAddress.unsafe(knownMac))
      } yield assertTrue(d.exists(_.name == "kid-ipad"))
    },
    // ── #720: defense-in-depth API-side FQDN backfill ──────────────────────
    test("events: ipv4-typed event is backfilled when a sibling fqdn event lands later") {
      // Race-loser arrives first as ipv4. A subsequent fqdn event for the
      // same (router_id, dest_ip) within the window teaches the API the
      // mapping; the earlier row's host should now read as the resolved
      // fqdn via SELECT-side coalesce.
      for {
        _        <- cleanDb
        rRepo    <- ZIO.service[RouterRepo]
        cRepo    <- ZIO.service[ConnectionEventRepo]
        routes   <- buildRoutes
        (id, tk) <- seedRouter(rRepo)
        evIp   = RouterEvent(
          "connection_attempt",
          mac = Some(MacAddress.unsafe(knownMac)),
          host = Some(HostId.IPv4(IpAddress.unsafe("34.223.124.45"))),
          destIp = Some(IpAddress.unsafe("34.223.124.45")),
          allowed = Some(false),
          reason = Some("blocked"),
          ts = "2026-05-07T14:01:00Z",
          eventId = Some(UUID.randomUUID()),
        )
        evFqdn = RouterEvent(
          "connection_attempt",
          mac = Some(MacAddress.unsafe(knownMac)),
          host = Some(HostId.Fqdn(Hostname.unsafe("neverssl.com"))),
          destIp = Some(IpAddress.unsafe("34.223.124.45")),
          allowed = Some(false),
          reason = Some("blocked"),
          ts = "2026-05-07T14:01:05Z",
          eventId = Some(UUID.randomUUID()),
        )
        _    <- post(
          routes,
          "/api/router/events",
          RouterEventsRequest(id, List(evIp)).toJson,
          Some(tk),
        )
        _    <- post(
          routes,
          "/api/router/events",
          RouterEventsRequest(id, List(evFqdn)).toJson,
          Some(tk),
        )
        rows <- cRepo.listForRouter(id, 100)
      } yield assertTrue(rows.size == 2) &&
        // both rows now surface as the resolved fqdn (the ipv4 row via
        // resolved_host_value coalesce; the fqdn row natively)
        assertTrue(rows.forall(_.host == HostId.Fqdn(Hostname.unsafe("neverssl.com"))))
    },
    test("events: ipv4-typed event picks up resolution from a prior fqdn event") {
      // Reverse arrival order: fqdn event first, ipv4 event arrives later.
      // The ipv4 row is inserted with resolved_host_value already populated.
      for {
        _        <- cleanDb
        rRepo    <- ZIO.service[RouterRepo]
        cRepo    <- ZIO.service[ConnectionEventRepo]
        routes   <- buildRoutes
        (id, tk) <- seedRouter(rRepo)
        evFqdn = RouterEvent(
          "connection_attempt",
          mac = Some(MacAddress.unsafe(knownMac)),
          host = Some(HostId.Fqdn(Hostname.unsafe("neverssl.com"))),
          destIp = Some(IpAddress.unsafe("34.223.124.45")),
          allowed = Some(true),
          reason = Some("allow"),
          ts = "2026-05-07T14:01:00Z",
          eventId = Some(UUID.randomUUID()),
        )
        evIp   = RouterEvent(
          "connection_attempt",
          mac = Some(MacAddress.unsafe(knownMac)),
          host = Some(HostId.IPv4(IpAddress.unsafe("34.223.124.45"))),
          destIp = Some(IpAddress.unsafe("34.223.124.45")),
          allowed = Some(true),
          reason = Some("allow"),
          ts = "2026-05-07T14:01:05Z",
          eventId = Some(UUID.randomUUID()),
        )
        _    <- post(
          routes,
          "/api/router/events",
          RouterEventsRequest(id, List(evFqdn)).toJson,
          Some(tk),
        )
        _    <- post(
          routes,
          "/api/router/events",
          RouterEventsRequest(id, List(evIp)).toJson,
          Some(tk),
        )
        rows <- cRepo.listForRouter(id, 100)
      } yield assertTrue(rows.size == 2) &&
        assertTrue(rows.forall(_.host == HostId.Fqdn(Hostname.unsafe("neverssl.com"))))
    },
    test("events: different routers with same dest_ip do not cross-attribute") {
      // Per-router isolation: a fqdn observation on router A must NOT
      // resolve an ipv4 event on router B. Reused-IP cloud endpoints
      // would otherwise produce wrong attributions across households.
      for {
        _      <- cleanDb
        rRepo  <- ZIO.service[RouterRepo]
        cRepo  <- ZIO.service[ConnectionEventRepo]
        routes <- buildRoutes
        // seedRouter only seeds one — manually seed two
        tkA = "ROUTER_TOKEN_A"
        tkB = "ROUTER_TOKEN_B"
        idA <- rRepo.create("router-a", Sha256Hex.unsafe("a" * 64))
        _   <- rRepo.completeEnrollment(idA, Sha256Hex.unsafe(RouterAuth.sha256Hex(tkA)))
        idB <- rRepo.create("router-b", Sha256Hex.unsafe("b" * 64))
        _   <- rRepo.completeEnrollment(idB, Sha256Hex.unsafe(RouterAuth.sha256Hex(tkB)))
        evFqdnA = RouterEvent(
          "connection_attempt",
          mac = Some(MacAddress.unsafe(knownMac)),
          host = Some(HostId.Fqdn(Hostname.unsafe("not-neverssl.example"))),
          destIp = Some(IpAddress.unsafe("34.223.124.45")),
          allowed = Some(true),
          reason = Some("allow"),
          ts = "2026-05-07T14:01:00Z",
          eventId = Some(UUID.randomUUID()),
        )
        evIpB   = RouterEvent(
          "connection_attempt",
          mac = Some(MacAddress.unsafe(knownMac)),
          host = Some(HostId.IPv4(IpAddress.unsafe("34.223.124.45"))),
          destIp = Some(IpAddress.unsafe("34.223.124.45")),
          allowed = Some(true),
          reason = Some("allow"),
          ts = "2026-05-07T14:01:05Z",
          eventId = Some(UUID.randomUUID()),
        )
        _     <- post(
          routes,
          "/api/router/events",
          RouterEventsRequest(idA, List(evFqdnA)).toJson,
          Some(tkA),
        )
        _     <- post(
          routes,
          "/api/router/events",
          RouterEventsRequest(idB, List(evIpB)).toJson,
          Some(tkB),
        )
        rowsB <- cRepo.listForRouter(idB, 100)
      } yield assertTrue(rowsB.size == 1) &&
        // router B's ipv4 row must NOT inherit router A's fqdn
        assertTrue(rowsB.head.host == HostId.IPv4(IpAddress.unsafe("34.223.124.45")))
    },
    test(
      "events: fqdn arriving outside the backfill window does not attribute the earlier ipv4 row",
    ) {
      // The backfill is for the conntrack/dns-tail race window — not a
      // long-running cache. A fqdn observed 10 minutes after an ipv4 event
      // for the same dest_ip is too late and must not retroactively
      // re-label what is more plausibly a fresh-DNS-resolution flow.
      for {
        _        <- cleanDb
        rRepo    <- ZIO.service[RouterRepo]
        cRepo    <- ZIO.service[ConnectionEventRepo]
        routes   <- buildRoutes
        (id, tk) <- seedRouter(rRepo)
        evIp   = RouterEvent(
          "connection_attempt",
          mac = Some(MacAddress.unsafe(knownMac)),
          host = Some(HostId.IPv4(IpAddress.unsafe("34.223.124.45"))),
          destIp = Some(IpAddress.unsafe("34.223.124.45")),
          allowed = Some(true),
          reason = Some("allow"),
          ts = "2026-05-07T14:01:00Z",
          eventId = Some(UUID.randomUUID()),
        )
        // 10 minutes later — outside the 5-minute window
        evFqdn = RouterEvent(
          "connection_attempt",
          mac = Some(MacAddress.unsafe(knownMac)),
          host = Some(HostId.Fqdn(Hostname.unsafe("neverssl.com"))),
          destIp = Some(IpAddress.unsafe("34.223.124.45")),
          allowed = Some(true),
          reason = Some("allow"),
          ts = "2026-05-07T14:11:00Z",
          eventId = Some(UUID.randomUUID()),
        )
        _    <- post(
          routes,
          "/api/router/events",
          RouterEventsRequest(id, List(evIp)).toJson,
          Some(tk),
        )
        _    <- post(
          routes,
          "/api/router/events",
          RouterEventsRequest(id, List(evFqdn)).toJson,
          Some(tk),
        )
        rows <- cRepo.listForRouter(id, 100)
      } yield assertTrue(rows.size == 2) &&
        // ipv4 row stays unresolved (the late fqdn doesn't reach it)
        assertTrue(
          rows.exists(r => r.host == HostId.IPv4(IpAddress.unsafe("34.223.124.45"))),
        ) &&
        assertTrue(rows.exists(_.host == HostId.Fqdn(Hostname.unsafe("neverssl.com"))))
    },
  ) @@ TestAspect.sequential
}
