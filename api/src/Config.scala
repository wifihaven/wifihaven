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
)

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
) {
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
