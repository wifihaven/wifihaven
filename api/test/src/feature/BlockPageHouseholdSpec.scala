package wifihaven.api.feature

import wifihaven.api.JwtConfig
import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.db.TypeMeta.given
import wifihaven.api.notify.{EscalationNotice, Notifier}
import wifihaven.api.policy.*
import wifihaven.api.routes.*
import wifihaven.shared.*
import wifihaven.shared.Clock.TestClock
import wifihaven.shared.types.*
import wifihaven.testinfra.*
import doobie.*
import doobie.implicits.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.interop.catz.*
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*
import zio.test.*

/**
 * #2566 / #2569 / #2322 — the block page's household derivation.
 *
 * The block page is unauthenticated: the router's HTTP/80 DNAT redirect carries only `?mac=&host=`
 * (#1615/#1617/#1618), so neither `GET /api/blocked` nor `POST /api/access-requests` had a
 * household in scope. Both coped by guessing — `HouseholdId.Default` on the read side, an in-SQL
 * `ORDER BY d.household_id LIMIT 1` on the write side — and both guesses are wrong once a MAC
 * exists in more than one household (V74/V75).
 *
 * The fix threads a router-bound, HMAC-signed **block-page token** (`bpt`) from the agent's
 * redirect through the SPA into both endpoints. These pins are the acceptance gate: every one of
 * them is a NEGATIVE test about household A's data never surfacing on household B's block page (and
 * vice versa), driven end-to-end over the real routes against embedded Postgres.
 */
object BlockPageHouseholdSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private val jwtCfg = JwtConfig(secret = "test-secret-at-least-32-chars!!x", expiryHours = 1)

  private val macA = MacAddress.unsafe("aa:bb:cc:00:00:0a")
  private val macB = MacAddress.unsafe("aa:bb:cc:00:00:0b")

  /** The SAME MAC in BOTH households — the post-V74 shape #2322 is about. */
  private val macM = MacAddress.unsafe("aa:bb:cc:00:00:00")

  private val host = Hostname.unsafe("youtube.com")

  private val noopNotifier: Notifier = new Notifier {
    def alertCreated(a: Alert): UIO[Unit]                                          = ZIO.unit
    def betaInvite(email: String, slug: String, url: String, ttl: Int): UIO[Unit]  = ZIO.unit
    def betaFlipNotice(
        householdId: HouseholdId,
        slug: String,
        window: String,
        flipDate: java.time.Instant,
        daysUntilFlip: Int,
    ): UIO[Unit] = ZIO.unit
    def passwordReset(email: String, resetUrl: String, ttlMinutes: Int): UIO[Unit] = ZIO.unit
    def escalation(notice: EscalationNotice): UIO[Unit]                            = ZIO.unit
  }

  // ── stack ──────────────────────────────────────────────────────────────────

  private def makePolicy =
    for {
      pr     <- ZIO.service[ProfileRepo]
      hsr    <- ZIO.service[HouseholdSettingsRepo]
      tlr    <- ZIO.service[TimeLimitRepo]
      atlr   <- ZIO.service[AppTimeLimitRepo]
      dr     <- ZIO.service[DeviceRepo]
      blr    <- ZIO.service[BlocklistRepo]
      trRepo <- ZIO.service[TrafficReportRepo]
      er     <- ZIO.service[TimeExtensionRepo]
      ar     <- ZIO.service[AppRepo]
      nsr    <- ZIO.service[NamedScheduleRepo]
      clk    <- ZIO.service[Clock]
    } yield PolicyServiceLive(
      pr,
      hsr,
      tlr,
      atlr,
      dr,
      blr,
      trRepo,
      er,
      ar,
      clk,
      namedScheduleRepo = nsr,
    ): PolicyService

  private def makeTimeStatus =
    for {
      pr   <- ZIO.service[ProfileRepo]
      tlr  <- ZIO.service[TimeLimitRepo]
      atlr <- ZIO.service[AppTimeLimitRepo]
      dr   <- ZIO.service[DeviceRepo]
      trr  <- ZIO.service[TrafficReportRepo]
      er   <- ZIO.service[TimeExtensionRepo]
    } yield new TimeStatusServiceLive(pr, tlr, atlr, dr, trr, er): TimeStatusService

  /** The router-facing routes, which is where a router mints its block-page token. */
  private def makeRouterRoutes =
    for {
      rr  <- ZIO.service[RouterRepo]
      ber <- ZIO.service[BlockEventRepo]
      ps  <- makePolicy
    } yield RouterRoutes.routes(rr, ps, RouterAuthLive(rr), ber)

  private def makeBlockedRoutes =
    for {
      ps  <- makePolicy
      dr  <- ZIO.service[DeviceRepo]
      pr  <- ZIO.service[ProfileRepo]
      blr <- ZIO.service[BlocklistRepo]
      tss <- makeTimeStatus
      hsr <- ZIO.service[HouseholdSettingsRepo]
      clk <- ZIO.service[Clock]
    } yield BlockedRoutes.routes(ps, dr, pr, blr, tss, hsr, clk)

  private def makeAlertRoutes =
    for {
      alertRepo <- ZIO.service[AlertRepo]
      dr        <- ZIO.service[DeviceRepo]
      pr        <- ZIO.service[ProfileRepo]
      extRepo   <- ZIO.service[TimeExtensionRepo]
      appRepo   <- ZIO.service[AppRepo]
      hsRepo    <- ZIO.service[HouseholdSettingsRepo]
      ur        <- ZIO.service[UserRepo]
      clk       <- ZIO.service[Clock]
    } yield AlertRoutes.routes(
      AuthServiceLive(ur, jwtCfg, clk),
      alertRepo,
      dr,
      pr,
      extRepo,
      appRepo,
      hsRepo,
      noopNotifier,
      clk,
      RateLimiter.allowAll,
    )

  // ── helpers ────────────────────────────────────────────────────────────────

  /**
   * Mint a block-page token as the router holding `routerToken`. Parsed as a bare string map rather
   * than a typed response so this pin exercises the WIRE, not a Scala model.
   */
  private def mintBpt(routes: Routes[Any, Response], routerToken: String): Task[String] =
    for {
      resp <- routes.runZIO(
        Request
          .get(URL.decode("/api/router/block-page-token").toOption.get)
          .addHeader(Header.Authorization.Bearer(routerToken)),
      )
      body <- resp.body.asString
      _    <- ZIO
        .fail(new RuntimeException(s"mint failed: ${resp.status} $body"))
        .unless(resp.status == Status.Ok)
      m    <- ZIO.fromEither(body.fromJson[Map[String, String]]).mapError(new RuntimeException(_))
      tok  <- ZIO.fromOption(m.get("token")).orElseFail(new RuntimeException(s"no token in $body"))
    } yield tok

  private def getBlocked(
      routes: Routes[Any, Response],
      mac: MacAddress,
      bpt: Option[String],
  ): Task[BlockedInfoResponse] = {
    val qs = s"mac=${mac.value}&host=${host.value}" + bpt.fold("")(t => s"&bpt=$t")
    for {
      resp <- routes.runZIO(Request.get(URL.decode(s"/api/blocked?$qs").toOption.get))
      body <- resp.body.asString
      r    <- ZIO.fromEither(body.fromJson[BlockedInfoResponse]).mapError(new RuntimeException(_))
    } yield r
  }

  /**
   * POST the public access-request intake. The body is hand-built JSON (rather than
   * `CreateAccessRequest(...).toJson`) so this pin is a wire test: it must keep passing whichever
   * way the model carries `bpt`.
   */
  private def postAccessRequest(
      routes: Routes[Any, Response],
      mac: MacAddress,
      bpt: Option[String],
      note: Option[String],
  ): Task[(Status, String)] = {
    val fields = List(
      Some(s""""mac":"${mac.value}""""),
      Some(s""""host":"${host.value}""""),
      Some(""""kind":"exemption""""),
      note.map(n => s""""note":"$n""""),
      bpt.map(t => s""""bpt":"$t""""),
    ).flatten.mkString(",")
    routes
      .runZIO(
        Request
          .post(
            URL.decode("/api/access-requests").toOption.get,
            Body.fromString(s"{$fields}"),
          )
          .addHeader(Header.ContentType(MediaType.application.json))
          .addHeader("X-Forwarded-For", "203.0.113.9"),
      )
      .flatMap(r => r.body.asString.map((r.status, _)))
  }

  /** Seed the two-household fixture and put `macM` in BOTH households. */
  private def seedShared =
    for {
      two <- TestDatabase.seedTwoHouseholds(macA, macB)
      dr  <- ZIO.service[DeviceRepo]
      xa  <- ZIO.service[Transactor[Task]]
      _   <- dr.upsert(macM, "sharedA", Some(two.profileA), "192.168.1.20", two.hhA)
      _   <-
        sql"INSERT INTO devices(mac,name,profile_id,household_id) VALUES ($macM,'sharedB',${two.profileB},${two.hhB})".update.run
          .transact(xa)
    } yield two

  private def alertHouseholds(xa: Transactor[Task]): Task[List[(HouseholdId, String)]] =
    sql"SELECT household_id, COALESCE(note,'') FROM alerts WHERE kind='access_request' ORDER BY id"
      .query[(HouseholdId, String)]
      .to[List]
      .transact(xa)

  // ── pins ───────────────────────────────────────────────────────────────────

  def spec = suite("block page household derivation (#2566/#2569/#2322)")(
    test("an enrolled router can mint a block-page token for its own household") {
      for {
        _   <- cleanDb
        two <- seedShared
        rr  <- makeRouterRoutes
        tok <- mintBpt(rr, two.tokenB)
      } yield assertTrue(tok.nonEmpty)
    },
    test("GET /api/blocked with household B's bpt renders B's decision, not household 1's") {
      for {
        _     <- cleanDb
        two   <- seedShared
        rr    <- makeRouterRoutes
        br    <- makeBlockedRoutes
        bptB  <- mintBpt(rr, two.tokenB)
        bptA  <- mintBpt(rr, two.tokenA)
        // profileB is paused (seedTwoHouseholds); profileA is not. The shared MAC therefore has a
        // DIFFERENT answer in each household, so a wrong derivation is directly observable.
        infoB <- getBlocked(br, macM, Some(bptB))
        infoA <- getBlocked(br, macM, Some(bptA))
      } yield assertTrue(infoB.blocked) &&
        assertTrue(infoB.reasonClass.contains("paused")) &&
        assertTrue(!infoA.blocked)
    },
    test("GET /api/blocked screen-time numbers come from the bpt's household") {
      for {
        _    <- cleanDb
        two  <- seedShared
        tlr  <- ZIO.service[TimeLimitRepo]
        // Only household B's profile has a daily cap, so a leaked household-1 read shows no cap.
        _    <- tlr.upsert(two.profileB, 45)
        rr   <- makeRouterRoutes
        br   <- makeBlockedRoutes
        bptB <- mintBpt(rr, two.tokenB)
        info <- getBlocked(br, macM, Some(bptB))
      } yield assertTrue(info.dailyLimitMinutes.contains(45))
    },
    test("an access request posted behind household B's router attributes to B") {
      for {
        _       <- cleanDb
        two     <- seedShared
        xa      <- ZIO.service[Transactor[Task]]
        rr      <- makeRouterRoutes
        ar      <- makeAlertRoutes
        bptB    <- mintBpt(rr, two.tokenB)
        (st, _) <- postAccessRequest(ar, macM, Some(bptB), None)
        rows    <- alertHouseholds(xa)
      } yield assertTrue(st == Status.Created) &&
        assertTrue(rows.map(_._1) == List(two.hhB))
    },
    test("a pending household-B request does NOT satisfy a household-A intake, and never leaks") {
      for {
        _          <- cleanDb
        two        <- seedShared
        xa         <- ZIO.service[Transactor[Task]]
        rr         <- makeRouterRoutes
        ar         <- makeAlertRoutes
        bptB       <- mintBpt(rr, two.tokenB)
        bptA       <- mintBpt(rr, two.tokenA)
        _          <- postAccessRequest(ar, macM, Some(bptB), Some("B-secret-note"))
        // Same (mac, host) inside the debounce window, but from household A's router.
        (st, body) <- postAccessRequest(ar, macM, Some(bptA), None)
        rows       <- alertHouseholds(xa)
      } yield assertTrue(st == Status.Created) &&
        // The hh-B row did not suppress hh-A's genuine request…
        assertTrue(rows.map(_._1).toSet == Set(two.hhA, two.hhB)) &&
        // …and hh-B's free text never reached the unauthenticated caller.
        assertTrue(!body.contains("B-secret-note"))
    },
    test("the unauthenticated intake response carries no deviceName / profileName / note") {
      for {
        _          <- cleanDb
        two        <- seedShared
        rr         <- makeRouterRoutes
        ar         <- makeAlertRoutes
        bptB       <- mintBpt(rr, two.tokenB)
        (st, body) <- postAccessRequest(ar, macM, Some(bptB), Some("please"))
      } yield assertTrue(st == Status.Created) &&
        assertTrue(!body.contains("deviceName")) &&
        assertTrue(!body.contains("profileName")) &&
        assertTrue(!body.contains("sharedB")) &&
        assertTrue(!body.contains("B-Kids")) &&
        assertTrue(!body.contains("please"))
    },
  ) @@ TestAspect.sequential
}
