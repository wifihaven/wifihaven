package wifihaven.api

import zio.*
import zio.http.*

// #612: cross-origin browser access for the split SPA (SPA on its own
// hostname, API on api[-staging].wifihaven.net). Self-hosted single-origin
// installs leave allowedOrigins empty — the middleware is skipped entirely
// so no CORS headers are emitted on that path.
object Cors {

  def wrap[Env, Err](
      routes: Routes[Env, Err],
      cfg: CorsConfig,
  ): Routes[Env, Err] = {
    val allowed = cfg.origins
    if (allowed.isEmpty) routes
    else routes @@ Middleware.cors(buildConfig(allowed))
  }

  private[api] def buildConfig(allowed: List[String]): Middleware.CorsConfig = {
    val allowedSet = allowed.toSet
    Middleware.CorsConfig(
      allowedOrigin = origin =>
        if (allowedSet.contains(Header.Origin.render(origin)))
          Some(Header.AccessControlAllowOrigin.Specific(origin))
        else None,
      allowedMethods = Header.AccessControlAllowMethods(
        Method.GET,
        Method.POST,
        Method.PUT,
        Method.PATCH,
        Method.DELETE,
        Method.OPTIONS,
      ),
      allowedHeaders = Header.AccessControlAllowHeaders.Some(
        NonEmptyChunk("Authorization", "Content-Type", "If-None-Match"),
      ),
      allowCredentials = Header.AccessControlAllowCredentials.DoNotAllow,
      exposedHeaders = Header.AccessControlExposeHeaders.Some(
        NonEmptyChunk("ETag"),
      ),
      maxAge = Some(Header.AccessControlMaxAge(10.minutes)),
    )
  }
}
