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
 * #1980: a host that belongs to an allowed (TimeLimited, within-budget) app's host-set — whether
 * the app's MAIN or SHARED hosts — must take precedence over category-blocklist membership for the
 * profile. The app assignment is an explicit, authoritative allow; a category blocklist is a broad
 * catch. App-allow wins while the app is within its daily budget.
 *
 * Concrete repro: `gimkit.com` is a member of the `games` blocklist AND a host of the Gimkit app,
 * assigned TimeLimited (10 min/day) to a kids profile. While within budget the host must be carved
 * into `extraAllowed` (which the router applies above the `bl_` blocklist drop — #421,
 * `feedback_extraallowed_beats_blocked`). Once the per-app cap exhausts, the cap path moves the
 * distinctive hosts to `extraBlocked` and the blocklist drop becoming redundant is fine.
 *
 * Pre-#1980 a TimeLimited app contributed NOTHING to `extraAllowed` while within budget (the
 * per-app cap path only emits the exhausted → `extraBlocked` direction), so a TimeLimited app host
 * that is also a blocklist member was dropped by the blocklist during its allowed window.
 */
object PolicySnapshotAppBlocklistPrecedenceSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private def makePsAt(dt: LocalDateTime) =
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
      ref    <- Ref.make(dt)
      clk = new Clock.TestClock(ref)
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

  private def seedRouterRow: ZIO[RouterRepo, Throwable, RouterId] =
    ZIO.serviceWithZIO[RouterRepo](_.create("gw-seed", Sha256Hex.unsafe("b" * 64)))

  private def seedTraffic(
      routerId: RouterId,
      mac: String,
      hostname: String,
      date: LocalDate,
      minutes: Int,
  ): ZIO[TrafficReportRepo, Throwable, Unit] =
    ZIO.serviceWithZIO[TrafficReportRepo] { tr =>
      val buckets = minutes / 5
      val today0  = date.atStartOfDay(ZoneOffset.UTC).toInstant
      val inserts = (0 until buckets).map { i =>
        val start = today0.plusSeconds(i * 300L)
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
      tr.insertBatch(inserts).unit
    }

  /**
   * Gimkit: `gimkit.com` distinctive (main) + `gameshared.example` shared; both also in `games`.
   */
  private def seedGimkit(ar: AppRepo, kid: ProfileId, cap: Option[Int]): Task[Unit] =
    for {
      appId <- ar.create("Gimkit", "gimkit", None, None)
      _     <- ar.setHostEntries(
        appId,
        List(
          AppHostEntry(Hostname.unsafe("gimkit.com"), shared = false),
          AppHostEntry(Hostname.unsafe("gameshared.example"), shared = true),
        ),
      )
      _     <- ar.upsertAssignment(appId, kid, AppMode.TimeLimited, cap, exemptFromDaily = false)
    } yield ()

  /** A `games` category blocklist that contains BOTH Gimkit hosts. */
  private def seedGamesBlocklist(blr: BlocklistRepo): Task[Unit] =
    blr.insertBatch(List(("gimkit.com", "games"), ("gameshared.example", "games"))).unit

  def spec = suite("PolicySnapshot — allowed-app host beats category blocklist (#1980)")(
    test(
      "within budget: main + shared app hosts carve into extraAllowed (beat the games blocklist)",
    ) {
      val mac = "aa:bb:cc:dd:ee:80"
      for {
        _    <- cleanDb
        pr   <- ZIO.service[ProfileRepo]
        dr   <- ZIO.service[DeviceRepo]
        ar   <- ZIO.service[AppRepo]
        blr  <- ZIO.service[BlocklistRepo]
        kid  <- TestLayers.seedKidsProfile(pr)
        _    <- pr.setBlockedCategories(kid, List(BlocklistId.unsafe("games")))
        _    <- TestLayers.seedDevice(dr, mac, "kid-ipad", kid)
        _    <- seedGamesBlocklist(blr)
        _    <- seedGimkit(ar, kid, Some(10))
        svc  <- makePsAt(TestClock.schoolDayAfternoon)
        snap <- svc.snapshot
        rules = snap.profiles(kid).rules
        ea    = rules.extraAllowed.map(_.value).toSet
        eb    = rules.extraBlocked.map(_.value).toSet
        bl    = rules.blocklistIds.map(_.value).toSet
      } yield
      // The games blocklist is actually applied to this profile…
      assertTrue(bl.contains("games")) &&
        // …yet both the main and shared app hosts are carved into extraAllowed, so the router's
        // extraAllowed-beats-blocklist precedence (#421) keeps them reachable within budget.
        assertTrue(ea.contains("gimkit.com")) &&
        assertTrue(ea.contains("gameshared.example")) &&
        // Within budget nothing is dropped at the per-app cap layer.
        assertTrue(!eb.contains("gimkit.com")) &&
        assertTrue(!rules.blocked)
    },
    test("budget exhausted: distinctive host falls to extraBlocked and out of extraAllowed") {
      val mac = "aa:bb:cc:dd:ee:81"
      for {
        _    <- cleanDb
        pr   <- ZIO.service[ProfileRepo]
        dr   <- ZIO.service[DeviceRepo]
        ar   <- ZIO.service[AppRepo]
        blr  <- ZIO.service[BlocklistRepo]
        kid  <- TestLayers.seedKidsProfile(pr)
        _    <- pr.setBlockedCategories(kid, List(BlocklistId.unsafe("games")))
        _    <- TestLayers.seedDevice(dr, mac, "kid-ipad", kid)
        rid  <- seedRouterRow
        _    <- seedGamesBlocklist(blr)
        _    <- seedGimkit(ar, kid, Some(10))
        // 10 min on the distinctive host → aggregate hits the 10-min cap.
        _    <- seedTraffic(rid, mac, "gimkit.com", LocalDate.of(2025, 1, 6), 10)
        svc  <- makePsAt(TestClock.schoolDayAfternoon)
        snap <- svc.snapshot
        rules = snap.profiles(kid).rules
        ea    = rules.extraAllowed.map(_.value).toSet
        eb    = rules.extraBlocked.map(_.value).toSet
      } yield
      // Cap exhausted: the app's own cap path moves the distinctive host to the blocked lane and it
      // is no longer carved into extraAllowed (the blocklist drop becoming redundant is fine).
      assertTrue(eb.contains("gimkit.com")) &&
        assertTrue(!ea.contains("gimkit.com"))
    },
    test("/decision agrees: within budget the app host is allowed despite blocklist membership") {
      val mac = "aa:bb:cc:dd:ee:82"
      for {
        _      <- cleanDb
        pr     <- ZIO.service[ProfileRepo]
        dr     <- ZIO.service[DeviceRepo]
        ar     <- ZIO.service[AppRepo]
        blr    <- ZIO.service[BlocklistRepo]
        kid    <- TestLayers.seedKidsProfile(pr)
        _      <- pr.setBlockedCategories(kid, List(BlocklistId.unsafe("games")))
        _      <- TestLayers.seedDevice(dr, mac, "kid-ipad", kid)
        _      <- seedGamesBlocklist(blr)
        _      <- seedGimkit(ar, kid, Some(10))
        ps     <- makePsAt(TestClock.schoolDayAfternoon)
        main   <- ps.decide(mac, "gimkit.com")
        shared <- ps.decide(mac, "gameshared.example")
      } yield assertTrue(main.decision == ConnectionDecision.Allow) &&
        assertTrue(shared.decision == ConnectionDecision.Allow)
    },
    test("/decision agrees: budget exhausted → the distinctive app host is blocked") {
      val mac = "aa:bb:cc:dd:ee:83"
      for {
        _    <- cleanDb
        pr   <- ZIO.service[ProfileRepo]
        dr   <- ZIO.service[DeviceRepo]
        ar   <- ZIO.service[AppRepo]
        blr  <- ZIO.service[BlocklistRepo]
        kid  <- TestLayers.seedKidsProfile(pr)
        _    <- pr.setBlockedCategories(kid, List(BlocklistId.unsafe("games")))
        _    <- TestLayers.seedDevice(dr, mac, "kid-ipad", kid)
        rid  <- seedRouterRow
        _    <- seedGamesBlocklist(blr)
        _    <- seedGimkit(ar, kid, Some(10))
        _    <- seedTraffic(rid, mac, "gimkit.com", LocalDate.of(2025, 1, 6), 10)
        ps   <- makePsAt(TestClock.schoolDayAfternoon)
        main <- ps.decide(mac, "gimkit.com")
      } yield assertTrue(main.decision == ConnectionDecision.Block)
    },
  ) @@ TestAspect.sequential
}
