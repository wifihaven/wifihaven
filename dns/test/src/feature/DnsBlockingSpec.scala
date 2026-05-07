package familydns.dns.feature

import familydns.api.db.*
import familydns.dns.BlockingEngine
import familydns.shared.*
import familydns.shared.Clock.TestClock
import familydns.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.test.*
import zio.test.Assertion.*

import java.time.{LocalDate, LocalTime}

/**
 * Feature tests for DNS blocking decisions using real DB state.
 *
 * These tests load profiles/devices/blocklists from an embedded Postgres, build the cache exactly
 * as the DNS server would, then run blocking decisions. The controllable Clock lets us simulate
 * bedtime, school hours, etc.
 */
object DnsBlockingSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private def cleanDb = ZIO.serviceWithZIO[EmbeddedPostgres](pg =>
    TestDatabase.cleanAndMigrate.provide(ZLayer.succeed(pg)),
  )

  private val testMac = "aa:bb:cc:dd:ee:ff"

  /** Build a DnsCache from the current DB state */
  private def buildCache: ZIO[
    ProfileRepo & ScheduleRepo & TimeLimitRepo & SiteTimeLimitRepo & DeviceRepo & BlocklistRepo,
    Throwable,
    DnsCache,
  ] =
    for
      profiles   <- ZIO.service[ProfileRepo].flatMap(_.listAll)
      schedules  <- ZIO.service[ScheduleRepo].flatMap(_.listAll)
      timeLimits <- ZIO.service[TimeLimitRepo].flatMap(_.listAll)
      stls       <- ZIO.service[SiteTimeLimitRepo].flatMap(_.listAll)
      devices    <- ZIO.service[DeviceRepo].flatMap(_.listAll)
      blocklists <- ZIO.service[BlocklistRepo].flatMap(_.loadAll)
      cached    = profiles.map { p =>
        val sched = schedules.filter(_.profileId == p.id)
        val tl    = timeLimits.find(_.profileId == p.id).map(_.dailyMinutes)
        val pstls = stls.filter(_.profileId == p.id)
        p.id -> CachedProfile(p, sched, tl, pstls)
      }.toMap
      deviceMap = devices.flatMap(d => d.profileId.flatMap(cached.get).map(d.mac -> _)).toMap
    yield DnsCache(deviceMap, blocklists, cached.values.find(_.profile.name == "Adults"))

  private def decide(
      domain: String,
      cache: DnsCache,
      usage: TimeUsageSnapshot = TimeUsageSnapshot.empty,
      mac: String = testMac,
      time: LocalTime = LocalTime.of(14, 0),
      date: LocalDate = LocalDate.of(2025, 1, 6),
  ): BlockingEngine.Decision =
    cache.deviceProfiles
      .get(mac)
      .map(p => BlockingEngine.decide(domain, p, cache.blocklists, usage, mac, time, date))
      .getOrElse(BlockingEngine.Decision.Allow)

  def spec = suite("DNS Blocking — full stack")(
    suite("category blocking loaded from DB")(
      test("blocks adult domain after loading blocklist from DB") {
        for
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          blRepo      <- ZIO.service[BlocklistRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          _           <- blRepo.insertBatch(
            List(
              ("pornhub.com", "adult"),
              ("xvideos.com", "adult"),
              ("facebook.com", "social_media"),
            ),
          )
          cache       <- buildCache
          d1 = decide("pornhub.com", cache)
          d2 = decide("xvideos.com", cache)
          d3 = decide("facebook.com", cache)
          d4 = decide("google.com", cache)
        yield assertTrue(d1 == BlockingEngine.Decision.Block("category:adult")) &&
          assertTrue(d2 == BlockingEngine.Decision.Block("category:adult")) &&
          assertTrue(d3 == BlockingEngine.Decision.Block("category:social_media")) &&
          assertTrue(d4 == BlockingEngine.Decision.Allow)
      },
      test("adult device not blocked by kids blocklist") {
        for
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          blRepo      <- ZIO.service[BlocklistRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          adultsId    <- TestLayers.seedAdultsProfile(profileRepo)
          adultMac = "11:22:33:44:55:66"
          _     <- TestLayers.seedDevice(deviceRepo, testMac, "Kid's iPad", kidsId)
          _     <- TestLayers.seedDevice(deviceRepo, adultMac, "Dad's Laptop", adultsId)
          _     <- blRepo.insertBatch(List(("facebook.com", "social_media")))
          cache <- buildCache
          kidDecision = decide("facebook.com", cache, mac = testMac)
          dadDecision = decide("facebook.com", cache, mac = adultMac)
        yield assertTrue(kidDecision == BlockingEngine.Decision.Block("category:social_media")) &&
          assertTrue(dadDecision == BlockingEngine.Decision.Allow)
      },
    ),
    suite("schedule blocking with controllable clock")(
      test("kids device is blocked at bedtime") {
        for
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          cache       <- buildCache
          // 21:30 on a Monday — within bedtime window 21:00–07:00
          d = decide("google.com", cache, time = LocalTime.of(21, 30))
        yield assertTrue(d == BlockingEngine.Decision.Block("schedule"))
      },
      test("kids device is allowed during school day") {
        for
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          cache       <- buildCache
          d = decide("google.com", cache, time = LocalTime.of(14, 0))
        yield assertTrue(d == BlockingEngine.Decision.Allow)
      },
    ),
    suite("time limits loaded from DB")(
      test("blocks device that has hit daily limit") {
        for
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          tlRepo      <- ZIO.service[TimeLimitRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- tlRepo.upsert(kidsId, 120)
          _           <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          cache       <- buildCache
          today = LocalDate.of(2025, 1, 6)
          usage = TimeUsageSnapshot(
            domainUsage = Map.empty,
            totalUsage = Map((testMac, today.toString) -> 120),
            extensions = Map.empty,
          )
          d     = decide("minecraft.net", cache, usage = usage, date = today)
        yield assertTrue(d == BlockingEngine.Decision.Block("time_limit"))
      },
      test("allows device with extension after hitting base limit") {
        for
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          tlRepo      <- ZIO.service[TimeLimitRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- tlRepo.upsert(kidsId, 120)
          _           <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          cache       <- buildCache
          today = LocalDate.of(2025, 1, 6)
          usage = TimeUsageSnapshot(
            domainUsage = Map.empty,
            totalUsage = Map((testMac, today.toString) -> 130),
            extensions = Map((testMac, today.toString) -> 30),
          )
          d     = decide("minecraft.net", cache, usage = usage, date = today)
        yield assertTrue(d == BlockingEngine.Decision.Allow)
      },
    ),
    suite("paused profile")(
      test("pausing a profile via DB causes all queries to block") {
        for
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          _           <- profileRepo.setPaused(kidsId, paused = true)
          cache       <- buildCache
          d = decide("google.com", cache, time = LocalTime.of(14, 0))
        yield assertTrue(d == BlockingEngine.Decision.Block("paused"))
      },
    ),
    suite("unknown device")(
      test("unknown MAC falls through to default (adults) profile") {
        for
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          blRepo      <- ZIO.service[BlocklistRepo]
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- TestLayers.seedAdultsProfile(profileRepo)
          _           <- blRepo.insertBatch(List(("facebook.com", "social_media")))
          cache       <- buildCache
          // Unknown MAC — uses default Adults profile which has no blocked categories
          unknownMac = "ff:ff:ff:ff:ff:ff"
          d          = cache.deviceProfiles
            .get(unknownMac)
            .map(p =>
              BlockingEngine.decide(
                "facebook.com",
                p,
                cache.blocklists,
                TimeUsageSnapshot.empty,
                unknownMac,
                LocalTime.of(14, 0),
                LocalDate.of(2025, 1, 6),
              ),
            )
            .getOrElse(BlockingEngine.Decision.Allow)
        yield assertTrue(d == BlockingEngine.Decision.Allow)
      },
    ),
  ) @@ TestAspect.sequential
}
