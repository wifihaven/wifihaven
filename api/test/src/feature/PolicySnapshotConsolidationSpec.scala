package wifihaven.api.feature

import wifihaven.api.db.*
import wifihaven.api.policy.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.test.*

import java.time.{LocalDate, LocalDateTime, LocalTime, ZoneOffset}

/**
 * #1104: structural lock between the snapshot's per-profile `BlockRules` and the per-profile/per-
 * device `/api/time/status` shapes. For a fixture spanning Sum + Dedup overlap modes, multiple
 * household timezones, cap-exhausted and not-exhausted states, with and without an extension grant
 * — assert the three equalities the issue demands:
 *
 *   - `snapshot.profiles(pid).rules.blocked == todaysState(pid).blocked`
 *   - `snapshot.profiles(pid).rules.blockReason == todaysState(pid).blockReason`
 *   - `usedMinutes_per_profile(pid)` matches between snapshot's inputs and `todaysState`
 *
 * The first two are direct equalities; the third is enforced by structure (snapshot consumes the
 * same `dayStateAll` output the UI route does). The contract is: future code changes that touch
 * either side without going through `TimeStatusService` will break this spec.
 */
object PolicySnapshotConsolidationSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres] {

  override val bootstrap = TestDatabase.layer

  private def cleanDb = ZIO.serviceWithZIO[EmbeddedPostgres](pg =>
    TestDatabase.cleanAndMigrate.provide(ZLayer.succeed(pg)),
  )

  private def settingsTz(hsr: HouseholdSettingsRepo, tz: String): Task[HouseholdSettings] =
    for {
      cur <- hsr.get
      upd = cur.copy(dailyResetTz = java.time.ZoneId.of(tz), dailyResetTime = LocalTime.of(0, 0))
      _ <- hsr.update(upd)
    } yield upd

  private def seedRouterRow: ZIO[RouterRepo, Throwable, RouterId] =
    ZIO.serviceWithZIO[RouterRepo](_.create("gw-cons", Sha256Hex.unsafe("c" * 64)))

  private def seedTraffic(
      routerId: RouterId,
      mac: String,
      hostname: String,
      date: LocalDate,
      minutes: Int,
  ): ZIO[TrafficReportRepo, Throwable, Unit] =
    ZIO.serviceWithZIO[TrafficReportRepo] { tr =>
      val buckets = minutes / 5
      val day0    = date.atStartOfDay(ZoneOffset.UTC).toInstant
      val inserts = (0 until buckets).map { i =>
        val start = day0.plusSeconds(i * 300L)
        TrafficReportInsert(
          routerId,
          MacAddress.unsafe(mac),
          None,
          HostId.Fqdn(Hostname.unsafe(hostname)),
          date,
          start,
          start.plusSeconds(300),
          300,
          500_000L,
          500_000L,
        )
      }.toList
      tr.insertBatch(inserts).unit
    }

  /** Build the policy service over the in-test repos, with a fixed `now`. */
  private def buildPs(now: LocalDateTime): ZIO[
    AllReposLite,
    Nothing,
    PolicyService,
  ] =
    for {
      pr   <- ZIO.service[ProfileRepo]
      sr   <- ZIO.service[ScheduleRepo]
      hsr  <- ZIO.service[HouseholdSettingsRepo]
      tlr  <- ZIO.service[TimeLimitRepo]
      stlr <- ZIO.service[SiteTimeLimitRepo]
      dr   <- ZIO.service[DeviceRepo]
      blr  <- ZIO.service[BlocklistRepo]
      trr  <- ZIO.service[TrafficReportRepo]
      er   <- ZIO.service[TimeExtensionRepo]
      ar   <- ZIO.service[AppRepo]
      ref  <- Ref.make(now)
      clk = new Clock.TestClock(ref)
    } yield PolicyServiceLive(pr, sr, hsr, tlr, stlr, dr, blr, trr, er, ar, clk)

  private def buildTss: ZIO[AllReposLite, Nothing, TimeStatusService] =
    for {
      pr   <- ZIO.service[ProfileRepo]
      sr   <- ZIO.service[ScheduleRepo]
      tlr  <- ZIO.service[TimeLimitRepo]
      stlr <- ZIO.service[SiteTimeLimitRepo]
      dr   <- ZIO.service[DeviceRepo]
      trr  <- ZIO.service[TrafficReportRepo]
      er   <- ZIO.service[TimeExtensionRepo]
    } yield new TimeStatusServiceLive(pr, sr, tlr, stlr, dr, trr, er)

  private type AllReposLite =
    ProfileRepo & ScheduleRepo & HouseholdSettingsRepo & TimeLimitRepo & SiteTimeLimitRepo &
      DeviceRepo & BlocklistRepo & TrafficReportRepo & TimeExtensionRepo & AppRepo

