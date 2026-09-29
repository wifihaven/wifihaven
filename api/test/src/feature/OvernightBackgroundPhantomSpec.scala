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
   * The prod overnight shape: 00:00–06:00 UTC, background-only. `api.wifihaven.net` and the
   * 1Password log forwarder drip every 3 minutes (continuous under the 120s continuation, so they
   * stitch into one long span); the beacon / IP-echo / Apple-init probes fire sporadically.
   */
  private def seedOvernightBackground(
      rid: RouterId,
      date: LocalDate,
  ): ZIO[TrafficReportRepo, Throwable, Unit] =
    ZIO.foreachDiscard(0 until 120) { i =>
      val m = i * 3
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
        overnightMin    = nightOnly.get(kid2).map(r => (r.usedSeconds / 60L).toInt).getOrElse(0)
        realSessionMins = usedMin - overnightMin
      } yield assertTrue(
        // THE BUG: six hours of background-only traffic must not become screen time.
        overnightMin <= 2,
        // LIVENESS ANCHOR: the same rig still credits the genuine 20-minute session in full, so a
        // rig that produced no traffic (or a change that suppressed everything) cannot pass.
        realSessionMins >= 18,
      )
    },
  )
}
