package wifihaven.api.feature

import wifihaven.api.db.*
import wifihaven.api.db.TypeMeta.given
import wifihaven.api.metrics.MetricsRuntime
import wifihaven.api.usage.{AmbientLearnJob, HouseholdTickIsolation, TimeUsedRollupJob}
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.testinfra.*
import doobie.*
import doobie.implicits.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.interop.catz.*
import zio.{Clock as _, *}
import zio.metrics.connectors.prometheus.PrometheusPublisher
import zio.test.*

import java.time.{Instant, LocalDate, ZoneId}

/**
 * #2553 (multi-tenant, epic #2085) — the screen-time WRITE path must bucket and gate each household
 * under ITS OWN `household_settings` row.
 *
 * The bug: `TimeUsedRollupJob.doTick` and `AmbientLearnJob.doTick` read the settings ONCE per tick
 * — explicitly `getForHousehold(HouseholdId.Default)` — and applied that single row to every
 * household they then iterated. Both jobs already read *presence* per household (#2313); only the
 * *settings* stayed global. So household N's `time_used_daily` / `app_used_daily` rows were keyed
 * on household #1's `daily_reset_tz` / `daily_reset_time`, and gated by household #1's heartbeat
 * filter and ambient knobs. A household in another timezone got its day boundary drawn in the
 * operator household's timezone — and, because the day key comes from the SAME settings that select
 * the presence rows, it rolled up an empty day.
 *
 * Pins (embedded Postgres, no repo mocks, `now` injected):
 *   - THE load-bearing one: two households in DIFFERENT timezones, one tick, each household's rows
 *     land under ITS OWN local date with ITS OWN minutes — and nothing lands under the operator
 *     household's date.
 *   - differing heartbeat knobs produce different gated minutes from IDENTICAL presence.
 *   - the ambient learner learns each household's own `yesterday`.
 *   - the now-narrowed rollup-cache invalidation: household A's settings write leaves household B's
 *     cached rows INTACT (only true once the coupling above is severed — see the comment on
 *     `HouseholdSettingsRepoLive.update`, and the ordering constraint recorded on #2553).
 *   - failure isolation: a household with NO settings row is skipped loudly, and every other
 *     household still rolls up.
 *
 * Households are minted via `HouseholdRepo.create` — the #2355 SSOT seam that seeds the settings +
 * billing + global-sentinel rows in one transaction — never household 1 by hand.
 */
object PerHouseholdRollupSettingsSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Transactor[Task]] {

  override val bootstrap = TestDatabase.layer

  private val cleanDb = TestDatabase.cleanAndMigrate

  // At 10:30 UTC three distinct local dates exist worldwide, so BOTH test households can differ
  // from the operator household (#1) at the same instant. With only two zones one of them would
  // inevitably share household #1's date and its pin would pass vacuously on the buggy code.
  //   household #1 — UTC            → 2026-01-06
  //   household A  — Kiritimati +14 → 2026-01-07 (UTC 2026-01-06 10:00 .. 2026-01-07 10:00)
  //   household B  — Niue       −11 → 2026-01-05 (UTC 2026-01-05 11:00 .. 2026-01-06 11:00)
  private val Now: Instant = Instant.parse("2026-01-06T10:30:00Z")
  private val DateOperator = LocalDate.of(2026, 1, 6)
  private val DateA        = LocalDate.of(2026, 1, 7)
  private val DateB        = LocalDate.of(2026, 1, 5)
  private val TzA: ZoneId  = ZoneId.of("Pacific/Kiritimati")
  private val TzB: ZoneId  = ZoneId.of("Pacific/Niue")

  private val macA = MacAddress.unsafe("aa:bb:cc:00:53:0a")
  private val macB = MacAddress.unsafe("aa:bb:cc:00:53:0b")

  private val AppHost = "games.example.com"

  /** One household's seeded identifiers. */
  private final case class Tenant(hh: HouseholdId, profile: ProfileId, router: RouterId)

