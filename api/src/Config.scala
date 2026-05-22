package wifihaven.api

import zio.*
import zio.config.*
import zio.config.magnolia.*
import zio.config.typesafe.*

case class AppConfig(
    db: DbConfig,
    http: HttpConfig,
    jwt: JwtConfig,
    cors: CorsConfig,
) {
  // WIFIHAVEN_DEBUG env var: when set to a non-empty, non-"0"/"false"/"no"
  // value, mounts the read-only /api/debug/* endpoints (loopback only).
  // Read from env, not HOCON, so it stays out of application.conf — debug
  // belongs to the runtime environment, not the persistent config.
  val debugEnabled: Boolean = AppConfig.envTruthy(sys.env.get("WIFIHAVEN_DEBUG"))

  // #706: WIFIHAVEN_ENV gates dev-only fixtures (currently: the test_ads /
  // test_social blocklist seed). Recognized values: "prod" (default, no
  // fixtures), "dev" (re-seed test blocklists after migrations). Read from
  // env, not HOCON — environment identity belongs to the deploy target, not
  // the persistent config file. Default is "prod" so any unset deployment
  // is safe.
  val env: String    =
    sys.env.get("WIFIHAVEN_ENV").map(_.trim.toLowerCase).filter(_.nonEmpty).getOrElse("prod")
  val isDev: Boolean = env == "dev"
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
