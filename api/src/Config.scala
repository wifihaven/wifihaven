package wifihaven.api

import wifihaven.shared.types.Hostname
import zio.*
import zio.config.*
import zio.config.magnolia.*
import zio.config.typesafe.*

case class AppConfig(
    db: DbConfig,
    http: HttpConfig,
    jwt: JwtConfig,
    cors: CorsConfig,
    policy: PolicyConfig = PolicyConfig(),
    metrics: MetricsConfig = MetricsConfig(),
    ws: WsConfig = WsConfig(),
    partition: PartitionConfig = PartitionConfig(),
    beta: BetaConfig = BetaConfig(),
    stripe: StripeConfig = StripeConfig(),
    email: EmailConfig = EmailConfig(),
    flip: FlipConfig = FlipConfig(),
    support: SupportConfig = SupportConfig(),
) {
  // WIFIHAVEN_DEBUG env var: when set to a non-empty, non-"0"/"false"/"no"
  // value, mounts the read-only /api/debug/* endpoints (loopback only).
  // Read from env, not HOCON, so it stays out of application.conf — debug
  // belongs to the runtime environment, not the persistent config.
  val debugEnabled: Boolean = AppConfig.envTruthy(sys.env.get("WIFIHAVEN_DEBUG"))

  // #706 / #958: when set, the API startup seeder also inserts the dev-only
  // `test_ads` and `test_social` blocklists. Prod must leave this UNSET so a
  // fresh enrollment never carries those rows. The V32 cleanup migration
  // wipes any historical leak on first boot after upgrade.
  val seedTestBlocklists: Boolean =
    AppConfig.envTruthy(sys.env.get("WIFIHAVEN_SEED_TEST_BLOCKLISTS"))
}

case class DbConfig(
    host: String,
    port: Int,
    database: String,
    user: String,
    password: String,
    poolSize: Int,
    // #1255: tolerance for transient DB unavailability (planned resize, failover,
    // brief network hiccup). Optional — absent block uses the defaults below.
    resilience: DbResilienceConfig = DbResilienceConfig(),
)

// #1255: how the API rides out a *transient* DB outage instead of crashing
// `main`. The startup DB-heavy init (Flyway migrations, ensureDefault, seeds)
// retries with bounded exponential backoff + jitter on connection-class
// failures only; genuine errors (a bad migration, a constraint violation) still
// fail fast. The Hikari pool is configured so its creation never hard-fails on a
// DB that is briefly down at boot.
case class DbResilienceConfig(
    // base delay for the exponential backoff between startup-init retries
    startupRetryBaseMillis: Long = 1000,
    // per-attempt backoff is capped at this so a late retry doesn't sleep for ages
    startupRetryMaxBackoffSeconds: Long = 30,
    // total wall-clock budget for retrying; after this the init fails loudly
    startupRetryMaxElapsedSeconds: Long = 300,
    // Hikari initializationFailTimeout. Negative => create the pool even if the DB
    // is down at boot (connections are established lazily on first use), so pool
    // creation can never take the process down during a DB blip.
    initializationFailTimeoutMillis: Long = -1,
    // Hikari connectionTimeout — how long getConnection waits before throwing a
    // SQLTransientConnectionException (which the startup retry treats as transient).
    connectionTimeoutMillis: Long = 30000,
) {
  def startupRetryBase: zio.Duration       = zio.Duration.fromMillis(startupRetryBaseMillis)
  def startupRetryMaxBackoff: zio.Duration = zio.Duration.fromSeconds(startupRetryMaxBackoffSeconds)
  def startupRetryMaxElapsed: zio.Duration = zio.Duration.fromSeconds(startupRetryMaxElapsedSeconds)
}

case class HttpConfig(
    host: String,
    port: Int,
    staticDir: String,
    serveSpa: Boolean,
)

