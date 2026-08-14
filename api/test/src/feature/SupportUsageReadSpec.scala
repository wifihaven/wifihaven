package wifihaven.api.feature

import wifihaven.api.{PlainConfig, SupportConfig}
import wifihaven.api.auth.RateLimiter
import wifihaven.api.db.*
import wifihaven.api.notify.Notifier
import wifihaven.api.policy.PolicyService
import wifihaven.api.routes.SupportAgentRoutes
import wifihaven.api.support.*
import wifihaven.shared.*
import wifihaven.shared.Clock
import wifihaven.shared.Clock.TestClock
import wifihaven.shared.types.*
import wifihaven.testinfra.*
import doobie.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*
import zio.test.*

import java.time.{Instant, LocalDate, ZoneOffset}

/**
 * #2665 (epic #2197) — today's screen time is inside the CONSENTED support read.
 *
 * The prod defect this closes: a customer asked "how much screen time did macbook-pro use today?",
 * granted a 24h data-access consent, and the agent still could not answer — `HouseholdSummary`
 * carried name/plan/counts/profile-names and nothing else, so the grant bought them nothing
 * (`support_agent_action_total{op="household_read"}` had no series at all).
 *
 * What this suite pins, full-stack over embedded Postgres with no repo mocks and an injected Clock:
 *
 *   - the widened payload carries today's per-profile `usedMinutes` / `dailyLimitMinutes` /
 *     `remainingMinutes` / `blockedNow` / `blockReason`, sourced from the SAME
 *     `TimeStatusService.dayStateAll` the snapshot's TimeLimit decision and `GET /api/blocked` read
 *     (single-source-of-truth — screen-time accounting has drifted between readers three times:
 *     #2016, #2068, #2274);
 *   - devices carry their profile NAME, which is what lets the agent answer a per-DEVICE question
 *     ("macbook-pro") from per-PROFILE minutes without a second usage computation existing;
 *   - the read is scoped to the token's household — household B's usage is unreachable from an
 *     A-bound session and vice versa (the seventh member of the #2251 / #2257 / #2314 / #2603 /
 *     #2630 / #2636 leak family would otherwise land here);
 *   - a session WITHOUT data-access consent gets the same 403 it always did — the widening does not
 *     move one byte across the consent gate;
 *   - #2454 still holds: a `dataAccess=true` session is refused GitHub issue filing, with the
 *     widened payload in play. That refusal is what stops consented household data — now including
 *     screen-time minutes — reaching a public issue tracker.
 */
object SupportUsageReadSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private val TokenSecret = "agent-token-secret-0123456789abcdef"
  private val AppBaseUrl  = "https://app.example.test"

  private val macA = MacAddress.unsafe("aa:bb:cc:00:66:5a")
  private val macB = MacAddress.unsafe("aa:bb:cc:00:66:5b")

  private val liveCfg = SupportConfig(
    responderEnabled = true,
    issueFilingEnabled = true,
    plain = PlainConfig(apiKey = "plain-api-key-test", webhookSecret = "webhook-secret-xyz"),
    anthropicApiKey = "sk-ant-test",
    claudeAgentId = "agent_test",
    claudeEnvironmentId = "env_test",
    agentTokenSecret = TokenSecret,
    deploymentEnv = "staging",
    githubSupportBotToken = "github_pat_test",
  )

  private final case class Harness(
      routes: Routes[Any, Response],
      consentRepo: SupportConsentRepo,
      github: GithubIssueClient.Recorder,
  )

  private def makeHarness =
    for {
      hhRepo      <- ZIO.service[HouseholdRepo]
      userRepo    <- ZIO.service[UserRepo]
      billRepo    <- ZIO.service[HouseholdBillingRepo]
      devRepo     <- ZIO.service[DeviceRepo]
      profRepo    <- ZIO.service[ProfileRepo]
      hsRepo      <- ZIO.service[HouseholdSettingsRepo]
      timeStatus  <- TestLayers.timeStatusService
      consentRepo <- ZIO.service[SupportConsentRepo]
      clock       <- ZIO.service[Clock]
      plainRec    <- PlainClient.recorder
      dispRec     <- CloudAgentDispatcher.recorder
      tracker     <- DispatchTracker.make(
        DispatchTracker.deadAfterFor(liveCfg),
        wifihaven.api.observability.AgentTokenRejection.Channel.Support,
      )
      ghRec       <- Ref.make(List.empty[IssueFileRequest]).map(GithubIssueClient.Recorder.apply)
      responder = SupportResponder(
        liveCfg,
        hhRepo,
        userRepo,
        billRepo,
        devRepo,
        profRepo,
        hsRepo,
        timeStatus,
        consentRepo,
        PlainClient.recording(plainRec),
        GithubIssueClient.recording(ghRec),
        CloudAgentDispatcher.recording(dispRec),
        clock,
        RateLimiter.allowAll,
        RateLimiter.allowAll,
        RateLimiter.allowAll,
        RateLimiter.allowAll,
        RateLimiter.allowAll,
        RateLimiter.allowAll,
        AppBaseUrl,
        Notifier.logOnly,
        RateLimiter.allowAll,
        tracker,
      ).copy(runDetached = identity)
    } yield Harness(SupportAgentRoutes.routes(responder), consentRepo, ghRec)

  // ── helpers ─────────────────────────────────────────────────────────────────

  /** A token for `(hh, thread)` with the given scope — what the dispatch path would have minted. */
  private def mintAgentToken(hh: HouseholdId, thread: String, dataAccess: Boolean) =
    ZIO
      .serviceWithZIO[Clock](_.instant)
      .map(now =>
        ConsentToken.mint(
          hh,
          thread,
          dataAccess,
          now,
          java.time.Duration.ofHours(24),
          TokenSecret,
          ConsentToken.newSessionId(),
        ),
      )

  /** Record the LIVE grant the customer's own authenticated action would have written. */
  private def grantConsent(h: Harness, hh: HouseholdId, thread: String) =
    for {
      now <- ZIO.serviceWithZIO[Clock](_.instant)
      _   <- h.consentRepo.grant(
        household = hh,
        threadId = thread,
        nonce = s"nonce-$thread",
        linkIssuedAt = now,
        linkExpiresAt = now.plusSeconds(3600),
        grantedByUserId = None,
        now = now,
        expiresAt = now.plusSeconds(24 * 3600),
      )
    } yield ()

  /** A consented session for `(hh, thread)`: live grant + a `dataAccess=true` token. */
  private def consentedToken(h: Harness, hh: HouseholdId, thread: String) =
    grantConsent(h, hh, thread) *> mintAgentToken(hh, thread, dataAccess = true)

  private def getHousehold(h: Harness, token: String): Task[(Status, String)] =
    h.routes
      .runZIO(
        Request
          .get(URL.decode("/api/support/agent/household").toOption.get)
          .addHeader(Header.Authorization.Bearer(token)),
      )
      .flatMap(r => r.body.asString.map((r.status, _)))

  private def readSummary(h: Harness, token: String): Task[SupportResponder.HouseholdSummary] =
    getHousehold(h, token).flatMap { case (status, body) =>
      if status != Status.Ok then ZIO.fail(new RuntimeException(s"read failed: $status $body"))
      else
        ZIO
          .fromEither(body.fromJson[SupportResponder.HouseholdSummary])
          .mapError(e => new RuntimeException(s"$e — body was $body"))
    }

  private def fileIssue(h: Harness, token: String): Task[Status] =
    h.routes
      .runZIO(
        Request
          .post(
            URL.decode("/api/support/agent/issues").toOption.get,
            Body.fromString("""{"title":"t","body":"b"}"""),
          )
          .addHeader(Header.Authorization.Bearer(token)),
      )
      .map(_.status)

  /**
   * Seed `minutes` of presence for `mac` on `router` as 5-minute buckets from the start of `date`.
   * The SAME shape every other presence-accounting spec uses — one `traffic_reports` row per bucket
   * — so what this suite reads back is genuinely what the production accounting computes.
   */
  private def seedTraffic(
      router: RouterId,
      mac: MacAddress,
      date: LocalDate,
      minutes: Int,
  ): ZIO[TrafficReportRepo, Throwable, Unit] =
    ZIO.serviceWithZIO[TrafficReportRepo] { tr =>
      val dayStart = date.atStartOfDay(ZoneOffset.UTC).toInstant
      val inserts  = (0 until minutes / 5).map { i =>
        val start = dayStart.plusSeconds(i * 300L)
        TrafficReportInsert(
          router,
          mac,
          None,
          HostId.Fqdn(Hostname.unsafe("example.com")),
          date,
          start,
          start.plusSeconds(300),
          300,
          500_000L,
          500_000L,
        )
      }.toList
      tr.insertBatch(inserts).unit
    }

  private def householdToday(hh: HouseholdId, now: Instant) =
    ZIO
      .serviceWithZIO[HouseholdSettingsRepo](_.getForHousehold(hh))
      .map(PolicyService.householdLocalDate(now, _))

  private def profileNamed(s: SupportResponder.HouseholdSummary, name: String) =
    s.profiles.find(_.name == name)

  // ── the suite ───────────────────────────────────────────────────────────────

  def spec = suite("the consented support read carries today's screen time (#2665)")(
    test("today's per-profile minutes, limit and remaining ride the consented payload") {
      for {
        _    <- cleanDb
        two  <- TestLayers.seedTwoHouseholds(macA, macB)
        h    <- makeHarness
        tlr  <- ZIO.service[TimeLimitRepo]
        now  <- ZIO.serviceWithZIO[Clock](_.instant)
        date <- householdToday(two.hhA, now)
        // 60 minutes used today against a 120-minute cap → 60 remaining.
        _    <- tlr.upsert(two.profileA, 120)
        _    <- seedTraffic(two.routerIdA, macA, date, 60)
        tok  <- consentedToken(h, two.hhA, "th_usage")
        s    <- readSummary(h, tok)
        kids = profileNamed(s, "A-Kids")
      } yield assertTrue(
        // The date the minutes are FOR — household-local, so "today" is unambiguous in the reply.
        s.date.contains(date.toString),
        kids.exists(_.usedMinutes == 60),
        kids.exists(_.dailyLimitMinutes.contains(120)),
        kids.exists(_.remainingMinutes.contains(60)),
        // Under the cap and not paused: nothing is blocking this profile right now.
        kids.exists(!_.blockedNow),
        kids.exists(_.blockReason.isEmpty),
      )
    },
    test("an exhausted daily limit surfaces as blockedNow + TimeLimit, not as a bare number") {
      for {
        _    <- cleanDb
        two  <- TestLayers.seedTwoHouseholds(macA, macB)
        h    <- makeHarness
        tlr  <- ZIO.service[TimeLimitRepo]
        now  <- ZIO.serviceWithZIO[Clock](_.instant)
        date <- householdToday(two.hhA, now)
        _    <- tlr.upsert(two.profileA, 30)
        _    <- seedTraffic(two.routerIdA, macA, date, 60)
        tok  <- consentedToken(h, two.hhA, "th_over")
        s    <- readSummary(h, tok)
        kids = profileNamed(s, "A-Kids")
      } yield assertTrue(
        kids.exists(_.usedMinutes == 60),
        kids.exists(_.remainingMinutes.contains(0)),
        kids.exists(_.blockedNow),
        kids.exists(_.blockReason.contains("TimeLimit")),
      )
    },
    test("devices name their profile, so a per-DEVICE question resolves to per-PROFILE minutes") {
      for {
        _    <- cleanDb
        two  <- TestLayers.seedTwoHouseholds(macA, macB)
        h    <- makeHarness
        now  <- ZIO.serviceWithZIO[Clock](_.instant)
        date <- householdToday(two.hhA, now)
        _    <- seedTraffic(two.routerIdA, macA, date, 25)
        tok  <- consentedToken(h, two.hhA, "th_dev")
        s    <- readSummary(h, tok)
        dev  = s.devices.find(_.name == "devA")
        kids = profileNamed(s, "A-Kids")
      } yield assertTrue(
        dev.exists(_.profileName.contains("A-Kids")),
        kids.exists(_.usedMinutes == 25),
        // Bounded on purpose: the device carries no MAC, no IP, no hostnames — the name and the
        // profile it belongs to are the whole of what answering the question needs.
        s.devices.nonEmpty,
      )
    },
    test("two households: an A-bound session never sees B's usage, and vice versa") {
      for {
        _     <- cleanDb
        two   <- TestLayers.seedTwoHouseholds(macA, macB)
        h     <- makeHarness
        now   <- ZIO.serviceWithZIO[Clock](_.instant)
        dateA <- householdToday(two.hhA, now)
        dateB <- householdToday(two.hhB, now)
        _     <- seedTraffic(two.routerIdA, macA, dateA, 20)
        _     <- seedTraffic(two.routerIdB, macB, dateB, 90)
        tokA  <- consentedToken(h, two.hhA, "th_a")
        tokB  <- consentedToken(h, two.hhB, "th_b")
        sA    <- readSummary(h, tokA)
        sB    <- readSummary(h, tokB)
      } yield assertTrue(
        // A sees only A: its own device, its own minutes. (Household A is the default install, so
        // it also carries the template's seeded `Kids`/`Adults` profiles — hence a containment
        // assertion on names rather than an equality one.)
        sA.devices.map(_.name) == List("devA"),
        profileNamed(sA, "A-Kids").exists(_.usedMinutes == 20),
        // B's 90 minutes are unreachable from A — not under B's name, and not summed into ANY of
        // A's profiles. The total pins that: 20 is all the minutes A can see, anywhere.
        profileNamed(sA, "B-Kids").isEmpty,
        sA.profiles.map(_.usedMinutes).sum == 20,
        // …and symmetrically. Non-vacuous: B's own read really does carry its 90 minutes, so the
        // isolation above is scoping and not an empty read on both sides.
        sB.profiles.map(_.name) == List("B-Kids"),
        sB.devices.map(_.name) == List("devB"),
        profileNamed(sB, "B-Kids").exists(_.usedMinutes == 90),
        profileNamed(sB, "B-Kids").exists(_.blockedNow),
        profileNamed(sB, "B-Kids").exists(_.blockReason.contains("Paused")),
      )
    },
    test("a session WITHOUT consent still gets 403 — the widening crosses no gate") {
      for {
        _       <- cleanDb
        two     <- TestLayers.seedTwoHouseholds(macA, macB)
        h       <- makeHarness
        now     <- ZIO.serviceWithZIO[Clock](_.instant)
        date    <- householdToday(two.hhA, now)
        _       <- seedTraffic(two.routerIdA, macA, date, 45)
        // (a) no scope on the token and no grant at all;
        noScope <- mintAgentToken(two.hhA, "th_none", dataAccess = false)
        rNone   <- getHousehold(h, noScope)
        // (b) a token that CLAIMS the scope but has no live grant behind it (#2476).
        claimed <- mintAgentToken(two.hhA, "th_claim", dataAccess = false)
        rClaim  <- getHousehold(
          h,
          ConsentToken.mint(
            two.hhA,
            "th_claim",
            true,
            now,
            java.time.Duration.ofHours(24),
            TokenSecret,
            ConsentToken.newSessionId(),
          ),
        )
      } yield assertTrue(
        rNone._1 == Status.Forbidden,
        rClaim._1 == Status.Forbidden,
        // Nothing of the widened payload leaks into the refusal bodies.
        !rNone._2.contains("usedMinutes"),
        !rClaim._2.contains("usedMinutes"),
        !rNone._2.contains("45"),
        claimed.nonEmpty,
      )
    },
    test("#2454 holds with the widened payload: a data session reads usage and files nothing") {
      for {
        _      <- cleanDb
        two    <- TestLayers.seedTwoHouseholds(macA, macB)
        h      <- makeHarness
        now    <- ZIO.serviceWithZIO[Clock](_.instant)
        date   <- householdToday(two.hhA, now)
        _      <- seedTraffic(two.routerIdA, macA, date, 35)
        tok    <- consentedToken(h, two.hhA, "th_2454")
        s      <- readSummary(h, tok)
        // The SAME session that just read screen-time minutes tries to open a public issue.
        status <- fileIssue(h, tok)
        filed  <- h.github.issues.get
      } yield assertTrue(
        profileNamed(s, "A-Kids").exists(_.usedMinutes == 35),
        status == Status.Forbidden,
        // Structural, not scrubber-shaped: nothing reached the GitHub client at all.
        filed.isEmpty,
      )
    },
  ) @@ TestAspect.sequential
}
