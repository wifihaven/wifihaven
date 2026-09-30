package wifihaven.api.feature

import wifihaven.api.db.*
import wifihaven.api.usage.TimeUsedRollupJob
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.test.*

import java.time.{LocalDate, LocalDateTime, LocalTime, ZoneOffset}

/**
 * #2813: an overnight window carrying only device background traffic must credit ~0 engaged
 * minutes, and WifiHaven's own control plane (`api.wifihaven.net`) must never count toward a
 * child's screen-time budget.
 *
 * The fixture is the prod shape captured on the Kids profile 2026-09-29 06:12–13:04Z (Kid Laptop
 * `ca:ef:a1:72:6a:a3`, 820 raw rows, children asleep throughout): a steady drip on
 * `api.wifihaven.net` (101 rows / 3.6 MB — an SPA tab left open) riding alongside
 * `client-log-forwarder.1password.com` (83 rows), the New Relic browser beacon, an IP-echo probe
 * and an Apple init probe. No engagement host appears anywhere in the window and every time-limited
 * app reports zero minutes, yet the profile accrued 77 minutes.
 *
 * The load-bearing case is `client-log-forwarder.1password.com`. The operator's 1Password app
 * claims the `1password.com` APEX and is assigned to this profile, so `Presence.isAppAttributed`
 * fires on the background log-shipping subdomain and anchors the whole overnight span (#1506).
 * Everything ambient inside that span — `api.wifihaven.net` included — then counts in full. A
 * brand-apex app template must not launder an anchor onto a specifically-enumerated background lane
 * of that brand.
 *
 * LIVENESS ANCHOR (recorded lesson: a one-sided rig asserts nothing). `realSessionMins` pins that
 * the SAME rig, on the SAME day, still credits a genuine 20-minute diverse session in full. A rig
 * that generated no traffic, or a change that suppressed everything, fails that assertion — so the
 * ~0 overnight result can only be earned by discrimination, never by emptiness.
 */
object OvernightBackgroundPhantomSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres] {

  override val bootstrap = TestDatabase.layer

  private val cleanDb = TestDatabase.cleanAndMigrate

  private val kidMac = "ca:ef:a1:72:6a:a3"

  private def seedRouterRow: ZIO[RouterRepo, Throwable, RouterId] =
    ZIO.serviceWithZIO[RouterRepo](_.create("gw-2813", Sha256Hex.unsafe("b" * 64)))