// #2084/#2266: the weak/placeholder-secret boot guard used to be throwing `require`s in this case
// class body. They moved to [[AppConfig.validateRequired]] (run at the boot boundary in
// `AppConfig.layer`) so ALL required-config violations are reported in one pass rather than aborting
// on the first (no-dark-by-default doc rule 4). HS256's whole security rests on secret entropy; a
// short or well-known secret is offline-brute-forceable, letting an attacker forge arbitrary admin
// JWTs. Cloud config always sets a generated 32+ char value (render.yaml `generateValue: true`);
// this guards the self-hosted path, which has no such backstop.
case class JwtConfig(
    secret: String,
    expiryHours: Int,
)

object JwtConfig {
  val MinSecretLength: Int = 32

  /** #2266: accumulate (not throw) every required-JWT-config violation. */
  private[api] def validate(cfg: JwtConfig): List[String] =
    List(
      Option.when(cfg.secret.length < MinSecretLength)(
        s"wifihaven.jwt.secret must be at least $MinSecretLength characters (got ${cfg.secret.length})",
      ),
      Option.when(cfg.secret.startsWith("change-this"))(
        "wifihaven.jwt.secret is still the shipped config/application.conf.example placeholder — generate a real secret",
      ),
    ).flatten
}

// #1242: Prometheus /metrics exposition. `enabled` mounts the GET /metrics
// route; when off, non-/metrics behaviour is unchanged and the route 404s.
// `scrapeToken`, when non-empty, is required as `Authorization: Bearer <token>`
// — the API is internet-facing on Render, so prod/staging set it. Empty (the
// self-hosted default) leaves /metrics open on the loopback-bound deployment.
//
// #2266 (no-dark-by-default): `requireToken` is the explicit, cloud-only "the
// scrape token is MANDATORY here" flag. Default `false` = the loopback-open
// self-hosted behaviour is unchanged. Set `true` (render.yaml, cloud) and an
// unset `scrapeToken` FAILS BOOT (AppConfig.validateRequired) instead of
// silently serving /metrics unauthenticated — closing the "token lost ⇒
// internet-facing /metrics falls open with no signal" gap. It is a named flag,
// not a secret-presence derivation, so "disabled" is a deliberate decision.
case class MetricsConfig(
    enabled: Boolean = true,
    scrapeToken: String = "",
    requireToken: Boolean = false,
) {
  val scrapeTokenOpt: Option[String] =
    Option(scrapeToken).map(_.trim).filter(_.nonEmpty)
}

object MetricsConfig {
  // #2266: fail loud when /metrics is mounted AND the token is declared mandatory
  // (requireToken=true) yet unset. Only meaningful while `enabled` — a 404'd
  // /metrics can't leak. Accumulated by [[AppConfig.validateRequired]].
  private[api] def validate(cfg: MetricsConfig): List[String] =
    Option
      .when(cfg.enabled && cfg.requireToken && cfg.scrapeTokenOpt.isEmpty)(
        "wifihaven.metrics.requireToken=true but wifihaven.metrics.scrapeToken is unset — " +
          "GET /metrics would be internet-facing and UNAUTHENTICATED; set the scrape token " +
          "(or set requireToken=false for a loopback-only self-hosted install)",
      )
      .toList
}

// #612: cross-origin browser access for the split SPA.
// `allowedOrigins` is a comma-separated list of full origins (scheme+host+
// optional-port), matched exactly. Empty disables CORS entirely — the
// self-hosted single-origin path stays header-clean. Never `*`.
case class CorsConfig(
    allowedOrigins: String,
) {
  val origins: List[String] =
    allowedOrigins.split(",").iterator.map(_.trim).filter(_.nonEmpty).toList
}

