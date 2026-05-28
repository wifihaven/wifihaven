package wifihaven.api.routes

import wifihaven.api.BuildInfo
import zio.*
import zio.http.*

/**
 * Unauthenticated, DB-free build-identity probe (#1140). The CD staging health-poll asserts the
 * served `sha` equals the SHA it just deployed before trusting the smoke tests, closing the
 * Render-rollover blind spot that produced the #1138 false positive.
 */
object VersionRoutes {
  def routes(info: BuildInfo): Routes[Any, Response] =
    Routes(
      Method.GET / "api" / "version" ->
        handler { (_: Request) => Response.json(s"""{"sha":"${info.sha}"}""") },
    )
}