  /**
   * A household minted through the SSOT create seam, with one profile, one device and one enrolled
   * router — the minimum a rollup tick needs to see it (`ProfileRepo.distinctHouseholds` enumerates
   * from `profiles`, and presence is scoped by the router's household).
   */
  private def tenant(
      name: String,
      slug: String,
      mac: MacAddress,
      tz: ZoneId,
      settings: HouseholdSettings => HouseholdSettings = identity,
  ): ZIO[
    HouseholdRepo & ProfileRepo & DeviceRepo & RouterRepo & HouseholdSettingsRepo,
    Throwable,
    Tenant,
  ] =
    for {
      hr  <- ZIO.service[HouseholdRepo]
      pr  <- ZIO.service[ProfileRepo]
      dr  <- ZIO.service[DeviceRepo]
      rr  <- ZIO.service[RouterRepo]
      hsr <- ZIO.service[HouseholdSettingsRepo]
      hh  <- hr.create(name, slug)
      cur <- hsr.getForHousehold(hh)
      _   <- hsr.update(hh, settings(cur.copy(dailyResetTz = tz)))
      pid <- pr.create(s"$name-Kids", Nil, hh)
      _   <- dr.upsert(mac, s"$slug-dev", Some(pid), "192.168.1.10", hh)
      rid <- rr.create(s"gw-$slug", Sha256Hex.unsafe(slug.take(1) * 64), hh)
    } yield Tenant(hh, pid, rid)

  /**
   * `minutes/5` contiguous fully-active 5-minute buckets starting at `startUtc`, stamped with the
   * household-local `date` the router would have reported. `bytes` per direction — comfortably
   * above the default heartbeat floor unless a test deliberately lowers it.
   */
  private def seedTraffic(
      router: RouterId,
      mac: MacAddress,
      host: String,
      date: LocalDate,
      startUtc: Instant,
      minutes: Int,
      bytes: Long = 500_000L,
  ): ZIO[TrafficReportRepo, Throwable, Unit] =
    ZIO.serviceWithZIO[TrafficReportRepo] { tr =>
      val inserts = (0 until minutes / 5).map { i =>
        val start = startUtc.plusSeconds(i * 300L)
        TrafficReportInsert(
          router,
          mac,
          None,
          HostId.Fqdn(Hostname.unsafe(host)),
          date,
          start,
          start.plusSeconds(300),
          300,
          bytes,
          bytes,
        )
      }.toList
      tr.insertBatch(inserts).unit
    }

  /** One 60-second, 1 MB bucket — the isolated-span shape the ambient learner keys on. */
  private def seedBucket(
      router: RouterId,
      mac: MacAddress,
      host: String,
      date: LocalDate,
      startUtc: Instant,
  ): ZIO[TrafficReportRepo, Throwable, Unit] =
    ZIO.serviceWithZIO[TrafficReportRepo] { tr =>
      tr.insertBatch(
        List(
          TrafficReportInsert(
            router,
            mac,
            None,
            HostId.Fqdn(Hostname.unsafe(host)),
            date,
            startUtc,
            startUtc.plusSeconds(60),
            10,
            500_000L,
            500_000L,
          ),
        ),
      ).unit
    }

  private def rollupTick(now: Instant): ZIO[
    TimeUsedRollupRepo & AppUsedRollupRepo & ProfileRepo & DeviceRepo & AppTimeLimitRepo &
      TrafficReportRepo & HouseholdSettingsRepo & AmbientHostsRepo,
    Throwable,
    Int,
  ] =
    for {
      ru  <- ZIO.service[TimeUsedRollupRepo]
      aru <- ZIO.service[AppUsedRollupRepo]
      pr  <- ZIO.service[ProfileRepo]
      dr  <- ZIO.service[DeviceRepo]
      atl <- ZIO.service[AppTimeLimitRepo]
      trr <- ZIO.service[TrafficReportRepo]
      hsr <- ZIO.service[HouseholdSettingsRepo]
      ahr <- ZIO.service[AmbientHostsRepo]
      n   <- TimeUsedRollupJob.oneTickForTest(ru, aru, pr, dr, atl, trr, hsr, now, ahr)
    } yield n

  // ── Prometheus scrape helpers (same shape as MetricsExportSpec) ──────────────
  private val PollInterval = 100.millis

