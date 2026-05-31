package wifihaven.api

import zio.*
import zio.http.*

/**
 * #1248: the HTTP port is bound before DB-heavy startup (migrations + ensureDefault + seeds) runs,
 * so Render's port scan passes in milliseconds and a slow/pegged DB can no longer fail a deploy on
 * the port-scan timeout. The cost of binding early is that a real request can arrive before the
 * schema is migrated — `gate` is what makes that safe.
 *
 * `gate` wraps the non-health routes so that, while `ready` is false, every request short-circuits
 * to 503 `status=starting` *before* the handler runs (so it never queries an unmigrated DB). The
 * readiness check is re-read per request, so the gate begins passing traffic through the instant
 * startup flips it true. `/api/health` is deliberately NOT gated — it carries its own readiness
 * logic so Render's health check can observe the not-ready state and wait for promotion.
 */
object Readiness {

  private val notReady: Response =
    Response
      .json("""{"status":"starting"}""")
      .status(Status.ServiceUnavailable)

  def gate(routes: Routes[Any, Response], ready: UIO[Boolean]): Routes[Any, Response] =
    routes.transform[Any] { h =>
      Handler.fromFunctionZIO[Request] { req =>
        ready.flatMap {
          case true  => h(req)
          case false => ZIO.succeed(notReady)
        }
      }
    }
}
