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
import zio.test.TestAspect

import java.time.LocalDateTime

/**
 * #1742 anti-divergence pin. `PolicyServiceLive.snapshot` and `PolicyServiceLive.decide` must
 * compute the SAME `isScheduleBlock` carve gate (#1679) for the SAME profile + clock fixture.
 *
 * Before #1742 the snapshot read the precedence-collapsed reason
 * (`state.blockReason.contains(MacBlockReason.Schedule)`) while `decide` re-derived schedule
 * activity from raw schedules (`scheduleBlock(scheds, now).nonEmpty`). On a Paused-AND-schedule-
 * active profile the two disagreed: Paused outranks Schedule in the documented precedence (Paused >
 * Schedule > TimeLimit > Manual), so the snapshot kept the `allowedDuringScheduleBlock=false` host
 * in `extraAllowed` (paused-not-schedule), while `decide` suppressed the carve and reported Block —
 * latent because nftables had already allowed the connection via the snapshot's `extraAllowed`, but
 * a single source of truth violation per AGENTS.md §single-source-of-truth and #1532.
 */
object PolicyScheduleBlockSSotSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private val mac = "aa:bb:cc:17:42:01"

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

  def spec = suite("PolicyService isScheduleBlock SSOT (#1742)")(
    test(
      "schedule-only active: both paths suppress allowedDuringScheduleBlock=false carve",
    ) {
      // Bedtime 21:00–07:00, not paused. Schedule is the winning reason on BOTH paths, so the
      // `allowedDuringScheduleBlock=false` carve is suppressed in both: the host is NOT in the
      // snapshot's `extraAllowed`, and `decide` does NOT allow it via the extraAllowed carve.
      for {
        _     <- cleanDb
        pr    <- ZIO.service[ProfileRepo]
        dr    <- ZIO.service[DeviceRepo]
        ar    <- ZIO.service[AppRepo]
        kid   <- TestLayers.seedKidsProfile(pr)
        _     <- TestLayers.seedDevice(dr, mac, "kid", kid)
        appId <- ar.create("ScheduleRespect", "schedule-respect", None, None)
        _     <- ar.setHosts(appId, List(Hostname.unsafe("schedule-respect.example.com")))
        _     <- ar.upsertAssignment(
          appId,
          kid,
          AppMode.Allowed,
          None,
          true,
          allowedDuringScheduleBlock = false,
        )
        ps    <- makePsAt(TestClock.bedtime)
        snap  <- ps.snapshot
        d     <- ps.decide(mac, "schedule-respect.example.com")
        rules = snap.profiles(kid).rules
        ea    = rules.extraAllowed.map(_.value).toSet
      } yield assertTrue(
        rules.blocked,
        rules.blockReason == Some(MacBlockReason.Schedule),
        // snapshot: suppressed
        !ea.contains("schedule-respect.example.com"),
        // decide: not allowed via extraAllowed → blocked with Schedule reason
        d.decision == ConnectionDecision.Block,
        d.reason == "schedule",
      )
    },
    test(
      "paused + schedule active: Paused wins precedence — neither path treats this as a schedule block",
    ) {
      // Paused profile during the bedtime window. Precedence: Paused > Schedule, so the
      // `allowedDuringScheduleBlock=false` carve must SURVIVE on both paths (the toggle is
      // schedule-scope only per #1679). Snapshot: host in `extraAllowed`. Decide: allowed via the
      // extraAllowed carve (extraAllowed beats whole-MAC Paused, per #421/#1413).
      //
      // This is the case that disagreed before #1742: snapshot read `state.blockReason == Paused`
      // → isScheduleBlock=false → carve survives; decide read `scheduleBlock(scheds, now).nonEmpty`
      // → isScheduleBlock=true → carve suppressed → returned Block:Paused. Now both read the same
      // precedence-collapsed `dayState.blockReason`.
      for {
        _     <- cleanDb
        pr    <- ZIO.service[ProfileRepo]
        dr    <- ZIO.service[DeviceRepo]
        ar    <- ZIO.service[AppRepo]
        kid   <- TestLayers.seedKidsProfile(pr)
        _     <- pr.setPaused(kid, paused = true)
        _     <- TestLayers.seedDevice(dr, mac, "kid", kid)
        appId <- ar.create("ScheduleRespect", "schedule-respect", None, None)
        _     <- ar.setHosts(appId, List(Hostname.unsafe("schedule-respect.example.com")))
        _     <- ar.upsertAssignment(
          appId,
          kid,
          AppMode.Allowed,
          None,
          true,
          allowedDuringScheduleBlock = false,
        )
        ps    <- makePsAt(TestClock.bedtime)
        snap  <- ps.snapshot
        d     <- ps.decide(mac, "schedule-respect.example.com")
        rules = snap.profiles(kid).rules
        ea    = rules.extraAllowed.map(_.value).toSet
      } yield assertTrue(
        rules.blocked,
        rules.blockReason == Some(MacBlockReason.Paused),
        // snapshot: carve survives — Paused, not Schedule, so #1679 toggle does not fire.
        ea.contains("schedule-respect.example.com"),
        // decide: extraAllowed beats Paused at the router, so /decide must agree (Allow).
        d.decision == ConnectionDecision.Allow,
        d.reason == "extra_allowed",
      )
    },
    test(
      "out of schedule with daily limit exhausted: reason is TimeLimit on both paths",
    ) {
      // Schedule not active (school afternoon, 14:00). Daily limit exhausted via per-app
      // exhaustion that rolls up to a daily-cap-exhausted state? Simpler: set daily limit small
      // and seed enough traffic. We use the schoolDayAfternoon fixture so the bedtime window is
      // inactive — the only candidate block is TimeLimit.
      for {
        _   <- cleanDb
        pr  <- ZIO.service[ProfileRepo]
        dr  <- ZIO.service[DeviceRepo]
        tlr <- ZIO.service[TimeLimitRepo]
        kid <- TestLayers.seedKidsProfile(pr)
        _   <- tlr.upsert(kid, 120)
        _   <- TestLayers.seedDevice(dr, mac, "kid", kid)
        rr  <- ZIO.service[RouterRepo]
        rid <- rr.create("gw-1742-tl", Sha256Hex.unsafe("e" * 64))
        tr  <- ZIO.service[TrafficReportRepo]
        day0    = java.time.LocalDate
          .of(2025, 1, 6)
          .atStartOfDay(java.time.ZoneOffset.UTC)
          .toInstant
        inserts = (0 until 25).map { i =>
          val start = day0.plusSeconds(i * 300L)
          TrafficReportInsert(
            rid,
            MacAddress.unsafe(mac),
            None,
            HostId.Fqdn(Hostname.unsafe("cnn.com")),
            java.time.LocalDate.of(2025, 1, 6),
            start,
            start.plusSeconds(300),
            300,
            500_000L,
            500_000L,
          )
        }.toList
        _ <- tr.insertBatch(inserts)
        ps   <- makePsAt(TestClock.schoolDayAfternoon)
        snap <- ps.snapshot
        d    <- ps.decide(mac, "cnn.com")
        rules = snap.profiles(kid).rules
      } yield assertTrue(
        rules.blocked,
        rules.blockReason == Some(MacBlockReason.TimeLimit),
        d.decision == ConnectionDecision.Block,
        d.reason == "time_limit",
      )
    },
  ) @@ TestAspect.sequential
}