  /** One 60-second bucket at `offsetMin` past midnight UTC, well above the heartbeat byte floor. */
  private def seedBucket(
      routerId: RouterId,
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
            MacAddress.unsafe(kidMac),
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

  /**
   * The prod overnight shape: 00:00–04:00 UTC, background-only. `api.wifihaven.net` and the
   * 1Password log forwarder drip every 2 minutes; the beacon / IP-echo / Apple-init probes fire
   * sporadically.
   *
   * The 2-minute cadence is chosen for MARGIN, not to sit on the seam. With 60-second buckets the
   * stitch gap is `Presence.effectiveGap` = `max(DefaultContinuationSeconds, 2 × periodSeconds)` =
   * `max(120, 120)` = 120s (`Presence.scala` `DefaultContinuationSeconds`), so a 2-minute cadence
   * leaves a 60s gap — half the budget. A 3-minute cadence would leave exactly 120s and stitch only
   * because the merge test is `<=`, which means a one-second fixture change, or any change to that
   * constant, would silently split one long span into 120 separate ones and quietly change what
   * this test tests.
   */
  private def seedOvernightBackground(
      rid: RouterId,
      date: LocalDate,
  ): ZIO[TrafficReportRepo, Throwable, Unit] =
    ZIO.foreachDiscard(0 until 120) { i =>
      val m = i * 2
      seedBucket(rid, "api.wifihaven.net", date, m) *>
        seedBucket(rid, "client-log-forwarder.1password.com", date, m) *>
        ZIO.when(i % 20 == 0)(seedBucket(rid, "bam.nr-data.net", date, m)).unit *>
        ZIO.when(i % 25 == 0)(seedBucket(rid, "ipv4.icanhazip.com", date, m)).unit *>
        ZIO.when(i % 30 == 0)(seedBucket(rid, "init-p01md.apple.com", date, m)).unit *>
        ZIO.when(i % 40 == 0)(seedBucket(rid, "stocks-data-service.apple.com", date, m)).unit
    }

  /** The liveness anchor: a genuine 20-minute diverse session at 15:00 UTC. */
  private def seedRealSession(
      rid: RouterId,
      date: LocalDate,
      startMin: Int,
  ): ZIO[TrafficReportRepo, Throwable, Unit] =
    ZIO.foreachDiscard(0 until 20) { m =>
      seedBucket(rid, "a-z-animals.com", date, startMin + m) *>
        seedBucket(rid, "cdn.shopify.com", date, startMin + m)
    }

  def spec = suite("OvernightBackgroundPhantomSpec (#2813)")(
    test(
      "an overnight window of only background traffic credits ~0 minutes; a real session still counts",
    ) {
      for {
        _   <- cleanDb
        hsr <- ZIO.service[HouseholdSettingsRepo]
        pr  <- ZIO.service[ProfileRepo]
        dr  <- ZIO.service[DeviceRepo]
        ar  <- ZIO.service[AppRepo]
        ahr <- ZIO.service[AmbientHostsRepo]
        ru  <- ZIO.service[TimeUsedRollupRepo]
        aru <- ZIO.service[AppUsedRollupRepo]
        atl <- ZIO.service[AppTimeLimitRepo]
        trr <- ZIO.service[TrafficReportRepo]
        s0  <- hsr.getForHousehold(HouseholdId.Default)
        _   <- hsr.update(
          HouseholdId.Default,
          s0.copy(dailyResetTz = ZoneOffset.UTC, ambientGateEnabled = true),
        )
        kid <- TestLayers.seedKidsProfile(pr)
        _   <- TestLayers.seedDevice(dr, kidMac, "Kid Laptop", kid)
        // Prod shape: the operator's 1Password app claims the APEX and is assigned to this
        // profile (allowed + exempt), so the background log-shipping subdomain is app-attributed.
        _   <- TestLayers.seedAppAssignment(ar, kid, "1password.com", AppMode.Allowed)
        rid <- seedRouterRow
        day = LocalDate.of(2026, 9, 29)
        _ <- seedOvernightBackground(rid, day)
        _ <- seedRealSession(rid, day, 15 * 60)
        now = LocalDateTime.of(day, LocalTime.of(20, 0)).toInstant(ZoneOffset.UTC)
        _     <- TimeUsedRollupJob.oneTickForTest(ru, aru, pr, dr, atl, trr, hsr, now, ahr)
        rolls <- ru.getDayMapForHousehold(HouseholdId.Default, day)
        usedMin = (rolls(kid).usedSeconds / 60L).toInt
        // Same day, overnight window ONLY — no real session — so the phantom is isolated from the
        // liveness anchor and cannot be masked by it.
        _         <- cleanDb
        s1        <- hsr.getForHousehold(HouseholdId.Default)
        _         <- hsr.update(
          HouseholdId.Default,
          s1.copy(dailyResetTz = ZoneOffset.UTC, ambientGateEnabled = true),
        )
        kid2      <- TestLayers.seedKidsProfile(pr)
        _         <- TestLayers.seedDevice(dr, kidMac, "Kid Laptop", kid2)
        _         <- TestLayers.seedAppAssignment(ar, kid2, "1password.com", AppMode.Allowed)
        rid2      <- seedRouterRow
        _         <- seedOvernightBackground(rid2, day)
        _         <- TimeUsedRollupJob.oneTickForTest(ru, aru, pr, dr, atl, trr, hsr, now, ahr)
        nightOnly <- ru.getDayMapForHousehold(HouseholdId.Default, day)
        overnightMin = nightOnly.get(kid2).map(r => (r.usedSeconds / 60L).toInt).getOrElse(0)
      } yield assertTrue(
        // THE BUG: six hours of background-only traffic must not become screen time.
        overnightMin <= 2,
        // LIVENESS ANCHOR: the same rig, on the same day, still credits the genuine 20-minute
        // session in full, so a rig that produced no traffic — or a change that suppressed
        // everything — cannot pass. Asserted directly on the first run's total rather than as
        // `usedMin - overnightMin`: `overnightMin` comes from a separately seeded DB state, and it
        // is already pinned `<= 2` on its own, so the subtraction added nothing but coupling.
        usedMin >= 18,
      )
    },
    test("api.wifihaven.net alone never anchors, so our own control plane credits 0") {
      // #2813 acceptance criterion 2, pinned directly rather than inferred from the aggregate
      // above. A whole day of nothing but WifiHaven's own control-plane traffic — the SPA tab
      // left open on the laptop — must credit zero.
      //
      // SCOPE OF THE GUARANTEE. `api.wifihaven.net` is on `InfraHosts.suppressOnly`, not the
      // #2177 background class, so this is the STRONG property: the host is dropped from presence
      // counting outright and contributes 0 at any hour — including inside a span anchored by real
      // browsing, which is the ordinary afternoon case the class tier would NOT have covered
      // (operator call on #2813). Suppression carries no enforcement effect: `suppressOnly` is
      // absent from `canonical` / `PolicyService.infraAllowHosts`, so reachability is unchanged.
      //
      // Per #1506 the unassigned `wifihaven` app template would still win over suppression if an
      // operator assigned it — deliberately, since assigning it asks to see that activity.
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
        _   <- hsr.update(
          HouseholdId.Default,
          s0.copy(dailyResetTz = ZoneOffset.UTC, ambientGateEnabled = true),
        )
        kid <- TestLayers.seedKidsProfile(pr)
        _   <- TestLayers.seedDevice(dr, kidMac, "Kid Laptop", kid)
        rid <- seedRouterRow
        day = LocalDate.of(2026, 9, 29)
        // Six hours of nothing but our own control plane, at 1 MB a bucket — far above the
        // heartbeat byte floor, so nothing here is dropped for being small.
        _ <- ZIO.foreachDiscard(0 until 180)(i => seedBucket(rid, "api.wifihaven.net", day, i * 2))
        now = LocalDateTime.of(day, LocalTime.of(20, 0)).toInstant(ZoneOffset.UTC)
        _     <- TimeUsedRollupJob.oneTickForTest(ru, aru, pr, dr, atl, trr, hsr, now, ahr)
        rolls <- ru.getDayMapForHousehold(HouseholdId.Default, day)
        mins = rolls.get(kid).map(r => (r.usedSeconds / 60L).toInt).getOrElse(0)
        // LIVENESS ANCHOR: the identical rig, same host count and byte volume, on a host that is
        // NOT classified, must credit real minutes. Without this half the assertion above would
        // also pass on a rig that seeded nothing, or one whose rollup silently wrote no row.
        _    <- cleanDb
        s1   <- hsr.getForHousehold(HouseholdId.Default)
        _    <- hsr.update(
          HouseholdId.Default,
          s1.copy(dailyResetTz = ZoneOffset.UTC, ambientGateEnabled = true),
        )
        kid2 <- TestLayers.seedKidsProfile(pr)
        _    <- TestLayers.seedDevice(dr, kidMac, "Kid Laptop", kid2)
        rid2 <- seedRouterRow
        _ <- ZIO.foreachDiscard(0 until 180)(i => seedBucket(rid2, "a-z-animals.com", day, i * 2))
        _ <- TimeUsedRollupJob.oneTickForTest(ru, aru, pr, dr, atl, trr, hsr, now, ahr)
        ctrl <- ru.getDayMapForHousehold(HouseholdId.Default, day)
        ctrlMins = ctrl.get(kid2).map(r => (r.usedSeconds / 60L).toInt).getOrElse(0)
      } yield assertTrue(mins == 0, ctrlMins >= 180)
    },
    // Both tests recreate the shared embedded-Postgres database via `cleanAndMigrate`, so they
    // cannot run concurrently. Same aspect the sibling rollup/ambient DB specs carry.
  ) @@ TestAspect.sequential
}
