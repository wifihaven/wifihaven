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

case class JwtConfig(
    secret: String,
    expiryHours: Int,
) {
  // #2084: fail fast on a weak or unrotated-placeholder JWT secret rather than
  // silently starting with one. HS256's whole security rests on secret entropy;
  // a short or well-known secret is offline-brute-forceable, letting an
  // attacker forge arbitrary admin JWTs. Cloud config always sets a generated
  // 32+ char value (render.yaml `generateValue: true`); this guards the
  // self-hosted path, which has no such backstop.
  require(
    secret.length >= JwtConfig.MinSecretLength,
    s"wifihaven.jwt.secret must be at least ${JwtConfig.MinSecretLength} characters (got ${secret.length})",
  )
  require(
    !secret.startsWith("change-this"),
    "wifihaven.jwt.secret is still the shipped config/application.conf.example placeholder — generate a real secret",
  )
}

object JwtConfig {
  val MinSecretLength: Int = 32
}

// #1242: Prometheus /metrics exposition. `enabled` mounts the GET /metrics
// route; when off, non-/metrics behaviour is unchanged and the route 404s.
// `scrapeToken`, when non-empty, is required as `Authorization: Bearer <token>`
// — the API is internet-facing on Render, so prod/staging set it. Empty (the
// self-hosted default) leaves /metrics open on the loopback-bound deployment.
case class MetricsConfig(
    enabled: Boolean = true,
    scrapeToken: String = "",
) {
  val scrapeTokenOpt: Option[String] =
    Option(scrapeToken).map(_.trim).filter(_.nonEmpty)
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
    secretKey: String = "",
    webhookSecret: String = "",
    priceMonthly: String = "",
    priceAnnual: String = "",
    foundingPromoCode: String = "",
    appBaseUrl: String = "https://app.wifihaven.net",
    apiBase: String = "https://api.stripe.com",
) {
  // Billing is active only when a secret key is present. Everything downstream (route mounting, the
  // provisioning Customer seam) checks this so an unconfigured install is a no-op, not an error.
  val enabled: Boolean = secretKey.trim.nonEmpty

  private def base: String = appBaseUrl.stripSuffix("/")

  /** Hosted-Checkout success/cancel + Portal-return URLs back to the SPA billing page. */
  def checkoutSuccessUrl: String = s"$base/billing?checkout=success"
  def checkoutCancelUrl: String  = s"$base/billing?checkout=cancel"
  def portalReturnUrl: String    = s"$base/billing"

  val foundingPromoCodeOpt: Option[String] =
    Option(foundingPromoCode).map(_.trim).filter(_.nonEmpty)
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
    resendApiKey: String = "",
    fromAddress: String = "",
    appBaseUrl: String = "https://app.wifihaven.net",
) {
  val apiKeyTrimmed: String = resendApiKey.trim
  val fromTrimmed: String   = fromAddress.trim
  // Both secrets required — a key with no verified sender (or vice-versa) can't
  // send, so treat that as "off" rather than failing every notification at runtime.
  val enabled: Boolean      = apiKeyTrimmed.nonEmpty && fromTrimmed.nonEmpty
  def dashboardUrl: String  = appBaseUrl.stripSuffix("/")
}

object AppConfig {
  private[api] def envTruthy(v: Option[String]): Boolean =
    v.map(_.trim.toLowerCase).exists {
      case "" | "0" | "false" | "no" | "off" => false
      case _                                 => true
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
      )
    }
}
