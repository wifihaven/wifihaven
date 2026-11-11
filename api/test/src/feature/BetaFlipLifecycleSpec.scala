package wifihaven.api.feature

import wifihaven.api.FlipConfig
import wifihaven.api.billing.FlipService
import wifihaven.api.db.*
import wifihaven.api.notify.Notifier
import wifihaven.api.policy.{PolicyService, PolicyServiceLive}
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.shared.Clock
import wifihaven.shared.Clock.TestClock
import wifihaven.testinfra.*
import doobie.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.test.*

import java.time.{LocalDateTime, ZoneOffset}
import java.util.UUID

/**
 * #2137 (multi-tenant P5-6, epic #622) — the beta→paid flip lifecycle, end to end. Full stack,
 * embedded Postgres, NO repo mocks; a controllable [[TestClock]] walks the cohort through the
 * EVENT-TRIGGERED flip (design docs/design/multi-tenant-launch.md §5.4):
 *
 * threshold reached → clock starts → T-30 notice (once) → T-7 notice → window end → unconverted
 * `beta` households flip to `lapsed` (enforcement goes permissive; the router snapshot still
 * serves) → recovery via checkout re-arms enforcement.
 *
 * A converted (`active`) household is never noticed and never lapses. Notices are asserted
 * idempotent per window. Never brick the network: a lapse stops enforcement permissively — the
 * router-wire snapshot is never gated (§5.3).
 */
object BetaFlipLifecycleSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate

  // threshold=1 so a single active beta household starts the clock; real 60-day window / 7-day
  // lookback so the T-30/T-7 offsets and the flip land at the design instants under the TestClock.
  private val flipCfg = FlipConfig(thresholdHouseholds = 1, windowDays = 60, activeLookbackDays = 7)

  // A fixed PAST anchor so the single traffic_reports insert lands in the unbounded-below omnibus
  // partition (V41 seeds weekly partitions only from the real CURRENT_DATE's Monday forward; a past
  // period_start always falls in the omnibus and stays there as the cutover only moves forward).
  // The whole walk is relative to the injected TestClock, so the real wall-clock date is irrelevant.
  private val t0 = LocalDateTime.of(2025, 6, 2, 12, 0, 0)

  /** Records every flip notice as (household, window) so the walk can assert who/when/how-many. */
  private final class RecordingNotifier(ref: Ref[List[(HouseholdId, String)]]) extends Notifier {
    def alertCreated(a: Alert): UIO[Unit]                                               = ZIO.unit
    def betaInvite(email: String, slug: String, inviteUrl: String, ttl: Int): UIO[Unit] = ZIO.unit
    def betaFlipNotice(
        householdId: HouseholdId,
        slug: String,
        window: String,
        flipDate: java.time.Instant,
        daysUntilFlip: Int,
    ): UIO[Unit] = ref.update(_ :+ (householdId, window))
    def passwordReset(email: String, resetUrl: String, ttlMinutes: Int): UIO[Unit]      = ZIO.unit
  }

  private def mkPolicy(clock: Clock, billing: HouseholdBillingRepo) =
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
      clock,
      // #2137: wire the real billing read so a lapsed household yields a permissive snapshot.
      billingStatusOf = hid => billing.findByHousehold(hid).map(_.map(_.status)),
    ): PolicyService

  /** Create a household + its billing row, and give it one device (so its snapshot has content). */
  private def seedHousehold(slug: String, status: String) =
    for {
      hr      <- ZIO.service[HouseholdRepo]
      billing <- ZIO.service[HouseholdBillingRepo]
      pr      <- ZIO.service[ProfileRepo]
      dr      <- ZIO.service[DeviceRepo]
      hid     <- hr.create(slug, slug, 1)
      // #2355: HouseholdRepo.create now seeds a billing row (status='beta', founding=false) as part
      // of the ONE creation primitive; billing.create is idempotent (ON CONFLICT DO UPDATE), so this
      // steers the pre-seeded row to this fixture's status + founding member.
      _       <- billing.create(hid, status, founding = true)
      pid     <- pr.create(s"$slug-profile", Nil, hid)
      mac = MacAddress.unsafe(f"aa:bb:cc:00:00:${hid.value}%02x")
      _ <- dr.upsert(mac, s"$slug-device", Some(pid), "10.0.0.2", hid)
    } yield (hid, mac)

  /** Make `hid` "active" for the flip threshold: enroll a router + a recent traffic report. */
  private def makeActive(hid: HouseholdId, mac: MacAddress, at: java.time.Instant) =
    for {
      rr  <- ZIO.service[RouterRepo]
      trr <- ZIO.service[TrafficReportRepo]
      rid <- rr.create(
        "router-" + hid.value,
        PolicyService.hashToken(UUID.randomUUID().toString),
        hid,
      )
      _   <- trr.insertBatch(
        List(
          TrafficReportInsert(
            rid,
            mac,
            None,
            HostId.Fqdn(Hostname.unsafe("example.com")),
            at.atZone(ZoneOffset.UTC).toLocalDate,
            at,
            at.plusSeconds(300),
            300,
            0L,
            0L,
          ),
        ),
      )
    } yield ()

  def spec = suite("BetaFlipLifecycleSpec")(
    test(
      "beta cohort: threshold → clock start → T-30/T-7 notices (idempotent) → lapse → recovery",
    ) {
      for {
        _           <- cleanDb
        (clock, tc) <- TestClock.makeWithControl(t0)
        billing     <- ZIO.service[HouseholdBillingRepo]
        cohortRepo  <- ZIO.service[BetaCohortRepo]
        hr          <- ZIO.service[HouseholdRepo]
        noticeRef   <- Ref.make(List.empty[(HouseholdId, String)])
        notifier = new RecordingNotifier(noticeRef)
        policy <- mkPolicy(clock, billing)
        flip   <- FlipService.make(cohortRepo, billing, hr, notifier, policy, clock, flipCfg)

        // Two households: B unconverted (beta, active on the network), C already converted (active).
        (hidB, macB) <- seedHousehold("beta-house", "beta")
        (hidC, macC) <- seedHousehold("paid-house", "active")
        _            <- makeActive(hidB, macB, t0.toInstant(ZoneOffset.UTC))

        // ── Tick 1: threshold reached (1 active beta hh) → clock starts, latched ──
        _       <- flip.tick
        cohort1 <- cohortRepo.get
        window1 <- flip.currentWindow
        // A second tick must NOT re-stamp the start instant (the latch).
        _       <- tc.advance(java.time.Duration.ofHours(1))
        _       <- flip.tick
        cohort2 <- cohortRepo.get

        // ── Advance to T-30 (window end − 30d) → one T-30 notice for B only ──
        _        <- tc.setTo(t0.plusDays(30))
        _        <- flip.tick
        _        <- flip.tick // idempotent within the window
        afterT30 <- noticeRef.get

        // ── Advance to T-7 → one T-7 notice for B ──
        _       <- tc.setTo(t0.plusDays(53))
        _       <- flip.tick
        afterT7 <- noticeRef.get

        // Enforcement still armed for B pre-flip (its device is in the snapshot).
        snapBpre <- policy.snapshot(hidB)

        // ── Advance past window end → B flips to lapsed; C untouched ──
        _             <- tc.setTo(t0.plusDays(61))
        _             <- flip.tick
        bBilling      <- billing.findByHousehold(hidB)
        cBilling      <- billing.findByHousehold(hidC)
        cohortFlipped <- cohortRepo.get
        snapBlapsed   <- policy.snapshot(hidB)
        snapClapsed   <- policy.snapshot(hidC)

        // ── Recovery: B converts back to active → enforcement re-arms ──
        _              <- billing.markActive(
          hidB,
          Some("sub_x"),
          Some("price_x"),
          None,
          t0.plusDays(62).toInstant(ZoneOffset.UTC),
        )
        snapBrecovered <- policy.snapshot(hidB)
      } yield assertTrue(
        // clock started once and latched to t0
        cohort1.clockStartedAt.contains(t0.toInstant(ZoneOffset.UTC)),
        cohort2.clockStartedAt == cohort1.clockStartedAt,
        window1.open,
        window1.flipDate.contains(t0.plusDays(60).toInstant(ZoneOffset.UTC)),
        // exactly one T-30 notice, for B, even across two ticks in the window
        afterT30 == List((hidB, "t30")),
        // T-7 adds exactly one more, again B only
        afterT7 == List((hidB, "t30"), (hidB, "t7")),
        // pre-flip: B enforcing (device present)
        snapBpre.devices.contains(macB),
        // flip: B lapsed + stamped; C untouched
        bBilling.exists(_.status == "lapsed"),
        bBilling.exists(_.lapsedAt.isDefined),
        cBilling.exists(_.status == "active"),
        cohortFlipped.flippedAt.isDefined,
        // lapsed B → permissive snapshot (no devices/profiles), but the snapshot STILL serves
        snapBlapsed.devices.isEmpty,
        snapBlapsed.profiles.isEmpty,
        snapBlapsed.global == BlockRules.allowAll,
        // C stays enforcing throughout
        snapClapsed.devices.contains(macC),
        // recovery re-arms enforcement for B
        snapBrecovered.devices.contains(macB),
      )
    },
  ) @@ TestAspect.sequential
}