// #944: hosts always present in every profile's snapshot `extraAllowed` so a
// paused household member can still reach the wifihaven admin UI to unpause
// themselves. Set per deployment to the SPA + API hostnames this API serves
// (prod or staging, not both). Empty disables the global allow list — the
// default for self-hosted single-origin installs that don't need it. Hostname
// only for now; port-aware allow/block requires plumbing port through
// connection events / traffic reports / snapshot, tracked in #296.
// Precursor to the DB-backed global profile in #937.
case class PolicyConfig(
    uiAllowedHosts: String = "",
    // #1849: how often the reconcile ticker rebuilds the cached snapshot and pushes it on an ETag
    // move. Bounds the staleness of time/usage-dependent transitions (schedule edges, daily-limit
    // exhaustion) on the cached REST poll path; defaulted to 5s to match the agent's
    // `policy_poll_interval` so the cache preserves the pre-cache ~per-poll freshness exactly while
    // moving the ~500ms build off the request path (#1512). Mutations invalidate immediately and do
    // not wait for this tick.
    snapshotCacheRefreshSeconds: Int = 5,
) {
  // Clamp to a 1s floor so a misconfigured `0`/negative can't turn `Schedule.fixed(Duration.Zero)`
  // into a no-delay tight loop that pegs the DB with back-to-back ~500ms snapshot builds.
  val snapshotCacheRefreshInterval: zio.Duration =
    zio.Duration.fromSeconds(math.max(1, snapshotCacheRefreshSeconds).toLong)

  val uiAllowedHostsParsed: List[Hostname] =
    uiAllowedHosts
      .split(",")
      .iterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .map { raw =>
        Hostname
          .parse(raw)
          .fold(
            err =>
              throw new IllegalArgumentException(
                s"wifihaven.policy.uiAllowedHosts: invalid hostname '$raw': $err",
              ),
            identity,
          )
      }
      .toList
}

// #1969 — SPA-websocket upgrade auth for the browser-facing `GET /api/ws` (design
// docs/design/spa-websocket.md §4/§8). The cookie/JWT verify reuses the existing
// AuthService (no second auth surface); the only ws-specific server config here is
// the `Origin` allowlist — "the one server-side ws config that differs by hosting
// mode" (§8).
//
// `allowedOrigins` is a comma-separated list of allowed Origin HOSTS (scheme/port
// ignored — the design's allowlist is host-based: app.wifihaven.net, staging.*,
// localhost). Matched case-insensitively, with two wildcard forms: a leading
// `*.suffix` matches any subdomain of `suffix` (and `suffix` itself), and a
// trailing `prefix.*` matches `prefix` and any host starting `prefix.`. EMPTY
// disables the cross-origin check entirely — the self-hosted same-origin default,
// where SameSite=Strict on the wh_ws cookie (§4.2) is the CSWSH guard; cloud/
// staging set the allowlist so a cross-site upgrade is rejected pre-101 (§8).
case class WsConfig(
    allowedOrigins: String = "",
    // §4.3 — cadence at which each open connection re-checks `now ≥ jwtExp` and, once
    // crossed, closes with `4401 token-expired`. Bounds mid-connection stale-authz
    // carry-over to one tick (mirrors the design's ~30s app-level heartbeat). Clamped
    // to a 1s floor so a misconfigured 0/negative can't become a no-delay tight loop.
    expiryCheckSeconds: Int = 30,
) {
  val allowedOriginHosts: List[String] =
    allowedOrigins.split(",").iterator.map(_.trim.toLowerCase).filter(_.nonEmpty).toList

  val expiryCheckInterval: zio.Duration =
    zio.Duration.fromSeconds(math.max(1, expiryCheckSeconds).toLong)

  /**
   * Whether `host` (the Origin header's host, lower-cased) is permitted. An empty allowlist returns
   * `true` for every host — the self-hosted same-origin mode where the cross-origin check is off.
   * When the allowlist is non-empty the check is enforced (an absent Origin is handled by the
   * caller, which has no host to pass here).
   */
  def originAllowed(host: String): Boolean =
    allowedOriginHosts.isEmpty || {
      val h = host.toLowerCase
      allowedOriginHosts.exists { pat =>
        if pat.startsWith("*.") then { val s = pat.drop(2); h == s || h.endsWith("." + s) }
        else if pat.endsWith(".*") then {
          val p = pat.dropRight(2); h == p || h.startsWith(p + ".")
        } else h == pat
      }
    }

  /** Origin enforcement is on only when an allowlist is configured (cloud/staging). */
  val enforceOrigin: Boolean = allowedOriginHosts.nonEmpty
}

