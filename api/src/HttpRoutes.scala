package wifihaven.api

import doobie.*
import doobie.implicits.*
import wifihaven.api.auth.*
import wifihaven.api.beta.BetaService
import wifihaven.api.cache.TimeStatusCache
import wifihaven.api.db.*
import wifihaven.api.metrics.{HttpMetrics, RouterMetricsService}
import wifihaven.api.notify.Notifier
import wifihaven.api.observability.LoggingMiddleware
import wifihaven.api.policy.*
import wifihaven.api.routes.*
import wifihaven.shared.Clock
import zio.*
import zio.http.*
import zio.interop.catz.*
import zio.metrics.connectors.prometheus.PrometheusPublisher

// #2392: `allRoutes` and its three `buildXRoutes` helpers were relocated OUT of
// `object Main` into this separate source file. The Scala 3 TreePickler crashes
// per-source-file ("unresolved symbols: method allRoutes ... when pickling
// Main.scala") on the amd64 clean full-compile once `object Main`'s tree grows past a
// threshold; the in-object #2380 split bought too little headroom and the #2386 seed
// backfill tipped it back over. Pickling is per-compilation-unit, so moving these ~640
// lines to their own file removes that tree from Main.scala's pickle entirely. Behavior,
// route ordering, and `++` composition are identical to the in-object form; every
// stateful binding is still threaded as an explicit param (never re-resolved).
object HttpRoutes {
  def allRoutes(
      templates: Map[wifihaven.shared.types.AppTemplateId, AppTemplate],
      bundledBlocklists: Map[wifihaven.shared.types.BlocklistId, BundledBlocklist],
      ready: UIO[Boolean],
  ) =
    for {
      auth           <- ZIO.service[AuthService]
      userRepo       <- ZIO.service[UserRepo]
      entitlements   <- ZIO.service[EntitlementsRepo]
      upRepo         <- ZIO.service[UserProfileRepo]
      profileRepo    <- ZIO.service[ProfileRepo]
      namedSchedRepo <- ZIO.service[NamedScheduleRepo]
      hsRepo         <- ZIO.service[HouseholdSettingsRepo]
      tlRepo         <- ZIO.service[TimeLimitRepo]
      atlRepo        <- ZIO.service[AppTimeLimitRepo]
      deviceRepo     <- ZIO.service[DeviceRepo]
      blRepo         <- ZIO.service[BlocklistRepo]
      blCache        <- ZIO.service[BlocklistCache]
      blFetcher2     <- ZIO.service[BlocklistFetcher]
      usageRepo      <- ZIO.service[TimeUsageRepo]
      extRepo        <- ZIO.service[TimeExtensionRepo]
      routerRepo     <- ZIO.service[RouterRepo]
      trafficRepo    <- ZIO.service[TrafficReportRepo]
      rollupRepo2    <- ZIO.service[RollupRepo]
      connRepo       <- ZIO.service[ConnectionEventRepo]
      blockEvRepo    <- ZIO.service[BlockEventRepo]
      alertRepo      <- ZIO.service[AlertRepo]
      appRepo        <- ZIO.service[AppRepo]
      appRollupRepo  <- ZIO.service[wifihaven.api.db.AppUsedRollupRepo]
      // #2132 (multi-tenant P5-2): the beta request → provisioning → invite-accept pipeline repos.
      householdRepo  <- ZIO.service[HouseholdRepo]
      betaRepo       <- ZIO.service[BetaRequestRepo]
      // #2308: single-use, short-TTL password-reset tokens (V77) for the forgot-password flow.
      resetTokenRepo <- ZIO.service[PasswordResetTokenRepo]
      ambientRepoR   <- ZIO.service[wifihaven.api.db.AmbientHostsRepo]
      notifier       <- ZIO.service[Notifier]
      policy         <- ZIO.service[PolicyService]
      timeStatus     <- ZIO.service[wifihaven.api.policy.TimeStatusService]
      cfg            <- ZIO.service[AppConfig]
      clock          <- ZIO.service[Clock]
      timeCache      <- ZIO.service[TimeStatusCache]
      xa             <- ZIO.service[Transactor[Task]]
      promPublisher  <- ZIO.service[PrometheusPublisher]
      routerAuth         = new RouterAuthLive(routerRepo)
      // #2566/#2569/#2322: the block page's household derivation, shared by GET /api/blocked and
      // POST /api/access-requests (they are two halves of one page and must agree). Signed with the
      // API's existing JWT secret, domain-separated by BlockPageToken — no new secret to provision.
      blockPageHousehold = new BlockPageHouseholdLive(cfg.jwt.secret, routerRepo)
      routerMetrics           <- RouterMetricsService.make
      // #2079: per-source-IP rate limit on the unauthenticated login route — 10
      // attempts / 15 min. #2081: per-source-IP rate limit on the unauthenticated
      // access-requests route (block-page kid request) — 20 / 5 min, on top of the
      // existing per-(mac,host) debounce (which a varying host bypasses).
      loginRateLimiter        <- RateLimiterLive.make(maxAttempts = 10, windowSeconds = 15 * 60)
      accessReqRateLimiter    <- RateLimiterLive.make(maxAttempts = 20, windowSeconds = 5 * 60)
      // #2569: the same treatment for GET /api/blocked, which was unauthenticated AND unlimited —
      // a per-MAC oracle for the resolving household's profile name and screen-time.
      //
      // TWO buckets, because one key cannot do both jobs:
      //   - perDevice, keyed (source IP, MAC), bounds ONE device's share. A household NATs behind
      //     one address in the cloud deploy, so a per-IP-only budget lets one device hammering a
      //     blocked endpoint 429 every other device in the house off its own block page.
      //   - perSource, keyed source IP alone, is the MAC-ENUMERATION bound — and it is the one
      //     that actually has to exist. A sweep varies the MAC by definition, so a per-(ip, mac)
      //     bucket hands it a fresh budget for every MAC it tries and bounds nothing. This route's
      //     `Unauthenticated` census verdict rests on the residual household-1 disclosure being
      //     rate-limited, so dropping this bucket would invalidate that argument, not just widen a
      //     limit.
      // Budgets derive from the intake's 20 / 5 min (#2081): a block page is a READ a single
      // device legitimately repeats (every blocked request DNATs to a fresh page load, one API
      // call each), so perDevice is 3x that, and perSource is 5x perDevice — a household of five
      // simultaneously-blocked devices at full per-device rate, which also caps a sweep at 300
      // distinct MACs per window instead of unbounded.
      blockedPerDeviceLimiter <- RateLimiterLive.make(maxAttempts = 60, windowSeconds = 5 * 60)
      blockedPerSourceLimiter <- RateLimiterLive.make(maxAttempts = 300, windowSeconds = 5 * 60)
      // #2132: per-source-IP rate limit on the unauthenticated beta-intake route — 5 / hour, a
      // slow cadence (a genuine prospect requests once), on top of the idempotent-email dedup.
      betaReqRateLimiter      <- RateLimiterLive.make(maxAttempts = 5, windowSeconds = 60 * 60)
      // #2308: per-source-IP rate limits on the two unauthenticated password-reset routes. Requests
      // are tight (5 / hour) to blunt email-bombing a known address; the reset consume is a touch
      // looser (10 / 15 min) so a legitimate user can retry a weak password without lockout, while
      // still bounding token brute-force.
      forgotPwRateLimiter     <- RateLimiterLive.make(maxAttempts = 5, windowSeconds = 60 * 60)
      resetPwRateLimiter      <- RateLimiterLive.make(maxAttempts = 10, windowSeconds = 15 * 60)
      // #2132: the beta pipeline service (slug derivation, invite token mint + TTL, provisioning,
      // accept). Clock-injected so the invite TTL is TestClock-driven in specs.
      // #2135: the billing state machine (Checkout/Portal/webhook + the provisioning Customer seam).
      billing                 <- ZIO.service[wifihaven.api.billing.BillingService]
      // #2137: the cohort flip lifecycle service (shared with the BetaFlipJob forked in the run
      // scope). Surfaces the flip-window state to the SPA billing page.
      flipService             <- ZIO.service[wifihaven.api.billing.FlipService]
      // #2199: the support integration service (server-signed widget identity + household→Plain
      // customer mapping). Dark unless the Plain keys are set.
      support                 <- ZIO.service[wifihaven.api.support.SupportService]
      // #2200: the Claude responder — the webhook→gate→dispatch pipeline plus the cloud agent's
      // token-authenticated callback endpoints. Runs iff support.responderEnabled (#2265). Issue
      // filing is rate-limited per-thread (3/h) and globally (10/h) — the #2241 volume control the
      // support_agent_action_total{op="issue"} alert watches.
      billingRepo             <- ZIO.service[wifihaven.api.db.HouseholdBillingRepo]
      plainClient             <- ZIO.service[wifihaven.api.support.PlainClient]
      agentDispatcher         <- ZIO.service[wifihaven.api.support.CloudAgentDispatcher]
      githubIssues            <- ZIO.service[wifihaven.api.support.GithubIssueClient]
      issueThreadLimiter      <- RateLimiterLive.make(maxAttempts = 3, windowSeconds = 60 * 60)
      issueGlobalLimiter      <- RateLimiterLive.make(maxAttempts = 10, windowSeconds = 60 * 60)
      // Cost guardrails (2026-07-16): each dispatched agent session bills real tokens, so dispatch
      // is hard-capped — 4/hour per thread and 50/day globally. With a worst-case ~$0.50/draft the
      // global cap bounds even sustained abuse at pocket change; normal beta volume never hits it.
      dispatchThreadLimiter   <- RateLimiterLive.make(maxAttempts = 4, windowSeconds = 60 * 60)
      dispatchGlobalLimiter <- RateLimiterLive.make(maxAttempts = 50, windowSeconds = 24 * 60 * 60)
      // #2307: the static-reject cap for unregistered cold email. Cheap (a fixed string, no AI), so
      // the ceiling is generous, but bounded globally so a spammer forging many cold-email threads
      // can't turn us into a reply-amplification (backscatter) source.
      rejectLimiter         <- RateLimiterLive.make(maxAttempts = 100, windowSeconds = 60 * 60)
      // #2419: the per-(household, thread) data-access consent record (V84) + the cap on how often
      // the agent can make us post a consent prompt into ONE thread (3/hour — an assistant that
      // keeps re-asking, or a prompt-injected one, must not be able to spam the customer).
      supportConsentRepo    <- ZIO.service[wifihaven.api.db.SupportConsentRepo]
      consentThreadLimiter  <- RateLimiterLive.make(maxAttempts = 3, windowSeconds = 60 * 60)
      // #2437: the cap on how often ONE thread can page the operator (3/hour). An escalation is a
      // human interrupt, so a looping or prompt-injected agent must not be able to turn our alert
      // mailbox into a firehose; 3/hour still lets a genuine back-and-forth re-escalate.
      escalateThreadLimiter <- RateLimiterLive.make(maxAttempts = 3, windowSeconds = 60 * 60)
      // #2472: the dispatch→completion tracker. Taken from the environment (not built here) because
      // Main forks its sweep fiber against the SAME instance the responder records into — a second
      // instance would sweep an empty map and report nothing, which is exactly the silence this
      // change closes.
      dispatchTracker       <- ZIO.service[wifihaven.api.support.DispatchTracker]
      supportResponder = wifihaven.api.support.SupportResponder(
        cfg.support,
        householdRepo,
        userRepo,
        billingRepo,
        deviceRepo,
        profileRepo,
        supportConsentRepo,
        plainClient,
        githubIssues,
        agentDispatcher,
        clock,
        issueThreadLimiter,
        issueGlobalLimiter,
        dispatchThreadLimiter,
        dispatchGlobalLimiter,
        rejectLimiter,
        consentThreadLimiter,
        // The consent link's origin: the ONE per-env SPA origin the API already carries (#2250),
        // shared with the password-reset / dashboard links rather than a new support.* key.
        cfg.email.appBaseUrl,
        // #2437: the operator-notification seam — the same #578 Notifier the alert/beta/reset paths
        // use, so an escalation reaches a human without inventing a transport.
        notifier,
        escalateThreadLimiter,
        dispatchTracker,
      )
      // #2203: the PRESS responder — the public inbound webhook (from the Cloudflare Email Worker)
      // → rate-cap → dispatch pipeline, plus the press agent's reply-target-bound EMAIL callback.
      // Runs iff press.responderEnabled (#2265). NO household gate and NO data token (public
      // audience); the reply is emailed to the sender via the shared #578 EmailSender (destination
      // locked into the session token, so a hijacked agent cannot redirect it).
      pressDispatcher            <- ZIO.service[wifihaven.api.press.PressAgentDispatcher]
      pressEmailSender           <- ZIO.service[wifihaven.api.notify.EmailSender]
      // #2296: the press correspondence log (V71). Recording is fail-open inside the responder; the
      // household-1-only read route (PressRoutes) reads it back.
      pressLog                   <- ZIO.service[wifihaven.api.db.PressMessageRepo]
      // Same dispatch cost caps as the #2200 support responder (4/sender/hour, 50/day global) — each
      // dispatched session bills tokens, and the global cap is the true ceiling for this public
      // endpoint (the per-sender key is best-effort — an anonymous From is trivially rotated).
      pressDispatchSenderLimiter <- RateLimiterLive.make(maxAttempts = 4, windowSeconds = 60 * 60)
      pressDispatchGlobalLimiter <-
        RateLimiterLive.make(maxAttempts = 50, windowSeconds = 24 * 60 * 60)
      // #2437: the per-sender cap on operator pages from the press agent (3/hour), matching support's.
      pressEscalateLimiter       <- RateLimiterLive.make(maxAttempts = 3, windowSeconds = 60 * 60)
      pressResponder = wifihaven.api.press.PressResponder(
        cfg.press,
        pressEmailSender,
        pressDispatcher,
        pressLog,
        clock,
        pressDispatchSenderLimiter,
        pressDispatchGlobalLimiter,
        // #2437: press has NO inbox — this Notifier is the ONLY way a press inquiry (or an escalation
        // of one) reaches a human at all.
        notifier,
        pressEscalateLimiter,
      )
      // #2233: the operator-run press-OUTREACH send capability. The media-contacts manifest + the
      // sendable release template load from bundled resources at boot (fail-fast if malformed), like
      // AppTemplates / BundledBlocklists. The send endpoints stay dark until press.outreach.enabled.
      pressContacts <- wifihaven.api.press.PressOutreach.loadContacts()
      pressRelease  <- wifihaven.api.press.PressOutreach.loadReleaseTemplate()
      betaService   = BetaService(
        betaRepo,
        householdRepo,
        userRepo,
        auth,
        notifier,
        clock,
        cfg.beta,
        billing,
      )
      // #2308: the forgot/reset-password service — mints single-use short-TTL tokens, emails the
      // reset link via the #578 Notifier, and on reset bumps token_version to invalidate prior JWTs.
      // Clock-injected so the token TTL is TestClock-driven in specs.
      passwordReset = PasswordResetServiceLive(
        userRepo,
        resetTokenRepo,
        auth,
        notifier,
        cfg.email,
        clock,
      )
      // #1970 (S3): the SPA-websocket change-source bus (design §5.2.2). The write sites publish
      // change events here; the SpaPush consumer (forked in the run scope) drains it and fans out
      // role-filtered, subscription-gated `now`/`connectionEvents`/`stale` frames.
      spaEventBus <- SpaEventBus.make
      // #1846: the transport-agnostic ingest service shared by the REST ingest routes and the new
      // websocket transport, plus the per-router ws connection registry. #1970: it also publishes
      // SPA change events (new connection-events head + `now` trigger + `stale{alerts}`) to the bus.
      routerIngest = new RouterIngestService(
        routerRepo,
        trafficRepo,
        usageRepo,
        deviceRepo,
        connRepo,
        alertRepo,
        hsRepo,
        spaEventBus,
      )
      // #2619: the registry's delivery sink stamps `routers.last_etag` with the etag a `policy`
      // frame actually delivered — the same column the REST poll writes at serve time. ws is the
      // shipped router default since #2608 and the poll goes dormant on a healthy link (#2037), so
      // for most of the fleet this is the only writer.
      wsRegistry    <- RouterWsRegistry.make((id, etag) => routerRepo.touch(id, Some(etag), None))
      // #1968: the browser-facing SPA websocket registry (S1). A FORK of the router registry pattern
      // (design §5.1), not a generalization — keyed by per-connection id + role with a subscription
      // set. Additive: REST stays the fallback throughout the rollout (no flag day).
      spaWsRegistry <- SpaWsRoutes.registry
      dbHealthCheck = sql"SELECT 1".query[Int].unique.transact(xa).unit
    } yield {
      // #1177: split the route composition into typed chunks. A flat `++` chain across
      // 18+ Routes was hitting Scala 3's type-inference recursion limit on CI (Linux
      // JDK 21 default stack), failing with "Recursion limit exceeded" at the first
      // `++` even though local macOS builds happened to fit. Explicit `Routes[Any, Response]`
      // ascriptions on the chunks ground the inference per chunk so the final fold is
      // a short, well-typed concatenation.
      // #1248: the observability endpoints are NOT readiness-gated. /api/health
      // carries its own not-ready logic (503 status=starting until migrations +
      // seeds finish) so Render's health check can observe startup progress
      // while the port is already bound; /metrics reads only the in-memory
      // Prometheus registry (no DB) and must stay scrapeable during a slow
      // startup — exactly the scenario this gate exists for. Everything else is
      // gated below.
      //
      // #1204: /metrics is deliberately left OUT of HttpMetrics.instrument below —
      // the scrape endpoint should not count itself, and counting it would just
      // add a self-referential series that climbs with Prometheus' poll rate.
      val healthRoutes: Routes[Any, Response] =
        HealthRoutes.routes(ready, dbHealthCheck)

      val metricsRoutes: Routes[Any, Response] =
        MetricsRoutes.routes(cfg.metrics, promPublisher)

      // #2379: these three chunks are built by top-level `private def`s (see below) so their
      // large subtrees leave the `allRoutes` method tree and don't crash the amd64 pickler. The
      // stateful bindings resolved above are threaded in as explicit params (NOT re-resolved), so
      // rate-limiter / ws-registry / event-bus state is shared, not forked.
      val systemRoutes: Routes[Any, Response] =
        buildSystemRoutes(
          auth,
          userRepo,
          upRepo,
          loginRateLimiter,
          passwordReset,
          forgotPwRateLimiter,
          resetPwRateLimiter,
          betaService,
          betaRepo,
          betaReqRateLimiter,
          billing,
          flipService,
          support,
          supportResponder,
          pressResponder,
          pressLog,
          cfg,
          pressEmailSender,
          pressContacts,
          pressRelease,
          profileRepo,
          tlRepo,
          namedSchedRepo,
          timeCache,
          policy,
          spaEventBus,
          hsRepo,
          deviceRepo,
        )

      val statsRoutes: Routes[Any, Response] =
        buildStatsRoutes(
          auth,
          deviceRepo,
          tlRepo,
          atlRepo,
          trafficRepo,
          extRepo,
          profileRepo,
          upRepo,
          hsRepo,
          timeStatus,
          clock,
          timeCache,
          policy,
          spaEventBus,
          ambientRepoR,
          connRepo,
          appRepo,
          rollupRepo2,
          appRollupRepo,
          blRepo,
          blCache,
          blFetcher2,
          bundledBlocklists,
          blockPageHousehold,
          // Named: two same-typed RateLimiters with very different budgets (60 vs 300), so a
          // positional transposition would compile and silently swap the fairness bound with the
          // enumeration bound.
          blockedPerDeviceLimiter = blockedPerDeviceLimiter,
          blockedPerSourceLimiter = blockedPerSourceLimiter,
        )

      val routerAndAdminRoutes: Routes[Any, Response] =
        buildRouterAndAdminRoutes(
          auth,
          routerRepo,
          routerAuth,
          policy,
          blockEvRepo,
          userRepo,
          entitlements,
          rollupRepo2,
          routerIngest,
          wsRegistry,
          routerMetrics,
          spaWsRegistry,
          clock,
          cfg,
          alertRepo,
          deviceRepo,
          profileRepo,
          extRepo,
          appRepo,
          hsRepo,
          notifier,
          accessReqRateLimiter,
          upRepo,
          blRepo,
          templates,
          connRepo,
          usageRepo,
          trafficRepo,
          timeCache,
          blockPageHousehold,
        )

      val spaRoutes: Routes[Any, Response] =
        if (cfg.http.serveSpa) StaticRoutes.routes(cfg.http.staticDir) else Routes.empty

      // #638: OpenAPI spec auto-generated from the live route table + Swagger UI.
      // Unauthenticated by design (discovery surface); the spec itself advertises
      // which routes require bearer auth. Constructed AFTER the real route chunks
      // so the spec enumerates them all; mounted alongside the SPA fallback so
      // /api/openapi.json + /api/docs resolve before the SPA catch-all.
      val openApiRoutes: Routes[Any, Response] =
        OpenApiRoutes.routes(
          wifihaven.api.BuildInfo.fromEnv.sha,
          systemRoutes,
          statsRoutes,
          routerAndAdminRoutes,
          healthRoutes,
        )

      // #1248: gate all real routes behind readiness (503 until migrations +
      // seeds complete). #1204: wrap health + the gated real routes in the HTTP
      // metrics middleware (templated route labels), then mount the uninstrumented
      // /metrics scrape endpoint alongside.
      // #1570: ErrorBoundary.observe wraps every served route family so any error response (4xx
      // WARN / 5xx ERROR) is logged + metered (api_errors_total{route,status}) in one place —
      // matching the coverage of HttpMetrics.instrument (which already counts all these in
      // http_requests_total). This includes the SPA catch-all (StaticRoutes): an unmatched
      // `/api/*` path 404s there ("no such API route") and a malformed URI 400s — both are real
      // API errors worth surfacing, and the SPA fallback serves index.html (200) for client routes
      // so there is no asset-404 flood. The `route` label is the route's bounded template (the
      // trailing catch-all collapses every path to one series), so no per-path leak.
      //
      // It sits *inside* the readiness gate so the gate's startup 503s don't spam the log. Only
      // /api/health (its own 503-while-starting semantics, polled constantly) and /metrics (the
      // scrape endpoint) are left out — mirroring HttpMetrics' own exclusion of /metrics.
      // #602: LoggingMiddleware.annotate wraps every served route family so every
      // ZIO.log* emitted inside a handler (or anything it calls) inherits the
      // templated `route` and `method` as MDC keys via FiberRef — handlers no
      // longer wrap individual log lines for that context. ErrorBoundary still
      // adds `status` because it sees the response. The middleware sits *inside*
      // HttpMetrics (so the same routes it counts also get logged) and *outside*
      // the readiness gate so even a gated 503 carries its route/method context.
      val assembled = HttpMetrics.instrument(
        healthRoutes ++
          LoggingMiddleware.annotate(
            Readiness.gate(
              ErrorBoundary.observe(
                openApiRoutes ++ systemRoutes ++ statsRoutes ++ routerAndAdminRoutes ++ spaRoutes,
              ),
              ready,
            ),
          ),
      ) ++ metricsRoutes
      // #1849: hand the ws registry back so the run scope can wire it as PolicyService's push sink
      // and fork the reconcile ticker (both need the run scope, which `allRoutes` is not).
      // #1970 (S3): also hand back the SPA registry + change-source bus so the run scope can fork the
      // SpaPush consumer and add the SPA `now`-trigger sink to the (now multi-subscriber) publisher.
      (assembled, wsRegistry, spaWsRegistry, spaEventBus)
    }

