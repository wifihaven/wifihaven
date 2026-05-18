package wifihaven.api.routes

import zio.*
import zio.http.*

/**
 * Unauthenticated health probe for Docker/uptime monitors/reverse proxies.
 *
 * `checkDb` performs a cheap round-trip to Postgres (e.g. `SELECT 1`). Only the failure's class
 * name is exposed in the response body so SQL details don't leak.
 *
 * HEAD mirrors GET's status code with an empty body, per HTTP convention, so HEAD-based uptime
 * probes work.
 */
object HealthRoutes {
  def routes(checkDb: Task[Unit]): Routes[Any, Response] = {
    val getResponse: UIO[Response] =
      checkDb.fold(
        err => {
          val cls = err.getClass.getSimpleName
          Response
            .json(s"""{"status":"error","db":"$cls"}""")
            .status(Status.ServiceUnavailable)
        },
        _ => Response.json("""{"status":"ok","db":"ok"}"""),
      )

    Routes(
      Method.GET / "api" / "health" ->
        handler { (_: Request) => getResponse },
      Method.HEAD / "api" / "health" ->
        handler { (_: Request) => getResponse.map(_.copy(body = Body.empty)) },
    )
  }
}
