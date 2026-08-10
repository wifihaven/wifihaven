package wifihaven.api.feature

import wifihaven.api.{PlainConfig, SupportConfig}
import wifihaven.api.auth.RateLimiter
import wifihaven.api.db.*
import wifihaven.api.notify.Notifier
import wifihaven.api.observability.AgentTokenRejection
import wifihaven.api.routes.SupportAgentRoutes
import wifihaven.api.support.*
import wifihaven.shared.Clock
import wifihaven.shared.Clock.TestClock
import wifihaven.shared.types.HouseholdId
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import doobie.*
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*
import zio.metrics.*
import zio.test.*

/**
 * #2477 — the dispatch WATCHDOG: a positively-observable "no customer is waiting on a dead agent
 * session" signal, for both responder channels.
 *
 * WHAT #2472 LEFT OPEN. `DispatchTracker` already pairs a dispatch with its terminal callback and
 * counts the failures (`*_dispatch_total{outcome="callback_slow"|"no_callback"}`). But those are
 * COUNTERS that only move when something is wrong, so a healthy system emits nothing at all — and a
 * sweep fiber that died, a responder that was never enabled, and a genuinely quiet support queue
 * are then the SAME picture: no samples. #2546 is the standing proof that this is not theoretical —
 * #2469's prompt-drift detector has never emitted a sample in any environment, and its silence has
 * read as health ever since.
 *
 * WHAT THIS PINS. Every sweep emits its series unconditionally:
 *   - `agent_dispatch_unreplied{channel}` — a GAUGE of the dispatches currently outstanding past
 *     `DispatchTracker.SlowAfter`, written on EVERY tick including the healthy case, where it is an
 *     explicit 0 rather than an absence;
 *   - `agent_dispatch_sweeps_total{channel}` — the liveness anchor. A zero gauge only means "no
 *     customer is waiting" if the thing that wrote the zero is running; without this counter,
 *     "healthy" and "the watchdog is dead" are again indistinguishable (the docs/process/...
 *     absence-needs-a-liveness-anchor rule, and the whole point of #2546).
 *
 * Both are labelled ONLY by the bounded `channel` enum (support | press) — never a thread id,
 * household, email, or transport-and-thread pair.
 */
object DispatchWatchdogSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private val WebhookSecret = "plain-webhook-signing-secret-2477"
  private val TokenSecret   = "agent-token-secret-0123456789abcdef"

  private val liveCfg = SupportConfig(
    responderEnabled = true,
    dispatcher = "claude-code-cloud",
    plain = PlainConfig(
      apiKey = "plain-api-key-test",
      webhookSecret = WebhookSecret,
      escalationLabelTypeId = "lt_escalated_test",
    ),
    claudeCodeRoutineId = "routine_test",
    claudeCodeRoutineToken = "sk-ant-oat01-test",
    agentTokenSecret = TokenSecret,
    deploymentEnv = "staging",
  )

  private val Transport = CloudAgentObservability.ClaudeCodeCloud
  private val Support   = AgentTokenRejection.Channel.Support
  private val Press     = AgentTokenRejection.Channel.Press

  private final case class Rig(routes: Routes[Any, Response], tracker: DispatchTracker)

  private def makeRig =
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
      tracker     <- DispatchTracker.make(DispatchTracker.deadAfterFor(liveCfg), Support)
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
        GithubIssueClient.noop,
        CloudAgentDispatcher.recording(dispRec),
        clock,
        RateLimiter.allowAll,
        RateLimiter.allowAll,
        RateLimiter.allowAll,
        RateLimiter.allowAll,
        RateLimiter.allowAll,
        RateLimiter.allowAll,
        "https://app.example.test",
        Notifier.logOnly,
        RateLimiter.allowAll,
        tracker,
      )
    } yield Rig(SupportAgentRoutes.routes(responder), tracker)

  private def payload(tenant: Long, threadId: String): String =
    s"""{"workspaceId":"w_1","id":"pEv_chat","payload":{"eventType":"thread.chat_received",""" +
      s""""chat":{"text":${"my wifi is down".toJson},"createdBy":{"actorType":"customer"}},""" +
      s""""thread":{"id":"$threadId",""" +
      s""""customer":{"id":"c_1","externalId":"$tenant"}}}}"""

  private def dispatchOne(rig: Rig, hh: HouseholdId, threadId: String): Task[Status] = {
    val body = payload(hh.value, threadId)
    val req  = Request
      .post(URL.decode("/api/support/webhook").toOption.get, Body.fromString(body))
      .addHeader(PlainWebhook.SignatureHeader, SupportService.hmacSha256Hex(WebhookSecret, body))
    rig.routes.runZIO(req).map(_.status)
  }

  private def agentPost(rig: Rig, path: String, body: String, token: String): Task[Status] =
    rig.routes
      .runZIO(
        Request
          .post(URL.decode(path).toOption.get, Body.fromString(body))
          .addHeader(Header.Authorization.Bearer(token)),
      )
      .map(_.status)

  private def mintToken(hh: HouseholdId, threadId: String) =
    ZIO
      .serviceWithZIO[Clock](_.instant)
      .map(now => ConsentToken.mint(hh, threadId, false, now, liveCfg.agentTokenTtl, TokenSecret))

  /**
   * The gauge as scraped. NOTE the trap this spec is written around: a gauge that has NEVER been
   * written also reads 0.0, so `assertTrue(gauge == 0.0)` on a fresh series passes vacuously — the
   * exact confusion (`absent` masquerading as healthy) that #2477 exists to remove. Every zero
   * assertion below is therefore made only after the gauge has been observed NON-zero, or is paired
   * with the sweep-counter liveness anchor.
   */
  private def unreplied(channel: String): UIO[Double] =
    Metric
      .gauge(DispatchTracker.UnrepliedGauge)
      .tagged("channel", channel)
      .value
      .map(_.value)

  private def sweeps(channel: String): UIO[Double] =
    Metric
      .counter(DispatchTracker.SweepsCounter)
      .tagged("channel", channel)
      .value
      .map(_.count)

  private def noCallbacks: UIO[Double] =
    Metric
      .counter("support_dispatch_total")
      .tagged("outcome", DispatchTracker.Outcome.NoCallback)
      .tagged("transport", Transport)
      .value
      .map(_.count)

  private def seeded(tag: String) =
    for {
      _      <- cleanDb
      hhRepo <- ZIO.service[HouseholdRepo]
      hh     <- hhRepo.create(s"Family $tag", s"family-$tag")
      rig    <- makeRig
    } yield (hh, rig)

  private def at(d: java.time.Duration) =
    ZIO.serviceWithZIO[Clock](_.instant).map(_.plus(d))

  private val PastSlow = DispatchTracker.SlowAfter.plusMinutes(1)
  private val PastDead = DispatchTracker.deadAfterFor(liveCfg).plusMinutes(1)

  def spec = suite("dispatch watchdog (#2477)")(
    test("the HEALTHY case WRITES an explicit zero, and the sweep counts itself") {
      for {
        (hh, rig) <- seeded("healthy")
        before    <- sweeps(Support)
        // Raise the gauge first, so the zero below is a value the sweep WROTE and not the 0.0 an
        // untouched gauge reads back as.
        _         <- dispatchOne(rig, hh, "th_healthy")
        now       <- at(PastSlow)
        _         <- rig.tracker.sweep(now)
        raised    <- unreplied(Support)
        token     <- mintToken(hh, "th_healthy")
        _         <- agentPost(rig, "/api/support/agent/reply", """{"markdown":"ok"}""", token)
        _         <- rig.tracker.sweep(now.plusSeconds(60))
        healthy   <- unreplied(Support)
        // The heartbeat moves on EVERY tick — healthy ones included — so a stopped sweep reads as
        // a flat counter rather than as an absence indistinguishable from a quiet queue.
        after     <- sweeps(Support)
      } yield assertTrue(raised == 1.0, healthy == 0.0, after == before + 2)
    },
    test("a dispatch answered INSIDE the window is not counted against the window") {
      for {
        (hh, rig) <- seeded("answered")
        // TWO dispatches, one answered and one abandoned. The abandoned one holds the gauge at a
        // known non-zero, so "the answered one is not counted" is read off a LIVE series rather
        // than off a zero that would also appear if nothing were emitting at all.
        _         <- dispatchOne(rig, hh, "th_answered")
        _         <- dispatchOne(rig, hh, "th_abandoned")
        token     <- mintToken(hh, "th_answered")
        replied   <- agentPost(
          rig,
          "/api/support/agent/reply",
          """{"markdown":"here you go"}""",
          token,
        )
        now       <- at(PastSlow)
        _         <- rig.tracker.sweep(now)
        gauge     <- unreplied(Support)
      } yield assertTrue(replied == Status.Ok, gauge == 1.0)
    },
    test("a dispatch PAST the window with no reply raises the gauge, and it stays raised") {
      for {
        (hh, rig) <- seeded("stuck")
        _         <- dispatchOne(rig, hh, "th_stuck")
        // Inside the window it is a healthy in-flight dispatch, not a watchdog condition.
        early     <- at(java.time.Duration.ofMinutes(1))
        _         <- rig.tracker.sweep(early)
        fresh     <- unreplied(Support)
        now       <- at(PastSlow)
        _         <- rig.tracker.sweep(now)
        raised    <- unreplied(Support)
        // The WARN counter fires once; the GAUGE is a level, so it must still read 1 on the next
        // tick — that is what makes "someone is waiting right now" alertable at all.
        _         <- rig.tracker.sweep(now.plusSeconds(60))
        still     <- unreplied(Support)
      } yield assertTrue(fresh == 0.0, raised == 1.0, still == 1.0)
    },
    test("past the dead threshold the gauge falls back to zero as no_callback records the loss") {
      for {
        (hh, rig) <- seeded("dead")
        _         <- dispatchOne(rig, hh, "th_dead")
        slow      <- at(PastSlow)
        _         <- rig.tracker.sweep(slow)
        raised    <- unreplied(Support)
        before    <- noCallbacks
        dead      <- at(PastDead)
        _         <- rig.tracker.sweep(dead)
        after     <- noCallbacks
        gauge     <- unreplied(Support)
      } yield assertTrue(raised == 1.0, after == before + 1, gauge == 0.0)
    },
    test("an ESCALATION is terminal: the escalated thread is not counted against the window") {
      for {
        (hh, rig) <- seeded("esc")
        _         <- dispatchOne(rig, hh, "th_esc")
        // The same live-series control as above: one abandoned dispatch pins the gauge non-zero.
        _         <- dispatchOne(rig, hh, "th_esc_abandoned")
        token     <- mintToken(hh, "th_esc")
        status    <- agentPost(
          rig,
          "/api/support/agent/escalate",
          """{"note":"needs a human"}""",
          token,
        )
        now       <- at(PastSlow)
        _         <- rig.tracker.sweep(now)
        gauge     <- unreplied(Support)
      } yield assertTrue(status == Status.Ok, gauge == 1.0)
    },
    test("the two channels are independent: press's stuck dispatch never moves support's gauge") {
      for {
        _           <- cleanDb
        supportTrk  <- DispatchTracker.make(DispatchTracker.deadAfterFor(liveCfg), Support)
        pressTrk    <- DispatchTracker.make(DispatchTracker.deadAfterFor(liveCfg), Press)
        now         <- at(java.time.Duration.ZERO)
        // The correlation key is opaque to the tracker: support keys on a Plain thread id, press
        // (#2517) on its reply-target address. The watchdog cares only about the channel label.
        _           <- pressTrk.dispatched("reporter@example.test", HouseholdId(1L), Transport, now)
        pressBefore <- sweeps(Press)
        supBefore   <- sweeps(Support)
        later       <- at(PastSlow)
        _           <- supportTrk.sweep(later)
        _           <- pressTrk.sweep(later)
        sup         <- unreplied(Support)
        press       <- unreplied(Press)
        pressAfter  <- sweeps(Press)
        supAfter    <- sweeps(Support)
      } yield assertTrue(
        // Support's zero is not an absence: its own sweep ran and wrote it in the same pass that
        // wrote press's one. Anchored on a DELTA, not on `supAfter > 0` — the suite is sequential
        // and the counter is a process-global registry series, so the earlier tests have already
        // pushed it above zero and a bare `> 0` would hold whether or not this sweep ran at all.
        // That is the same vacuous-anchor trap this spec is written to avoid.
        sup == 0.0,
        press == 1.0,
        pressAfter == pressBefore + 1,
        supAfter == supBefore + 1,
      )
    },
  ) @@ TestAspect.sequential
}
