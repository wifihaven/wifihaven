package wifihaven.api.feature

import wifihaven.api.db.*
import wifihaven.api.policy.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.shared.Clock.TestClock
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.test.*

import java.time.{LocalDateTime, LocalTime, ZoneId}

/**
 * #1174 regression pin: a MAC blocked by a SCHEDULE downtime window — combined with deployment-
 * configured `uiAllowedHosts` — must still let the SPA hosts through, because the kid sees the
 * block page (and the request-an-extension flow) on the SPA. After #1318/#1319/#1321 the ui hosts
 * ride in `snapshot.global.extraAllowed` (one fleet-wide @global_allow, carving every drop incl.
 * @blocked_macs),
 *   not per-profile `extraAllowed`. This spec pins both halves at the API layer:
 *
 *   - the schedule actually blocks the MAC (rules.blocked = true, blockReason = Schedule)
 *   - global.extraAllowed contains every configured ui host so the router can carve them out
 *
 * Bug-class background: `PolicySnapshotGlobalAllowSpec` already covers paused-blocked + uiHosts;
 * this file covers the schedule-block case specifically, which is the original #1174 reproducer.
 */
object PolicyScheduleGlobalAllowSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.bedtime)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private val uiHosts: List[Hostname] =
    List("wifihaven.net", "www.wifihaven.net", "api.wifihaven.net").map(Hostname.unsafe)

  private def makePsAt(dt: LocalDateTime) =
    for {
      pr   <- ZIO.service[ProfileRepo]
      nsr  <- ZIO.service[NamedScheduleRepo]
      hsr  <- ZIO.service[HouseholdSettingsRepo]
      tlr  <- ZIO.service[TimeLimitRepo]
      atlr <- ZIO.service[AppTimeLimitRepo]
      dr   <- ZIO.service[DeviceRepo]
      blr  <- ZIO.service[BlocklistRepo]
      trr  <- ZIO.service[TrafficReportRepo]
      er   <- ZIO.service[TimeExtensionRepo]
      ar   <- ZIO.service[AppRepo]
      ref  <- Ref.make(dt)
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
      uiHosts,
      NoopGlobalPolicyRepo,
      nsr,
    ): PolicyService

  // Attach a 21:00-07:00 bedtime named schedule to Kids and place the kid MAC in it.
  private val kidMac            = "aa:bb:cc:11:22:33"
  private def seedBedtimeAndKid =
    for {
      pr  <- ZIO.service[ProfileRepo]
      nsr <- ZIO.service[NamedScheduleRepo]
      dr  <- ZIO.service[DeviceRepo]
      ps0 <- pr.listAll
      kids = ps0.find(_.name == "Kids").get
      sid <- nsr.create(
        "Bedtime",
        Some("overnight"),
        List(
          ScheduleWindow(
            List("mon", "tue", "wed", "thu", "fri", "sat", "sun"),
            LocalTime.of(21, 0),
            LocalTime.of(7, 0),
            ZoneId.of("UTC"),
          ),
        ),
      )
      _   <- nsr.setProfileBlockSchedules(kids.id, List(sid))
      _   <- TestLayers.seedDevice(dr, kidMac, "kid-ipad", kids.id)
    } yield kids.id

  def spec = suite("policy snapshot — schedule block + global ui allow (#1174)")(
    test(
      "kid under bedtime schedule with uiAllowedHosts → blocked + ui hosts in global.extraAllowed",
    ) {
      for {
        _    <- cleanDb
        pid  <- seedBedtimeAndKid
        svc  <- makePsAt(TestClock.bedtime)
        snap <- svc.snapshot
      } yield {
        val kidsRules     = snap.profiles(pid).rules
        val globalAllowed = snap.global.extraAllowed.map(_.value).toSet
        val expected      = uiHosts.map(_.value).toSet
        assertTrue(
          // Schedule fold (#1069 / #1494) blocks the MAC with reason=Schedule.
          kidsRules.blocked,
          kidsRules.blockReason.contains(MacBlockReason.Schedule),
          // Configured ui hosts are reachable via the fleet-wide global.extraAllowed
          // (post-#1318/#1321 they no longer ride per-profile).
          expected.subsetOf(globalAllowed),
          // And not duplicated into the profile's own extraAllowed.
          expected.intersect(kidsRules.extraAllowed.map(_.value).toSet).isEmpty,
        )
      }
    },
    test("outside the window → kid not blocked; ui hosts still in global.extraAllowed") {
      for {
        _    <- cleanDb
        pid  <- seedBedtimeAndKid
        svc  <- makePsAt(TestClock.schoolDayAfternoon)
        snap <- svc.snapshot
      } yield {
        val kidsRules     = snap.profiles(pid).rules
        val globalAllowed = snap.global.extraAllowed.map(_.value).toSet
        val expected      = uiHosts.map(_.value).toSet
        assertTrue(
          !kidsRules.blocked,
          expected.subsetOf(globalAllowed),
        )
      }
    },
  ) @@ TestAspect.sequential
}