  // #2379: `allRoutes`'s `yield` used to build every route chunk inline, making it a
  // ~440-line method whose tree crashed the Scala 3.3.3 pickler on the amd64 clean
  // full-compile (`AssertionError: unresolved symbols: method allRoutes ... when
  // pickling`, TreePickler.scala:829) while compiling fine on macOS/arm64 and in the
  // warm/incremental CI test job. This is the same "compiles locally, fails on Linux
  // CI" Scala limit #1177 dodged one level down (splitting the flat `++` chain into
  // typed chunks); here we push the three big chunks OUT of the method tree into
  // top-level `private def`s so those subtrees leave `allRoutes`. Every stateful
  // binding is threaded as an EXPLICIT parameter — never re-resolved via
  // `ZIO.service`/`.make` inside these helpers — so rate-limiter / ws-registry /
  // event-bus state is not forked. Behavior, route ordering, and `++` composition are
  // identical to the inline form; only the compiled tree shape changes.
  private def buildSystemRoutes(
      auth: AuthService,
      userRepo: UserRepo,
      upRepo: UserProfileRepo,
      loginRateLimiter: RateLimiter,
      passwordReset: PasswordResetService,
      forgotPwRateLimiter: RateLimiter,
      resetPwRateLimiter: RateLimiter,
      betaService: BetaService,
      betaRepo: BetaRequestRepo,
      betaReqRateLimiter: RateLimiter,
      billing: wifihaven.api.billing.BillingService,
      flipService: wifihaven.api.billing.FlipService,
      support: wifihaven.api.support.SupportService,
      supportResponder: wifihaven.api.support.SupportResponder,
      pressResponder: wifihaven.api.press.PressResponder,
      pressLog: wifihaven.api.db.PressMessageRepo,
      cfg: AppConfig,
      pressEmailSender: wifihaven.api.notify.EmailSender,
      pressContacts: List[wifihaven.api.press.PressOutreach.Contact],
      pressRelease: String,
      profileRepo: ProfileRepo,
      tlRepo: TimeLimitRepo,
      namedSchedRepo: NamedScheduleRepo,
      timeCache: TimeStatusCache,
      policy: PolicyService,
      spaEventBus: SpaEventBus,
      hsRepo: HouseholdSettingsRepo,
      deviceRepo: DeviceRepo,
  ): Routes[Any, Response] =
    VersionRoutes.routes(wifihaven.api.BuildInfo.fromEnv) ++
      AuthRoutes.routes(auth, userRepo, upRepo, loginRateLimiter) ++
      // #2308: forgot-password request (public) + token consume / reset (public). Both rate-
      // limited + enumeration-safe.
      PasswordResetRoutes.routes(passwordReset, forgotPwRateLimiter, resetPwRateLimiter) ++
      // #2132: beta request intake (public) + operator approval/reject + invite accept (public).
      BetaRoutes.routes(auth, betaService, betaRepo, userRepo, betaReqRateLimiter) ++
      // #2135: billing status (admin) + Checkout/Portal starts (admin) + signature-verified
      // Stripe webhook (public). Disabled installs 404 the admin surfaces and no-op the webhook.
      BillingRoutes.routes(
        auth,
        billing,
        // #2137: a read failure degrades to "no window" rather than failing the billing page.
        flipService.currentWindow.orElseSucceed(
          wifihaven.api.billing.FlipService.FlipWindow(open = false, flipDate = None),
        ),
      ) ++
      // #2199: admin-only server-signed Plain widget identity. Dark (returns {configured:false})
      // until the operator sets the Plain widget app id + identity secret.
      SupportRoutes.routes(auth, support) ++
      // #2419: the customer's JWT-authenticated consent action — the ONE writer of a data-access
      // consent record (the agent can only ASK, via its thread-bound token).
      SupportConsentRoutes.routes(auth, supportResponder) ++
      // #2200: the Plain new-message webhook (signature-verified, authenticated-origin-gated
      // cloud-agent dispatch — UI-originated or a #2307 registered-admin email) + the agent's
      // token-authenticated callback endpoints. Off unless
      // support.responderEnabled is set explicitly (#2265): webhook no-ops, agent endpoints 404.
      SupportAgentRoutes.routes(supportResponder) ++
      // #2203: the PRESS/PR inbox — a public, unauthenticated inbound webhook from the Cloudflare
      // Email Worker (signature-verified, NO household gate) + the press agent's
      // token-authenticated EMAIL-reply callback (destination locked into the token). Off unless
      // press.responderEnabled is set explicitly (#2265): webhook no-ops, agent endpoint 404.
      // The press agent holds no household data token (public audience).
      PressAgentRoutes.routes(pressResponder) ++
      // #2296: the household-1-only admin read of the recorded press correspondence log. Press is
      // a company-global channel (no household_id); a non-default household gets 404 (not 403),
      // so the log's existence is not disclosed across the tenant boundary.
      PressRoutes.routes(auth, pressLog) ++
      // #2233: the operator-only press-OUTREACH send surface (preview = dry-run default; send
      // requires confirm:true + resolved fill tokens + configured email). Both endpoints 404
      // unless press.outreach.enabled (dark-by-default #2265) and for any non-operator household.
      PressOutreachRoutes.routes(
        auth,
        cfg.pressOutreach,
        cfg.email.enabled,
        pressEmailSender,
        pressLog,
        pressContacts,
        pressRelease,
      ) ++
      ProfileRoutes.routes(
        auth,
        profileRepo,
        tlRepo,
        upRepo,
        userRepo,
        namedSchedRepo,
        // #1538: same cache instance TimeRoutes uses, so a schedule detach busts the
        // per-profile time-status entry instead of leaving a stale "paused for schedule".
        timeCache,
        // #1849: bust the computed-snapshot cache on a profile/schedule mutation.
        // #1970 (S3): also nudge `stale{profiles}` so an open SPA re-fetches the class-(3)
        // profiles resource (design §3.2) — composed onto the existing invalidate callback, so
        // every profile mutation publishes without touching the route's signature.
        policy.invalidate <* spaEventBus.publish(SpaEvent.Stale(StaleTopic.Profiles)),
      ) ++
      ScheduleRoutes.routes(
        auth,
        namedSchedRepo,
        policy.invalidate <* spaEventBus.publish(SpaEvent.Stale(StaleTopic.Schedules)),
      ) ++
      HouseholdSettingsRoutes.routes(auth, hsRepo, policy.invalidate) ++
      // #2382: the server-level per-household "disable enforcement" escape hatch (admin-write). The
      // flag is a behavioral setting on household_settings; a toggle busts the computed-snapshot
      // cache so the permissive/enforcing flip reaches connected ws routers and the next REST poll.
      HouseholdEnforcementRoutes.routes(auth, hsRepo, policy.invalidate) ++
      DeviceRoutes.routes(
        auth,
        deviceRepo,
        upRepo,
        profileRepo,
        policy.invalidate <* spaEventBus.publish(SpaEvent.Stale(StaleTopic.Devices)),
      )

