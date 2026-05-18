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
      // #626: zio-http 3.0.1's CORS middleware intersects the browser's
      // Access-Control-Request-Headers against this set by case-sensitive
      // string equality. Browsers normalize header names to lowercase per
      // the Fetch spec (e.g. `content-type`), so mixed-case entries here
      // never match and the response Allow-Headers comes back empty. Keep
      // these lowercase — header names are case-insensitive on the wire.
      allowedHeaders = Header.AccessControlAllowHeaders.Some(
        NonEmptyChunk("authorization", "content-type", "if-none-match"),
      ),
      allowCredentials = Header.AccessControlAllowCredentials.DoNotAllow,
      exposedHeaders = Header.AccessControlExposeHeaders.Some(
        NonEmptyChunk("ETag"),
      ),
      maxAge = Some(Header.AccessControlMaxAge(10.minutes)),
    )
  }
}
