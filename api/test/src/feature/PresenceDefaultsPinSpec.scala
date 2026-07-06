package wifihaven.api.feature

import wifihaven.api.JwtConfig
import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.presence.Presence
import wifihaven.api.routes.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.shared.Clock.TestClock
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*
import zio.test.*

import java.time.{LocalDate, ZoneOffset}

/**
 * #1468 (subsumes #930) — end-to-end default-pinning gate for the presence / usage tuning knobs.
 *
 * This is a TRIPWIRE, not a behavioural test. It pins the tuning defaults *end-to-end* (DB
 * migration `DEFAULT` → repo → wire JSON for the household settings, and config → usage-series
 * output for the rate-independence invariant) so that a future PR cannot silently shift the visible
 * screen-time numbers. The defaults live in three places that must stay in lockstep:
 *
 *   - the Flyway migration `DEFAULT`s (V23 heartbeat, V24 FQDN allowlist, V52
 *     `presence_continuation_seconds`),
 *   - the Scala fallbacks (`HouseholdSettings.DefaultPresenceContinuationSeconds`,
 *     `Presence.DefaultContinuationSeconds`),
 *   - and the design contract in `docs/design/presence-tuning.md` §3.
 *
 * If you are *intentionally* retuning a default, update the literal pinned here in the SAME PR —
 * the gate failing is the point; it forces the change to be deliberate and reviewed against the
 * design doc, not an accidental drift.
 *
 * Note on `presence_model`: the issue title mentions pinning `presence_model = session`, but the
 * session model is GLOBAL and UNCONDITIONAL — there is no `presence_model` column or toggle (design
 * doc §4.6 / §5 item 1; V52 ships no such column). "Session is the model" is therefore pinned
 * structurally by the rate-independence assertion below (the session/union semantics are what make
 * R drop out of the formula), not by a config value.
 */
object PresenceDefaultsPinSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val jwtCfg  = JwtConfig(secret = "test-secret-at-least-32-chars!!x", expiryHours = 1)
  private val cleanDb = TestDatabase.cleanAndMigrate

  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwtCfg, clock)

  private def url(p: String) = URL.decode(p).toOption.get

  // ── Pinned fresh-install defaults ────────────────────────────────────────
  //
  // These are the values a freshly-installed household gets — i.e. the DB
  // `DEFAULT`s applied when `ensureDefault` inserts the singleton row naming
  // only id/daily_reset_time/daily_reset_tz (Main.ensureDefault / the test
  // template seed do exactly that). Changing any migration default below the
  // line MUST come here too.

  private val PinnedPresenceContinuationSeconds = 120   // V52, design §3 "idle window N"
  private val PinnedHeartbeatEnabled            = true  // V23 SET DEFAULT TRUE
  private val PinnedHeartbeatBytesThreshold     = 10240 // V23 SET DEFAULT 10240 (10 KB)

  // #1525: V24__heartbeat_host_patterns.sql is retired — the column is no longer read or written
  // by the API. Host-identity suppression now lives in the server-side canonical
  // `shared.types.InfraHosts` code constant (allow+suppress canonical ∪ suppress-only). The wire
  // field is retained on `HeartbeatFilter` for back-compat input tolerance, but the repo round-trip
  // always returns the empty list. The DB column gets dropped in a follow-up migration-only PR.
  private val PinnedHeartbeatHostPatterns: List[String] = Nil

  private def householdRoutesAndToken =
    for {
      auth <- makeAuth
      repo <- ZIO.service[HouseholdSettingsRepo]
      tk   <- auth.login("admin", "changeme").map(_.token.value)
    } yield (HouseholdSettingsRoutes.routes(auth, repo), tk)

  // ── Usage-series plumbing for the rate-independence invariant ─────────────

  private def buildUsageRoutes =
    for {
      deviceRepo      <- ZIO.service[DeviceRepo]
      trafficRepo     <- ZIO.service[TrafficReportRepo]
      userProfileRepo <- ZIO.service[UserProfileRepo]
      profileRepo     <- ZIO.service[ProfileRepo]
      appRepo         <- ZIO.service[AppRepo]
      rollupRepo      <- ZIO.service[RollupRepo]
      hsRepo          <- ZIO.service[HouseholdSettingsRepo]
      atlRepo         <- ZIO.service[AppTimeLimitRepo]
      aruRepo         <- ZIO.service[AppUsedRollupRepo]
      clock           <- ZIO.service[Clock]
      auth            <- makeAuth
    } yield UsageRoutes.routes(
      auth,
      deviceRepo,
      trafficRepo,
      userProfileRepo,
      profileRepo,
      appRepo,
      rollupRepo,
      hsRepo,
      atlRepo,
      aruRepo,
      clock,
    )

  private def seedRouter: ZIO[RouterRepo, Throwable, RouterId] =
    ZIO.serviceWithZIO[RouterRepo] { rr =>
      for {
        id <- rr.create("test-router", Sha256Hex.unsafe("t" * 64))
        _  <- rr.completeEnrollment(id, Sha256Hex.unsafe("u" * 64))
      } yield id
    }

  // The sampled-active floor a request-driven app registers per reporting window:
  // lots of local "think time", only a sliver of bytes-observed activity. This is
  // exactly the §2a/§2b undercount scenario, and it is what gives the
  // rate-independence assertion teeth — see the test for why.
  private val SampledActiveFloorSeconds = 10

  /**
   * One `traffic_reports` row for `mac`/`host` covering `[startSec, startSec + periodSeconds)` of
   * `date` (UTC), with `periodSeconds` standing in for the router's `usage_report_interval` R.
   * `activeSeconds` is the sampled floor (well below `periodSeconds`), the sparse-app case. Bytes
   * well above the 10 KB heartbeat threshold so the (default-on) filter keeps the row as real
   * activity rather than stripping it as a keepalive.
   */
  private def insertWindow(
      routerId: RouterId,
      mac: String,
      host: String,
      date: LocalDate,
      startSec: Long,
      periodSeconds: Int,
  ): ZIO[TrafficReportRepo, Throwable, Int] =
    ZIO.serviceWithZIO[TrafficReportRepo] { tr =>
      val start = date.atStartOfDay(ZoneOffset.UTC).toInstant.plusSeconds(startSec)
      val end   = start.plusSeconds(periodSeconds.toLong)
      tr.insertBatch(
        List(
          TrafficReportInsert(
            routerId,
            MacAddress.unsafe(mac),
            None,
            HostId.Fqdn(Hostname.unsafe(host)),
            date,
            start,
            end,
            SampledActiveFloorSeconds,
            25_000L,
            25_000L,
          ),
        ),
      )
    }

  private def seriesTotalForHour(
      routes: Routes[Any, Response],
      token: String,
      mac: String,
      date: LocalDate,
      hour: Int,
  ) =
    for {
      resp <- routes.runZIO(
        Request
          .get(url(s"/api/usage/series?mac=$mac&date=$date"))
          .addHeader(Header.Authorization.Bearer(token)),
      )
      body <- resp.body.asString
      out  <- ZIO.fromEither(body.fromJson[UsageSeriesResponse])
    } yield (resp.status, out.buckets(hour).totalMins)

  def spec = suite("presence/usage default-pinning gate (#1468, subsumes #930)")(
    test("fresh-install household settings pin the heartbeat + presence defaults end-to-end") {
      // cleanAndMigrate seeds the singleton row exactly as Main.ensureDefault does
      // (id/reset columns only), so the heartbeat + presence columns take their DB
      // DEFAULTs. GET /api/household/settings then exposes them on the wire.
      for {
        _               <- cleanDb
        (routes, token) <- householdRoutesAndToken
        resp            <- routes.runZIO(
          Request
            .get(url("/api/household/settings"))
            .addHeader(Header.Authorization.Bearer(token)),
        )
        body            <- resp.body.asString
        s               <- ZIO.fromEither(body.fromJson[HouseholdSettings])
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(s.presenceContinuationSeconds == PinnedPresenceContinuationSeconds) &&
        assertTrue(s.heartbeatFilter.enabled == PinnedHeartbeatEnabled) &&
        assertTrue(s.heartbeatFilter.bytesThreshold == PinnedHeartbeatBytesThreshold) &&
        assertTrue(s.heartbeatFilter.heartbeatHostPatterns == PinnedHeartbeatHostPatterns)
    },
    test("Scala default fallbacks match the pinned migration defaults") {
      // The code-side fallbacks (used when a config/row is absent) must not drift
      // from the DB DEFAULTs pinned above.
      assertTrue(
        HouseholdSettings.DefaultPresenceContinuationSeconds == PinnedPresenceContinuationSeconds,
      ) &&
      assertTrue(Presence.DefaultContinuationSeconds == PinnedPresenceContinuationSeconds)
    },
    test("changing usage_report_interval (R) does NOT shift the visible usage numbers") {
      // §2d rate-independence, end-to-end through GET /api/usage/series: the SAME
      // logical 5 minutes of continuous (but SPARSE — only a 10 s sampled floor
      // per window) activity, reported once as a single 300 s window (R = 300)
      // and once as five contiguous 60 s windows (R = 60), must read the same
      // visible minutes.
      //
      // The sparseness is what gives this teeth: the session/union model reads
      // the full [first,last] span (300 s = 5 min) for BOTH rates, because it is
      // defined on period_start/period_end timestamps with R out of the formula.
      // A regression to a rate-dependent model — e.g. the legacy
      // sum(activeSeconds)-per-bucket path — would instead see coarse = 10 s and
      // fine = 5 × 10 s = 50 s, i.e. the visible number would SHIFT with R. This
      // assertion fails loudly if that dependence is reintroduced.
      val macCoarse = "aa:bb:cc:dd:ee:01" // R = 300
      val macFine   = "aa:bb:cc:dd:ee:02" // R = 60
      val hour      = 14
      val startSec  = hour.toLong * 3600  // 14:00:00 UTC
      for {
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        deviceRepo  <- ZIO.service[DeviceRepo]
        kidsId      <- TestLayers.seedKidsProfile(profileRepo)
        _           <- TestLayers.seedDevice(deviceRepo, macCoarse, "iPad-coarse", kidsId)
        _           <- TestLayers.seedDevice(deviceRepo, macFine, "iPad-fine", kidsId)
        routerId    <- seedRouter
        today = TestClock.schoolDayAfternoon.toLocalDate
        // R = 300: one window [14:00, 14:05).
        _      <- insertWindow(routerId, macCoarse, "mathacademy.com", today, startSec, 300)
        // R = 60: five contiguous windows tiling the SAME [14:00, 14:05).
        _      <- ZIO.foreachDiscard(0 until 5) { i =>
          insertWindow(routerId, macFine, "mathacademy.com", today, startSec + i * 60L, 60)
        }
        routes <- buildUsageRoutes
        auth   <- makeAuth
        token  <- auth.login("admin", "changeme").map(_.token.value)
        coarse <- seriesTotalForHour(routes, token, macCoarse, today, hour)
        fine   <- seriesTotalForHour(routes, token, macFine, today, hour)
        (coarseStatus, coarseMins) = coarse
        (fineStatus, fineMins)     = fine
      } yield assertTrue(coarseStatus == Status.Ok) &&
        assertTrue(fineStatus == Status.Ok) &&
        // R-independence: the rate must not change the visible number …
        assertTrue(coarseMins == fineMins) &&
        // … and that number is the true 5-minute span, not a rate-dependent artifact.
        assertTrue(coarseMins == 5)
    },
  ) @@ TestAspect.sequential
}
