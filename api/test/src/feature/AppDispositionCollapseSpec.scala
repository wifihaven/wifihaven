package wifihaven.api.feature

import wifihaven.api.db.*
import wifihaven.api.policy.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.test.*

import java.time.{LocalDate, LocalDateTime, ZoneOffset}

/**
 * #1630: collapse the per-app disposition readers into one fold so the divergence between
 * `expandAppDispositions` (read for snapshot extraAllowed/extraBlocked) and `exemptPatterns` (read
 * for daily-total exclusion) can no longer recur.
 *
 * The symptom the collapse fixes: a `mode=Allowed, exemptFromDaily=true` app reaches `extraAllowed`
 * (so traffic to it is permitted) but its hosts are NOT excluded from the daily total — because
 * `AppTimeLimitRepo.listForProfile` filters `WHERE mode='time_limited'`, so the exempt flag is
 * invisible to the daily-usage path.
 */
object AppDispositionCollapseSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres] {

  override val bootstrap = TestDatabase.layer

  private val cleanDb = TestDatabase.cleanAndMigrate

  private val today = LocalDate.of(2025, 1, 6) // Monday
  private val noon  = LocalDateTime.of(2025, 1, 6, 12, 0, 0)

  private val kidMac = "aa:bb:cc:11:22:33"

  private def seedRouterRow: ZIO[RouterRepo, Throwable, RouterId] =
    ZIO.serviceWithZIO[RouterRepo](_.create("gw-1630", Sha256Hex.unsafe("e" * 64)))

  /** Seed `minutes` of traffic against `hostname` for `mac` on `today`. */
  private def seedTraffic(
      routerId: RouterId,
      mac: String,
      hostname: String,
      minutes: Int,
  ): ZIO[TrafficReportRepo, Throwable, Unit] =
    ZIO.serviceWithZIO[TrafficReportRepo] { tr =>
      val buckets = minutes / 5
      val day0    = today.atStartOfDay(ZoneOffset.UTC).toInstant
      val inserts = (0 until buckets).map { i =>
        val start = day0.plusSeconds(i * 300L)
        TrafficReportInsert(
          routerId,
          MacAddress.unsafe(mac),
          None,
          HostId.Fqdn(Hostname.unsafe(hostname)),
          today,
          start,
          start.plusSeconds(300),
          300,
          500_000L,
          500_000L,
        )
      }.toList
      tr.insertBatch(inserts).unit
    }

  private def buildPs(
      now: LocalDateTime,
  ): ZIO[
    ProfileRepo & HouseholdSettingsRepo & TimeLimitRepo & AppTimeLimitRepo & DeviceRepo &
      BlocklistRepo & TrafficReportRepo & TimeExtensionRepo & AppRepo & NamedScheduleRepo,
    Nothing,
    PolicyService,
  ] =
    for {
      pr   <- ZIO.service[ProfileRepo]
      hsr  <- ZIO.service[HouseholdSettingsRepo]
      tlr  <- ZIO.service[TimeLimitRepo]
      atlr <- ZIO.service[AppTimeLimitRepo]
      dr   <- ZIO.service[DeviceRepo]
      blr  <- ZIO.service[BlocklistRepo]
      trr  <- ZIO.service[TrafficReportRepo]
      er   <- ZIO.service[TimeExtensionRepo]
      ar   <- ZIO.service[AppRepo]
      nsr  <- ZIO.service[NamedScheduleRepo]
      ref  <- Ref.make(now)
      clk = new Clock.TestClock(ref)
    } yield PolicyServiceLive(
      pr,
      hsr,
      tlr,
      atlr,
      dr,
      blr,
      trr,
      er,
      ar,
      clk,
      Nil,
      nsr,
    )

  private def buildTss: ZIO[
    ProfileRepo & TimeLimitRepo & AppTimeLimitRepo & DeviceRepo & TrafficReportRepo &
      TimeExtensionRepo,
    Nothing,
    TimeStatusService,
  ] =
    for {
      pr   <- ZIO.service[ProfileRepo]
      tlr  <- ZIO.service[TimeLimitRepo]
      atlr <- ZIO.service[AppTimeLimitRepo]
      dr   <- ZIO.service[DeviceRepo]
      trr  <- ZIO.service[TrafficReportRepo]
      er   <- ZIO.service[TimeExtensionRepo]
    } yield new TimeStatusServiceLive(pr, tlr, atlr, dr, trr, er)

  // Use the seeded Adults profile (no schedules, no blocklists) so the test isolates the per-app
  // disposition behavior — the Kids profile carries a 21:00-07:00 schedule + blocklists that would
  // perturb the assertions.
  private def seedProfileAndDevice(
      pr: ProfileRepo,
      dr: DeviceRepo,
  ): Task[(ProfileId, MacAddress)] =
    for {
      pid <- TestLayers.seedAdultsProfile(pr)
      _   <- TestLayers.seedDevice(dr, kidMac, "ipad", pid)
    } yield (pid, MacAddress.unsafe(kidMac))

  private val nowInstant = noon.toInstant(ZoneOffset.UTC)