  private def buildStatsRoutes(
      auth: AuthService,
      deviceRepo: DeviceRepo,
      tlRepo: TimeLimitRepo,
      atlRepo: AppTimeLimitRepo,
      trafficRepo: TrafficReportRepo,
      extRepo: TimeExtensionRepo,
      profileRepo: ProfileRepo,
      upRepo: UserProfileRepo,
      hsRepo: HouseholdSettingsRepo,
      timeStatus: wifihaven.api.policy.TimeStatusService,
      clock: Clock,
      timeCache: TimeStatusCache,
      policy: PolicyService,
      spaEventBus: SpaEventBus,
      ambientRepoR: wifihaven.api.db.AmbientHostsRepo,
      connRepo: ConnectionEventRepo,
      appRepo: AppRepo,
      rollupRepo2: RollupRepo,
      appRollupRepo: wifihaven.api.db.AppUsedRollupRepo,
      blRepo: BlocklistRepo,
      blCache: BlocklistCache,
      blFetcher2: BlocklistFetcher,
      bundledBlocklists: Map[wifihaven.shared.types.BlocklistId, BundledBlocklist],
      // #2569: shared block-page household derivation + the two limiters on GET /api/blocked.
      // Threaded in (not re-resolved) so the limiter state is shared across the route table.
      blockPageHousehold: BlockPageHousehold,
      blockedPerDeviceLimiter: RateLimiter,
      blockedPerSourceLimiter: RateLimiter,
  ): Routes[Any, Response] =
    TimeRoutes.routes(
      auth,
      deviceRepo,
      tlRepo,
      atlRepo,
      trafficRepo,
      extRepo,
      profileRepo,
      upRepo,
      hsRepo,
      timeStatus,
      clock,
      timeCache,
      // #1849: a +Time grant can lift a TimeLimit block, so bust the computed-snapshot cache.
      policy.invalidate,
      // #1974 (S6a): the grant changes remaining-minutes — push fresh timeStatus/appUsage live.
      spaEventBus,
      ambientRepoR,
    ) ++
      LogRoutes.routes(auth, connRepo, upRepo) ++
      UsageRoutes.routes(
        auth,
        deviceRepo,
        trafficRepo,
        upRepo,
        profileRepo,
        appRepo,
        rollupRepo2,
        hsRepo,
        atlRepo,
        appRollupRepo,
        clock,
        ambientRepoR,
      ) ++
      DashboardNowRoutes.routes(
        auth,
        trafficRepo,
        connRepo,
        deviceRepo,
        profileRepo,
        upRepo,
        atlRepo,
        clock,
      ) ++
      BlocklistRoutes.routes(auth, blRepo, blCache, blFetcher2, bundledBlocklists) ++
      // #335: kid-side block page support. Unauthenticated — the router DNATs blocked
      // traffic to the SPA's /blocked, which calls GET /api/blocked?mac=&host= to render
      // the reason + today's usage. Accidentally removed from registration in #1060;
      // re-wired here so the kid-friendly path stops falling back to the legacy
      // router-supplied `reason` query string.
      BlockedRoutes.routes(
        policy,
        deviceRepo,
        profileRepo,
        blRepo,
        timeStatus,
        hsRepo,
        clock,
        blockPageHousehold,
        perDeviceLimiter = blockedPerDeviceLimiter,
        perSourceLimiter = blockedPerSourceLimiter,
      )

