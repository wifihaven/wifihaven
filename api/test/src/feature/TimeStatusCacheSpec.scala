package wifihaven.api.feature

import wifihaven.api.JwtConfig
import wifihaven.api.auth.*
import wifihaven.api.cache.TimeStatusCache
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
import zio.test.TestAspect

import java.time.{LocalDate, ZoneOffset}

/**
 * Integration coverage for the in-process /api/time/status cache (#802).
 *
 * Drives the real TimeRoutes against an embedded Postgres so cache behavior is observed end-to-end
 * — handler, builder, repo. Tests assert the contract from the issue body: identical bodies on hit,
 * different profileId doesn't collide, mutating writes show up after invalidation, and the
 * Cache-Control header reflects today-vs-past freshness.
 */
object TimeStatusCacheSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val jwtCfg   = JwtConfig(secret = "test-secret-at-least-32-chars!!", expiryHours = 1)
  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwtCfg, clock)
  private val cleanDb  = TestDatabase.cleanAndMigrate

  private val macKid   = "aa:bb:cc:dd:ee:01"
  private val macAdult = "aa:bb:cc:dd:ee:02"

  private def seedRouter: ZIO[RouterRepo, Throwable, RouterId] =
    ZIO.serviceWithZIO[RouterRepo] { rr =>
      for {
        id <- rr.create("test-router", Sha256Hex.unsafe("t" * 64))
        _  <- rr.completeEnrollment(id, Sha256Hex.unsafe("u" * 64))
      } yield id
    }

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
          500_000L,
          500_000L,
        )
      }.toList
      tr.insertBatch(inserts).as(bucketOffset + buckets)
    }

  // Build a TimeRoutes wired with the cache under test. Same shape as TimeApiSpec.
  private def buildRoutes(cache: TimeStatusCache) =
    for {
      auth        <- makeAuth
      deviceRepo  <- ZIO.service[DeviceRepo]
      tlRepo      <- ZIO.service[TimeLimitRepo]
      atlRepo     <- ZIO.service[AppTimeLimitRepo]
      trafficRepo <- ZIO.service[TrafficReportRepo]
      extRepo     <- ZIO.service[TimeExtensionRepo]
      profileRepo <- ZIO.service[ProfileRepo]
      upRepo      <- ZIO.service[UserProfileRepo]
      hsRepo      <- ZIO.service[HouseholdSettingsRepo]
      clock       <- ZIO.service[Clock]
      tss = new wifihaven.api.policy.TimeStatusServiceLive(
        profileRepo,
        tlRepo,
        atlRepo,
        deviceRepo,
        trafficRepo,
        extRepo,
      )
    } yield TimeRoutes.routes(
      auth,
      deviceRepo,
      tlRepo,
      atlRepo,
      trafficRepo,
      extRepo,
      profileRepo,
      upRepo,
      hsRepo,
      tss,
      clock,
      cache,
    )

  private def get(routes: Routes[Any, Response], path: String, token: String): Task[Response] =
    routes.runZIO(
      Request
        .get(URL.decode(path).toOption.get)
        .addHeader(Header.Authorization.Bearer(token)),
    )

  def spec = suite("Time status cache (#802)")(
    test("identical payload on hit; stats reflect hit/miss") {
      for {
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        tlRepo      <- ZIO.service[TimeLimitRepo]
        deviceRepo  <- ZIO.service[DeviceRepo]
        auth        <- makeAuth
        token       <- auth.login("admin", "changeme").map(_.token.value)
        kidsId      <- TestLayers.seedKidsProfile(profileRepo)
        _           <- tlRepo.upsert(kidsId, 120)
        _           <- TestLayers.seedDevice(deviceRepo, macKid, "iPad-Kid", kidsId)
        routerId    <- seedRouter
        today = TestClock.schoolDayAfternoon.toLocalDate
        _      <- seedTraffic(routerId, macKid, "minecraft.net", today, 25)
        cache  <- TimeStatusCache.make()
        routes <- buildRoutes(cache)
        path = s"/api/time/status?profileId=${kidsId.value}&date=$today"
        miss     <- get(routes, path, token)
        missBody <- miss.body.asString
        hit      <- get(routes, path, token)
        hitBody  <- hit.body.asString
        stats    <- cache.snapshot
      } yield assertTrue(
        miss.status == Status.Ok,
        hit.status == Status.Ok,
        missBody == hitBody,
        // 1 profile × 2 requests → 1 miss, 1 hit.
        stats.misses == 1L,
        stats.hits == 1L,
      )
    },
    test("different profileId does not collide in the cache") {
      for {
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        tlRepo      <- ZIO.service[TimeLimitRepo]
        deviceRepo  <- ZIO.service[DeviceRepo]
        auth        <- makeAuth
        token       <- auth.login("admin", "changeme").map(_.token.value)
        kidsId      <- TestLayers.seedKidsProfile(profileRepo)
        adultsId    <- TestLayers.seedAdultsProfile(profileRepo)
        _           <- tlRepo.upsert(kidsId, 120)
        _           <- TestLayers.seedDevice(deviceRepo, macKid, "iPad-Kid", kidsId)
        _           <- TestLayers.seedDevice(deviceRepo, macAdult, "iPad-Adult", adultsId)
        routerId    <- seedRouter
        today = TestClock.schoolDayAfternoon.toLocalDate
        _   <- seedTraffic(routerId, macKid, "minecraft.net", today, 25)
        off <- seedTraffic(routerId, macAdult, "wsj.com", today, 40)
        _ = off
        cache     <- TimeStatusCache.make()
        routes    <- buildRoutes(cache)
        kidsResp  <- get(routes, s"/api/time/status?profileId=${kidsId.value}&date=$today", token)
        kidsBody  <- kidsResp.body.asString
        adultResp <- get(routes, s"/api/time/status?profileId=${adultsId.value}&date=$today", token)
        adultBody <- adultResp.body.asString
        kidsStatus  <- ZIO.fromEither(kidsBody.fromJson[List[ProfileTimeStatus]])
        adultStatus <- ZIO.fromEither(adultBody.fromJson[List[ProfileTimeStatus]])
      } yield assertTrue(
        kidsStatus.size == 1,
        adultStatus.size == 1,
        kidsStatus.head.profileId == kidsId,
        adultStatus.head.profileId == adultsId,
        // Distinct usage values → no key collision masquerading as a hit.
        kidsStatus.head.usedMins == 25,
        adultStatus.head.usedMins == 40,
      )
    },
    test("invalidateAll forces a re-fetch (proxy for TTL expiry)") {
      for {
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        tlRepo      <- ZIO.service[TimeLimitRepo]
        deviceRepo  <- ZIO.service[DeviceRepo]
        trafficRepo <- ZIO.service[TrafficReportRepo]
        auth        <- makeAuth
        token       <- auth.login("admin", "changeme").map(_.token.value)
        kidsId      <- TestLayers.seedKidsProfile(profileRepo)
        _           <- tlRepo.upsert(kidsId, 120)
        _           <- TestLayers.seedDevice(deviceRepo, macKid, "iPad-Kid", kidsId)
        routerId    <- seedRouter
        today = TestClock.schoolDayAfternoon.toLocalDate
        _      <- seedTraffic(routerId, macKid, "minecraft.net", today, 25)
        cache  <- TimeStatusCache.make()
        routes <- buildRoutes(cache)
        path = s"/api/time/status?profileId=${kidsId.value}&date=$today"
        before      <- get(routes, path, token).flatMap(_.body.asString)
        // While cached the new bucket is invisible — that's the contract.
        _           <- seedTraffic(routerId, macKid, "google.com", today, 30, bucketOffset = 100)
        cachedAgain <- get(routes, path, token).flatMap(_.body.asString)
        _           <- cache.invalidateAll
        afterReset  <- get(routes, path, token).flatMap(_.body.asString)
        beforeP     <- ZIO.fromEither(before.fromJson[List[ProfileTimeStatus]])
        cachedP     <- ZIO.fromEither(cachedAgain.fromJson[List[ProfileTimeStatus]])
        afterP      <- ZIO.fromEither(afterReset.fromJson[List[ProfileTimeStatus]])
      } yield assertTrue(
        beforeP.head.usedMins == 25,
        cachedP.head.usedMins == 25,
        afterP.head.usedMins == 55, // 25 + 30 after invalidation re-queries
      )
    },
    test("POST /api/time/extend invalidates the cached daily status for that profile (#946)") {
      for {
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        tlRepo      <- ZIO.service[TimeLimitRepo]
        deviceRepo  <- ZIO.service[DeviceRepo]
        trafficRepo <- ZIO.service[TrafficReportRepo]
        auth        <- makeAuth
        token       <- auth.login("admin", "changeme").map(_.token.value)
        kidsId      <- TestLayers.seedKidsProfile(profileRepo)
        adultsId    <- TestLayers.seedAdultsProfile(profileRepo)
        _ <- tlRepo.upsert(kidsId, 30) // tight cap so the kid is over limit at 25m used + buffer
        _ <- tlRepo.upsert(adultsId, 120)
        _ <- TestLayers.seedDevice(deviceRepo, macKid, "iPad-Kid", kidsId)
        _ <- TestLayers.seedDevice(deviceRepo, macAdult, "iPad-Adult", adultsId)
        routerId <- seedRouter
        today = TestClock.schoolDayAfternoon.toLocalDate
        _      <- seedTraffic(routerId, macKid, "minecraft.net", today, 25)
        _      <- seedTraffic(routerId, macAdult, "wsj.com", today, 40)
        cache  <- TimeStatusCache.make()
        routes <- buildRoutes(cache)
        kidsPath  = s"/api/time/status?profileId=${kidsId.value}&date=$today"
        adultPath = s"/api/time/status?profileId=${adultsId.value}&date=$today"
        // Prime both profiles' cache entries.
        primedKids  <- get(routes, kidsPath, token).flatMap(_.body.asString)
        primedAdult <- get(routes, adultPath, token).flatMap(_.body.asString)
        kidsBefore  <- ZIO.fromEither(primedKids.fromJson[List[ProfileTimeStatus]])
        adultBefore <- ZIO.fromEither(primedAdult.fromJson[List[ProfileTimeStatus]])
        // Grant 15 extra minutes to kids; cache for kids must drop, cache for adults must stay.
        grantBody = GrantExtensionRequest(kidsId, 15, None).toJson
        grantResp       <- routes.runZIO(
          Request
            .post(URL.decode("/api/time/extend").toOption.get, Body.fromString(grantBody))
            .addHeader(Header.Authorization.Bearer(token)),
        )
        statsAfterGrant <- cache.snapshot
        // Refetch — kids should hit the DB again and reflect the new cap; adults should hit the cache.
        refetchKids     <- get(routes, kidsPath, token).flatMap(_.body.asString)
        refetchAdult    <- get(routes, adultPath, token).flatMap(_.body.asString)
        kidsAfter       <- ZIO.fromEither(refetchKids.fromJson[List[ProfileTimeStatus]])
        adultAfter      <- ZIO.fromEither(refetchAdult.fromJson[List[ProfileTimeStatus]])
        finalStats      <- cache.snapshot
      } yield assertTrue(
        grantResp.status == Status.Ok,
        // Before the grant: 30m cap, 0 extension.
        kidsBefore.head.dailyLimitMins.contains(30),
        kidsBefore.head.extensionMins == 0,
        // After the grant + refetch: extension shows up immediately (not after 30s).
        kidsAfter.head.extensionMins == 15,
        kidsAfter.head.remainingMins.contains(20), // 30 + 15 - 25
        // Adults entry survives — invalidation is profile-scoped, not global.
        adultBefore == adultAfter,
        // The kids refetch was a miss (cache was invalidated); the adult refetch was a hit.
        finalStats.misses == statsAfterGrant.misses + 1L,
        finalStats.hits == statsAfterGrant.hits + 1L,
      )
    },
    test("Cache-Control: no-store for today, max-age=3600 for past") {
      for {
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        tlRepo      <- ZIO.service[TimeLimitRepo]
        deviceRepo  <- ZIO.service[DeviceRepo]
        auth        <- makeAuth
        token       <- auth.login("admin", "changeme").map(_.token.value)
        kidsId      <- TestLayers.seedKidsProfile(profileRepo)
        _           <- tlRepo.upsert(kidsId, 60)
        _           <- TestLayers.seedDevice(deviceRepo, macKid, "iPad-Kid", kidsId)
        cache       <- TimeStatusCache.make()
        routes      <- buildRoutes(cache)
        today = TestClock.schoolDayAfternoon.toLocalDate
        past  = today.minusDays(7)
        todayResp <- get(routes, s"/api/time/status?profileId=${kidsId.value}&date=$today", token)
        pastResp  <- get(routes, s"/api/time/status?profileId=${kidsId.value}&date=$past", token)
      } yield assertTrue(
        todayResp.headers.get("Cache-Control").exists(_.contains("no-store")),
        pastResp.headers.get("Cache-Control").exists(_.contains("max-age=3600")),
      )
    },
  ) @@ TestAspect.sequential
}
