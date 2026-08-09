package wifihaven.api.feature

import wifihaven.api.db.*
import wifihaven.api.usage.{AmbientLearnJob, TimeUsedRollupJob}
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.test.*

import java.time.{LocalDate, LocalDateTime, ZoneOffset}

/**
 * #2077: end-to-end feature coverage for the isolation-learned ambient baseline
 * (docs/design/idle-traffic-discrimination.md) on embedded Postgres:
 *
 *   - the learner job turns repeated isolated-span days into `ambient_host_days` rows and the
 *     thresholded ambient set; below-threshold and diverse-context hosts never qualify
 *   - the window prune keeps the table bounded
 *   - with the gate ENABLED, the rollup credits ~0 for an idle-overnight fixture while a diverse
 *     real session keeps its full minutes (the #2016/#2068 guard invariants)
 *   - settings knobs round-trip through the repo
 */
object AmbientBaselineSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres] {

  override val bootstrap = TestDatabase.layer

  private val cleanDb = TestDatabase.cleanAndMigrate

  private def seedRouterRow: ZIO[RouterRepo, Throwable, RouterId] =
    ZIO.serviceWithZIO[RouterRepo](_.create("gw-amb", Sha256Hex.unsafe("a" * 64)))

  private val kidMac = "aa:bb:cc:dd:ee:40"

  // One 60-second bucket at `offsetMin` minutes past midnight UTC on `date`, 1 MB.
  private def seedBucket(
      routerId: RouterId,
      mac: String,
      hostname: String,
      date: LocalDate,
      offsetMin: Int,
      bytes: Long = 1_000_000L,
  ): ZIO[TrafficReportRepo, Throwable, Unit] =
    ZIO.serviceWithZIO[TrafficReportRepo] { tr =>
      val start = date.atStartOfDay(ZoneOffset.UTC).toInstant.plusSeconds(offsetMin * 60L)
      tr.insertBatch(
        List(
          TrafficReportInsert(
            routerId,
            MacAddress.unsafe(mac),
            None,
            HostId.Fqdn(Hostname.unsafe(hostname)),
            date,
            start,
            start.plusSeconds(60),
            10,
            bytes / 2,
            bytes / 2,
          ),
        ),
      ).unit
    }

  // The prod idle-overnight shape: two isolated ambient-host bursts, hours apart.
  private def seedIdleNight(
      rid: RouterId,
      date: LocalDate,
  ): ZIO[TrafficReportRepo, Throwable, Unit] =
    seedBucket(rid, kidMac, "valid.apple.com", date, 2 * 60) *>
      seedBucket(rid, kidMac, "weatherkit.apple.com", date, 4 * 60)

  // A diverse real session: 20 consecutive minutes across three hosts (incl. one ambient).
  private def seedRealSession(
      rid: RouterId,
      date: LocalDate,
      startMin: Int,
  ): ZIO[TrafficReportRepo, Throwable, Unit] =
    ZIO.foreachDiscard(0 until 20) { m =>
      seedBucket(rid, kidMac, "a-z-animals.com", date, startMin + m) *>
        seedBucket(rid, kidMac, "cdn.shopify.com", date, startMin + m) *>
        ZIO.when(m == 10)(seedBucket(rid, kidMac, "valid.apple.com", date, startMin + m)).unit
    }

  private def learnTick(now: java.time.Instant): ZIO[
    AmbientHostsRepo & ProfileRepo & DeviceRepo & AppTimeLimitRepo & TrafficReportRepo &
      HouseholdSettingsRepo,
    Throwable,
    Int,
  ] =
    for {
      ahr <- ZIO.service[AmbientHostsRepo]
      pr  <- ZIO.service[ProfileRepo]
      dr  <- ZIO.service[DeviceRepo]
      atl <- ZIO.service[AppTimeLimitRepo]
      trr <- ZIO.service[TrafficReportRepo]
      hsr <- ZIO.service[HouseholdSettingsRepo]
      n   <- AmbientLearnJob.oneTickForTest(ahr, pr, dr, atl, trr, hsr, now)
    } yield n

  private def utcNoon(d: LocalDate) =
    LocalDateTime.of(d, java.time.LocalTime.NOON).toInstant(ZoneOffset.UTC)

