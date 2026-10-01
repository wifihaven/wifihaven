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
 * #2815: the SUPPRESSION tier is the other face of the #2813 apex-laundering bug. #2813 taught the
 * ANCHOR decision to compare specificity — the background class beats app attribution when it
 * claimed the host more specifically — but left `Presence.suppressedAsBackground` on the old
 * unconditional "#1506 attribution always wins" rule. So a brand-apex app template still
 * UN-SUPPRESSES a background lane that `InfraHosts` enumerates by exact host, and those rows count
 * toward the profile's daily total.
 *
 * Cross-producting every prod app's host-set against `canonical ++ suppressOnly` finds exactly four
 * such pairs, all brand-apex-versus-enumerated-lane:
 *
 * brave.com(2) < collector.bsg.brave.com(4) / star-randsrv.bsg.brave.com(4) plex.tv(2) <
 * pubsub.plex.tv(3) wifihaven.net(2) < api.wifihaven.net(3) [created by #2813 itself]
 * launchdarkly.com(2) < events.launchdarkly.com(3) [the only one ASSIGNED on prod]
 *
 * The LaunchDarkly pair is the one that matters, and it is subtle: `launchdarkly.com` reaches
 * `appHostPatterns` as a SHARED host of the assigned, time-limited "Feeling Great" app
 * (`shared_hosts:` in the template). The #1897 shared-host work already guarantees a shared backend
 * can never inflate an app's own engaged minutes — that path reads `distinctiveHosts` — but
 * `appHostPatterns` is built from `hosts` (all of them), so a shared backend DOES still override
 * background suppression and reach the profile's daily total. That residual inflation path is what
 * this fixture pins shut.
 *
 * The rule is the #2813 rule applied to the second predicate, so one precedence rule governs both:
 * strictly-more-specific background entry wins; EQUAL specificity keeps the app winning, which is
 * what preserves the legitimate #1506 case (an app that genuinely depends on an infra host and
 * names it exactly).
 */
object SuppressionSpecificitySpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres] {

  override val bootstrap = TestDatabase.layer

  private val cleanDb = TestDatabase.cleanAndMigrate

  private val kidMac = "aa:bb:cc:dd:ee:81"

  private def seedRouterRow: ZIO[RouterRepo, Throwable, RouterId] =
    ZIO.serviceWithZIO[RouterRepo](_.create("gw-2815", Sha256Hex.unsafe("c" * 64)))

  /**
   * One 60-second bucket at `offsetMin` past midnight UTC, 1 MB — well over the heartbeat floor.
   */
  private def seedBucket(
      routerId: RouterId,
      hostname: String,
      date: LocalDate,
      offsetMin: Int,
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
            500_000L,
            500_000L,
          ),
        ),
      ).unit
    }

  private val day = LocalDate.of(2026, 9, 30)
  private val now = LocalDateTime.of(day, LocalTime.of(20, 0)).toInstant(ZoneOffset.UTC)

  /** Roll up one day and return the profile's credited minutes. */
  private def rollupMins(profileId: ProfileId) =
    for {
      hsr   <- ZIO.service[HouseholdSettingsRepo]
      pr    <- ZIO.service[ProfileRepo]
      dr    <- ZIO.service[DeviceRepo]
      ru    <- ZIO.service[TimeUsedRollupRepo]
      aru   <- ZIO.service[AppUsedRollupRepo]
      atl   <- ZIO.service[AppTimeLimitRepo]
      trr   <- ZIO.service[TrafficReportRepo]
      ahr   <- ZIO.service[AmbientHostsRepo]
      _     <- TimeUsedRollupJob.oneTickForTest(ru, aru, pr, dr, atl, trr, hsr, now, ahr)
      rolls <- ru.getDayMapForHousehold(HouseholdId.Default, day)
    } yield rolls.get(profileId).map(r => (r.usedSeconds / 60L).toInt).getOrElse(0)

  /**
   * Fresh household + profile + device, with an app assigned claiming `appHost`.
   *
   * `exemptFromDaily = false` is load-bearing, not incidental: `seedAppAssignment` defaults it to
   * TRUE, and an exempt app's hosts are filtered out of the profile's daily total before the cap is
   * evaluated (`ProfileAppDispositions.exemptPatterns`). Under the default every arm of this spec
   * would read 0 minutes for the exempt reason rather than the suppression reason, and the ~0
   * assertions would pass without testing anything.
   */
  private def setup(appHost: String, mode: AppMode = AppMode.Allowed) =
    for {
      _   <- cleanDb
      hsr <- ZIO.service[HouseholdSettingsRepo]
      pr  <- ZIO.service[ProfileRepo]
      dr  <- ZIO.service[DeviceRepo]
      ar  <- ZIO.service[AppRepo]
      s0  <- hsr.getForHousehold(HouseholdId.Default)
      _   <- hsr.update(HouseholdId.Default, s0.copy(dailyResetTz = ZoneOffset.UTC))
      kid <- TestLayers.seedKidsProfile(pr)
      _   <- TestLayers.seedDevice(dr, kidMac, "kid-laptop", kid)
      _   <- TestLayers.seedAppAssignment(ar, kid, appHost, mode, None, exemptFromDaily = false)
      rid <- seedRouterRow
    } yield (kid, rid)

  def spec = suite("SuppressionSpecificitySpec (#2815)")(
    test("a brand-apex app does NOT un-suppress a more-specific background lane") {
      for {
        // `brave.com` is the app apex; `collector.bsg.brave.com` is Shields telemetry, enumerated
        // on `suppressOnly` and documented there as "Background, not user-initiated".
        setup0 <- setup("brave.com")
        (kid, rid) = setup0
        _      <- ZIO.foreachDiscard(0 until 120)(i =>
          seedBucket(rid, "collector.bsg.brave.com", day, i * 2),
        )
        mins   <- rollupMins(kid)
        // LIVENESS ANCHOR: an identical rig on the app's OWN apex must credit real minutes, so a
        // rig that seeded nothing — or a change that suppressed the whole brand — cannot pass.
        setup1 <- setup("brave.com")
        (kid2, rid2) = setup1
        _ <- ZIO.foreachDiscard(0 until 120)(i => seedBucket(rid2, "search.brave.com", day, i * 2))
        ctrlMins <- rollupMins(kid2)
      } yield assertTrue(mins == 0, ctrlMins >= 200)
    },
    test("#1506 preserved: an app naming the infra host EXACTLY still wins over suppression") {
      for {
        // `time.apple.com` is on `suppressOnly`. An app claiming it at equal specificity is the
        // legitimate dependency case the #1506 seam exists for, and must keep counting.
        setup0 <- setup("time.apple.com")
        (kid, rid) = setup0
        _    <- ZIO.foreachDiscard(0 until 120)(i => seedBucket(rid, "time.apple.com", day, i * 2))
        mins <- rollupMins(kid)
        // and with NO app claiming it, the same traffic is suppressed — so the assertion above is
        // earned by the attribution, not by the host being countable anyway.
        setup1 <- setup("unrelated-app.example")
        (kid2, rid2) = setup1
        _ <- ZIO.foreachDiscard(0 until 120)(i => seedBucket(rid2, "time.apple.com", day, i * 2))
        unclaimed <- rollupMins(kid2)
      } yield assertTrue(mins >= 200, unclaimed == 0)
    },
    // Both tests recreate the shared embedded-Postgres database, so they cannot run concurrently.
  ) @@ TestAspect.sequential
}
