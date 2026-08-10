package wifihaven.api.feature

import wifihaven.api.PressConfig
import wifihaven.api.auth.RateLimiter
import wifihaven.api.db.*
import wifihaven.api.notify.{EmailSender, Notifier}
import wifihaven.api.press.*
import wifihaven.api.routes.PressAgentRoutes
import wifihaven.api.support.{CloudAgentObservability, DispatchTracker, SupportService}
import wifihaven.shared.Clock
import wifihaven.shared.Clock.TestClock
import wifihaven.testinfra.*
import doobie.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*
import zio.metrics.*
import zio.test.*

/**
 * #2517 — dispatch→completion tracking for the #2203 PRESS responder.
 *
 * WHAT IS WRONG WITHOUT IT. Press dispatch is fire-and-forget, exactly as support's was before
 * #2472: we fire the trigger, record `press_dispatch_total{outcome="dispatched",transport}`, and
 * never observe the return trip. A cloud session that accepts the trigger and then dies — crashed,
 * timed out, quota-exhausted, no tool call — is INDISTINGUISHABLE from one that answered the
 * journalist, and during launch coverage that is a reporter who emailed us and got nothing while
 * every dashboard stayed green (docs/process/no-dark-by-default.md).
 *
 * The correlation key is the difference from support, and the reason the tracker is GENERALIZED
 * over its key rather than forked: the support token carries a Plain `threadId`, while the press
 * token binds a reply-target address plus the recorded inbound row id (#2296 / #2407 / #2451). One
 * component, two channels, so completion accounting cannot drift between the two audiences that
 * already share one dispatch envelope (docs/process/single-source-of-truth.md).
 *
 * The load-bearing pins, driven through the REAL responder + routes on embedded Postgres (no repo
 * mocks — only the outbound email and the cloud-agent transport are recorders, per
 * docs/process/testing.md), with the Clock injected and every threshold crossed by MOVING THE
 * SWEEP'S INSTANT, never by sleeping:
 *   - a signed inbound that DISPATCHES registers the dispatch, and the agent's token-authenticated
 *     `/api/press/agent/reply` CLOSES it — `press_dispatch_total{outcome="completed",transport}` —
 *     after which a sweep long past the dead threshold reports NOTHING;
 *   - the #2437 `/api/press/agent/escalate` closes it too: a handoff to a human is a terminal
 *     action, not a dead session;
 *   - a dispatch nobody calls back is reported at the WARN tier past `SlowAfter`
 *     (`{outcome="callback_slow"}`), exactly ONCE however often the sweep runs, and then as
 *     `{outcome="no_callback"}` past the press agent-token TTL, also exactly once;
 *   - a REJECTED callback (no bearer) closes nothing — a forged caller cannot silence the report;
 *   - CHANNEL ISOLATION, the regression risk of generalizing a working component: a press callback
 *     must not close a support dispatch, and each channel's completion lands on its OWN series.
 *     (#2472's own suite, `SupportDispatchCompletionSpec`, remains the unchanged pin that support
 *     behaviour did not move.)
 */
object PressDispatchCompletionSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private val WebhookSecret = "press-webhook-signing-secret-2517"
  private val TokenSecret   = "press-agent-token-secret-0123456789abcdef"

  /** A distinctive untrusted payload that must never appear in a tracker report. */
  private val SecretMsg = "SECRET-JOURNALIST-PAYLOAD-2517-do-not-log"

  // The Claude Code Cloud transport — the one whose usage-limit suspends motivate the two tiers.
  private val liveCfg = PressConfig(
    responderEnabled = true,
    dispatcher = "claude-code-cloud",
    webhookSecret = WebhookSecret,
    claudeCodeRoutineId = "routine_press_test",
    claudeCodeRoutineToken = "sk-ant-oat01-test",
    agentTokenSecret = TokenSecret,
    deploymentEnv = "staging",
    fromAddress = "WifiHaven Press <press-staging@wifihaven.net>",
    replyToAddress = "press@staging.wifihaven.net",
  )

  private val Transport = CloudAgentObservability.ClaudeCodeCloud

  private final case class Rig(routes: Routes[Any, Response], tracker: DispatchTracker)

  private def makeRig =
    for {
      pressLog <- ZIO.service[PressMessageRepo]
      clock    <- ZIO.service[Clock]
      emailRef <- Ref.make(List.empty[EmailSender.Sent])
      dispRec  <- PressAgentDispatcher.recorder
      tracker  <- DispatchTracker.make(
        DispatchTracker.deadAfterFor(liveCfg),
        DispatchTracker.Channel.Press,
      )
      responder = PressResponder(
        liveCfg,
        EmailSender.recording(emailRef),
        PressAgentDispatcher.recording(dispRec),
        pressLog,
        clock,
        RateLimiter.allowAll,
        RateLimiter.allowAll,
        Notifier.logOnly,
        RateLimiter.allowAll,
        tracker,
      )
    } yield Rig(PressAgentRoutes.routes(responder), tracker)

  private def payload(from: String, text: String, subject: String = "Press inquiry"): String =
    s"""{"from":${from.toJson},"subject":${subject.toJson},"text":${text.toJson},""" +
      s""""messageId":"<abc@mail>"}"""

  /** POST the signed Cloudflare-Email-Worker envelope; returns the route status. */
  private def dispatchOne(rig: Rig, from: String): Task[Status] = {
    val body = payload(from, SecretMsg)
    val req  = Request
      .post(URL.decode("/api/press/inbound").toOption.get, Body.fromString(body))
      .addHeader(PressInbound.SignatureHeader, SupportService.hmacSha256Hex(WebhookSecret, body))
    rig.routes.runZIO(req).map(_.status)
  }

  private def agentPost(
      rig: Rig,
      path: String,
      body: String,
      token: Option[String],
  ): Task[Status] = {
    val base = Request.post(URL.decode(path).toOption.get, Body.fromString(body))
    val req  = token.fold(base)(t => base.addHeader(Header.Authorization.Bearer(t)))
    rig.routes.runZIO(req).map(_.status)
  }

  /**
   * The token the dispatched agent holds. `pressMessageId` is the recorded inbound row's id — the
   * correlation key the tracker pairs on — so the test reads it back from the repo rather than
   * assuming a value, which is also what pins that the key the responder recorded and the key the
   * token carries are the SAME one.
   */
  private def mintToken(from: String, pressMessageId: Long, subject: String = "Press inquiry") =
    ZIO.serviceWithZIO[Clock](_.instant).map { now =>
      PressToken.mint(
        replyTo = from,
        subject = subject,
        pressMessageId = pressMessageId,
        inboundMessageId = "<abc@mail>",
        // #2467 — the accumulated References chain. Empty here: this suite is about the
        // dispatch→completion pairing, and threading has its own coverage in PressResponderSpec.
        inboundReferences = "",
        now = now,
        // The rig's TTL comes from the SAME config the tracker's dead threshold does, so retuning
        // `press.agentTokenTtlMinutes` can never put `PastDead` beyond the test token's own expiry.
        ttl = liveCfg.agentTokenTtl,
        secret = TokenSecret,
      )
    }

  /** The id of the single recorded inbound row — the press correlation key. */
  private def latestInboundId: ZIO[PressMessageRepo, Throwable, Long] =
    ZIO.serviceWithZIO[PressMessageRepo](_.listRecent(10)).map(_.map(_.id).max)

  private def counter(series: String, outcome: String): UIO[Double] =
    Metric
      .counter(series)
      .tagged("outcome", outcome)
      .tagged("transport", Transport)
      .value
      .map(_.count)

  private def pressCounter(outcome: String): UIO[Double] = counter("press_dispatch_total", outcome)

  private def seeded =
    for {
      _   <- cleanDb
      rig <- makeRig
    } yield rig

  /** `now` shifted by `d` from the injected clock — the sweep takes its instant as a parameter. */
  private def at(d: java.time.Duration) =
    ZIO.serviceWithZIO[Clock](_.instant).map(_.plus(d))

  private val PastSlow = DispatchTracker.SlowAfter.plusMinutes(1)

  // The ERROR tier is the CONFIGURED press agent-token TTL: past it a resumed session's callback
  // would 401 (#2473), so the dispatch is unconditionally dead. Read from the same config the rig
  // builds the tracker from — never a literal, which would drift the moment the TTL is retuned.
  private val PastDead = DispatchTracker.deadAfterFor(liveCfg).plusMinutes(1)

  def spec = suite("press dispatch→completion tracking (#2517)")(
    test("an agent reply CLOSES the dispatch: completed is metered and no sweep ever reports it") {
      for {
        rig        <- seeded
        before     <- pressCounter(DispatchTracker.Outcome.Completed)
        status     <- dispatchOne(rig, "reporter@example.test")
        id         <- latestInboundId
        token      <- mintToken("reporter@example.test", id)
        replied    <- agentPost(
          rig,
          "/api/press/agent/reply",
          """{"markdown":"Happy to help — here is our public background."}""",
          Some(token),
        )
        after      <- pressCounter(DispatchTracker.Outcome.Completed)
        // Sweep FAR past the dead threshold: a closed dispatch must leave nothing behind.
        deadBefore <- pressCounter(DispatchTracker.Outcome.NoCallback)
        now        <- at(PastDead)
        _          <- rig.tracker.sweep(now)
        deadAfter  <- pressCounter(DispatchTracker.Outcome.NoCallback)
      } yield assertTrue(
        status == Status.Ok,
        replied == Status.Ok,
        after == before + 1,
        deadAfter == deadBefore,
      )
    },
    test("an ESCALATION closes the dispatch too — a handoff to a human is terminal (#2437)") {
      for {
        rig        <- seeded
        before     <- pressCounter(DispatchTracker.Outcome.Completed)
        _          <- dispatchOne(rig, "reporter-esc@example.test")
        id         <- latestInboundId
        token      <- mintToken("reporter-esc@example.test", id)
        escalated  <- agentPost(
          rig,
          "/api/press/agent/escalate",
          """{"note":"Asking to schedule a call — needs a human."}""",
          Some(token),
        )
        after      <- pressCounter(DispatchTracker.Outcome.Completed)
        deadBefore <- pressCounter(DispatchTracker.Outcome.NoCallback)
        now        <- at(PastDead)
        _          <- rig.tracker.sweep(now)
        deadAfter  <- pressCounter(DispatchTracker.Outcome.NoCallback)
      } yield assertTrue(
        escalated == Status.Ok,
        after == before + 1,
        deadAfter == deadBefore,
      )
    },
    test("a dispatch nobody answers is WARNed past SlowAfter — once, however often we sweep") {
      for {
        rig    <- seeded
        before <- pressCounter(DispatchTracker.Outcome.CallbackSlow)
        _      <- dispatchOne(rig, "silent@example.test")
        now    <- at(PastSlow)
        _      <- rig.tracker.sweep(now)
        once   <- pressCounter(DispatchTracker.Outcome.CallbackSlow)
        _      <- rig.tracker.sweep(now)
        _      <- rig.tracker.sweep(now)
        twice  <- pressCounter(DispatchTracker.Outcome.CallbackSlow)
      } yield assertTrue(once == before + 1, twice == once)
    },
    test("…and is an attributable ERROR past the token TTL — also exactly once") {
      for {
        rig    <- seeded
        before <- pressCounter(DispatchTracker.Outcome.NoCallback)
        _      <- dispatchOne(rig, "vanished@example.test")
        now    <- at(PastDead)
        _      <- rig.tracker.sweep(now)
        once   <- pressCounter(DispatchTracker.Outcome.NoCallback)
        _      <- rig.tracker.sweep(now)
        twice  <- pressCounter(DispatchTracker.Outcome.NoCallback)
      } yield assertTrue(once == before + 1, twice == once)
    },
    test("a REJECTED callback closes nothing — a forged caller cannot silence the report") {
      for {
        rig    <- seeded
        _      <- dispatchOne(rig, "forged@example.test")
        denied <- agentPost(rig, "/api/press/agent/reply", """{"markdown":"hi"}""", None)
        before <- pressCounter(DispatchTracker.Outcome.NoCallback)
        now    <- at(PastDead)
        _      <- rig.tracker.sweep(now)
        after  <- pressCounter(DispatchTracker.Outcome.NoCallback)
      } yield assertTrue(denied == Status.Unauthorized, after == before + 1)
    },
    test("channel isolation: a press completion lands on press_dispatch_total, never support's") {
      for {
        rig           <- seeded
        supportBefore <- counter("support_dispatch_total", DispatchTracker.Outcome.Completed)
        pressBefore   <- pressCounter(DispatchTracker.Outcome.Completed)
        _             <- dispatchOne(rig, "isolated@example.test")
        id            <- latestInboundId
        token         <- mintToken("isolated@example.test", id)
        _             <- agentPost(
          rig,
          "/api/press/agent/reply",
          """{"markdown":"public background"}""",
          Some(token),
        )
        pressAfter    <- pressCounter(DispatchTracker.Outcome.Completed)
        supportAfter  <- counter("support_dispatch_total", DispatchTracker.Outcome.Completed)
      } yield assertTrue(pressAfter == pressBefore + 1, supportAfter == supportBefore)
    },
  ) @@ TestAspect.sequential
}