  def spec = suite("AmbientBaselineSpec (#2077)")(
    test("learner: three isolated days make a host ambient; one day does not; prune bounds table") {
      for {
        _   <- cleanDb
        hsr <- ZIO.service[HouseholdSettingsRepo]
        pr  <- ZIO.service[ProfileRepo]
        dr  <- ZIO.service[DeviceRepo]
        ahr <- ZIO.service[AmbientHostsRepo]
        s0  <- hsr.getForHousehold(HouseholdId.Default)
        _   <- hsr.update(HouseholdId.Default, s0.copy(dailyResetTz = ZoneOffset.UTC))
        s   <- hsr.getForHousehold(HouseholdId.Default)
        kid <- TestLayers.seedKidsProfile(pr)
        _   <- TestLayers.seedDevice(dr, kidMac, "kid-ipad", kid)
        rid <- seedRouterRow
        d1 = LocalDate.of(2026, 7, 1)
        d2 = d1.plusDays(1)
        d3 = d1.plusDays(2)
        // valid.apple.com isolated on all three nights; known-issues.apple.com only once;
        // a-z-animals.com only ever inside a diverse session.
        _ <- seedIdleNight(rid, d1) *> seedIdleNight(rid, d2) *> seedIdleNight(rid, d3)
        _ <- seedBucket(rid, kidMac, "known-issues.apple.com", d2, 3 * 60 + 30)
        _ <- seedRealSession(rid, d2, 15 * 60)
        // Run the learner once per following day (each tick learns "yesterday").
        _ <- learnTick(utcNoon(d2)) *> learnTick(utcNoon(d3)) *> learnTick(utcNoon(d3.plusDays(1)))
        ambient <- ahr.ambientHosts(s, d3.plusDays(1))
        window  <- ahr.listWindow(s, d3.plusDays(1))
        // Prune: pretend the window advanced far past the seeded days.
        pruned  <- ahr.pruneBefore(d3.plusDays(30))
        after   <- ahr.listWindow(s, d3.plusDays(31))
      } yield assertTrue(
        ambient == Set("valid.apple.com", "weatherkit.apple.com"),
        window.exists(r => r.host == "known-issues.apple.com" && r.isolatedDays == 1),
        // real-session hosts never appear as candidates at all
        !window.exists(_.host == "a-z-animals.com"),
        !window.exists(_.host == "cdn.shopify.com"),
        pruned > 0,
        after.isEmpty,
      )
    },
    test(
      "gate on: idle-overnight credits 0, real session keeps full minutes, gated <= ungated (rollup e2e)",
    ) {
      for {
        _   <- cleanDb
        hsr <- ZIO.service[HouseholdSettingsRepo]
        pr  <- ZIO.service[ProfileRepo]
        dr  <- ZIO.service[DeviceRepo]
        ahr <- ZIO.service[AmbientHostsRepo]
        ru  <- ZIO.service[TimeUsedRollupRepo]
        aru <- ZIO.service[AppUsedRollupRepo]
        atl <- ZIO.service[AppTimeLimitRepo]
        trr <- ZIO.service[TrafficReportRepo]
        s0  <- hsr.getForHousehold(HouseholdId.Default)
        _   <- hsr.update(HouseholdId.Default, s0.copy(dailyResetTz = ZoneOffset.UTC))
        kid <- TestLayers.seedKidsProfile(pr)
        _   <- TestLayers.seedDevice(dr, kidMac, "kid-ipad", kid)
        rid <- seedRouterRow
        d1    = LocalDate.of(2026, 7, 1)
        d2    = d1.plusDays(1)
        d3    = d1.plusDays(2)
        today = d3.plusDays(1)
        // Learn the ambient set from three idle nights.
        _ <- seedIdleNight(rid, d1) *> seedIdleNight(rid, d2) *> seedIdleNight(rid, d3)
        _ <- learnTick(utcNoon(d2)) *> learnTick(utcNoon(d3)) *> learnTick(utcNoon(today))
        // Today: the same idle-overnight shape plus a 20-min diverse session at 15:00.
        _ <- seedIdleNight(rid, today)
        _ <- seedRealSession(rid, today, 15 * 60)
        now = LocalDateTime.of(today, java.time.LocalTime.of(20, 0)).toInstant(ZoneOffset.UTC)
        // Ungated rollup first (gate off).
        _        <- ZIO.serviceWithZIO[HouseholdSettingsRepo](r =>
          r.getForHousehold(HouseholdId.Default)
            .flatMap(s => r.update(HouseholdId.Default, s.copy(ambientGateEnabled = false))),
        )
        _        <- TimeUsedRollupJob.oneTickForTest(ru, aru, pr, dr, atl, trr, hsr, now, ahr)
        offRolls <- ru.getDayMapForHousehold(HouseholdId.Default, today)
        // Gated rollup.
        _        <- ZIO.serviceWithZIO[HouseholdSettingsRepo](r =>
          r.getForHousehold(HouseholdId.Default)
            .flatMap(s => r.update(HouseholdId.Default, s.copy(ambientGateEnabled = true))),
        )
        _        <- TimeUsedRollupJob.oneTickForTest(ru, aru, pr, dr, atl, trr, hsr, now, ahr)
        onRolls  <- ru.getDayMapForHousehold(HouseholdId.Default, today)
        offMin = (offRolls(kid).usedSeconds / 60L).toInt
        onMin  = (onRolls(kid).usedSeconds / 60L).toInt
      } yield assertTrue(
        // Ungated: the 20-min session plus phantom idle minutes.
        offMin >= 22,
        // Gated: the real session credits in full (ambient row inside it included)...
        onMin >= 20,
        // ...but the isolated overnight bursts are gone.
        onMin <= offMin - 2,
      )
    },
    test("settings knobs round-trip and default to the V63 values") {
      for {
        _   <- cleanDb
        hsr <- ZIO.service[HouseholdSettingsRepo]
        s0  <- hsr.getForHousehold(HouseholdId.Default)
        _   <- hsr.update(
          HouseholdId.Default,
          s0.copy(
            ambientGateEnabled = true,
            ambientIsolationMaxHosts = 3,
            ambientMinIsolatedDays = 4,
            ambientLearningWindowDays = 21,
          ),
        )
        s1  <- hsr.getForHousehold(HouseholdId.Default)
      } yield assertTrue(
        !s0.ambientGateEnabled,
        s0.ambientIsolationMaxHosts == 2,
        s0.ambientMinIsolatedDays == 3,
        s0.ambientLearningWindowDays == 14,
        s1.ambientGateEnabled,
        s1.ambientIsolationMaxHosts == 3,
        s1.ambientMinIsolatedDays == 4,
        s1.ambientLearningWindowDays == 21,
      )
    },
    test("gate off is the status quo: rollup unchanged by learned entries") {
      for {
        _   <- cleanDb
        hsr <- ZIO.service[HouseholdSettingsRepo]
        pr  <- ZIO.service[ProfileRepo]
        dr  <- ZIO.service[DeviceRepo]
        ahr <- ZIO.service[AmbientHostsRepo]
        ru  <- ZIO.service[TimeUsedRollupRepo]
        aru <- ZIO.service[AppUsedRollupRepo]
        atl <- ZIO.service[AppTimeLimitRepo]
        trr <- ZIO.service[TrafficReportRepo]
        s0  <- hsr.getForHousehold(HouseholdId.Default)
        _   <- hsr.update(HouseholdId.Default, s0.copy(dailyResetTz = ZoneOffset.UTC))
        kid <- TestLayers.seedKidsProfile(pr)
        _   <- TestLayers.seedDevice(dr, kidMac, "kid-ipad", kid)
        rid <- seedRouterRow
        today = LocalDate.of(2026, 7, 4)
        _ <- seedIdleNight(rid, today)
        // Plant learned entries directly — with the gate off they must have no effect.
        _ <- ahr.upsertDay(today.minusDays(1), Map("valid.apple.com" -> 1))
        _ <- ahr.upsertDay(today.minusDays(2), Map("valid.apple.com" -> 1))
        _ <- ahr.upsertDay(today.minusDays(3), Map("valid.apple.com" -> 1))
        now = LocalDateTime.of(today, java.time.LocalTime.of(8, 0)).toInstant(ZoneOffset.UTC)
        _     <- TimeUsedRollupJob.oneTickForTest(ru, aru, pr, dr, atl, trr, hsr, now, ahr)
        rolls <- ru.getDayMapForHousehold(HouseholdId.Default, today)
      } yield assertTrue((rolls(kid).usedSeconds / 60L).toInt >= 2)
    },
  ) @@ TestAspect.sequential
}
