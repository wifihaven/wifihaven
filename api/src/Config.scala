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
)

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
    // #1174: public SPA base URL (scheme+host, e.g. https://wifihaven.net).
    // Included in every PolicySnapshot so the router agent's block-page
    // handler can redirect blocked kids to the SPA host instead of the API
    // host (the API origin 404s non-/api paths in cloud deployments). Empty
    // → snapshot.spaBaseUrl is None and the agent falls back to its enrolled
    // api_url, preserving pre-#1174 behaviour for single-origin self-hosted
    // installs that don't split SPA and API.
    spaBaseUrl: String = "",
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

  val spaBaseUrlOpt: Option[String] = Option(spaBaseUrl).map(_.trim).filter(_.nonEmpty)
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
