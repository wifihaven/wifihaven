package familydns.api.feature

import familydns.api.JwtConfig
import familydns.api.auth.*
import familydns.api.db.*
import familydns.api.policy.*
import familydns.api.routes.*
import familydns.shared.*
import familydns.shared.Clock.TestClock
import familydns.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*
import zio.test.*

import java.time.LocalDate
import java.util.UUID

object RouterApiSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val jwtCfg = JwtConfig(secret = "test-secret-at-least-32-chars!!", expiryHours = 1)

  private def cleanDb = ZIO.serviceWithZIO[EmbeddedPostgres](pg =>
    TestDatabase.cleanAndMigrate.provide(ZLayer.succeed(pg)),
  )

  private def makeAuth =
    for
      ur    <- ZIO.service[UserRepo]
      clock <- ZIO.service[Clock]
    yield AuthServiceLive(ur, jwtCfg, clock)

  private def makePolicyService =
    for
      pr    <- ZIO.service[ProfileRepo]
      sr    <- ZIO.service[ScheduleRepo]
      tlr   <- ZIO.service[TimeLimitRepo]
      stlr  <- ZIO.service[SiteTimeLimitRepo]
      dr    <- ZIO.service[DeviceRepo]
      blr   <- ZIO.service[BlocklistRepo]
      ur    <- ZIO.service[TimeUsageRepo]
      er    <- ZIO.service[TimeExtensionRepo]
      clock <- ZIO.service[Clock]
    yield (new PolicyServiceLive(pr, sr, tlr, stlr, dr, blr, ur, er, clock)): PolicyService

  /** Seed a router row with a known plain enrollment token, return (id, raw token). */
  private def seedRouter(name: String): ZIO[RouterRepo, Throwable, (UUID, String)] =
    for
      rr <- ZIO.service[RouterRepo]
      token = "et_" + UUID.randomUUID().toString.replace("-", "")
      hash  = PolicyService.hashToken(token)
      id <- rr.create(name, hash)
    yield (id, token)

  private def doRegister(routes: Routes[Any, Response], et: String): Task[Response] =
    routes.runZIO(
      Request.post(
        URL.decode("/api/router/register").toOption.get,
        Body.fromString(RegisterRouterRequest(et).toJson),
      ),
    )

  def spec = suite("Router API")(
    test("enrollment flow exchanges enrollment_token for router_token; row state updated") {
      for
        _        <- cleanDb
        rr       <- ZIO.service[RouterRepo]
        ber      <- ZIO.service[BlockEventRepo]
        (id, et) <- seedRouter("home-gw")
        routes = RouterRoutes.routes(rr, null, RouterAuthLive(rr), ber)
        resp <- doRegister(routes, et)
        body <- resp.body.asString
        out  <- ZIO.fromEither(body.fromJson[RegisterRouterResponse])
        row  <- rr.findById(id).map(_.get)
      yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(out.routerId == id) &&
        assertTrue(out.routerToken.startsWith("rt_")) &&
        assertTrue(row.enrollmentTokenHash.isEmpty) &&
        assertTrue(row.tokenHash.contains(PolicyService.hashToken(out.routerToken)))
    },
    test("enrollment token is single-use: second register returns 401") {
      for
        _       <- cleanDb
        rr      <- ZIO.service[RouterRepo]
        ber     <- ZIO.service[BlockEventRepo]
        (_, et) <- seedRouter("gw1")
        routes = RouterRoutes.routes(rr, null, RouterAuthLive(rr), ber)
        first  <- doRegister(routes, et)
        second <- doRegister(routes, et)
      yield assertTrue(first.status == Status.Ok) &&
        assertTrue(second.status == Status.Unauthorized)
    },
    test(
      "policy without bearer → 401; wrong bearer → 401; right bearer → 200; persists last_etag",
    ) {
      for
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
            .addHeader(Header.Authorization.Bearer(reg.routerToken)),
        )
        row     <- rr.findById(reg.routerId).map(_.get)
      yield assertTrue(noAuth.status == Status.Unauthorized) &&
        assertTrue(wrong.status == Status.Unauthorized) &&
        assertTrue(ok.status == Status.Ok) &&
        assertTrue(row.lastEtag.exists(_.startsWith("\"sha256:")))
    },
    test(
      "policy snapshot composition: devices, profiles, schedules, site_limits, blocklists, time_used",
    ) {
      for
        _     <- cleanDb
        pr    <- ZIO.service[ProfileRepo]
        sr    <- ZIO.service[ScheduleRepo]
        tlr   <- ZIO.service[TimeLimitRepo]
        stlr  <- ZIO.service[SiteTimeLimitRepo]
        dr    <- ZIO.service[DeviceRepo]
        blr   <- ZIO.service[BlocklistRepo]
        ur    <- ZIO.service[TimeUsageRepo]
        rr    <- ZIO.service[RouterRepo]
        kid   <- TestLayers.seedKidsProfile(pr, sr)
        _     <- tlr.upsert(kid, 120)
        _     <- stlr.replaceForProfile(
          kid,
          List(SiteTimeLimitRequest("youtube.com", 30, "YouTube")),
        )
        adult <- TestLayers.seedAdultsProfile(pr)
        _     <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:33", "kid-ipad", kid)
        _     <- TestLayers.seedDevice(dr, "11:22:33:44:55:66", "parent-phone", adult)
        _     <- ur.incrementUsage("aa:bb:cc:11:22:33", "youtube.com", LocalDate.of(2025, 1, 6), 12)
        _     <- ur.incrementUsage("aa:bb:cc:11:22:33", "cnn.com", LocalDate.of(2025, 1, 6), 47)
        _     <- blr.insertBatch(List(("doubleclick.net", "ads"), ("ads.example.com", "ads")))
        ber   <- ZIO.service[BlockEventRepo]
        (_, et) <- seedRouter("gw")
        ps      <- makePolicyService
        routes = RouterRoutes.routes(rr, ps, RouterAuthLive(rr), ber)
        regResp <- doRegister(routes, et)
        regBody <- regResp.body.asString
        reg     <- ZIO.fromEither(regBody.fromJson[RegisterRouterResponse])
        resp    <- routes.runZIO(
          Request
            .get(URL.decode("/api/router/policy").toOption.get)
            .addHeader(Header.Authorization.Bearer(reg.routerToken)),
        )
        body    <- resp.body.asString
        snap    <- ZIO.fromEither(body.fromJson[PolicySnapshot])
        kp = snap.profiles.find(_.id == kid).get
      yield assertTrue(snap.devices.size == 2) &&
        assertTrue(snap.profiles.exists(_.id == kid)) &&
        assertTrue(snap.profiles.exists(_.id == adult)) &&
        assertTrue(snap.defaultProfileId.exists(id => snap.profiles.exists(_.id == id))) &&
        assertTrue(kp.dailyMinutes.contains(120)) &&
        assertTrue(kp.schedules.exists(_.blockFrom == "21:00")) &&
        assertTrue(kp.siteLimits.exists(_.domain == "youtube.com")) &&
        assertTrue(kp.timeUsedToday.totalMinutes == 47) &&
        assertTrue(kp.timeUsedToday.byDomain.get("youtube.com").contains(12)) &&
        assertTrue(snap.blocklists.contains("ads")) &&
        assertTrue(snap.blocklists("ads").url == "/api/blocklists/ads.rpz")
    },
    test("etag deterministic across calls; If-None-Match → 304; mutation → fresh etag") {
      for
        _       <- cleanDb
        pr      <- ZIO.service[ProfileRepo]
        sr      <- ZIO.service[ScheduleRepo]
        rr      <- ZIO.service[RouterRepo]
        ber     <- ZIO.service[BlockEventRepo]
        kid     <- TestLayers.seedKidsProfile(pr, sr)
        ps      <- makePolicyService
        (_, et) <- seedRouter("gw")
        routes = RouterRoutes.routes(rr, ps, RouterAuthLive(rr), ber)
        regResp <- doRegister(routes, et)
        regBody <- regResp.body.asString
        reg     <- ZIO.fromEither(regBody.fromJson[RegisterRouterResponse])
        r1      <- routes.runZIO(
          Request
            .get(URL.decode("/api/router/policy").toOption.get)
            .addHeader(Header.Authorization.Bearer(reg.routerToken)),
        )
        b1      <- r1.body.asString
        s1      <- ZIO.fromEither(b1.fromJson[PolicySnapshot])
        r2      <- routes.runZIO(
          Request
            .get(URL.decode("/api/router/policy").toOption.get)
            .addHeader(Header.Authorization.Bearer(reg.routerToken))
            .addHeader(Header.IfNoneMatch.ETags(NonEmptyChunk(s1.etag))),
        )
        _       <- pr.setPaused(kid, true)
        r3      <- routes.runZIO(
          Request
            .get(URL.decode("/api/router/policy").toOption.get)
            .addHeader(Header.Authorization.Bearer(reg.routerToken)),
        )
        b3      <- r3.body.asString
        s3      <- ZIO.fromEither(b3.fromJson[PolicySnapshot])
      yield assertTrue(r1.status == Status.Ok) &&
        assertTrue(r2.status == Status.NotModified) &&
        assertTrue(r3.status == Status.Ok) &&
        assertTrue(s1.etag != s3.etag)
    },
    test("RPZ blocklist: 200 with formatted body, 401 unauth, 404 unknown") {
      for
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
        noAuth  <- routes.runZIO(Request.get(URL.decode("/api/blocklists/ads.rpz").toOption.get))
        ok      <- routes.runZIO(
          Request
            .get(URL.decode("/api/blocklists/ads.rpz").toOption.get)
            .addHeader(Header.Authorization.Bearer(reg.routerToken)),
        )
        okBody  <- ok.body.asString
        nf      <- routes.runZIO(
          Request
            .get(URL.decode("/api/blocklists/nonsense.rpz").toOption.get)
            .addHeader(Header.Authorization.Bearer(reg.routerToken)),
        )
      yield assertTrue(noAuth.status == Status.Unauthorized) &&
        assertTrue(ok.status == Status.Ok) &&
        assertTrue(okBody.contains("$ORIGIN ads.rpz.")) &&
        assertTrue(okBody.contains("doubleclick.net CNAME .")) &&
        assertTrue(okBody.contains("ads.example.com CNAME .")) &&
        assertTrue(ok.header(Header.ContentType).exists(_.renderedValue.startsWith("text/dns"))) &&
        assertTrue(ok.header(Header.ETag).isDefined) &&
        assertTrue(nf.status == Status.NotFound)
    },
    test("admin can create router; non-admin gets 403; row stores enrollment hash, no token yet") {
      for
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
            .addHeader(Header.Authorization.Bearer(kidLogin.token)),
        )
        ok       <- adminRoutes.runZIO(
          Request
            .post(
              URL.decode("/api/admin/routers").toOption.get,
              Body.fromString(CreateRouterRequest("home").toJson),
            )
            .addHeader(Header.Authorization.Bearer(adminLogin.token)),
        )
        okBody   <- ok.body.asString
        out      <- ZIO.fromEither(okBody.fromJson[CreateRouterResponse])
        row      <- rr.findById(out.routerId).map(_.get)
      yield assertTrue(rejected.status == Status.Forbidden) &&
        assertTrue(ok.status == Status.Ok) &&
        assertTrue(out.enrollmentToken.startsWith("et_")) &&
        assertTrue(row.tokenHash.isEmpty) &&
        assertTrue(row.enrollmentTokenHash.contains(PolicyService.hashToken(out.enrollmentToken)))
    },
  ) @@ TestAspect.sequential
}
