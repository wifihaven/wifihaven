package wifihaven.api.routes

import zio.*
import zio.http.*

/**
 * Unauthenticated health probe for Docker/uptime monitors/reverse proxies and Render's
 * `healthCheckPath`.
 *
 * `ready` reflects whether DB-heavy startup (migrations + ensureDefault + seeds) has finished.
 * #1248: the port is bound *before* that work runs so Render's port scan passes immediately; until
 * `ready` flips true this probe returns 503 `status=starting` and does NOT touch the DB (the schema
 * may be mid-migration). Once ready, `checkDb` performs a cheap round-trip to Postgres (e.g.
 * `SELECT 1`). Only the failure's class name is exposed so SQL details don't leak.
 *
 * HEAD mirrors GET's status code with an empty body, per HTTP convention, so HEAD-based uptime
 * probes work.
 */
object HealthRoutes {
  def routes(ready: UIO[Boolean], checkDb: Task[Unit]): Routes[Any, Response] = {
    val getResponse: UIO[Response] =
      ready.flatMap {
        case false =>
          ZIO.succeed(
            Response
              .json("""{"status":"starting"}""")
              .status(Status.ServiceUnavailable),
          )
        case true  =>
          checkDb.fold(
            err => {
              val cls = err.getClass.getSimpleName
              Response
                .json(s"""{"status":"error","db":"$cls"}""")
                .status(Status.ServiceUnavailable)
            },
            _ => Response.json("""{"status":"ok","db":"ok"}"""),
          )
      }

    Routes(
      Method.GET / "api" / "health"  ->
        handler { (_: Request) => getResponse },
      Method.HEAD / "api" / "health" ->
        handler { (_: Request) => getResponse.map(_.copy(body = Body.empty)) },
    )
  }
}
