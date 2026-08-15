package wifihaven.api.feature

import doobie.*
import doobie.implicits.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import wifihaven.api.JwtConfig
import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.db.TypeMeta.given
import wifihaven.api.routes.*
import wifihaven.shared.*
import wifihaven.shared.Clock.TestClock
import wifihaven.shared.types.*
import wifihaven.testinfra.*
import zio.http.*
import zio.interop.catz.*
import zio.json.*
import zio.test.*
import zio.{Clock as _, *}

import java.time.{Instant, LocalDate}

/**
 * #2708 (multi-tenant, epic #2085/#622) — the ROLLUP tiers of `GET /api/usage/traffic` are
 * household-scoped.
 *
 * `RollupRepo.listHourlyInRange` / `listDailyInRange` took no `HouseholdId` and had no household
 * predicate; their only filter was the MAC list, and `macs = Nil` disabled it. A household with
 * ZERO devices resolves the no-filter path to `Nil` macs (`UsageTrafficQuery.resolveMacs`), and the
 * handler's empty-macs short-circuit only fired when a filter had actually been supplied — so the
 * read widened to EVERY household's rollup rows. `UsageTraffic.buildAggregate` falls back to
 * `mac.value` for an unknown device, so the foreign rows rendered rather than being dropped.
 *
 * This is the #2568 class (which scoped only `listTrafficRollupRows`) reaching the pre-aggregated
 * tiers. The raw tier was already scoped by #2313.
 *
 * Both directions are pinned, per the `MultiTenantSeesOwnDataSpec` convention: the zero-device
 * household reads NOTHING, and — the liveness anchor, without which a rig that returns empty for
 * everyone would pass for free — the household that OWNS the rows still reads them back.
 */
object RollupHouseholdScopeSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private val macA = MacAddress.unsafe("aa:bb:cc:00:00:0a")
  private val macB = MacAddress.unsafe("aa:bb:cc:00:00:0b")

  // The host only household B ever contacted. Its presence anywhere in household A's response body
  // is the leak.
  private val hostB = "b-only.example"

  private val jwt = JwtConfig(secret = "test-secret-at-least-32-chars!!x", expiryHours = 1)

  // Suite clock: Mon 2025-01-06T14:00Z (TestClock.schoolDayAfternoon).
  private val trafficAt = Instant.parse("2025-01-06T10:00:00Z")

  // bucket=1h over a 62h window: cap=Hourly (BucketPolicy.grainForBucket("1h")) and pref=Hourly
  // (windowGrain, 24h < 62h <= 14d), so `UsageTrafficQuery.pickTier` selects the HOURLY rollup.
  private val hourlyFrom = Instant.parse("2025-01-04T00:00:00Z")
  private val hourlyTo   = Instant.parse("2025-01-06T14:00:00Z")

  // bucket=1d over a 30-day window: cap=Daily and pref=Daily (> 14d), so the DAILY rollup is read.
  private val dailyFrom = Instant.parse("2024-12-07T00:00:00Z")
  private val dailyTo   = Instant.parse("2025-01-06T14:00:00Z")

  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      hr    <- ZIO.service[HouseholdRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwt, clock, hr): AuthService

  private def login(auth: AuthService, user: String, pw: String, slug: String): Task[String] =
    auth
      .login(s"$slug/$user", pw)
      .mapError(e => new RuntimeException(s"login failed: $e"))
      .map(_.token.value)

  private def getJson(
      routes: Routes[Any, Response],
      path: String,
      token: String,
  ): Task[(Status, String)] =
    for {
      resp <- routes.runZIO(
        Request.get(URL.decode(path).toOption.get).addHeader(Header.Authorization.Bearer(token)),
      )
      body <- resp.body.asString
    } yield (resp.status, body)

  private def buildRoutes(auth: AuthService) =
    for {
      deviceRepo      <- ZIO.service[DeviceRepo]
      trafficRepo     <- ZIO.service[TrafficReportRepo]
      userProfileRepo <- ZIO.service[UserProfileRepo]
      profileRepo     <- ZIO.service[ProfileRepo]
      appRepo         <- ZIO.service[AppRepo]
      rollupRepo      <- ZIO.service[RollupRepo]
      hsRepo          <- ZIO.service[HouseholdSettingsRepo]
      atlRepo         <- ZIO.service[AppTimeLimitRepo]
      aruRepo         <- ZIO.service[AppUsedRollupRepo]
      clock           <- ZIO.service[Clock]
    } yield UsageRoutes.routes(
      auth,
      deviceRepo,
      trafficRepo,
      userProfileRepo,
      profileRepo,
      appRepo,
      rollupRepo,
      hsRepo,
      atlRepo,
      aruRepo,
      clock,
    )

  /**
   * Seed two households where A has ZERO devices and B owns one device with rollup-backed traffic,
   * then roll `traffic_reports` into both `traffic_hourly` and `traffic_daily`.
   *
   * Deleting A's device is the whole point of the fixture: it is what makes `resolveMacs` return
   * `Nil` on the no-filter path, which is the input that used to widen the rollup read.
   */
  private def seedFixture =
    for {
      two <- TestLayers.seedTwoHouseholds(macA, macB)
      xa  <- ZIO.service[Transactor[Task]]
      tr  <- ZIO.service[TrafficReportRepo]
      ru  <- ZIO.service[RollupRepo]
      _   <- sql"DELETE FROM devices WHERE household_id = ${two.hhA}".update.run.transact(xa)
      _   <- tr.insertBatch(
        List(
          TrafficReportInsert(
            two.routerIdB,
            macB,
            None,
            HostId.Fqdn(Hostname.unsafe(hostB)),
            LocalDate.parse("2025-01-06"),
            trafficAt,
            trafficAt.plusSeconds(300),
            300,
            123_000L,
            456_000L,
          ),
        ),
      )
      _   <- ru.rerollHourly(Instant.parse("2024-12-01T00:00:00Z"))
      _   <- ru.rerollDaily(LocalDate.parse("2024-12-01"))
    } yield two

  private def trafficPath(bucket: String, from: Instant, to: Instant): String =
    s"/api/usage/traffic?bucket=$bucket&groupBy=domain&from=$from&to=$to"

  private def rowsOf(body: String): Task[List[TrafficUsageAggregateRow]] =
    ZIO
      .fromEither(body.fromJson[TrafficUsageResponse])
      .mapError(e => new RuntimeException(s"$e — body=$body"))
      // (the body is carried into the message so a route-level 4xx reads as itself, not as a
      // bare JSON parse error)
      .map(_.aggregateRows)

  // A device row for household A's own MAC is NOT seeded, so `devices` for A is empty and the
  // no-filter read resolves to `Nil` macs — the exact input the leak needed.
  private def scopePin(name: String, bucket: String, from: Instant, to: Instant) =
    test(name) {
      for {
        _            <- cleanDb
        two          <- seedFixture
        auth         <- makeAuth
        routes       <- buildRoutes(auth)
        tokenA       <- login(auth, two.adminA, two.password, two.slugA)
        tokenB       <- login(auth, two.adminB, two.password, two.slugB)
        (stA, bodyA) <- getJson(routes, trafficPath(bucket, from, to), tokenA)
        (stB, bodyB) <- getJson(routes, trafficPath(bucket, from, to), tokenB)
        rowsA        <- rowsOf(bodyA)
        rowsB        <- rowsOf(bodyB)
      } yield assertTrue(stA == Status.Ok, stB == Status.Ok) &&
        // Negative: household A owns no devices and no routers' worth of traffic — it must read
        // nothing, and B's host must not appear anywhere in its body.
        assertTrue(rowsA.isEmpty, !bodyA.contains(hostB), !bodyA.contains(macB.value)) &&
        // Liveness anchor: the rollup rows DO exist and household B reads them back. Without this,
        // an always-empty read would satisfy the negative pin for free.
        assertTrue(
          rowsB.nonEmpty,
          bodyB.contains(hostB),
          rowsB.map(_.totalBytesIn).sum == 123_000L,
          rowsB.map(_.totalBytesOut).sum == 456_000L,
        )
    }

  def spec = suite("#2708 — traffic rollup reads are household-scoped")(
    scopePin(
      "hourly tier — a zero-device household reads NO other household's traffic_hourly rows",
      "1h",
      hourlyFrom,
      hourlyTo,
    ),
    scopePin(
      "daily tier — a zero-device household reads NO other household's traffic_daily rows",
      "1d",
      dailyFrom,
      dailyTo,
    ),
    // The OTHER `MacScope` constructor. `NoDevices` replaced the two hand-rolled
    // `macs.isEmpty && (macsRaw.nonEmpty || profileIds.nonEmpty)` short-circuits, so it needs its
    // own pin: a filter that WAS supplied and selected nothing must read nothing — distinct from
    // the no-filter case above, which reads the whole household.
    test("a supplied filter that selects no device reads nothing (MacScope.NoDevices)") {
      for {
        _      <- cleanDb
        two    <- seedFixture
        auth   <- makeAuth
        routes <- buildRoutes(auth)
        tokenB <- login(auth, two.adminB, two.password, two.slugB)
        // B DOES own rows (the liveness anchor below proves it), but this MAC is not its device.
        unknown = "aa:bb:cc:00:00:ff"
        (stFiltered, filtered) <- getJson(
          routes,
          trafficPath("1h", hourlyFrom, hourlyTo) + s"&mac=$unknown",
          tokenB,
        )
        (stAll, all)           <- getJson(routes, trafficPath("1h", hourlyFrom, hourlyTo), tokenB)
        rowsAll                <- rowsOf(all)
      } yield
      // A MAC with no device row in this household is a 404 (the handler's per-mac guard) — it
      // never reaches the read at all. Either way it must not return another household's rows.
      assertTrue(stFiltered == Status.NotFound, !filtered.contains(hostB)) &&
        // Liveness anchor: the same token over the same window with NO filter does read rows, so
        // the empty/404 above is the filter's doing, not an inert fixture.
        assertTrue(stAll == Status.Ok, rowsAll.nonEmpty, all.contains(hostB))
    },
    // #2708 deliberately widened the no-filter case WITHIN a household: it now restricts by
    // household rather than by the current `devices` list, so traffic whose device row was deleted
    // is still the household's own traffic and still reported. Pre-#2708 it silently vanished.
    // Pinned in both directions — the orphaned rows appear for their OWN household and for no other.
    test("traffic whose device row was deleted still reads back — for its own household only") {
      for {
        _      <- cleanDb
        two    <- seedFixture
        xa     <- ZIO.service[Transactor[Task]]
        // Delete B's device row, leaving its already-rolled traffic orphaned.
        _      <- sql"DELETE FROM devices WHERE household_id = ${two.hhB}".update.run.transact(xa)
        auth   <- makeAuth
        routes <- buildRoutes(auth)
        tokenA <- login(auth, two.adminA, two.password, two.slugA)
        tokenB <- login(auth, two.adminB, two.password, two.slugB)
        (stB, bodyB) <- getJson(routes, trafficPath("1h", hourlyFrom, hourlyTo), tokenB)
        (stA, bodyA) <- getJson(routes, trafficPath("1h", hourlyFrom, hourlyTo), tokenA)
        rowsB        <- rowsOf(bodyB)
        rowsA        <- rowsOf(bodyA)
      } yield assertTrue(stA == Status.Ok, stB == Status.Ok) &&
        // Own household still sees the orphaned rows (labelled by bare MAC).
        assertTrue(rowsB.nonEmpty, bodyB.contains(hostB)) &&
        // And the widening stops at the tenant boundary: A, which owns nothing, still reads nothing.
        assertTrue(rowsA.isEmpty, !bodyA.contains(hostB))
    },
  ) @@ TestAspect.sequential
}