// #808 — in-process weekly-partition auto-create job (durable fix for the 2026-06-29 P0 #2053).
// `weeksAhead` is how many weeks of future partitions the job keeps provisioned ahead of the
// current ISO week on each RANGE-partitioned ingest table. Default 6 (design §"Open operator
// decisions" recommends ≥ 2 weeks of lead; 6 leaves comfortable runway for a multi-day outage of
// the API/job). Clamped to a floor of 2 so a misconfigured 0/1 can't reduce the runway to the
// danger zone the runway alert pages on. Absent HOCON block uses the defaults.
case class PartitionConfig(
    weeksAhead: Int = 6,
) {
  val weeksAheadClamped: Int = math.max(2, weeksAhead)
}

// #2132 (multi-tenant P5-2, epic #622) — beta request → provisioning pipeline.
// `inviteBaseUrl` is the SPA origin the operator-issued invite link points at
// (design §3.4: the accept page is `/welcome?token=…`); the approve response
// returns `<inviteBaseUrl>/welcome?token=…` for the operator to send manually.
// `inviteTtlHours` is the single-use invite token lifetime (design §3.3: ~7 days,
// following the enrollment-token conventions in docs/process/security.md). Absent
// HOCON block uses the defaults (the cloud app apex + 7 days).
case class BetaConfig(
    inviteBaseUrl: String = "https://app.wifihaven.net",
    inviteTtlHours: Int = 168,
) {
  // Clamp to a 1h floor so a misconfigured 0/negative can't mint already-expired tokens.
  val inviteTtl: zio.Duration = zio.Duration.fromSeconds(math.max(1, inviteTtlHours).toLong * 3600)

  /** The full invite URL for a freshly-minted token — `<base>/welcome?token=…`. */
  def inviteUrl(rawToken: String): String =
    s"${inviteBaseUrl.stripSuffix("/")}/welcome?token=$rawToken"
}

// #2135 (multi-tenant P5-5, epic #622) — Stripe billing (design docs/design/multi-tenant-launch.md
// §5, pricing-analysis.md §7). The whole block is optional; an empty `secretKey` DISABLES billing
// entirely (`enabled = false`) so the self-hosted single-install path — which never bills — starts
// clean and the /api/billing/* routes return 404-shaped "not configured". Secrets (`secretKey`,
// `webhookSecret`) come from env via the entrypoint-rendered HOCON, NEVER committed
// (docs/process/security.md). Price ids / promo code differ between Stripe test and live modes, so
// they are config too (not constants). `appBaseUrl` is the SPA origin the hosted Checkout / Portal
// return to.
case class StripeConfig(
    // #2266: EXPLICIT named enable flag (no dark-by-default). Billing is off by default and turns on
    // only when the operator sets `enabled=true` — NOT inferred from `secretKey` presence. A true
    // flag makes `secretKey` REQUIRED (StripeConfig.validate → AppConfig.validateRequired fails boot),
    // so a cloud deploy that means to bill but dropped the key crashes loudly instead of silently
    // no-opping. Default false = the self-hosted / never-bills posture, logged at boot + on
    // /api/debug/config.
    enabled: Boolean = false,
    secretKey: String = "",
    webhookSecret: String = "",
    priceMonthly: String = "",
    priceAnnual: String = "",
    foundingPromoCode: String = "",
    appBaseUrl: String = "https://app.wifihaven.net",
    apiBase: String = "https://api.stripe.com",
) {
  private def base: String = appBaseUrl.stripSuffix("/")

  /** Hosted-Checkout success/cancel + Portal-return URLs back to the SPA billing page. */
  def checkoutSuccessUrl: String = s"$base/billing?checkout=success"
  def checkoutCancelUrl: String  = s"$base/billing?checkout=cancel"
  def portalReturnUrl: String    = s"$base/billing"

  val foundingPromoCodeOpt: Option[String] =
    Option(foundingPromoCode).map(_.trim).filter(_.nonEmpty)
}

