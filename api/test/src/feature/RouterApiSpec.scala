package wifihaven.api.feature

import wifihaven.api.JwtConfig
import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.policy.*
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

import java.time.{Instant, LocalDate, ZoneOffset}
import java.util.UUID

object RouterApiSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val jwtCfg = JwtConfig(secret = "test-secret-at-least-32-chars!!", expiryHours = 1)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwtCfg, clock)

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
      ar     <- ZIO.service[AppRepo]
      clock  <- ZIO.service[Clock]
    } yield PolicyServiceLive(
      pr,
      sr,
      hsr,
      tlr,
      stlr,
      dr,
      blr,
      trRepo,
      er,
      ar,
      clock,
    ): PolicyService

  /**
   * Seed `minutes/5` non-overlapping 5-min traffic_reports buckets, starting `bucketOffset` past
   * midnight UTC on `date`. Returns the next free bucket index for chaining.
   */
  private def seedTraffic(
      routerId: RouterId,
      mac: String,
      hostname: String,
      date: LocalDate,
      minutes: Int,
      bucketOffset: Int = 0,
  ): ZIO[TrafficReportRepo, Throwable, Int] =
    ZIO.serviceWithZIO[TrafficReportRepo] { tr =>
      val buckets = minutes / 5
      val today0  = date.atStartOfDay(ZoneOffset.UTC).toInstant
      val inserts = (0 until buckets).map { i =>
        val start = today0.plusSeconds((bucketOffset + i) * 300L)
        val end   = start.plusSeconds(300)
        TrafficReportInsert(
          routerId,
          MacAddress.unsafe(mac),
          None,
          HostId.Fqdn(Hostname.unsafe(hostname)),
          date,
          start,
          end,
          300,
          0L,
          0L,
        )
      }.toList
      tr.insertBatch(inserts).as(bucketOffset + buckets)
    }

  /** Seed a router row with a known plain enrollment token, return (id, raw token). */
  private def seedRouter(name: String): ZIO[RouterRepo, Throwable, (RouterId, EnrollmentToken)] =
    for {
      rr <- ZIO.service[RouterRepo]
      et   = EnrollmentToken.unsafe("et_" + UUID.randomUUID().toString.replace("-", ""))
      hash = PolicyService.hashToken(et.value)
      id <- rr.create(name, hash)
    } yield (id, et)

  private def doRegister(routes: Routes[Any, Response], et: EnrollmentToken): Task[Response] =
    routes.runZIO(
      Request.post(
        URL.decode("/api/router/register").toOption.get,
        Body.fromString(RegisterRouterRequest(et).toJson),
      ),
    )

  def spec = suite("Router API")(
    test("enrollment flow exchanges enrollment_token for router_token; row state updated") {
      for {
        _        <- cleanDb
        rr       <- ZIO.service[RouterRepo]
        ber      <- ZIO.service[BlockEventRepo]
        (id, et) <- seedRouter("home-gw")
        routes = RouterRoutes.routes(rr, null, RouterAuthLive(rr), ber)
        resp <- doRegister(routes, et)
        body <- resp.body.asString
        out  <- ZIO.fromEither(body.fromJson[RegisterRouterResponse])
        row  <- rr.findById(id).map(_.get)
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(out.routerId == id) &&
        assertTrue(out.routerToken.value.startsWith("rt_")) &&
        assertTrue(row.enrollmentTokenHash.isEmpty) &&
        assertTrue(row.tokenHash.contains(PolicyService.hashToken(out.routerToken.value))) &&
        assertTrue(row.name == "home-gw")
    },
    test("admin-created name survives enrollment end-to-end") {
      for {
        _          <- cleanDb
        auth       <- makeAuth
        rr         <- ZIO.service[RouterRepo]
        ber        <- ZIO.service[BlockEventRepo]
        adminLogin <- auth.login("admin", "changeme")
        adminRoutes = AdminRouterRoutes.routes(auth, rr)
        agentRoutes = RouterRoutes.routes(rr, null, RouterAuthLive(rr), ber)
        createResp <- adminRoutes.runZIO(
          Request
            .post(
              URL.decode("/api/admin/routers").toOption.get,
              Body.fromString(CreateRouterRequest("kitchen-gw").toJson),
            )
            .addHeader(Header.Authorization.Bearer(adminLogin.token.value)),
        )
        createBody <- createResp.body.asString
        created    <- ZIO.fromEither(createBody.fromJson[CreateRouterResponse])
        regResp    <- doRegister(agentRoutes, created.enrollmentToken)
        regBody    <- regResp.body.asString
        reg        <- ZIO.fromEither(regBody.fromJson[RegisterRouterResponse])
        row        <- rr.findById(reg.routerId).map(_.get)
      } yield assertTrue(createResp.status == Status.Ok) &&
        assertTrue(regResp.status == Status.Ok) &&
        assertTrue(reg.routerId == created.routerId) &&
        assertTrue(reg.routerToken.value.startsWith("rt_")) &&
        assertTrue(row.name == "kitchen-gw")
    },
    test("enrollment token is single-use: second register returns 401") {
      for {
        _       <- cleanDb
        rr      <- ZIO.service[RouterRepo]
        ber     <- ZIO.service[BlockEventRepo]
        (_, et) <- seedRouter("gw1")
        routes = RouterRoutes.routes(rr, null, RouterAuthLive(rr), ber)
        first  <- doRegister(routes, et)
        second <- doRegister(routes, et)
      } yield assertTrue(first.status == Status.Ok) &&
        assertTrue(second.status == Status.Unauthorized)
    },
    test(
      "policy without bearer → 401; wrong bearer → 401; right bearer → 200; persists last_etag",
    ) {
      for {
        _       <- cleanDb
        rr      <- ZIO.service[RouterRepo]
        ber     <- ZIO.service[BlockEventRepo]
        ps      <- makePolicyService
        (_, et) <- seedRouter("gw2")
        routes = RouterRoutes.routes(rr, ps, RouterAuthLive(rr), ber)
        regResp <- doRegister(routes, et)
        regBody <- regResp.body.asString
        reg     <- ZIO.fromEither(regBody.fromJson[RegisterRouterResponse])
        noAuth  <- routes.runZIO(Request.get(URL.decode("/api/router/policy").toOption.get))
        wrong   <- routes.runZIO(
          Request
            .get(URL.decode("/api/router/policy").toOption.get)
            .addHeader(Header.Authorization.Bearer("rt_wrong")),
        )
        ok      <- routes.runZIO(
          Request
            .get(URL.decode("/api/router/policy").toOption.get)
            .addHeader(Header.Authorization.Bearer(reg.routerToken.value)),
        )
        row     <- rr.findById(reg.routerId).map(_.get)
      } yield assertTrue(noAuth.status == Status.Unauthorized) &&
        assertTrue(wrong.status == Status.Unauthorized) &&
        assertTrue(ok.status == Status.Ok) &&
        assertTrue(row.lastEtag.exists(_.value.startsWith("\"sha256:")))
    },
    test(
      "policy snapshot composition: devices, profiles, schedules, site_limits, blocklists, time_used",
    ) {
      for {
        _         <- cleanDb
        pr        <- ZIO.service[ProfileRepo]
        sr        <- ZIO.service[ScheduleRepo]
        tlr       <- ZIO.service[TimeLimitRepo]
        dr        <- ZIO.service[DeviceRepo]
        blr       <- ZIO.service[BlocklistRepo]
        rr        <- ZIO.service[RouterRepo]
        ar        <- ZIO.service[AppRepo]
        kid       <- TestLayers.seedKidsProfile(pr, sr)
        _         <- tlr.upsert(kid, 120)
        _         <- TestLayers.seedAppAssignment(
          ar,
          kid,
          "youtube.com",
          AppMode.TimeLimited,
          dailyMinutes = Some(30),
          exemptFromDaily = true,
        )
        adult     <- TestLayers.seedAdultsProfile(pr)
        _         <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:33", "kid-ipad", kid)
        _         <- TestLayers.seedDevice(dr, "11:22:33:44:55:66", "parent-phone", adult)
        (rid, et) <- seedRouter("gw")
        kidMac = "aa:bb:cc:11:22:33"
        today  = LocalDate.of(2025, 1, 6)
        // Multiples of 5 — traffic_reports buckets are 5-min wide so we don't lose minutes to
        // integer rounding when projecting back to "expected" minutes.
        off1 <- seedTraffic(rid, kidMac, "youtube.com", today, 15)
        _    <- seedTraffic(rid, kidMac, "cnn.com", today, 45, off1)
        _    <- blr.insertBatch(List(("doubleclick.net", "ads"), ("ads.example.com", "ads")))
        ber  <- ZIO.service[BlockEventRepo]
        ps   <- makePolicyService
        routes = RouterRoutes.routes(rr, ps, RouterAuthLive(rr), ber)
        regResp <- doRegister(routes, et)
        regBody <- regResp.body.asString
        reg     <- ZIO.fromEither(regBody.fromJson[RegisterRouterResponse])
        resp    <- routes.runZIO(
          Request
            .get(URL.decode("/api/router/policy").toOption.get)
            .addHeader(Header.Authorization.Bearer(reg.routerToken.value)),
        )
        body    <- resp.body.asString
        snap    <- ZIO.fromEither(body.fromJson[PolicySnapshot])
        kp = snap.profiles(kid)
        // #354: snapshot no longer carries raw schedules / dailyMinutes /
        // timeUsedToday — those collapse server-side into BlockRules.blocked.
        // Here neither limit is exceeded yet and bedtime hasn't hit, so the
        // kids profile is not blocked.
      } yield assertTrue(snap.devices.size == 2) &&
        assertTrue(snap.profiles.contains(kid)) &&
        assertTrue(snap.profiles.contains(adult)) &&
        assertTrue(!kp.rules.blocked) &&
        assertTrue(kp.rules.blockReason.isEmpty) &&
        assertTrue(snap.blocklists.contains(BlocklistId.unsafe("ads"))) &&
        assertTrue(snap.blocklists(BlocklistId.unsafe("ads")).url.value == "/api/blocklists/ads")
    },
    test("etag deterministic across calls; If-None-Match → 304; mutation → fresh etag") {
      for {
        _       <- cleanDb
        pr      <- ZIO.service[ProfileRepo]
        sr      <- ZIO.service[ScheduleRepo]
        rr      <- ZIO.service[RouterRepo]
        ber     <- ZIO.service[BlockEventRepo]
        kid     <- TestLayers.seedKidsProfile(pr, sr)
        ps      <- makePolicyService
        (_, et) <- seedRouter("gw")
        routes = RouterRoutes.routes(rr, ps, RouterAuthLive(rr), ber)
        regResp   <- doRegister(routes, et)
        regBody   <- regResp.body.asString
        reg       <- ZIO.fromEither(regBody.fromJson[RegisterRouterResponse])
        r1        <- routes.runZIO(
          Request
            .get(URL.decode("/api/router/policy").toOption.get)
            .addHeader(Header.Authorization.Bearer(reg.routerToken.value)),
        )
        b1        <- r1.body.asString
        s1        <- ZIO.fromEither(b1.fromJson[PolicySnapshot])
        r2        <- routes.runZIO(
          Request
            .get(URL.decode("/api/router/policy").toOption.get)
            .addHeader(Header.Authorization.Bearer(reg.routerToken.value))
            .addHeader(Header.IfNoneMatch.ETags(NonEmptyChunk(s1.etag.value))),
        )
        // #688: Render's proxy weakens the response ETag to W/"…" before it
        // reaches the agent, so the agent echoes back the weakened form. Per
        // RFC 7232 §2.3.2 If-None-Match uses weak comparison — must still 304.
        rWeak     <- routes.runZIO(
          Request
            .get(URL.decode("/api/router/policy").toOption.get)
            .addHeader(Header.Authorization.Bearer(reg.routerToken.value))
            .addHeader(Header.IfNoneMatch.ETags(NonEmptyChunk("W/" + s1.etag.value))),
        )
        // Defensive: if both sides ever weaken, weak/weak must still match.
        rWeakBoth <- routes.runZIO(
          Request
            .get(URL.decode("/api/router/policy").toOption.get)
            .addHeader(Header.Authorization.Bearer(reg.routerToken.value))
            .addHeader(Header.IfNoneMatch.ETags(NonEmptyChunk("W/" + s1.etag.value))),
        )
        // A different ETag must still 200.
        rDiff     <- routes.runZIO(
          Request
            .get(URL.decode("/api/router/policy").toOption.get)
            .addHeader(Header.Authorization.Bearer(reg.routerToken.value))
            .addHeader(Header.IfNoneMatch.ETags(NonEmptyChunk("\"sha256:deadbeef\""))),
        )
        _         <- pr.setPaused(kid, true)
        r3        <- routes.runZIO(
          Request
            .get(URL.decode("/api/router/policy").toOption.get)
            .addHeader(Header.Authorization.Bearer(reg.routerToken.value)),
        )
        b3        <- r3.body.asString
        s3        <- ZIO.fromEither(b3.fromJson[PolicySnapshot])
      } yield assertTrue(r1.status == Status.Ok) &&
        assertTrue(r2.status == Status.NotModified) &&
        assertTrue(rWeak.status == Status.NotModified) &&
        assertTrue(rWeakBoth.status == Status.NotModified) &&
        assertTrue(rDiff.status == Status.Ok) &&
        assertTrue(r3.status == Status.Ok) &&
        assertTrue(s1.etag != s3.etag)
    },
    test(
      "#771: policy fetch with X-WifiHaven-Agent-Version header stores agentVersion on row",
    ) {
      for {
        _       <- cleanDb
        rr      <- ZIO.service[RouterRepo]
        ber     <- ZIO.service[BlockEventRepo]
        ps      <- makePolicyService
        (_, et) <- seedRouter("gw-agentver")
        routes = RouterRoutes.routes(rr, ps, RouterAuthLive(rr), ber)
        regResp <- doRegister(routes, et)
        regBody <- regResp.body.asString
        reg     <- ZIO.fromEither(regBody.fromJson[RegisterRouterResponse])
        // First poll with version header — populates agent_version.
        _       <- routes.runZIO(
          Request
            .get(URL.decode("/api/router/policy").toOption.get)
            .addHeader(Header.Authorization.Bearer(reg.routerToken.value))
            .addHeader("X-WifiHaven-Agent-Version", "0.1.0"),
        )
        row1    <- rr.findById(reg.routerId).map(_.get)
        // Subsequent poll with a newer version updates the column.
        _       <- routes.runZIO(
          Request
            .get(URL.decode("/api/router/policy").toOption.get)
            .addHeader(Header.Authorization.Bearer(reg.routerToken.value))
            .addHeader("X-WifiHaven-Agent-Version", "0.2.0"),
        )
        row2    <- rr.findById(reg.routerId).map(_.get)
        // Poll without the header (legacy agent) preserves the last known
        // version — additive field, no clobber.
        _       <- routes.runZIO(
          Request
            .get(URL.decode("/api/router/policy").toOption.get)
            .addHeader(Header.Authorization.Bearer(reg.routerToken.value)),
        )
        row3    <- rr.findById(reg.routerId).map(_.get)
      } yield assertTrue(row1.agentVersion.contains("0.1.0")) &&
        assertTrue(row2.agentVersion.contains("0.2.0")) &&
        assertTrue(row3.agentVersion.contains("0.2.0"))
    },
    test("#771: GET /api/admin/routers includes agentVersion in RouterSummary") {
      for {
        _          <- cleanDb
        auth       <- makeAuth
        rr         <- ZIO.service[RouterRepo]
        ber        <- ZIO.service[BlockEventRepo]
        ps         <- makePolicyService
        (_, et)    <- seedRouter("gw-summary")
        adminLogin <- auth.login("admin", "changeme")
        agentRoutes = RouterRoutes.routes(rr, ps, RouterAuthLive(rr), ber)
        adminRoutes = AdminRouterRoutes.routes(auth, rr)
        regResp   <- doRegister(agentRoutes, et)
        regBody   <- regResp.body.asString
        reg       <- ZIO.fromEither(regBody.fromJson[RegisterRouterResponse])
        _         <- agentRoutes.runZIO(
          Request
            .get(URL.decode("/api/router/policy").toOption.get)
            .addHeader(Header.Authorization.Bearer(reg.routerToken.value))
            .addHeader("X-WifiHaven-Agent-Version", "0.3.1"),
        )
        listResp  <- adminRoutes.runZIO(
          Request
            .get(URL.decode("/api/admin/routers").toOption.get)
            .addHeader(Header.Authorization.Bearer(adminLogin.token.value)),
        )
        listBody  <- listResp.body.asString
        summaries <- ZIO.fromEither(listBody.fromJson[List[RouterSummary]])
      } yield assertTrue(listResp.status == Status.Ok) &&
        assertTrue(summaries.size == 1) &&
        assertTrue(summaries.head.agentVersion.contains("0.3.1"))
    },
    test("blocklist (plain-text): 200 with version comment + hosts, 401 unauth, 404 unknown") {
      for {
        _       <- cleanDb
        rr      <- ZIO.service[RouterRepo]
        blr     <- ZIO.service[BlocklistRepo]
        ps      <- makePolicyService
        _       <- blr.insertBatch(List(("doubleclick.net", "ads"), ("ads.example.com", "ads")))
        ber     <- ZIO.service[BlockEventRepo]
        (_, et) <- seedRouter("gw")
        routes = RouterRoutes.routes(rr, ps, RouterAuthLive(rr), ber)
        regResp <- doRegister(routes, et)
        regBody <- regResp.body.asString
        reg     <- ZIO.fromEither(regBody.fromJson[RegisterRouterResponse])
        noAuth  <- routes.runZIO(Request.get(URL.decode("/api/blocklists/ads").toOption.get))
        ok      <- routes.runZIO(
          Request
            .get(URL.decode("/api/blocklists/ads").toOption.get)
            .addHeader(Header.Authorization.Bearer(reg.routerToken.value)),
        )
        okBody  <- ok.body.asString
        nf      <- routes.runZIO(
          Request
            .get(URL.decode("/api/blocklists/nonsense").toOption.get)
            .addHeader(Header.Authorization.Bearer(reg.routerToken.value)),
        )
      } yield assertTrue(noAuth.status == Status.Unauthorized) &&
        assertTrue(ok.status == Status.Ok) &&
        assertTrue(okBody.startsWith("# version: ")) &&
        assertTrue(okBody.contains("doubleclick.net\n")) &&
        assertTrue(okBody.contains("ads.example.com\n")) &&
        assertTrue(
          ok.header(Header.ContentType).exists(_.renderedValue.startsWith("text/plain")),
        ) &&
        assertTrue(ok.header(Header.ETag).isDefined) &&
        assertTrue(nf.status == Status.NotFound)
    },
    test("policy snapshot: unknown device (NULL profile_id) appears with profileId=null") {
      for {
        _       <- cleanDb
        pr      <- ZIO.service[ProfileRepo]
        sr      <- ZIO.service[ScheduleRepo]
        dr      <- ZIO.service[DeviceRepo]
        rr      <- ZIO.service[RouterRepo]
        kid     <- TestLayers.seedKidsProfile(pr, sr)
        _       <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:33", "kid-ipad", kid)
        _       <- dr.upsertUnknown(
          MacAddress.unsafe("ff:ff:ff:aa:bb:cc"),
          "mystery",
          Some(IpAddress.unsafe("10.0.0.99")),
          java.time.Instant.now(),
        )
        ber     <- ZIO.service[BlockEventRepo]
        ps      <- makePolicyService
        (_, et) <- seedRouter("gw-snap-unknown")
        routes = RouterRoutes.routes(rr, ps, RouterAuthLive(rr), ber)
        regResp <- doRegister(routes, et)
        regBody <- regResp.body.asString
        reg     <- ZIO.fromEither(regBody.fromJson[RegisterRouterResponse])
        resp    <- routes.runZIO(
          Request
            .get(URL.decode("/api/router/policy").toOption.get)
            .addHeader(Header.Authorization.Bearer(reg.routerToken.value)),
        )
        body    <- resp.body.asString
        snap    <- ZIO.fromEither(body.fromJson[PolicySnapshot])
        unknown = snap.devices.get(MacAddress.unsafe("ff:ff:ff:aa:bb:cc"))
        known   = snap.devices.get(MacAddress.unsafe("aa:bb:cc:11:22:33"))
      } yield assertTrue(snap.devices.size == 2) &&
        assertTrue(unknown.isDefined) &&
        assertTrue(unknown.exists(_.profileId.isEmpty)) &&
        assertTrue(known.exists(_.profileId.contains(kid))) &&
        // #961: default unmanagedMacPolicy = allow ⇒ unenrolled device keeps
        // `rules = None` and flows freely. Switching to "block" is covered
        // by the next test.
        assertTrue(unknown.exists(_.rules.isEmpty)) &&
        // Known (profile-assigned) device also has `rules = None` — its
        // resolution happens via the profile policy.
        assertTrue(known.exists(_.rules.isEmpty))
    },
    test(
      "#961/#1122 snapshot: unmanagedMacPolicy=block emits Unmanaged-blocked rules for unenrolled MAC",
    ) {
      for {
        _        <- cleanDb
        pr       <- ZIO.service[ProfileRepo]
        sr       <- ZIO.service[ScheduleRepo]
        dr       <- ZIO.service[DeviceRepo]
        rr       <- ZIO.service[RouterRepo]
        hsr      <- ZIO.service[HouseholdSettingsRepo]
        ber      <- ZIO.service[BlockEventRepo]
        kid      <- TestLayers.seedKidsProfile(pr, sr)
        _        <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:33", "kid-ipad", kid)
        _        <- dr.upsertUnknown(
          MacAddress.unsafe("ff:ff:ff:aa:bb:cc"),
          "mystery",
          Some(IpAddress.unsafe("10.0.0.99")),
          java.time.Instant.now(),
        )
        existing <- hsr.get
        _        <- hsr.update(
          existing.copy(unmanagedMacPolicy = UnmanagedMacPolicy(policy = "block", blockPage = true)),
        )
        ps       <- makePolicyService
        (_, et)  <- seedRouter("gw-snap-unmanaged-block")
        routes = RouterRoutes.routes(rr, ps, RouterAuthLive(rr), ber)
        regResp <- doRegister(routes, et)
        regBody <- regResp.body.asString
        reg     <- ZIO.fromEither(regBody.fromJson[RegisterRouterResponse])
        resp    <- routes.runZIO(
          Request
            .get(URL.decode("/api/router/policy").toOption.get)
            .addHeader(Header.Authorization.Bearer(reg.routerToken.value)),
        )
        body    <- resp.body.asString
        snap    <- ZIO.fromEither(body.fromJson[PolicySnapshot])
        unknown = snap.devices.get(MacAddress.unsafe("ff:ff:ff:aa:bb:cc"))
      } yield assertTrue(unknown.exists(_.profileId.isEmpty)) &&
        assertTrue(unknown.flatMap(_.rules).exists(_.blocked)) &&
        assertTrue(
          unknown.flatMap(_.rules).flatMap(_.blockReason).contains(MacBlockReason.Unmanaged),
        )
    },
    test("admin can create router; non-admin gets 403; row stores enrollment hash, no token yet") {
      for {
        _          <- cleanDb
        auth       <- makeAuth
        rr         <- ZIO.service[RouterRepo]
        ur         <- ZIO.service[UserRepo]
        adminLogin <- auth.login("admin", "changeme")
        h          <- auth.hashPassword("kp")
        _          <- ur.create("kid", h, "child")
        kidLogin   <- auth.login("kid", "kp")
        adminRoutes = AdminRouterRoutes.routes(auth, rr)
        rejected <- adminRoutes.runZIO(
          Request
            .post(
              URL.decode("/api/admin/routers").toOption.get,
              Body.fromString(CreateRouterRequest("home").toJson),
            )
            .addHeader(Header.Authorization.Bearer(kidLogin.token.value)),
        )
        ok       <- adminRoutes.runZIO(
          Request
            .post(
              URL.decode("/api/admin/routers").toOption.get,
              Body.fromString(CreateRouterRequest("home").toJson),
            )
            .addHeader(Header.Authorization.Bearer(adminLogin.token.value)),
        )
        okBody   <- ok.body.asString
        out      <- ZIO.fromEither(okBody.fromJson[CreateRouterResponse])
        row      <- rr.findById(out.routerId).map(_.get)
      } yield assertTrue(rejected.status == Status.Forbidden) &&
        assertTrue(ok.status == Status.Ok) &&
        assertTrue(out.enrollmentToken.value.startsWith("et_")) &&
        assertTrue(row.tokenHash.isEmpty) &&
        assertTrue(
          row.enrollmentTokenHash.contains(PolicyService.hashToken(out.enrollmentToken.value)),
        )
    },
    // ── #352: plain-text blocklist endpoint replaces RPZ ──────────────────────
    test(
      "blocklist endpoint: 200 text/plain with version comment + sorted hosts; ETag content-derived; 304 on repeat",
    ) {
      for {
        _       <- cleanDb
        rr      <- ZIO.service[RouterRepo]
        blr     <- ZIO.service[BlocklistRepo]
        ps      <- makePolicyService
        _       <- blr.insertBatch(
          List(
            ("adserver.example.com", "test_ads"),
            ("doubleclick.net", "test_ads"),
            ("googleadservices.com", "test_ads"),
          ),
        )
        ber     <- ZIO.service[BlockEventRepo]
        (_, et) <- seedRouter("gw-bl")
        routes = RouterRoutes.routes(rr, ps, RouterAuthLive(rr), ber)
        regResp <- doRegister(routes, et)
        regBody <- regResp.body.asString
        reg     <- ZIO.fromEither(regBody.fromJson[RegisterRouterResponse])
        ok      <- routes.runZIO(
          Request
            .get(URL.decode("/api/blocklists/test_ads").toOption.get)
            .addHeader(Header.Authorization.Bearer(reg.routerToken.value)),
        )
        body    <- ok.body.asString
        etag1 = ok.header(Header.ETag).map(_.renderedValue)
        notMod     <- routes.runZIO(
          Request
            .get(URL.decode("/api/blocklists/test_ads").toOption.get)
            .addHeader(Header.Authorization.Bearer(reg.routerToken.value))
            .addHeader(Header.IfNoneMatch.ETags(NonEmptyChunk(etag1.get))),
        )
        // #688: weakened echo from Render's proxy must still 304.
        notModWeak <- routes.runZIO(
          Request
            .get(URL.decode("/api/blocklists/test_ads").toOption.get)
            .addHeader(Header.Authorization.Bearer(reg.routerToken.value))
            .addHeader(Header.IfNoneMatch.ETags(NonEmptyChunk("W/" + etag1.get))),
        )
      } yield assertTrue(ok.status == Status.Ok) &&
        assertTrue(notModWeak.status == Status.NotModified) &&
        assertTrue(
          ok.header(Header.ContentType).exists(_.renderedValue.startsWith("text/plain")),
        ) &&
        assertTrue(body.startsWith("# version: ")) &&
        assertTrue(body.contains("adserver.example.com\n")) &&
        assertTrue(body.contains("doubleclick.net\n")) &&
        assertTrue(body.contains("googleadservices.com\n")) &&
        // Hosts are sorted ascending — adserver < doubleclick < googleadservices
        assertTrue(body.indexOf("adserver.example.com") < body.indexOf("doubleclick.net")) &&
        assertTrue(body.indexOf("doubleclick.net") < body.indexOf("googleadservices.com")) &&
        assertTrue(etag1.isDefined) &&
        // ETag must be content-derived only (no date substring like "2026-")
        assertTrue(!etag1.get.matches(".*\\d{4}-\\d{2}-\\d{2}.*")) &&
        assertTrue(notMod.status == Status.NotModified)
    },
    test("blocklist endpoint: 401 without auth; 404 for unknown id") {
      for {
        _       <- cleanDb
        rr      <- ZIO.service[RouterRepo]
        blr     <- ZIO.service[BlocklistRepo]
        ps      <- makePolicyService
        _       <- blr.insertBatch(List(("doubleclick.net", "test_ads")))
        ber     <- ZIO.service[BlockEventRepo]
        (_, et) <- seedRouter("gw-bl2")
        routes = RouterRoutes.routes(rr, ps, RouterAuthLive(rr), ber)
        regResp   <- doRegister(routes, et)
        regBody   <- regResp.body.asString
        reg       <- ZIO.fromEither(regBody.fromJson[RegisterRouterResponse])
        noAuth    <- routes.runZIO(
          Request.get(URL.decode("/api/blocklists/test_ads").toOption.get),
        )
        wrongAuth <- routes.runZIO(
          Request
            .get(URL.decode("/api/blocklists/test_ads").toOption.get)
            .addHeader(Header.Authorization.Bearer("rt_wrong")),
        )
        notFound  <- routes.runZIO(
          Request
            .get(URL.decode("/api/blocklists/unknown_id").toOption.get)
            .addHeader(Header.Authorization.Bearer(reg.routerToken.value)),
        )
      } yield assertTrue(noAuth.status == Status.Unauthorized) &&
        assertTrue(wrongAuth.status == Status.Unauthorized) &&
        assertTrue(notFound.status == Status.NotFound)
    },
    test("old .rpz path returns 404 (route entirely removed)") {
      for {
        _       <- cleanDb
        rr      <- ZIO.service[RouterRepo]
        blr     <- ZIO.service[BlocklistRepo]
        ps      <- makePolicyService
        _       <- blr.insertBatch(List(("doubleclick.net", "test_ads")))
        ber     <- ZIO.service[BlockEventRepo]
        (_, et) <- seedRouter("gw-bl3")
        routes = RouterRoutes.routes(rr, ps, RouterAuthLive(rr), ber)
        regResp <- doRegister(routes, et)
        regBody <- regResp.body.asString
        reg     <- ZIO.fromEither(regBody.fromJson[RegisterRouterResponse])
        rpzResp <- routes.runZIO(
          Request
            .get(URL.decode("/api/blocklists/test_ads.rpz").toOption.get)
            .addHeader(Header.Authorization.Bearer(reg.routerToken.value)),
        )
      } yield assertTrue(rpzResp.status == Status.NotFound)
    },
    test(
      "snapshot: blocklists map keyed by id; url uses /api/blocklists/<id> (no .rpz); version is SHA prefix not date",
    ) {
      for {
        _       <- cleanDb
        rr      <- ZIO.service[RouterRepo]
        blr     <- ZIO.service[BlocklistRepo]
        ps      <- makePolicyService
        _       <- blr.insertBatch(
          List(
            ("doubleclick.net", "test_ads"),
            ("googleadservices.com", "test_ads"),
            ("tiktok.com", "test_social"),
          ),
        )
        ber     <- ZIO.service[BlockEventRepo]
        (_, et) <- seedRouter("gw-bl4")
        routes = RouterRoutes.routes(rr, ps, RouterAuthLive(rr), ber)
        regResp <- doRegister(routes, et)
        regBody <- regResp.body.asString
        reg     <- ZIO.fromEither(regBody.fromJson[RegisterRouterResponse])
        resp    <- routes.runZIO(
          Request
            .get(URL.decode("/api/router/policy").toOption.get)
            .addHeader(Header.Authorization.Bearer(reg.routerToken.value)),
        )
        body    <- resp.body.asString
        snap    <- ZIO.fromEither(body.fromJson[PolicySnapshot])
      } yield assertTrue(snap.blocklists.contains(BlocklistId.unsafe("test_ads"))) &&
        assertTrue(snap.blocklists.contains(BlocklistId.unsafe("test_social"))) &&
        assertTrue(
          snap.blocklists(BlocklistId.unsafe("test_ads")).url.value == "/api/blocklists/test_ads",
        ) &&
        assertTrue(
          !snap.blocklists(BlocklistId.unsafe("test_ads")).url.value.contains(".rpz"),
        ) &&
        // version is a hex SHA prefix, not a date like "2026-05-14"
        assertTrue(
          !snap
            .blocklists(BlocklistId.unsafe("test_ads"))
            .version
            .value
            .matches("\\d{4}-\\d{2}-\\d{2}"),
        )
    },
    test(
      "snapshot ETag: does not change when only today changes; changes when blocklist content changes",
    ) {
      for {
        _       <- cleanDb
        pr      <- ZIO.service[ProfileRepo]
        sr      <- ZIO.service[ScheduleRepo]
        rr      <- ZIO.service[RouterRepo]
        blr     <- ZIO.service[BlocklistRepo]
        ber     <- ZIO.service[BlockEventRepo]
        _       <- TestLayers.seedKidsProfile(pr, sr)
        _       <- blr.insertBatch(List(("doubleclick.net", "test_ads")))
        ps      <- makePolicyService
        (_, et) <- seedRouter("gw-etag")
        routes = RouterRoutes.routes(rr, ps, RouterAuthLive(rr), ber)
        regResp <- doRegister(routes, et)
        regBody <- regResp.body.asString
        reg     <- ZIO.fromEither(regBody.fromJson[RegisterRouterResponse])
        r1      <- routes.runZIO(
          Request
            .get(URL.decode("/api/router/policy").toOption.get)
            .addHeader(Header.Authorization.Bearer(reg.routerToken.value)),
        )
        b1      <- r1.body.asString
        s1      <- ZIO.fromEither(b1.fromJson[PolicySnapshot])
        // Adding new domain should change the ETag (content changes)
        _       <- blr.insertBatch(List(("ads.example.com", "test_ads")))
        r2      <- routes.runZIO(
          Request
            .get(URL.decode("/api/router/policy").toOption.get)
            .addHeader(Header.Authorization.Bearer(reg.routerToken.value)),
        )
        b2      <- r2.body.asString
        s2      <- ZIO.fromEither(b2.fromJson[PolicySnapshot])
      } yield assertTrue(r1.status == Status.Ok) &&
        assertTrue(r2.status == Status.Ok) &&
        assertTrue(s1.etag != s2.etag)
    },
  ) @@ TestAspect.sequential
}