  def spec = suite("AppDispositionCollapseSpec (#1630)")(
    test("Allowed-mode exempt app does NOT contribute to usedSecondsForProfile") {
      // mode=Allowed, exemptFromDaily=true, no per-app cap. Traffic against the app's host should
      // be excluded from the daily total. Today this FAILS: AppTimeLimitRepo filters
      // mode='time_limited' so the row is invisible to the exempt-pattern derivation, and the
      // traffic counts.
      for {
        _        <- cleanDb
        pr       <- ZIO.service[ProfileRepo]
        dr       <- ZIO.service[DeviceRepo]
        ar       <- ZIO.service[AppRepo]
        hsr      <- ZIO.service[HouseholdSettingsRepo]
        (pid, _) <- seedProfileAndDevice(pr, dr)
        _        <- TestLayers.seedAppAssignment(
          ar,
          pid,
          "khanacademy.org",
          AppMode.Allowed,
          dailyMinutes = None,
          exemptFromDaily = true,
        )
        rid      <- seedRouterRow
        _        <- seedTraffic(rid, kidMac, "khanacademy.org", 20)
        settings <- hsr.get
        tss      <- buildTss
        stOpt    <- tss.todaysState(HouseholdId.Default, nowInstant, settings, pid)
        state = stOpt.get
      } yield assertTrue(state.usedMinutes == 0)
    },
    test("Allowed-mode NON-exempt app DOES contribute to usedSecondsForProfile") {
      // mode=Allowed, exemptFromDaily=false. Traffic counts toward the daily total.
      for {
        _        <- cleanDb
        pr       <- ZIO.service[ProfileRepo]
        dr       <- ZIO.service[DeviceRepo]
        ar       <- ZIO.service[AppRepo]
        hsr      <- ZIO.service[HouseholdSettingsRepo]
        (pid, _) <- seedProfileAndDevice(pr, dr)
        _        <- TestLayers.seedAppAssignment(
          ar,
          pid,
          "khanacademy.org",
          AppMode.Allowed,
          dailyMinutes = None,
          exemptFromDaily = false,
        )
        rid      <- seedRouterRow
        _        <- seedTraffic(rid, kidMac, "khanacademy.org", 20)
        settings <- hsr.get
        tss      <- buildTss
        stOpt    <- tss.todaysState(HouseholdId.Default, nowInstant, settings, pid)
        state = stOpt.get
      } yield assertTrue(state.usedMinutes >= 15)
    },
    test("Allowed-mode exempt app still reaches the kid via extraAllowed") {
      // Snapshot carve-out: the assignment's host appears in the profile's BlockRules.extraAllowed
      // so the router carves it out of every drop. Passes today; verify it still does after the
      // collapse.
      for {
        _        <- cleanDb
        pr       <- ZIO.service[ProfileRepo]
        dr       <- ZIO.service[DeviceRepo]
        ar       <- ZIO.service[AppRepo]
        (pid, _) <- seedProfileAndDevice(pr, dr)
        _        <- TestLayers.seedAppAssignment(
          ar,
          pid,
          "khanacademy.org",
          AppMode.Allowed,
          dailyMinutes = None,
          exemptFromDaily = true,
        )
        ps       <- buildPs(noon)
        snap     <- ps.snapshot
        rules = snap.profiles(pid).rules
      } yield assertTrue(rules.extraAllowed.map(_.value).contains("khanacademy.org"))
    },
    test("Allowed-mode app with no cap does NOT fire a TimeLimit block") {
      // The assignment has no daily_minutes set. Even with substantial usage, the profile should
      // not be blocked under TimeLimit on account of this app. The Adults profile carries no
      // profile-level daily limit either, so any block reason here would be wrong.
      for {
        _        <- cleanDb
        pr       <- ZIO.service[ProfileRepo]
        dr       <- ZIO.service[DeviceRepo]
        ar       <- ZIO.service[AppRepo]
        hsr      <- ZIO.service[HouseholdSettingsRepo]
        (pid, _) <- seedProfileAndDevice(pr, dr)
        _        <- TestLayers.seedAppAssignment(
          ar,
          pid,
          "khanacademy.org",
          AppMode.Allowed,
          dailyMinutes = None,
          exemptFromDaily = false,
        )
        rid      <- seedRouterRow
        _        <- seedTraffic(rid, kidMac, "khanacademy.org", 120)
        settings <- hsr.get
        tss      <- buildTss
        stOpt    <- tss.todaysState(HouseholdId.Default, nowInstant, settings, pid)
        state = stOpt.get
      } yield assertTrue(
        !state.blocked || state.blockReason != Some(MacBlockReason.TimeLimit),
      )
    },
    test("TimeLimited-mode regression: cap still fires") {
      // Existing time-limited behavior is unchanged: mode=TimeLimited with dailyMinutes=60,
      // exemptFromDaily=false, and 60+ min of traffic ⇒ TimeLimit block.
      for {
        _        <- cleanDb
        pr       <- ZIO.service[ProfileRepo]
        dr       <- ZIO.service[DeviceRepo]
        ar       <- ZIO.service[AppRepo]
        hsr      <- ZIO.service[HouseholdSettingsRepo]
        tlr      <- ZIO.service[TimeLimitRepo]
        (pid, _) <- seedProfileAndDevice(pr, dr)
        // A profile daily cap of 60 lets the test observe a TimeLimit block when usage >= 60.
        _        <- tlr.upsert(pid, 60)
        _        <- TestLayers.seedAppAssignment(
          ar,
          pid,
          "youtube.com",
          AppMode.TimeLimited,
          dailyMinutes = Some(60),
          exemptFromDaily = false,
        )
        rid      <- seedRouterRow
        _        <- seedTraffic(rid, kidMac, "youtube.com", 65)
        settings <- hsr.get
        tss      <- buildTss
        stOpt    <- tss.todaysState(HouseholdId.Default, nowInstant, settings, pid)
        state = stOpt.get
      } yield assertTrue(
        state.blocked,
        state.blockReason == Some(MacBlockReason.TimeLimit),
      )
    },
  ) @@ TestAspect.sequential
}