  private def buildRouterAndAdminRoutes(
      auth: AuthService,
      routerRepo: RouterRepo,
      routerAuth: RouterAuth,
      policy: PolicyService,
      blockEvRepo: BlockEventRepo,
      userRepo: UserRepo,
      entitlements: EntitlementsRepo,
      rollupRepo2: RollupRepo,
      routerIngest: RouterIngestService,
      wsRegistry: RouterWsRegistry,
      routerMetrics: RouterMetricsService,
      spaWsRegistry: SpaWsRegistry,
      clock: Clock,
      cfg: AppConfig,
      alertRepo: AlertRepo,
      deviceRepo: DeviceRepo,
      profileRepo: ProfileRepo,
      extRepo: TimeExtensionRepo,
      appRepo: AppRepo,
      hsRepo: HouseholdSettingsRepo,
      notifier: Notifier,
      accessReqRateLimiter: RateLimiter,
      upRepo: UserProfileRepo,
      blRepo: BlocklistRepo,
      templates: Map[wifihaven.shared.types.AppTemplateId, AppTemplate],
      connRepo: ConnectionEventRepo,
      usageRepo: TimeUsageRepo,
      trafficRepo: TrafficReportRepo,
      timeCache: TimeStatusCache,
      // #2566/#2322: shared block-page household derivation for POST /api/access-requests.
      blockPageHousehold: BlockPageHousehold,
  ): Routes[Any, Response] =
    RouterRoutes.routes(routerRepo, policy, routerAuth, blockEvRepo, cfg.jwt.secret) ++
      AdminRouterRoutes.routes(auth, routerRepo, userRepo, entitlements) ++
      RollupAdminRoutes.routes(auth, rollupRepo2) ++
      RouterIngestRoutes.routes(routerAuth, routerIngest) ++
      // #1846: additive websocket transport. REST ingest/poll/metrics above stay fully live.
      RouterWsRoutes.routes(
        routerAuth,
        wsRegistry,
        routerIngest,
        routerMetrics,
        routerRepo,
        policy,
      ) ++
      RouterMetricsRoutes.routes(routerAuth, routerMetrics) ++
      // #1968/#1969: additive browser-facing websocket endpoint (`GET /api/ws`). S2 (#1969)
      // authorizes the upgrade via the `wh_ws` cookie (existing AuthService.verify) + the §8
      // Origin allowlist (cfg.ws); push sources are S3/S4. The SPA has no ws client yet (S5), so
      // this reads idle in prod until then.
      SpaWsRoutes.routes(auth, spaWsRegistry, clock, cfg.ws) ++
      AlertRoutes.routes(
        auth,
        alertRepo,
        deviceRepo,
        profileRepo,
        extRepo,
        appRepo,
        hsRepo,
        upRepo,
        notifier,
        clock,
        accessReqRateLimiter,
        blockPageHousehold,
      ) ++
      AppRoutes.routes(auth, appRepo, profileRepo, upRepo, blRepo, templates) ++
      AdminDebugRoutes.routes(auth, policy) ++
      DebugRoutes.routes(
        cfg.debugEnabled,
        cfg,
        deviceRepo,
        profileRepo,
        connRepo,
        usageRepo,
        trafficRepo,
        clock,
        timeCache,
      )
}