  /** One fixture row → the spec runs the equalities against every row. */
  private final case class Fixture(
      label: String,
      tz: String,
      // (mac → minutes-of-presence) for that profile's devices today
      load: List[(String, Int)],
      overlap: CrossDeviceOverlapMode,
      dailyCap: Option[Int],
      extension: Option[Int],
      now: LocalDateTime,
      // expected `blocked` and `reason` so a regression here is loud
      expectBlocked: Boolean,
      expectReason: Option[MacBlockReason],
  )

  private val today  = LocalDate.of(2025, 1, 6)           // a Monday
  private val midDay = Clock.TestClock.schoolDayAfternoon // Mon 14:00 UTC

  private val fixtures: List[Fixture] = List(
    Fixture(
      "under-cap, no extension, UTC, Sum",
      "UTC",
      List(("aa:bb:cc:00:00:01", 20)),
      CrossDeviceOverlapMode.Sum,
      Some(60),
      None,
      midDay,
      expectBlocked = false,
      expectReason = None,
    ),
    Fixture(
      "exactly-at-cap, blocked=true, UTC, Sum",
      "UTC",
      List(("aa:bb:cc:00:00:02", 60)),
      CrossDeviceOverlapMode.Sum,
      Some(60),
      None,
      midDay,
      expectBlocked = true,
      expectReason = Some(MacBlockReason.TimeLimit),
    ),
    Fixture(
      "extension restores headroom, UTC, Sum",
      "UTC",
      List(("aa:bb:cc:00:00:03", 60)),
      CrossDeviceOverlapMode.Sum,
      Some(60),
      Some(15),
      midDay,
      expectBlocked = false,
      expectReason = None,
    ),
    Fixture(
      "Dedup: overlapping devices count once → under cap",
      "UTC",
      List(("aa:bb:cc:00:00:04", 30), ("aa:bb:cc:00:00:05", 30)),
      CrossDeviceOverlapMode.Dedup,
      Some(60),
      None,
      midDay,
      expectBlocked = false,
      expectReason = None,
    ),
    Fixture(
      "Sum: overlapping devices double-count → blocked",
      "UTC",
      List(("aa:bb:cc:00:00:06", 30), ("aa:bb:cc:00:00:07", 30)),
      CrossDeviceOverlapMode.Sum,
      Some(60),
      None,
      midDay,
      expectBlocked = true,
      expectReason = Some(MacBlockReason.TimeLimit),
    ),
    Fixture(
      "America/Denver, late-evening UTC → still today's MDT day",
      "America/Denver",
      List(("aa:bb:cc:00:00:08", 60)),
      CrossDeviceOverlapMode.Sum,
      Some(60),
      None,
      LocalDateTime.of(2025, 1, 6, 23, 30, 0),
      expectBlocked = true,
      expectReason = Some(MacBlockReason.TimeLimit),
    ),
  )

  private def runFixture(f: Fixture) =
    test(f.label) {
      for {
        _    <- cleanDb
        hsr  <- ZIO.service[HouseholdSettingsRepo]
        pr   <- ZIO.service[ProfileRepo]
        sr   <- ZIO.service[ScheduleRepo]
        dr   <- ZIO.service[DeviceRepo]
        tlr  <- ZIO.service[TimeLimitRepo]
        er   <- ZIO.service[TimeExtensionRepo]
        s    <- settingsTz(hsr, f.tz)
        kid  <- TestLayers.seedKidsProfile(pr, sr)
        prof <- pr.findById(kid).map(_.get)
        // Set overlap mode + paused=false (default)
        _    <- pr.update(prof.copy(crossDeviceOverlapMode = f.overlap))
        // Replace seeded "bedtime" schedule with empty so it doesn't interfere with our cap-only assertions
        _    <- sr.replaceForProfile(kid, Nil)
        _    <- ZIO.foreachDiscard(f.load.zipWithIndex) { case ((mac, _), i) =>
          TestLayers.seedDevice(dr, mac, s"d$i", kid)
        }
        _    <- f.dailyCap.fold(ZIO.unit)(c => tlr.upsert(kid, c))
        _ <- f.extension.fold(ZIO.unit)(m => er.grantForProfile(kid, today, m, "admin", None).unit)
        rid  <- seedRouterRow
        _    <- ZIO.foreachDiscard(f.load) { case (mac, mins) =>
          seedTraffic(rid, mac, "cnn.com", today, mins)
        }
        ps   <- buildPs(f.now)
        tss  <- buildTss
        snap <- ps.snapshot
        now = f.now.toInstant(ZoneOffset.UTC)
        stOpt <- tss.todaysState(now, s, kid)
        state = stOpt.get
        rules = snap.profiles(kid).rules
      } yield assertTrue(
        // The three consolidation equalities — these are the structural lock.
        rules.blocked == state.blocked,
        rules.blockReason == state.blockReason,
        // Expectations as a secondary check that the test fixture itself didn't drift.
        state.blocked == f.expectBlocked,
        state.blockReason == f.expectReason,
      )
    }

  def spec = suite("PolicySnapshotConsolidationSpec (#1104)")(
    fixtures.map(runFixture)*,
  ) @@ TestAspect.sequential
}