object StripeConfig {
  // #2266: with billing explicitly enabled, `secretKey` is REQUIRED — an unset key then fails boot
  // (accumulated by AppConfig.validateRequired) rather than the old silent secretKey-presence no-op.
  private[api] def validate(cfg: StripeConfig): List[String] =
    Option
      .when(cfg.enabled && cfg.secretKey.trim.isEmpty)(
        "wifihaven.stripe.secretKey must be set when wifihaven.stripe.enabled=true (billing is on) — " +
          "set the key (or enabled=false to disable billing on this deploy)",
      )
      .toList
}

// #578 — outbound email transport for admin notifications (the deferred
// email-notification half of the block-page kid→parent request flow; epic #874).
// The one sanctioned email transport (docs/design/alerting.md §4 previously
// declared "no transport invented"; the operator signed off on Resend for #578).
//
// Sent over the JDK HttpClient to Resend's HTTPS API — NO new build dependency
// (same `ZIO.attemptBlocking` shape as `BlocklistFetcher`), honoring the #874
// "no SMTP libs without sign-off" constraint. Entirely config-gated: `enabled`
// is false unless BOTH an API key and a from-address are set, in which case the
// `Notifier` keeps its structured-log fallback and sends nothing. So the feature
// merges dark and the operator flips it on by adding the two secrets.
//
//   - `resendApiKey`   Resend API key (`re_…`); sent as `Authorization: Bearer`.
//     Cloud sets it via a Render secret; self-hosted leaves it empty (email off).
//   - `fromAddress`    verified sender, e.g. "WifiHaven <alerts@wifihaven.net>".
//   - `appBaseUrl`     SPA origin the "review in dashboard" link points at.
case class EmailConfig(
    // #2266: EXPLICIT named enable flag (no dark-by-default) — email is off unless the operator sets
    // `enabled=true`, NOT inferred from the two secrets being present. A true flag makes BOTH
    // `resendApiKey` and `fromAddress` REQUIRED (EmailConfig.validate → boot fails loud) so a deploy
    // that means to send but lost a secret crashes instead of silently logging. Default false = the
    // self-hosted no-email posture, logged at boot + on /api/debug/config.
    enabled: Boolean = false,
    resendApiKey: String = "",
    fromAddress: String = "",
    appBaseUrl: String = "https://app.wifihaven.net",
) {
  val apiKeyTrimmed: String = resendApiKey.trim
  val fromTrimmed: String   = fromAddress.trim
  def dashboardUrl: String  = appBaseUrl.stripSuffix("/")
}

object EmailConfig {
  // #2266: with email explicitly enabled, BOTH secrets are required (a key with no verified sender —
  // or vice-versa — can't send). Reports every gap so boot fails loud listing them all (rule 4).
  private[api] def validate(cfg: EmailConfig): List[String] =
    if !cfg.enabled then Nil
    else
      List(
        "wifihaven.email.resendApiKey" -> cfg.apiKeyTrimmed,
        "wifihaven.email.fromAddress"  -> cfg.fromTrimmed,
      ).collect {
        case (k, v) if v.isEmpty =>
          s"$k must be set when wifihaven.email.enabled=true — set it (or enabled=false to disable email)"
      }
}

// #2137 (multi-tenant P5-6, epic #622) — the beta→paid flip lifecycle
// (design docs/design/multi-tenant-launch.md §5.4, EVENT-TRIGGERED model per the operator's
// 2026-07-14 decision). The flip is NOT a hand-set calendar date: the cohort-wide flip clock
// STARTS the first time the count of active beta households reaches `thresholdHouseholds`, then
// runs `windowDays` and LATCHES (a later dip in the active count never pauses/resets it — the
// window end is persisted-start + windowDays, `beta_cohort.clock_started_at`, V70). At window end
// unconverted (`status='beta'`) households flip to `lapsed` — enforcement STOPS via a permissive
// snapshot (§5.3), never bricking the network.
//
// All three knobs are HOCON/env-tunable without a code change (zio-config). Defaults are the
// design's stated cohort v1 values (25 households / 60 days / 7-day active lookback).
case class FlipConfig(
    thresholdHouseholds: Int = 25,
    windowDays: Int = 60,
    activeLookbackDays: Int = 7,
) {
  // Clamp to sane floors so a misconfigured 0/negative can't start the clock on the first tick
  // (threshold ≥ 1) or make the window/lookback degenerate.
  val thresholdClamped: Int = math.max(1, thresholdHouseholds)
  val windowClamped: Int    = math.max(1, windowDays)
  val lookbackClamped: Int  = math.max(1, activeLookbackDays)

  /**
   * The flip window measured from the persisted clock-start instant (never recomputed from now).
   */
  val window: zio.Duration = zio.Duration.fromSeconds(windowClamped.toLong * 24 * 3600)

  /** How far back a router's traffic still counts its household as "active". */
  val activeLookback: zio.Duration =
    zio.Duration.fromSeconds(lookbackClamped.toLong * 24 * 3600)
}

