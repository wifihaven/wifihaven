package wifihaven.api.routes

import zio.http.*

/**
 * Maps DB / unexpected throwables to HTTP responses for router + admin routes.
 *
 * Per docs/resilience.md §3: DB failures must surface as 503 (not bare 500) so the OpenWRT agent
 * can distinguish "service degraded, retry later" from "bad request, do not retry." The body shape
 * matches HealthRoutes: `{"status":"error","db":"<class>"}`. Only the throwable's simple class name
 * is exposed — SQL fragments, table names, and stack traces are not leaked.
 *
 * `Retry-After: 30` matches the agent's base backoff in docs/resilience.md §2.
 */
object ErrorMapper {

  /**
   * #1570: the single typed-error → `Response` mapping. Routes that have migrated to the central
   * handler (Stage 1: the router ingest routes) fail with an [[ApiError]] and let this produce the
   * response, instead of constructing `Response.badRequest(...)` / `notFound(...)` inline.
   *
   * Every branch reproduces the EXACT status + body the hand-rolled code produced before — the
   * error responses are part of the cross-process wire contract (the OpenWRT agent branches on the
   * status; the SPA renders the body text). Mapping is pure: logging and metering happen once, at
   * the boundary ([[wifihaven.api.ErrorBoundary]]), so there is a single source for each concern.
   */
  def errorToResponse(e: ApiError): Response = e match {
    case ApiError.BadRequest(m)    => Response.badRequest(m)
    case ApiError.DecodeFailure(m) => Response.badRequest(m)
    case ApiError.Unauthorized(m)  => Response.unauthorized(m)
    case ApiError.Forbidden(m)     => Response.forbidden(m)
    case ApiError.NotFound(m)      => Response.notFound(m)
    case ApiError.Db(t)            => dbErrorToResponse(t)
    case ApiError.Internal(m)      => Response.internalServerError(m)
    case ApiError.Wrapped(r)       => r
  }

  /** 503 + JSON body + Retry-After. Use for any ZIO[_, Throwable, _] that touches the DB. */
  def dbErrorToResponse(t: Throwable): Response =
    dbUnavailable(t.getClass.getSimpleName)

  /**
   * Same shape as [[dbErrorToResponse]], but for cases where the original throwable has already
   * been funneled into a domain error (e.g. `AuthError.Unexpected`) and only a label is left to
   * expose. `label` MUST be a static identifier — never a SQL fragment or user-controlled string.
   */
  def dbUnavailable(label: String): Response =
    Response
      .json(s"""{"status":"error","db":"$label"}""")
      .status(Status.ServiceUnavailable)
      .addHeader("Retry-After", "30")
}
