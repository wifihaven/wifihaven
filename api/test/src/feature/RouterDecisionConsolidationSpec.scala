package wifihaven.api.feature

import wifihaven.api.db.*
import wifihaven.api.policy.*
import wifihaven.shared.*
import wifihaven.shared.Clock.TestClock
import wifihaven.shared.types.*
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.test.*

import java.time.{LocalDate, LocalDateTime, ZoneOffset}

/**
 * #1544 anti-divergence pin. `PolicyServiceLive.decide` (the per-host `/decision` fallback that
 * drives the kid block-page reason) and `PolicyServiceLive.snapshot` (which drives nftables
 * enforcement) must agree on the per-profile verdict. Before #1544 `decide` hand-re-implemented the
 * cap/site/schedule fold the snapshot gets from `TimeStatusService`; #1544 collapses it onto the
 * shared `ProfileDayState`. This spec exercises BOTH paths for the SAME profile + `now` + host and
 * asserts they encode the same decision across paused / schedule / daily-limit / site-limit /
 * exempt-app scenarios — so a future edit to the fold that touches only one path is loud.
 *
 * Mapping between the two shapes:
 *   - a whole-MAC block (`paused` / `schedule` / `time_limit`) ⇒ `snapshot.rules.blocked == true`
 *     with the matching `MacBlockReason`;
 *   - a per-host site-limit block ⇒ the host appears in `snapshot.rules.extraBlocked`;
 *   - an exempt-app carve-out ⇒ the host appears in `snapshot.rules.extraAllowed` (which beats the
 *     whole-MAC block at the router), so both paths allow it.
 */
object RouterDecisionConsolidationSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private val mac   = "aa:bb:cc:15:44:01"
  private val today = LocalDate.of(2025, 1, 6) // a Monday

  private def makePsAt(dt: LocalDateTime) =
    for {
      pr     <- ZIO.service[ProfileRepo]
      sr     <- ZIO.service[ScheduleRepo]
      hsr    <- ZIO.service[HouseholdSettingsRepo]
      tlr    <- ZIO.service[TimeLimitRepo]
      atlr   <- ZIO.service[AppTimeLimitRepo]
      dr     <- ZIO.service[DeviceRepo]
      blr    <- ZIO.service[BlocklistRepo]
      trRepo <- ZIO.service[TrafficReportRepo]
      er     <- ZIO.service[TimeExtensionRepo]
      ar     <- ZIO.service[AppRepo]
      nsr    <- ZIO.service[NamedScheduleRepo]
      ref    <- Ref.make(dt)
      clk = new Clock.TestClock(ref)
    } yield PolicyServiceLive(
      pr,
      sr,
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

  private def seedRouterRow: ZIO[RouterRepo, Throwable, RouterId] =
    ZIO.serviceWithZIO[RouterRepo](_.create("gw-cons-dec", Sha256Hex.unsafe("d" * 64)))

  /** Seed `minutes/5` non-overlapping 5-min traffic_reports buckets on `host`. */
  private def seedTraffic(
      rid: RouterId,
      host: String,
      minutes: Int,
      bucketOffset: Int = 0,
  ): ZIO[TrafficReportRepo, Throwable, Int] =
    ZIO.serviceWithZIO[TrafficReportRepo] { tr =>
      val buckets = minutes / 5
      val day0    = today.atStartOfDay(ZoneOffset.UTC).toInstant
      val inserts = (0 until buckets).map { i =>
        val start = day0.plusSeconds((bucketOffset + i) * 300L)
        TrafficReportInsert(
          rid,
          MacAddress.unsafe(mac),
          None,
          HostId.Fqdn(Hostname.unsafe(host)),
          today,
          start,
          start.plusSeconds(300),
          300,
          500_000L,
          500_000L,
        )
      }.toList
      tr.insertBatch(inserts).as(bucketOffset + buckets)
    }

  /** The MacBlockReason a whole-MAC decide() reason string maps to. */
  private def reasonToMac(r: String): Option[MacBlockReason] = r match {
    case "paused"     => Some(MacBlockReason.Paused)
    case "schedule"   => Some(MacBlockReason.Schedule)
    case "time_limit" => Some(MacBlockReason.TimeLimit)
    case _            => None
  }

  def spec = suite("RouterDecisionConsolidationSpec (#1544)")(
    test("paused: decide=block:paused matches snapshot blocked+Paused") {
      for {
        _    <- cleanDb
        pr   <- ZIO.service[ProfileRepo]
        sr   <- ZIO.service[ScheduleRepo]
        dr   <- ZIO.service[DeviceRepo]
        kid  <- TestLayers.seedKidsProfile(pr, sr)
        _    <- pr.setPaused(kid, true)
        _    <- TestLayers.seedDevice(dr, mac, "kid", kid)
        ps   <- makePsAt(TestClock.schoolDayAfternoon)
        d    <- ps.decide(mac, "example.com")
        snap <- ps.snapshot
        rules = snap.profiles(kid).rules
      } yield assertTrue(
        d.decision == ConnectionDecision.Block,
        d.reason == "paused",
        rules.blocked,
        rules.blockReason == Some(MacBlockReason.Paused),
        reasonToMac(d.reason) == rules.blockReason,
      )
    },
    test("schedule active: decide=block:schedule matches snapshot blocked+Schedule") {
      for {
        _    <- cleanDb
        pr   <- ZIO.service[ProfileRepo]
        sr   <- ZIO.service[ScheduleRepo]
        dr   <- ZIO.service[DeviceRepo]
        kid  <- TestLayers.seedKidsProfile(pr, sr) // Bedtime 21:00–07:00
        _    <- TestLayers.seedDevice(dr, mac, "kid", kid)
        ps   <- makePsAt(TestClock.bedtime)        // Monday 21:30 → schedule active
        d    <- ps.decide(mac, "example.com")
        snap <- ps.snapshot
        rules = snap.profiles(kid).rules
      } yield assertTrue(
        d.decision == ConnectionDecision.Block,
        d.reason == "schedule",
        rules.blocked,
        rules.blockReason == Some(MacBlockReason.Schedule),
        reasonToMac(d.reason) == rules.blockReason,
      )
    },
    test("daily limit reached: decide=block:time_limit matches snapshot blocked+TimeLimit") {
      for {
        _    <- cleanDb
        pr   <- ZIO.service[ProfileRepo]
        sr   <- ZIO.service[ScheduleRepo]
        dr   <- ZIO.service[DeviceRepo]
        tlr  <- ZIO.service[TimeLimitRepo]
        kid  <- TestLayers.seedKidsProfile(pr, sr)
        _    <- tlr.upsert(kid, 120)
        _    <- TestLayers.seedDevice(dr, mac, "kid", kid)
        rid  <- seedRouterRow
        _    <- seedTraffic(rid, "cnn.com", 125)
        ps   <- makePsAt(TestClock.schoolDayAfternoon) // 14:00, no schedule
        d    <- ps.decide(mac, "cnn.com")
        snap <- ps.snapshot
        rules = snap.profiles(kid).rules
      } yield assertTrue(
        d.decision == ConnectionDecision.Block,
        d.reason == "time_limit",
        rules.blocked,
        rules.blockReason == Some(MacBlockReason.TimeLimit),
        reasonToMac(d.reason) == rules.blockReason,
      )
    },
    test("app limit reached: decide=block:app_time_limit matches snapshot extraBlocked host") {
      for {
        _    <- cleanDb
        pr   <- ZIO.service[ProfileRepo]
        sr   <- ZIO.service[ScheduleRepo]
        dr   <- ZIO.service[DeviceRepo]
        ar   <- ZIO.service[AppRepo]
        kid  <- TestLayers.seedKidsProfile(pr, sr)
        // Site cap 30, non-exempt (counts to daily, but no daily cap set), usage 35 → site hit.
        _    <- TestLayers.seedAppAssignment(
          ar,
          kid,
          "youtube.com",
          AppMode.TimeLimited,
          dailyMinutes = Some(30),
          exemptFromDaily = false,
        )
        _    <- TestLayers.seedDevice(dr, mac, "kid", kid)
        rid  <- seedRouterRow
        _    <- seedTraffic(rid, "youtube.com", 35)
        ps   <- makePsAt(TestClock.schoolDayAfternoon)
        d    <- ps.decide(mac, "youtube.com")
        snap <- ps.snapshot
        rules = snap.profiles(kid).rules
      } yield assertTrue(
        d.decision == ConnectionDecision.Block,
        d.reason.startsWith("app_time_limit:"),
        // The whole-MAC block is NOT set for a per-site cap; enforcement is via extraBlocked.
        !rules.blocked,
        rules.extraBlocked.contains(Hostname.unsafe("youtube.com")),
      )
    },
    test("exempt app under exhausted daily cap: both allow the exempt host, block the non-exempt") {
      for {
        _          <- cleanDb
        pr         <- ZIO.service[ProfileRepo]
        sr         <- ZIO.service[ScheduleRepo]
        dr         <- ZIO.service[DeviceRepo]
        tlr        <- ZIO.service[TimeLimitRepo]
        ar         <- ZIO.service[AppRepo]
        kid        <- TestLayers.seedKidsProfile(pr, sr)
        _          <- tlr.upsert(kid, 30) // daily cap 30
        // Khan is exempt from the daily cap with plenty of per-site budget.
        _          <- TestLayers.seedAppAssignment(
          ar,
          kid,
          "khanacademy.org",
          AppMode.TimeLimited,
          dailyMinutes = Some(200),
          exemptFromDaily = true,
        )
        _          <- TestLayers.seedDevice(dr, mac, "kid", kid)
        rid        <- seedRouterRow
        // 35 min of non-exempt cnn.com (exhausts the 30 cap) + 10 min of exempt khan.
        n          <- seedTraffic(rid, "cnn.com", 35)
        _          <- seedTraffic(rid, "khanacademy.org", 10, bucketOffset = n)
        ps         <- makePsAt(TestClock.schoolDayAfternoon)
        dExempt    <- ps.decide(mac, "khanacademy.org")
        dNonExempt <- ps.decide(mac, "cnn.com")
        snap       <- ps.snapshot
        rules = snap.profiles(kid).rules
      } yield assertTrue(
        // Daily cap exhausted (exempt time excluded) → whole-MAC TimeLimit on the snapshot…
        rules.blocked,
        rules.blockReason == Some(MacBlockReason.TimeLimit),
        // …but the exempt app is carved out (extraAllowed beats the block at the router) and decide
        // agrees by allowing it.
        rules.extraAllowed.contains(Hostname.unsafe("khanacademy.org")),
        dExempt.decision == ConnectionDecision.Allow,
        // A non-exempt host is blocked by both paths.
        !rules.extraAllowed.contains(Hostname.unsafe("cnn.com")),
        dNonExempt.decision == ConnectionDecision.Block,
        dNonExempt.reason == "time_limit",
      )
    },
  ) @@ TestAspect.sequential
}