// #2199 (support intake B, epic #2197) — the Plain helpdesk integration (design/umbrella #2206 §1).
// Everything is config-gated + `sync:false` in render.yaml, mirroring the #578 Resend key: no keys ⇒
// the feature ships DARK (the widget renders nothing, the write client is a no-op) so the
// self-hosted single-install path and the existing test suite are entirely unaffected. Secrets are
// NEVER committed — the cloud entrypoint renders them into this block from env
// (docs/process/security.md).
//
// Two independent gates, because the two halves turn on with different secrets:
//   - WIDGET (household-gated identified chat): needs BOTH the public `plainAppId` and the
//     `plainIdentitySecret` (the HMAC-SHA256 email-hash secret for Plain "chat authentication",
//     https://help.plain.com/article/chat-authentication). The identity endpoint signs SERVER-SIDE
//     so household A can never obtain household B's widget identity (#2199 household-gating).
//   - WRITE API (customer upsert + thread write): needs `plainApiKey` (Plain's GraphQL write API
//     bearer). The customer-upsert half carries the household→Plain-customer mapping; the
//     thread-write half is the seam #2200's Claude responder posts AI drafts into.
//
//   - `plainApiKey`         Plain API key (write API) — sent as `Authorization: Bearer`. SECRET.
//   - `plainWebhookSecret`  Plain webhook signing secret — CONSUMED by #2200 (inbound new-message
//                           webhook HMAC verify). SECRET.
//   - `plainIdentitySecret` chat-auth HMAC-SHA256 email-hash secret. SECRET.
//   - `plainAppId`          Plain chat/widget app id — public (rendered into the SPA), not a secret,
//                           but still `sync:false` since it differs per workspace.
//   - `apiBase`             Plain GraphQL endpoint (region-specific; overridable for test).
//
// #2200 (support intake C) adds the cloud-agent responder surface (operator decision 2026-07-16:
// the responder is a CLOUD Claude agent — an Anthropic Managed Agents session dispatched per
// UI-originated inbound message — not an in-process Claude call). Same dark-by-default rule: any
// missing secret leaves that half off and existing behavior unchanged.
//   - `anthropicApiKey`       Anthropic API key used ONLY to create agent sessions. SECRET.
//   - `claudeAgentId`         the pre-provisioned Managed Agent id (`agent_…`) — created once from
//                             deploy/support-agent/agent.yaml via `ant beta:agents create`, never
//                             created in the request path. Per-workspace config, not a secret.
//   - `claudeEnvironmentId`   the Managed Agents environment id (`env_…`). Per-workspace config.
//   - `anthropicApiBase`      Anthropic API base (overridable for test).
//   - `agentTokenSecret`      HMAC secret for the per-session agent token (#2241 consent token) —
//                             the agent's ONLY credential; thread- + household-bound, short-TTL.
//                             SECRET, generated by the operator (≥32 chars recommended).
//   - `agentTokenTtlMinutes`  agent-token lifetime; short by design (#2241). Clamped to ≥1.
//   - `agentApiBase`          the public base URL of THIS API (what the cloud agent calls back to
//                             for /api/support/agent/*), e.g. https://api.wifihaven.net.
//   - `deploymentEnv`         which deployment this API is — "staging" | "prod" (a per-service
//                             literal in render.yaml; empty on self-hosted). Rides in the kickoff
//                             so the agent knows whether it is serving a real customer (prod) or
//                             an operator test (staging).
//   - `responderEnabled` / `issueFilingEnabled`  EXPLICIT #2265 enable flags (no dark-by-default);
//                             a true flag makes its config chain required at boot.
//   - `githubSupportBotToken` fine-grained GitHub token for the support bot — Issues:write ONLY on
//                             wifihaven/wifihaven (no contents / no pull_requests ⇒ no-PR is
//                             structural, #2241). SECRET. Required when issueFilingEnabled=true.
//                             (repo + REST base are constants in GithubIssueClient, not config.)
case class SupportConfig(
    plainApiKey: String = "",
    plainWebhookSecret: String = "",
    plainIdentitySecret: String = "",
    plainAppId: String = "",
    apiBase: String = "https://core-api.uk.plain.com/graphql/v1",
    // #2265: NO dark-by-default. These are EXPLICIT named enable flags — the responder / issue
    // filing are off only when the operator says so (default false = the self-hosted no-cloud-
    // support posture, logged at boot + visible on /api/health; never inferred from missing
    // secrets). A TRUE flag makes its whole config chain REQUIRED: boot fails loudly, bulk-listing
    // every gap (into AppConfig.validateRequired), instead of silently no-opping (the #1334/#1972
    // failure class). Flat Booleans (not a nested block) — SupportConfig is at zio-config-magnolia's
    // 16-field nested-derivation ceiling, so the rarely-overridden github repo/base moved to
    // constants (GithubIssueClient) to make room rather than adding a 17th field.
    responderEnabled: Boolean = false,
    issueFilingEnabled: Boolean = false,
    anthropicApiKey: String = "",
    claudeAgentId: String = "",
    claudeEnvironmentId: String = "",
    anthropicApiBase: String = "https://api.anthropic.com",
    agentTokenSecret: String = "",
    agentTokenTtlMinutes: Int = 30,
    agentApiBase: String = "https://api.wifihaven.net",
    deploymentEnv: String = "",
    githubSupportBotToken: String = "",
) {
  val apiKeyTrimmed: String         = plainApiKey.trim
  val webhookSecretTrimmed: String  = plainWebhookSecret.trim
  val identitySecretTrimmed: String = plainIdentitySecret.trim
  val appIdTrimmed: String          = plainAppId.trim

  val anthropicApiKeyTrimmed: String       = anthropicApiKey.trim
  val claudeAgentIdTrimmed: String         = claudeAgentId.trim
  val claudeEnvironmentIdTrimmed: String   = claudeEnvironmentId.trim
  val agentTokenSecretTrimmed: String      = agentTokenSecret.trim
  val agentApiBaseTrimmed: String          = agentApiBase.trim.stripSuffix("/")
  val deploymentEnvTrimmed: String         = deploymentEnv.trim
  val githubSupportBotTokenTrimmed: String = githubSupportBotToken.trim

  // The identified widget renders only when BOTH the public app id AND the identity secret are set —
  // an app id with no signing secret can't produce a verifiable identity (and vice-versa), so treat
  // a half-configured widget as OFF rather than emitting an unverifiable identity.
  val widgetEnabled: Boolean = appIdTrimmed.nonEmpty && identitySecretTrimmed.nonEmpty

  // The Plain write API (customer upsert + thread write) is live only when the API key is set;
  // otherwise PlainClient is the disabled no-op and every call is a metered skip.
  val writeEnabled: Boolean = apiKeyTrimmed.nonEmpty

  // #2265: fail LOUD, in bulk. With the responder explicitly enabled, EVERY config the chain needs
  // is required — webhook verification, the Plain write key (the reply path), the Anthropic
  // session-create triple, the agent-token secret, and the deployment identity. Returns ALL missing
  // keys at once (bulk validation, #2265/#2266 rule 4); AppConfig.validateRequired folds these into
  // the canonical accumulator that fails boot listing every violation. A method (not a body
  // `require`) keeps zio-config's derivation clean.
  def missingRequiredKeys: List[String] = {
    val responderKeys =
      if !responderEnabled then Nil
      else
        List(
          "support.plainApiKey"         -> apiKeyTrimmed,
          "support.plainWebhookSecret"  -> webhookSecretTrimmed,
          "support.anthropicApiKey"     -> anthropicApiKeyTrimmed,
          "support.claudeAgentId"       -> claudeAgentIdTrimmed,
          "support.claudeEnvironmentId" -> claudeEnvironmentIdTrimmed,
          "support.agentTokenSecret"    -> agentTokenSecretTrimmed,
          "support.deploymentEnv"       -> deploymentEnvTrimmed,
        ).collect { case (k, v) if v.isEmpty => k }
    val issueKeys     =
      if issueFilingEnabled && githubSupportBotTokenTrimmed.isEmpty then
        List("support.githubSupportBotToken")
      else Nil
    responderKeys ++ issueKeys
  }

  // #2200: the agent-facing endpoints authenticate solely with the HMAC agent token, whose secret
  // the responder validation guarantees when enabled; disabled ⇒ 404-shaped denial.
  val agentEndpointsEnabled: Boolean = responderEnabled && agentTokenSecretTrimmed.nonEmpty

  // Clamp to a 1-minute floor so a misconfigured 0/negative can't mint already-expired tokens.
  val agentTokenTtl: java.time.Duration =
    java.time.Duration.ofMinutes(math.max(1, agentTokenTtlMinutes).toLong)
}

