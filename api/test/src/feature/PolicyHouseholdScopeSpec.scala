package wifihaven.api.feature

import wifihaven.api.db.*
import wifihaven.api.policy.*
import wifihaven.api.routes.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.shared.Clock.TestClock
import wifihaven.testinfra.*
import doobie.Transactor
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*
import zio.test.*

/**
 * #2107 (multi-tenant, epic #622): tenant-isolation pins for the three router-facing PolicyService
 * reads — `snapshot`, `decide`, `renderBlocklist` — now scoped to the household resolved from the
 * router token (#2106). These are pins 3 + 5 from the design (§7) plus the decide-path pin.
 *
 * WIRE UNCHANGED is exercised implicitly: the responses are the ordinary `PolicySnapshot` /
 * `RouterDecisionResponse` / plain-text blocklist shapes — `household_id` appears on none of them.
 */
object PolicyHouseholdScopeSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private val macA = MacAddress.unsafe("aa:bb:cc:00:00:0a")
  private val macB = MacAddress.unsafe("aa:bb:cc:00:00:0b")

  private def makePolicyService =
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
      clock  <- ZIO.service[Clock]
    } yield PolicyServiceLive(pr, hsr, tlr, atlr, dr, blr, trRepo, er, ar, clock): PolicyService

  private def getPolicy(routes: Routes[Any, Response], token: String): Task[PolicySnapshot] =
    for {
      resp <- routes.runZIO(
        Request
          .get(URL.decode("/api/router/policy").toOption.get)
          .addHeader(Header.Authorization.Bearer(token)),
      )
      body <- resp.body.asString
      snap <- ZIO.fromEither(body.fromJson[PolicySnapshot]).mapError(new RuntimeException(_))
    } yield snap

  private def decide(
      routes: Routes[Any, Response],
      token: String,
      mac: MacAddress,
      host: String,
  ): Task[RouterDecisionResponse] =
    for {
      resp <- routes.runZIO(
        Request
          .post(
            URL.decode("/api/router/decision").toOption.get,
            Body.fromString(
              RouterDecisionRequest(mac, Hostname.unsafe(host)).toJson,
            ),
          )
          .addHeader(Header.Authorization.Bearer(token)),
      )
      body <- resp.body.asString
      out <- ZIO.fromEither(body.fromJson[RouterDecisionResponse]).mapError(new RuntimeException(_))
    } yield out

  private def blocklistBody(
      routes: Routes[Any, Response],
      token: String,
      id: String,
  ): Task[(Status, String)] =
    for {
      resp <- routes.runZIO(
        Request
          .get(URL.decode(s"/api/blocklists/$id").toOption.get)
          .addHeader(Header.Authorization.Bearer(token)),
      )
      body <- resp.body.asString
    } yield (resp.status, body)

  def spec = suite("PolicyService household scoping (#2107)")(
    // ── Pin 3: snapshot scoping ────────────────────────────────────────────────
    test("GET /api/router/policy returns ONLY the router's household devices + profiles") {
      for {
        _   <- cleanDb
        two <- TestLayers.seedTwoHouseholds(macA, macB)
        ber <- ZIO.service[BlockEventRepo]
        rr  <- ZIO.service[RouterRepo]
        ps  <- makePolicyService
        routes = RouterRoutes.routes(
          rr,
          ps,
          RouterAuthLive(rr),
          ber,
          TestLayers.TestBlockPageSecret,
        )
        snapA <- getPolicy(routes, two.tokenA)
        snapB <- getPolicy(routes, two.tokenB)
      } yield
      // Household A's router sees ONLY A's device + profile.
      assertTrue(snapA.devices.contains(macA)) &&
        assertTrue(!snapA.devices.contains(macB)) &&
        assertTrue(snapA.profiles.contains(two.profileA)) &&
        assertTrue(!snapA.profiles.contains(two.profileB)) &&
        // Household B's router sees ONLY B's device + profile — no leak either direction.
        assertTrue(snapB.devices.contains(macB)) &&
        assertTrue(!snapB.devices.contains(macA)) &&
        assertTrue(snapB.profiles.contains(two.profileB)) &&
        assertTrue(!snapB.profiles.contains(two.profileA))
    },
    // ── decide-path pin: a MAC in household B is never resolved for household A ──
    test("POST /api/router/decision resolves only the router's household device row") {
      for {
        _   <- cleanDb
        two <- TestLayers.seedTwoHouseholds(macA, macB)
        ber <- ZIO.service[BlockEventRepo]
        rr  <- ZIO.service[RouterRepo]
        ps  <- makePolicyService
        routes = RouterRoutes.routes(
          rr,
          ps,
          RouterAuthLive(rr),
          ber,
          TestLayers.TestBlockPageSecret,
        )
        // Household A asks about household B's MAC: scoped lookup does NOT find it → allow-all.
        aOnB <- decide(routes, two.tokenA, macB, "example.com")
        // Household B asks about its own MAC: resolves profileB (paused) → block.
        bOnB <- decide(routes, two.tokenB, macB, "example.com")
      } yield assertTrue(aOnB.decision == ConnectionDecision.Allow) &&
        assertTrue(bOnB.decision == ConnectionDecision.Block) &&
        assertTrue(bOnB.reason == BlockReason.asWire(MacBlockReason.Paused))
    },
    // ── Pin 5: blocklist authorization over a SHARED global catalog ─────────────
    test("GET /api/blocklists/<id> serves byte-identical content to both households") {
      for {
        _   <- cleanDb
        two <- TestLayers.seedTwoHouseholds(macA, macB)
        blr <- ZIO.service[BlocklistRepo]
        _   <- blr.insertBatch(
          List(("doubleclick.net", "kidsafe"), ("ads.example.com", "kidsafe")),
        )
        ber <- ZIO.service[BlockEventRepo]
        rr  <- ZIO.service[RouterRepo]
        ps  <- makePolicyService
        routes = RouterRoutes.routes(
          rr,
          ps,
          RouterAuthLive(rr),
          ber,
          TestLayers.TestBlockPageSecret,
        )
        (statusA, bodyA) <- blocklistBody(routes, two.tokenA, "kidsafe")
        (statusB, bodyB) <- blocklistBody(routes, two.tokenB, "kidsafe")
      } yield assertTrue(statusA == Status.Ok) &&
        assertTrue(statusB == Status.Ok) &&
        // Shared catalog: the household authorizes access but does NOT scope the content.
        assertTrue(bodyA == bodyB) &&
        assertTrue(bodyA.contains("doubleclick.net\n"))
    },
  ) @@ TestAspect.sequential
}
