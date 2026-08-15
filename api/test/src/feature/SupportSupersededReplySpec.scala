package wifihaven.api.feature

import wifihaven.api.{JwtConfig, PlainConfig, SupportConfig}
import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.notify.Notifier
import wifihaven.api.routes.{SupportAgentRoutes, SupportConsentRoutes}
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
 * #2668 — ONE customer turn gets ONE reply, even when two agent sessions are in flight on the same
 * thread.
 *
 * WHAT WENT WRONG (prod, 2026-08-09, threads `th_01KZM39Y…` and `th_01KZM2RM…`). Every data-access
 * consent grant produced TWO bot replies, seconds apart, saying the same thing in different words.
 * The mechanism is not consent-specific and is not what the issue first guessed (nothing on the
 * server or in the SPA posts a customer-attributed message at grant time — the only Plain writes
 * are the machine-user `replyToThread`, which comes back as `thread.chat_sent` and is dropped by
 * the #2403 loop guard):
 *
 *   - the #2460 resume dispatches a `dataAccess=true` session the moment the grant transitions;
 *   - the customer, doing exactly what the consent page tells them ("Head back to the chat … just
 *     ask again"), sends another message, which dispatches a SECOND session on the same thread;
 *   - both sessions run to completion and both call `POST /api/support/agent/reply`.
 *
 * `DispatchTracker` already DETECTED this — it logged "support dispatch superseded … the earlier
 * session no longer owes a reply on this thread" (priorAgeSeconds 20 and 35 on the two prod
 * threads) — but nothing enforced it, so the earlier session's answer still went to the customer.
 *
 * THE GUARD IS STRUCTURAL, not timing-based: the LATEST dispatch on a thread owns the turn (its
 * kickoff carries a superset of the earlier session's context — the same timeline plus whatever
 * arrived since), and a customer-visible callback from any other session is suppressed. Both
 * interleavings are pinned below, because the two dispatches raced ~20s apart in prod and could
 * land either way under load.
 */
object SupportSupersededReplySpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private val WebhookSecret = "plain-webhook-signing-secret-xyz"
  private val TokenSecret   = "agent-token-secret-0123456789abcdef"
  private val AppBaseUrl    = "https://app.example.test"

  private val jwt = JwtConfig(secret = "test-secret-at-least-32-chars!!x", expiryHours = 1)

  private val liveCfg = SupportConfig(
    responderEnabled = true,
    plain = PlainConfig(
      apiKey = "plain-api-key-test",
      webhookSecret = WebhookSecret,
      escalationLabelTypeId = "lt_escalated_test",
    ),
    anthropicApiKey = "sk-ant-test",
    claudeAgentId = "agent_test",
    claudeEnvironmentId = "env_test",
    agentTokenSecret = TokenSecret,
    deploymentEnv = "staging",
  )

  private final case class Harness(
      agentRoutes: Routes[Any, Response],
      consentRoutes: Routes[Any, Response],
      auth: AuthService,
      plain: PlainClient.Recorder,
      dispatch: CloudAgentDispatcher.Recorder,
      tracker: DispatchTracker,
  )

  private def makeHarness =
    for {
      hhRepo      <- ZIO.service[HouseholdRepo]
      userRepo    <- ZIO.service[UserRepo]
      billRepo    <- ZIO.service[HouseholdBillingRepo]
      devRepo     <- ZIO.service[DeviceRepo]
      profRepo    <- ZIO.service[ProfileRepo]
      consentRepo <- ZIO.service[SupportConsentRepo]
      // #2665: the consented read's two new inputs — the household-local timezone and the canonical
      // day-state primitive. Real ones from the environment, per docs/process/testing.md.
      hsRepo      <- ZIO.service[HouseholdSettingsRepo]
      timeStatus  <- TestLayers.timeStatusService
      clock       <- ZIO.service[Clock]
      plainRec    <- PlainClient.recorder
      dispRec     <- CloudAgentDispatcher.recorder
      tracker     <- DispatchTracker.make(
        DispatchTracker.deadAfterFor(liveCfg),
        DispatchTracker.Channel.Support,
      )
      base      = SupportResponder(
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
        AppBaseUrl,
        Notifier.logOnly,
        RateLimiter.allowAll,
        tracker,
      )
      // The inline runner, so the #2460 resume is observable deterministically rather than through
      // a wall-clock wait on a daemon fiber (the seam changes only WHERE it runs).
      responder = base.copy(runDetached = identity)
      auth      = AuthServiceLive(userRepo, jwt, clock, hhRepo): AuthService
    } yield Harness(
      SupportAgentRoutes.routes(responder),
      SupportConsentRoutes.routes(auth, responder),
      auth,
      plainRec,
      dispRec,
      tracker,
    )

  // ── driving the flow ────────────────────────────────────────────────────────

  private def payload(tenant: Long, threadId: String, text: String): String =
    s"""{"workspaceId":"w_1","id":"pEv_chat","payload":{"eventType":"thread.chat_received",""" +
      s""""chat":{"text":${text.toJson},"createdBy":{"actorType":"customer"}},""" +
      s""""thread":{"id":"$threadId","customer":{"id":"c_1","externalId":"$tenant"}}}}"""

  /**
   * The EMAIL twin of [[payload]]. Both channels dispatch through the same path, and the duplicate
   * costs more here: two real messages land in the customer's inbox. The identified shape
   * (`customer.externalId` = household) is what the prod email thread `th_01KZM416D…` carried.
   */
  private def emailPayload(tenant: Long, threadId: String, text: String): String =
    s"""{"workspaceId":"w_1","id":"pEv_email","payload":{"eventType":"thread.email_received",""" +
      s""""email":{"textContent":${text.toJson},"createdBy":{"actorType":"customer"}},""" +
      s""""thread":{"id":"$threadId","customer":{"id":"c_1","externalId":"$tenant"}}}}"""

  /** One inbound customer message; returns the agent token minted for the session it dispatched. */
  private def inbound(
      h: Harness,
      hh: HouseholdId,
      thread: String,
      text: String,
      channel: (Long, String, String) => String = payload,
  ): Task[String] = {
    val body = channel(hh.value, thread, text)
    for {
      before <- h.dispatch.dispatches.get.map(_.size)
      _      <- h.agentRoutes.runZIO(
        Request
          .post(URL.decode("/api/support/webhook").toOption.get, Body.fromString(body))
          .addHeader(
            PlainWebhook.SignatureHeader,
            SupportService.hmacSha256Hex(WebhookSecret, body),
          ),
      )
      all    <- h.dispatch.dispatches.get
      token  <- ZIO
        .fromOption(all.drop(before).headOption.map(_._1.agentToken))
        .orElseFail(new RuntimeException(s"no dispatch for $thread"))
    } yield token
  }

  private def agentPost(h: Harness, path: String, body: String, token: String): Task[Status] =
    h.agentRoutes
      .runZIO(
        Request
          .post(URL.decode(path).toOption.get, Body.fromString(body))
          .addHeader(Header.Authorization.Bearer(token)),
      )
      .map(_.status)

  private def reply(h: Harness, token: String, markdown: String): Task[Status] =
    agentPost(h, "/api/support/agent/reply", s"""{"markdown":${markdown.toJson}}""", token)

  private def postConsent(h: Harness, jwtToken: String, grant: String): Task[Status] =
    h.consentRoutes
      .runZIO(
        Request
          .post(
            URL.decode("/api/support/consent").toOption.get,
            Body.fromString(s"""{"grant":${grant.toJson}}"""),
          )
          .addHeader(Header.Authorization.Bearer(jwtToken)),
      )
      .map(_.status)

  private def seedAdmin(
      h: Harness,
      userRepo: UserRepo,
      hh: HouseholdId,
      slug: String,
      username: String,
  ): Task[String] =
    for {
      hashed <- h.auth.hashPassword("pwpwpwpw11")
      id     <- userRepo.create(username, hashed, "admin", hh, None)
      _      <- userRepo.clearMustChangePassword(id)
      login  <- h.auth
        .login(s"$slug/$username", "pwpwpwpw11")
        .mapError(e => new RuntimeException(s"login failed: $e"))
    } yield login.token.value

  private def grantTokenFromThread(h: Harness): Task[String] =
    h.plain.threads.get
      .map(_.lastOption.flatMap { w =>
        val marker = s"$AppBaseUrl/support/consent?g="
        val i      = w.markdown.indexOf(marker)
        if i < 0 then None else Some(w.markdown.drop(i + marker.length).takeWhile(_ != ')'))
      })
      .someOrFail(new RuntimeException("no consent link posted"))

  /**
   * The customer-visible replies on `thread`, consent prompts excluded and the server-owned
   * attribution header stripped — this suite is about HOW MANY answers the customer got, not how
   * they are framed (`SupportAttributionSpec` owns the header).
   */
  /**
   * The AGENT-authored replies on this thread — what "exactly one reply" is counting.
   *
   * Server-authored writes are excluded, which is why the consent prompt was already filtered out
   * by its link. #2709 added a second one: when a customer message arrives while an unredeemed
   * consent link is live, the server posts [[SupportResponder.consentLinkExplainer]] at dispatch so
   * the customer is answered without an agent turn. It is the server's own fixed copy, not a reply,
   * and counting it here would make these assertions weaker rather than stronger — the point is
   * that exactly one AGENT reply survives the race, and that is unchanged.
   */
  private def replies(h: Harness, thread: String): UIO[List[String]] =
    h.plain.threads.get.map(
      _.filter(w =>
        w.threadId == thread &&
          !w.markdown.contains("/support/consent?g=") &&
          w.markdown != SupportResponder.consentLinkExplainer,
      )
        .map(w => w.markdown.replace(SupportResponder.AiReplyAttribution, "").trim),
    )

  private def actionCounter(op: String, outcome: String): UIO[Double] =
    Metric
      .counter("support_agent_action_total")
      .tagged("op", op)
      .tagged("outcome", outcome)
      .value
      .map(_.count)

  private val ResumeAnswer  = "From the RESUME session: you have 1 profile."
  private val WebhookAnswer = "From the WEBHOOK session: you have 1 profile, Test Profile."

  /**
   * Everything up to (but not including) the grant: the customer's question, the agent's ask, and
   * the timeline the resume will read back. Returns the JWT + the redeemable grant token.
   */
  private def upToTheAsk(h: Harness, hh: HouseholdId, slug: String, thread: String) =
    for {
      userRepo <- ZIO.service[UserRepo]
      jwtTok   <- seedAdmin(h, userRepo, hh, slug, s"admin_$slug")
      first    <- inbound(h, hh, thread, "how many profiles do I have?")
      _        <- agentPost(h, "/api/support/agent/request-consent", "", first)
      grant    <- grantTokenFromThread(h)
      prompt   <- h.plain.threads.get.map(_.last.markdown)
      _        <- h.plain.history.set(
        List(
          PlainThreadMessage(ThreadMessageRole.Customer, "how many profiles do I have?"),
          PlainThreadMessage(ThreadMessageRole.AiAssistant, prompt),
        ),
      )
    } yield (jwtTok, grant)

  /** The `dataAccess=true` session the grant dispatched. */
  private def resumeToken(h: Harness, before: Int): Task[String] =
    h.dispatch.dispatches.get.flatMap(all =>
      ZIO
        .fromOption(all.drop(before).headOption.map(_._1.agentToken))
        .orElseFail(new RuntimeException("the grant dispatched no resume")),
    )

  def spec = suite("support: one turn, one reply (#2668)")(
    test("resume THEN webhook: the superseded resume session's reply never reaches the customer") {
      for {
        _               <- cleanDb
        hhRepo          <- ZIO.service[HouseholdRepo]
        h               <- makeHarness
        hh              <- hhRepo.create("Family A", "family-a")
        (jwtTok, grant) <- upToTheAsk(h, hh, "family-a", "th_a")
        before          <- h.dispatch.dispatches.get.map(_.size)
        // The grant closes the loop (#2460) — session R.
        granted         <- postConsent(h, jwtTok, grant)
        rToken          <- resumeToken(h, before)
        // …and THEN the customer goes back to the chat and asks again, exactly as the consent page
        // tells them to. That dispatches session W, which supersedes R.
        wToken          <- inbound(h, hh, "th_a", "did that work?")
        supBefore       <- actionCounter(AgentAction.Reply, "superseded")
        // Both sessions answer. Only the LATEST one owns the turn.
        rStatus         <- reply(h, rToken, ResumeAnswer)
        wStatus         <- reply(h, wToken, WebhookAnswer)
        sent            <- replies(h, "th_a")
        supAfter        <- actionCounter(AgentAction.Reply, "superseded")
      } yield assertTrue(
        granted == Status.Ok,
        rToken != wToken,
        // The suppressed session is told OK — it must not retry, and a 4xx would make it try to
        // recover a turn it no longer owns.
        rStatus == Status.Ok,
        wStatus == Status.Ok,
        sent == List(WebhookAnswer),
        supAfter == supBefore + 1,
      )
    },
    test("webhook THEN resume: the race lands the other way and still yields exactly one reply") {
      for {
        _               <- cleanDb
        hhRepo          <- ZIO.service[HouseholdRepo]
        h               <- makeHarness
        hh              <- hhRepo.create("Family B", "family-b")
        (jwtTok, grant) <- upToTheAsk(h, hh, "family-b", "th_b")
        // The customer's follow-up lands FIRST — session W…
        wToken          <- inbound(h, hh, "th_b", "did that work?")
        before          <- h.dispatch.dispatches.get.map(_.size)
        // …and the grant's resume dispatches after it, so R is now the session that owns the turn.
        granted         <- postConsent(h, jwtTok, grant)
        rToken          <- resumeToken(h, before)
        wStatus         <- reply(h, wToken, WebhookAnswer)
        rStatus         <- reply(h, rToken, ResumeAnswer)
        sent            <- replies(h, "th_b")
      } yield assertTrue(
        granted == Status.Ok,
        wStatus == Status.Ok,
        rStatus == Status.Ok,
        sent == List(ResumeAnswer),
      )
    },
    test("the EMAIL channel gets the same guard — two real messages in an inbox is worse") {
      // The prod email thread th_01KZM416D28FWVYQZHDNCZB7MF, exactly: the session that asked for
      // consent kept running and replied 2s after the grant (far too fast to be the resume, whose
      // healthy latency is 30-110s per #2472), then the resume read the household and replied
      // again. Two emails to the customer's inbox off one grant.
      for {
        _        <- cleanDb
        hhRepo   <- ZIO.service[HouseholdRepo]
        h        <- makeHarness
        hh       <- hhRepo.create("Family M", "family-m")
        userRepo <- ZIO.service[UserRepo]
        jwtTok   <- seedAdmin(h, userRepo, hh, "family-m", "admin_family-m")
        asked    <- inbound(h, hh, "th_m", "how many profiles do I have?", emailPayload)
        _        <- agentPost(h, "/api/support/agent/request-consent", "", asked)
        grant    <- grantTokenFromThread(h)
        prompt   <- h.plain.threads.get.map(_.last.markdown)
        _        <- h.plain.history.set(
          List(
            PlainThreadMessage(ThreadMessageRole.Customer, "how many profiles do I have?"),
            PlainThreadMessage(ThreadMessageRole.AiAssistant, prompt),
          ),
        )
        before   <- h.dispatch.dispatches.get.map(_.size)
        granted  <- postConsent(h, jwtTok, grant)
        rToken   <- resumeToken(h, before)
        // The asking session, still live on its 24h token, posts its own answer after the resume.
        stale    <- reply(h, asked, "From the ASKING session: I have asked for permission.")
        current  <- reply(h, rToken, ResumeAnswer)
        sent     <- replies(h, "th_m")
      } yield assertTrue(
        granted == Status.Ok,
        stale == Status.Ok,
        current == Status.Ok,
        sent == List(ResumeAnswer),
      )
    },
    test("the ORDINARY reply path has the same seam: two quick messages get one answer") {
      // dispatched=7 / reply=7 across two prod conversations — the doubling is not consent-only.
      // A customer who sends a second message before the first session answers gets ONE reply: the
      // later session's kickoff carries both messages, so nothing is lost by dropping the earlier.
      for {
        _      <- cleanDb
        hhRepo <- ZIO.service[HouseholdRepo]
        h      <- makeHarness
        hh     <- hhRepo.create("Family C", "family-c")
        first  <- inbound(h, hh, "th_c", "my iPad is blocked")
        second <- inbound(h, hh, "th_c", "actually it is the Switch")
        s1     <- reply(h, first, "answer to the iPad")
        s2     <- reply(h, second, "answer to the Switch")
        sent   <- replies(h, "th_c")
      } yield assertTrue(s1 == Status.Ok, s2 == Status.Ok, sent == List("answer to the Switch"))
    },
    test("the session that OWNS the turn may still write twice — escalate then reply (#2437)") {
      for {
        _      <- cleanDb
        hhRepo <- ZIO.service[HouseholdRepo]
        h      <- makeHarness
        hh     <- hhRepo.create("Family D", "family-d")
        token  <- inbound(h, hh, "th_d", "I need a human")
        esc    <- agentPost(h, "/api/support/agent/escalate", """{"note":"needs a human"}""", token)
        rep    <- reply(h, token, "a teammate will follow up")
        sent   <- replies(h, "th_d")
      } yield assertTrue(
        esc == Status.Ok,
        rep == Status.Ok,
        sent == List("a teammate will follow up"),
      )
    },
    test("FAIL OPEN: a reply for a thread the tracker never saw is still delivered") {
      // The tracker's state is in-memory (#2472), so a process restart drops the in-flight set.
      // "No record of this thread" is NOT evidence that the session was superseded, and answering
      // nobody is worse than answering twice — so an untracked session's reply goes through.
      for {
        _      <- cleanDb
        hhRepo <- ZIO.service[HouseholdRepo]
        h      <- makeHarness
        hh     <- hhRepo.create("Family E", "family-e")
        now    <- ZIO.serviceWithZIO[Clock](_.instant)
        orphan = ConsentToken.mint(
          hh,
          "th_e",
          false,
          now,
          liveCfg.agentTokenTtl,
          TokenSecret,
          ConsentToken.newSessionId(),
        )
        status <- reply(h, orphan, "the answer after a restart")
        sent   <- replies(h, "th_e")
      } yield assertTrue(status == Status.Ok, sent == List("the answer after a restart"))
    },
    test("a superseded session cannot post a second consent prompt either") {
      for {
        _       <- cleanDb
        hhRepo  <- ZIO.service[HouseholdRepo]
        h       <- makeHarness
        hh      <- hhRepo.create("Family F", "family-f")
        first   <- inbound(h, hh, "th_f", "how many profiles do I have?")
        second  <- inbound(h, hh, "th_f", "hello?")
        stale   <- agentPost(h, "/api/support/agent/request-consent", "", first)
        current <- agentPost(h, "/api/support/agent/request-consent", "", second)
        prompts <- h.plain.threads.get.map(_.count(_.markdown.contains("/support/consent?g=")))
      } yield assertTrue(stale == Status.Ok, current == Status.Ok, prompts == 1)
    },
    test("the supersede LOG stays a race signal: an ANSWERED turn's follow-up is not one") {
      // The tracker keeps a closed entry so `turnOwner` can still place an answered turn's
      // session. That must not turn every ordinary follow-up into "the earlier session no longer
      // owes a reply" — it already replied, and that line is the one an operator greps to find
      // this race.
      {
        for {
          _      <- cleanDb
          hhRepo <- ZIO.service[HouseholdRepo]
          h      <- makeHarness
          hh     <- hhRepo.create("Family G", "family-g")
          // Turn one, answered in full BEFORE the next message arrives.
          first  <- inbound(h, hh, "th_g", "my iPad is blocked")
          _      <- reply(h, first, "here is why")
          _      <- inbound(h, hh, "th_g", "thanks, and what about the Switch?")
          // Turn two on ANOTHER thread, genuinely raced: nothing answered in between.
          raced  <- inbound(h, hh, "th_g2", "why is my iPad blocked?")
          _      <- inbound(h, hh, "th_g2", "hello?")
          _      <- reply(h, raced, "the superseded answer")
          logs   <- ZTestLogger.logOutput
        } yield {
          val superseded = logs.map(_.message()).filter(_.contains("support dispatch superseded"))
          assertTrue(
            superseded.exists(_.contains("thread=th_g2")),
            !superseded.exists(_.contains("thread=th_g ")),
          )
        }
      }.provideSomeLayer[
        TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task],
      ](ZTestLogger.default)
    },
  ) @@ TestAspect.sequential
}