  /**
   * Fire exactly ONE scrape of the publisher fiber, waiting until it has re-parked on the TestClock
   * before advancing (#2042 — a bare `adjust` can slip past a mid-flight fiber under load).
   */
  private val tickPublisher: UIO[Unit] =
    zio.test.TestClock.sleeps.repeatUntil(_.nonEmpty) *> zio.test.TestClock.adjust(PollInterval)

  /**
   * Current value of `wifihaven_rollup_household_skipped_total{rollup_job=time_used_daily,
   * reason=settings_read}` in the latest exposition, or 0.0 when the series is absent. Label order
   * in the exposition is not guaranteed, so match on fragments rather than a fixed rendering.
   */
  private def skipCount: ZIO[PrometheusPublisher, Nothing, Double] =
    ZIO.serviceWithZIO[PrometheusPublisher](_.get).map { body =>
      body.linesIterator
        .filter(_.startsWith("wifihaven_rollup_household_skipped_total"))
        .filter(l =>
          l.contains(s"""rollup_job="${TimeUsedRollupJob.JobName}"""") &&
            l.contains(s"""reason="${HouseholdTickIsolation.ReasonSettingsRead}""""),
        )
        .flatMap(_.split(" ").lift(1).flatMap(_.toDoubleOption))
        .sum
    }

  private def learnTick(now: Instant): ZIO[
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

  def spec = suite("PerHouseholdRollupSettingsSpec (#2553)")(
    // ── THE load-bearing pin ────────────────────────────────────────────────────
    test("two households in different timezones each roll up on their OWN day boundary") {
      for {
        _   <- cleanDb
        ar  <- ZIO.service[AppRepo]
        ru  <- ZIO.service[TimeUsedRollupRepo]
        aru <- ZIO.service[AppUsedRollupRepo]
        // Household #1 (the operator household, whose row the buggy tick read for everyone) is
        // pinned to UTC so the wrong-day assertions below name a concrete date.
        hsr <- ZIO.service[HouseholdSettingsRepo]
        one <- hsr.getForHousehold(HouseholdId.Default)
        _   <- hsr.update(HouseholdId.Default, one.copy(dailyResetTz = ZoneId.of("UTC")))
        a   <- tenant("House A", "house-a", macA, TzA)
        b   <- tenant("House B", "house-b", macB, TzB)
        _   <- TestLayers.seedAppAssignment(
          ar,
          a.profile,
          AppHost,
          AppMode.TimeLimited,
          Some(120),
          false,
        )
        _   <- TestLayers.seedAppAssignment(
          ar,
          b.profile,
          AppHost,
          AppMode.TimeLimited,
          Some(120),
          false,
        )
        // 25 min inside household A's local Jan 7 (UTC 10:00–10:25 on Jan 6).
        _ <- seedTraffic(a.router, macA, AppHost, DateA, Instant.parse("2026-01-06T10:00:00Z"), 25)
        // 40 min inside household B's local Jan 5 (UTC 08:00–08:40 on Jan 6).
        _ <- seedTraffic(b.router, macB, AppHost, DateB, Instant.parse("2026-01-06T08:00:00Z"), 40)
        _ <- rollupTick(Now)
        // Each household's own local date carries its own minutes…
        ownA      <- ru.getDayForProfile(a.profile, DateA)
        ownB      <- ru.getDayForProfile(b.profile, DateB)
        // …and nothing was written under the operator household's date, nor under each other's.
        wrongA    <- ru.getDayForProfile(a.profile, DateOperator)
        wrongB    <- ru.getDayForProfile(b.profile, DateOperator)
        crossA    <- ru.getDayForProfile(a.profile, DateB)
        crossB    <- ru.getDayForProfile(b.profile, DateA)
        // The per-app rollup is keyed on the same day and must agree.
        appOwnA   <- aru.getDayForProfile(a.profile, DateA)
        appOwnB   <- aru.getDayForProfile(b.profile, DateB)
        appWrongA <- aru.getDayForProfile(a.profile, DateOperator)
        appWrongB <- aru.getDayForProfile(b.profile, DateOperator)
      } yield assertTrue(
        // sees-own: household A's 25 minutes under household A's local date
        ownA.exists(_.usedSeconds == 25L * 60L),
        // …household B's 40 minutes under household B's local date
        ownB.exists(_.usedSeconds == 40L * 60L),
        // the bug's signature: rows keyed on household #1's date (with 0 seconds, because the
        // presence read used that same wrong date) must not exist at all
        wrongA.isEmpty,
        wrongB.isEmpty,
        crossA.isEmpty,
        crossB.isEmpty,
        // app_used_daily follows the same per-household day key
        appOwnA.nonEmpty,
        appOwnB.nonEmpty,
        appWrongA.isEmpty,
        appWrongB.isEmpty,
      )
    },
    // ── Gating knobs are per-household too, not just the day key ────────────────
    test("differing heartbeat knobs gate identical presence differently per household") {
      for {
        _  <- cleanDb
        ru <- ZIO.service[TimeUsedRollupRepo]
        // Same timezone for both, so the ONLY difference is the heartbeat filter — this isolates
        // the knob from the day key the previous test pins.
        a  <- tenant(
          "Filtered",
          "filtered",
          macA,
          ZoneId.of("UTC"),
          _.copy(heartbeatFilter = HeartbeatFilter(enabled = true, 1_000_000, Nil)),
        )
        b  <- tenant(
          "Unfiltered",
          "unfiltered",
          macB,
          ZoneId.of("UTC"),
          _.copy(heartbeatFilter = HeartbeatFilter(enabled = false, 1_000_000, Nil)),
        )
        day   = LocalDate.of(2026, 1, 6)
        start = Instant.parse("2026-01-06T09:00:00Z")
        // IDENTICAL presence in both households: 20 min of low-byte buckets, below A's floor.
        _       <- seedTraffic(a.router, macA, "quiet.example.com", day, start, 20, bytes = 1_000L)
        _       <- seedTraffic(b.router, macB, "quiet.example.com", day, start, 20, bytes = 1_000L)
        _       <- rollupTick(Instant.parse("2026-01-06T12:00:00Z"))
        rolledA <- ru.getDayForProfile(a.profile, day)
        rolledB <- ru.getDayForProfile(b.profile, day)
      } yield assertTrue(
        // household A's own filter drops the sub-threshold buckets…
        rolledA.exists(_.usedSeconds == 0L),
        // …while household B, which disabled it, keeps the full 20 minutes.
        rolledB.exists(_.usedSeconds == 20L * 60L),
      )
    },
    // ── The ambient learner's day key is per-household as well ──────────────────
    test("the ambient learner learns each household's OWN yesterday") {
      for {
        _   <- cleanDb
        ahr <- ZIO.service[AmbientHostsRepo]
        hsr <- ZIO.service[HouseholdSettingsRepo]
        one <- hsr.getForHousehold(HouseholdId.Default)
        _   <- hsr.update(HouseholdId.Default, one.copy(dailyResetTz = ZoneId.of("UTC")))
        a   <- tenant("Amb A", "amb-a", macA, TzA)
        b   <- tenant("Amb B", "amb-b", macB, TzB)
        // Each household's "yesterday" at `Now`: A → Jan 6, B → Jan 4. Distinct hosts so the
        // learned rows are attributable, seeded as isolated single-host bursts hours apart.
        yA = DateA.minusDays(1)
        yB = DateB.minusDays(1)
        _        <- seedBucket(
          a.router,
          macA,
          "amb-a.example.com",
          yA,
          Instant.parse("2026-01-06T02:00:00Z"),
        )
        _        <- seedBucket(
          a.router,
          macA,
          "amb-a2.example.com",
          yA,
          Instant.parse("2026-01-06T05:00:00Z"),
        )
        _        <- seedBucket(
          b.router,
          macB,
          "amb-b.example.com",
          yB,
          Instant.parse("2026-01-04T02:00:00Z"),
        )
        _        <- seedBucket(
          b.router,
          macB,
          "amb-b2.example.com",
          yB,
          Instant.parse("2026-01-04T05:00:00Z"),
        )
        // A host BOTH households learn, on their own different local days — the only shape that
        // exposes whether the per-household counts were merged before the single upsert.
        _        <- seedBucket(
          a.router,
          macA,
          "amb-shared.example.com",
          yA,
          Instant.parse("2026-01-06T08:00:00Z"),
        )
        _        <- seedBucket(
          b.router,
          macB,
          "amb-shared.example.com",
          yB,
          Instant.parse("2026-01-04T08:00:00Z"),
        )
        _        <- learnTick(Now)
        // `listWindow` over a generous window so the read itself never hides a write.
        settings <- hsr.getForHousehold(a.hh)
        window   <- ahr.listWindow(settings.copy(ambientLearningWindowDays = 3650), DateA)
        globalDay = AmbientLearnJob.globalLearnDay(Now)
      } yield assertTrue(
        // Each household's presence was selected on ITS OWN local yesterday — A's rows are stamped
        // Jan 6 and B's Jan 4, and a tick that read one global `yesterday` finds neither.
        yA != yB,
        window.exists(_.host == "amb-a.example.com"),
        window.exists(_.host == "amb-b.example.com"),
        // …but both land in the SINGLE global day bucket, not each household's own local day.
        // `ambientHosts` qualifies on COUNT(DISTINCT day), so per-household day keys would let one
        // tick advance a shared host's day count twice and cross `ambientMinIsolatedDays` in half
        // the configured time (see the rationale in AmbientLearnJob.doTick).
        window.forall(_.lastIsolatedDay == globalDay),
        globalDay != yA,
        globalDay != yB,
        window.count(r => r.host.startsWith("amb-")) == 5,
        // Both households' counts survive in that one bucket. This needs a SHARED host to be a real
        // pin: `upsertDay` conflicts on (host, day) PER HOST, so with disjoint hosts a tick that
        // called it once per household would still leave every row present. Only a host BOTH
        // households learned exposes the replace — dropping `mergeCounts` leaves it at 1.
        window.exists(r => r.host == "amb-shared.example.com" && r.isolatedSpanCount == 2),
      )
    },
    // ── The global bucket is a real date, not just whatever the code computes ────
    test("globalLearnDay is the UTC day before now, independent of any household") {
      // Both ambient pins above compare against `globalLearnDay(Now)` — i.e. they call the function
      // under test to build their own expectation, so a change to it (an off-by-N, or keying on a
      // zone) would move expectation and actual together and stay green. Pin the value itself.
      assertTrue(
        AmbientLearnJob.globalLearnDay(Now) == LocalDate.of(2026, 1, 5),
        // …and that it is nobody's local yesterday here: A's is Jan 6, B's is Jan 4.
        AmbientLearnJob.globalLearnDay(Now) != DateA.minusDays(1),
        AmbientLearnJob.globalLearnDay(Now) != DateB.minusDays(1),
        // 23:30 UTC and 00:30 UTC straddle the boundary in the UTC zone, not a household's.
        AmbientLearnJob.globalLearnDay(Instant.parse("2026-01-06T23:30:00Z")) ==
          LocalDate.of(2026, 1, 5),
        AmbientLearnJob.globalLearnDay(Instant.parse("2026-01-07T00:30:00Z")) ==
          LocalDate.of(2026, 1, 6),
      )
    },
    // ── The prune boundary must respect the LONGEST window, not the writer's own ──
    test("prune keeps days inside the longest household window, and skips entirely on a skip") {
      for {
        _       <- cleanDb
        xa      <- ZIO.service[Transactor[Task]]
        pr      <- ZIO.service[ProfileRepo]
        ahr     <- ZIO.service[AmbientHostsRepo]
        hsr     <- ZIO.service[HouseholdSettingsRepo]
        one     <- hsr.getForHousehold(HouseholdId.Default)
        _       <- hsr.update(HouseholdId.Default, one.copy(dailyResetTz = ZoneId.of("UTC")))
        // Same tz; the ONLY difference is the learning window: 3 days vs 30.
        shortHh <- tenant(
          "Short",
          "short-win",
          macA,
          ZoneId.of("UTC"),
          _.copy(ambientLearningWindowDays = 3),
        )
        longHh  <- tenant(
          "Long",
          "long-win",
          macB,
          ZoneId.of("UTC"),
          _.copy(ambientLearningWindowDays = 30),
        )
        globalDay = AmbientLearnJob.globalLearnDay(Now)
        // A day that is dead to the 3-day window but alive to the 30-day one.
        midDay    = globalDay.minusDays(10)
        ancient   = globalDay.minusDays(90)
        _ <- ahr.upsertDay(midDay, Map("mid.example.com" -> 1))
        _ <- ahr.upsertDay(ancient, Map("ancient.example.com" -> 1))
        _ <- learnTick(Now)
        wide = one.copy(ambientLearningWindowDays = 3650)
        after     <- ahr.listWindow(wide, globalDay)
        // Now add a household that owns no settings row, so the tick records a skip. The retention
        // window is then not fully known, and pruning on the surviving subset could delete rows
        // inside the missing household's window — permanently.
        broken    <-
          sql"INSERT INTO households(name, slug, router_cap) VALUES('no-window','no-window',1) RETURNING id"
            .query[HouseholdId]
            .unique
            .transact(xa)
        _         <- pr.create("Broken-Kids", Nil, broken)
        _         <- ahr.upsertDay(globalDay.minusDays(200), Map("older.example.com" -> 1))
        _         <- learnTick(Now)
        afterSkip <- ahr.listWindow(wide, globalDay)
      } yield assertTrue(
        // the 30-day household still reads `mid`, so the prune must not have taken the SHORT
        // household's cutoff (which would have deleted it)
        after.exists(_.host == "mid.example.com"),
        // …but a day outside every window is still collected, so pruning does happen
        !after.exists(_.host == "ancient.example.com"),
        // a tick with a skipped household does not prune at all
        afterSkip.exists(_.host == "older.example.com"),
        afterSkip.exists(_.host == "mid.example.com"),
      )
    },
    // ── The invalidation this change is allowed to narrow (ordering per #2553) ──
    test("a settings write for household A leaves household B's cached rollup rows INTACT") {
      for {
        _     <- cleanDb
        hsr   <- ZIO.service[HouseholdSettingsRepo]
        ru    <- ZIO.service[TimeUsedRollupRepo]
        aru   <- ZIO.service[AppUsedRollupRepo]
        ar    <- ZIO.service[AppRepo]
        atl   <- ZIO.service[AppTimeLimitRepo]
        a     <- tenant("Inv A", "inv-a", macA, ZoneId.of("UTC"))
        b     <- tenant("Inv B", "inv-b", macB, ZoneId.of("UTC"))
        _     <- TestLayers.seedAppAssignment(
          ar,
          b.profile,
          AppHost,
          AppMode.TimeLimited,
          Some(120),
          false,
        )
        appId <- atl.listForProfile(b.profile).map(_.head.appId)
        day       = LocalDate.of(2026, 1, 6)
        watermark = Instant.parse("2026-01-06T09:00:00Z")
        _        <- ru.upsertDay(a.profile, day, RolledDay(600L, watermark))
        _        <- ru.upsertDay(b.profile, day, RolledDay(900L, watermark))
        _        <- aru.upsertDay(b.profile, appId, day, RolledAppDay(900L, watermark))
        // Household A saves its settings. Post-#2553 its own cached rows are evicted (the knobs
        // that define an active minute changed) — but household B's are a function of household B's
        // settings alone and must survive.
        cur      <- hsr.getForHousehold(a.hh)
        _        <- hsr.update(a.hh, cur.copy(presenceContinuationSeconds = 900))
        goneA    <- ru.getDayForProfile(a.profile, day)
        keptB    <- ru.getDayForProfile(b.profile, day)
        keptAppB <- aru.getDayForProfile(b.profile, day)
      } yield assertTrue(
        goneA.isEmpty,
        keptB.exists(_.usedSeconds == 900L),
        keptAppB.nonEmpty,
      )
    },
    // ── Failure isolation (#2386 fail-loud vs. one tenant blackholing the tick) ──
    test("a household with no settings row is skipped; every other household still rolls up") {
      for {
        _       <- cleanDb
        xa      <- ZIO.service[Transactor[Task]]
        pr      <- ZIO.service[ProfileRepo]
        ru      <- ZIO.service[TimeUsedRollupRepo]
        a       <- tenant("Healthy", "healthy", macA, ZoneId.of("UTC"))
        // A households row minted WITHOUT the settings row `HouseholdRepo.create` would have seeded
        // — the unprovisioned shape `getForHousehold` fails loud on (#2386). It owns a profile, so
        // `distinctHouseholds` enumerates it and the tick must survive it.
        broken  <-
          sql"INSERT INTO households(name, slug, router_cap) VALUES('broken','broken',1) RETURNING id"
            .query[HouseholdId]
            .unique
            .transact(xa)
        brokenP <- pr.create("Broken-Kids", Nil, broken)
        day = LocalDate.of(2026, 1, 6)
        _ <- seedTraffic(a.router, macA, AppHost, day, Instant.parse("2026-01-06T09:00:00Z"), 15)
        n <- rollupTick(Instant.parse("2026-01-06T12:00:00Z"))
        healthy <- ru.getDayForProfile(a.profile, day)
        skipped <- ru.getDayForProfile(brokenP, day)
      } yield assertTrue(
        // the tick completed rather than failing every tenant…
        n > 0,
        // …the healthy household rolled up its own minutes…
        healthy.exists(_.usedSeconds == 15L * 60L),
        // …and the unprovisioned household wrote nothing (it is skipped, never defaulted onto
        // another tenant's settings).
        skipped.isEmpty,
      )
    },
    // ── The skip must be LOUD — the counter is the only signal it happened ───────
    test("skipping a household increments wifihaven_rollup_household_skipped_total") {
      // The no-dark-by-default argument for skipping rather than failing the tick rests entirely on
      // the skip being observable: the run still records status=ok, so this counter (and its
      // rollup-health panel) is the ONLY thing separating "the batch ran" from "the batch covered
      // every tenant". Deleting the `recordRollupHouseholdSkipped` call passes every other pin in
      // this spec, which is exactly the #2546 shape — a passive detector whose absence reads as
      // health. So assert the emission directly, and assert it does NOT fire on the healthy path
      // (a wired-open counter is just as useless).
      (for {
        _  <- cleanDb
        xa <- ZIO.service[Transactor[Task]]
        pr <- ZIO.service[ProfileRepo]
        a  <- tenant("Loud", "loud", macA, ZoneId.of("UTC"))
        day    = LocalDate.of(2026, 1, 6)
        tickAt = Instant.parse("2026-01-06T12:00:00Z")
        _ <- seedTraffic(a.router, macA, AppHost, day, Instant.parse("2026-01-06T09:00:00Z"), 15)
        // Baseline: the registry is process-global and other specs may have touched this series, so
        // every assertion below is on the DELTA across scrapes, never an absolute value.
        _ <- tickPublisher
        before       <- skipCount
        // Phase 1 — every household healthy. The counter must not move.
        _            <- rollupTick(tickAt)
        _            <- tickPublisher
        afterHealthy <- skipCount
        // Phase 2 — add a household owning no settings row, then tick again.
        broken       <-
          sql"INSERT INTO households(name, slug, router_cap) VALUES('loud-broken','loud-broken',1) RETURNING id"
            .query[HouseholdId]
            .unique
            .transact(xa)
        _            <- pr.create("Loud-Broken-Kids", Nil, broken)
        _            <- rollupTick(tickAt)
        _            <- tickPublisher
        afterBroken  <- skipCount
      } yield assertTrue(
        // healthy tick emits nothing
        afterHealthy == before,
        // the skipped household increments it exactly once, under this job and reason
        afterBroken == afterHealthy + 1.0,
      ))
        .provideSome[TestDatabase.AllRepos & EmbeddedPostgres & Transactor[Task]](
          MetricsRuntime.prometheus(PollInterval),
        )
    },
  ) @@ TestAspect.sequential
}
