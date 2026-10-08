package wifihaven.api.feature

import doobie.*
import doobie.implicits.*
import wifihaven.api.db.*
import wifihaven.api.policy.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.shared.Clock.TestClock
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.interop.catz.*
import zio.test.*

import java.time.LocalDateTime

/**
 * #2847 (epic #2841, design docs/design/shared-devices.md §4.2, §7.1, §8): how a shared device
 * resolves in the snapshot the router enforces and in `decideDetailed` (`GET /api/blocked`).
 *
 *   - checked out (shared, no holder) → inline `rules = {blocked, CheckedOut}`, whatever the
 *     household's unmanaged-device policy is;
 *   - checked in (shared, holder P) → `profileId = P`, `rules = None`, i.e. P's rules unchanged.
 *
 * `decideDetailed` must report the same whole-MAC reason the router drops for, for checked-out AND
 * unmanaged devices (the unmanaged case disagreed before #2847: the snapshot blocked with
 * `Unmanaged` while `/api/blocked` said `Allow` / `NoProfile`).
 *
 * No writer for `devices.shared` exists yet (check-in routes are #2848), so the fixture flips the
 * column directly.
 */
object PolicySharedDeviceSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private val SharedMac = "aa:bb:cc:00:00:01"
  private val Host      = "example.com"

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

  private def markShared(mac: String): ZIO[Transactor[Task], Throwable, Unit] =
    ZIO.serviceWithZIO[Transactor[Task]] { xa =>
      sql"UPDATE devices SET shared = true WHERE mac = $mac".update.run
        .transact(xa)
        .unit
    }

  private def setUnmanagedPolicy(policy: String) =
    for {
      hsr      <- ZIO.service[HouseholdSettingsRepo]
      existing <- hsr.getForHousehold(HouseholdId.Default)
      _        <- hsr.update(
        HouseholdId.Default,
        existing.copy(unmanagedMacPolicy = UnmanagedMacPolicy(policy = policy, blockPage = true)),
      )
    } yield ()

  private def seedProfileless(mac: String) =
    ZIO.serviceWithZIO[DeviceRepo](
      _.upsertUnknown(
        MacAddress.unsafe(mac),
        "family-ipad",
        Some(IpAddress.unsafe("10.0.0.50")),
        java.time.Instant.now(),
      ),
    )

  private def deviceOf(snap: PolicySnapshot, mac: String): Option[DevicePolicy] =
    snap.devices.get(MacAddress.unsafe(mac))

  private def reasonOf(r: BlockRules): Option[String] = r.blockReason.map(MacBlockReason.asString)

  def spec = suite("PolicyService — shared devices (#2847)")(
    test("CheckedOut is in the MacBlockReason vocabulary: parse, fromWire, JSON kind") {
      val parsed = MacBlockReason.parse("CheckedOut")
      assertTrue(
        parsed.map(MacBlockReason.asString).contains("CheckedOut"),
        parsed.map(_.wireKind).contains("checked_out"),
        parsed.map(_.jsonKind).contains("checkedOut"),
        BlockReason.fromWire("CheckedOut") == parsed.get,
        BlockReason.fromWire("checked_out") == parsed.get,
      )
    },
    suite("snapshot")(
      test("checked out under an allow household: inline blocked rules with reason CheckedOut") {
        for {
          _    <- cleanDb
          _    <- seedProfileless(SharedMac)
          _    <- markShared(SharedMac)
          ps   <- makePsAt(TestClock.schoolDayAfternoon)
          snap <- ps.snapshot
          dev = deviceOf(snap, SharedMac)
        } yield assertTrue(
          dev.exists(_.profileId.isEmpty),
          dev.flatMap(_.rules).exists(_.blocked),
          dev.flatMap(_.rules).flatMap(reasonOf).contains("CheckedOut"),
          dev
            .flatMap(_.rules)
            .exists(r =>
              r.extraAllowed.isEmpty && r.extraBlocked.isEmpty && r.blocklistIds.isEmpty &&
                !r.blockIpOnly,
            ),
        )
      },
      test("checked out under a block household: CheckedOut, not Unmanaged") {
        for {
          _    <- cleanDb
          _    <- seedProfileless(SharedMac)
          _    <- markShared(SharedMac)
          _    <- setUnmanagedPolicy("block")
          ps   <- makePsAt(TestClock.schoolDayAfternoon)
          snap <- ps.snapshot
        } yield assertTrue(
          deviceOf(snap, SharedMac).flatMap(_.rules).flatMap(reasonOf).contains("CheckedOut"),
        )
      },
      test("checked in: ships the holder's profileId and no inline rules") {
        for {
          _    <- cleanDb
          pr   <- ZIO.service[ProfileRepo]
          dr   <- ZIO.service[DeviceRepo]
          kid  <- TestLayers.seedKidsProfile(pr)
          _    <- TestLayers.seedDevice(dr, SharedMac, "family-ipad", kid)
          _    <- markShared(SharedMac)
          _    <- setUnmanagedPolicy("block")
          ps   <- makePsAt(TestClock.schoolDayAfternoon)
          snap <- ps.snapshot
          dev = deviceOf(snap, SharedMac)
        } yield assertTrue(
          dev.exists(_.profileId.contains(kid)),
          dev.exists(_.rules.isEmpty),
        )
      },
      test("a non-shared profileless device under an allow household still ships no rules") {
        for {
          _    <- cleanDb
          _    <- seedProfileless(SharedMac)
          ps   <- makePsAt(TestClock.schoolDayAfternoon)
          snap <- ps.snapshot
        } yield assertTrue(deviceOf(snap, SharedMac).exists(_.rules.isEmpty))
      },
    ),
    suite("decideDetailed agrees with the snapshot")(
      test("checked out: Block with reason checked_out") {
        for {
          _  <- cleanDb
          _  <- seedProfileless(SharedMac)
          _  <- markShared(SharedMac)
          ps <- makePsAt(TestClock.schoolDayAfternoon)
          d  <- ps.decideDetailed(HouseholdId.Default, SharedMac, Host)
        } yield assertTrue(
          d.response.decision == ConnectionDecision.Block,
          d.response.reason == "checked_out",
          d.profile.isEmpty,
          d.dayState.isEmpty,
        )
      },
      test("unmanaged under a block household: Block with reason unmanaged_mac") {
        for {
          _  <- cleanDb
          _  <- seedProfileless(SharedMac)
          _  <- setUnmanagedPolicy("block")
          ps <- makePsAt(TestClock.schoolDayAfternoon)
          d  <- ps.decideDetailed(HouseholdId.Default, SharedMac, Host)
        } yield assertTrue(
          d.response.decision == ConnectionDecision.Block,
          d.response.reason == BlockReason.asWire(MacBlockReason.Unmanaged),
        )
      },
      test("unmanaged under an allow household: Allow / no_profile, unchanged") {
        for {
          _  <- cleanDb
          _  <- seedProfileless(SharedMac)
          ps <- makePsAt(TestClock.schoolDayAfternoon)
          d  <- ps.decideDetailed(HouseholdId.Default, SharedMac, Host)
        } yield assertTrue(
          d.response.decision == ConnectionDecision.Allow,
          d.response.reason == BlockReason.asWire(BlockReason.NoProfile),
        )
      },
      test("checked in to a paused holder: the holder's Paused verdict") {
        for {
          _   <- cleanDb
          pr  <- ZIO.service[ProfileRepo]
          dr  <- ZIO.service[DeviceRepo]
          kid <- TestLayers.seedKidsProfile(pr)
          _   <- pr.setPaused(kid, true)
          _   <- TestLayers.seedDevice(dr, SharedMac, "family-ipad", kid)
          _   <- markShared(SharedMac)
          ps  <- makePsAt(TestClock.schoolDayAfternoon)
          d   <- ps.decideDetailed(HouseholdId.Default, SharedMac, Host)
        } yield assertTrue(
          d.response.decision == ConnectionDecision.Block,
          d.response.reason == BlockReason.asWire(MacBlockReason.Paused),
          d.profile.exists(_.id == kid),
        )
      },
    ),
  ) @@ TestAspect.sequential
}
