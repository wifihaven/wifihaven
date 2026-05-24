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
// themselves. Set per deployment to the SPA + API host:port pairs this API
// serves (prod or staging, not both). Entries may include an optional :port
// suffix — dev installs reach the API at e.g. api.lan:8080, so the operator
// must be able to allow that exact host:port through. Empty disables the
// global allow list — the default for self-hosted single-origin installs
// that don't need it. Precursor to the DB-backed global profile in #937.
case class PolicyConfig(
    uiAllowedHosts: String = "",
) {
  // Accepts "host" or "host:port" — dev installs reach the API on a non-443
  // port (e.g. api.lan:8080), so the operator must be able to put the port
  // into the allow list. The host part is validated via Hostname.parse; the
  // port (if present) must be a 1..65535 integer. The whole entry is shipped
  // through to the wire as-is (Hostname is an opaque-type String).
  val uiAllowedHostsParsed: List[Hostname] =
    uiAllowedHosts
      .split(",")
      .iterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .map { raw =>
        val (host, portSuffix) = raw.indexOf(':') match {
          case -1 => (raw, "")
          case i  =>
            val h    = raw.substring(0, i)
            val port = raw.substring(i + 1)
            val p    = port.toIntOption.getOrElse(
              throw new IllegalArgumentException(
                s"wifihaven.policy.uiAllowedHosts: invalid port in '$raw' (expected integer 1..65535)",
              ),
            )
            if p < 1 || p > 65535 then
              throw new IllegalArgumentException(
                s"wifihaven.policy.uiAllowedHosts: port out of range in '$raw' (expected 1..65535)",
              )
            (h, s":$p")
        }
        Hostname
          .parse(host)
          .fold(
            err =>
              throw new IllegalArgumentException(
                s"wifihaven.policy.uiAllowedHosts: invalid hostname '$raw': $err",
              ),
            h => Hostname.unsafe(h.value + portSuffix),
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