object AppConfig {
  private[api] def envTruthy(v: Option[String]): Boolean =
    v.map(_.trim.toLowerCase).exists {
      case "" | "0" | "false" | "no" | "off" => false
      case _                                 => true
    }

  /**
   * #2266 (no-dark-by-default, doc rule 1 + rule 4): validate every REQUIRED config invariant,
   * accumulating ALL violations so a misconfigured deploy is diagnosable from a single boot attempt
   * — an operator shouldn't fix one key, redeploy, discover the next, and repeat. Pure and total
   * (returns the full list of human-readable errors; empty ⇒ valid) so it is unit-testable.
   *
   * This is where NEW required-config guards go: add the check here rather than as a throwing
   * `require` in a case-class body, so it accumulates with the others instead of aborting boot on
   * the first failure. GENUINELY-OPTIONAL, config-gated features are NOT validated here — they are
   * reported (not failed) via [[StartupFeatureReport]].
   */
  def validateRequired(cfg: AppConfig): List[String] =
    JwtConfig.validate(cfg.jwt) ++ MetricsConfig.validate(cfg.metrics) ++
      EmailConfig.validate(cfg.email) ++ StripeConfig.validate(cfg.stripe) ++
      // #2265: the support responder / issue filing are EXPLICIT-flag features — off by default, but
      // when a flag is turned on its whole config chain becomes required. `missingRequiredKeys`
      // returns every gap for the enabled flag(s); mapped to a message here so they accumulate with
      // the rest (rule 1 + 4). Flag=false ⇒ no keys required ⇒ nothing added.
      cfg.support.missingRequiredKeys.map(k =>
        s"$k must be set when the support responder / issue filing is enabled (#2265)",
      )

  /**
   * Boot-boundary form of [[validateRequired]]: succeed with `cfg` when valid, else FAIL LOUDLY
   * with a `Config.Error` listing every violation at once, crashing boot. Wired into [[layer]].
   */
  def validateRequiredZIO(cfg: AppConfig): IO[Config.Error, AppConfig] =
    validateRequired(cfg) match {
      case Nil  => ZIO.succeed(cfg)
      case errs =>
        ZIO.fail(
          Config.Error.InvalidData(
            message =
              "Invalid WifiHaven configuration — fix ALL of the following required keys and restart:\n" +
                errs.map("  - " + _).mkString("\n"),
          ),
        )
    }

  val layer: ZLayer[Any, Config.Error, AppConfig] =
    ZLayer.fromZIO {
      val path = sys.props.getOrElse("config.file", "config/application.conf")
      read(
        deriveConfig[AppConfig]
          .nested("wifihaven")
          .from(
            TypesafeConfigProvider.fromHoconFile(new java.io.File(path)),
          ),
      ).flatMap(validateRequiredZIO)
    }
}
