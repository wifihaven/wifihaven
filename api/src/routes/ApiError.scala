package wifihaven.api.routes

import zio.http.*

/**
 * #1570: the typed error vocabulary a route handler fails with so that mapping → HTTP status/body
 * happens in ONE place ([[ErrorMapper.errorToResponse]]) instead of being hand-rolled per route
 * with inline `Response.badRequest(...)` / `Response.notFound(...)` /
 * `ErrorMapper.dbErrorToResponse`.
 *
 * This is the seam that lets the centralized boundary handler ([[wifihaven.api.ErrorBoundary]]) log
 * + meter every error consistently. Routes migrate to it incrementally (Stage 1: the router ingest
 * routes); until a route is migrated it keeps producing `Response` directly and the boundary still
 * logs/meters it by status.
 *
 * CRITICAL — wire back-compat: each case maps to the EXACT same `Response` the hand-rolled code
 * produced before (same status code, same body constructor), because the OpenWRT agent and the SPA
 * parse these responses. The agent branches on status only (4xx = drop, 5xx = retry/back-off), so
 * the DB case MUST stay 503; the SPA reads the body as plain text for display and sniffs the 403
 * body for `password_change_required`. See docs/architecture.md + the #1570 issue.
 */
sealed trait ApiError

object ApiError {

  /** 400. Generic client error; body is the plain-text message (matches `Response.badRequest`). */
  final case class BadRequest(message: String) extends ApiError

  /**
   * 400, semantically distinct from [[BadRequest]]: a request body failed JSON decoding/validation.
   * `message` is the zio-json error string (it names the failing field/position) and becomes the
   * response body, so the boundary's WARN log surfaces the failing field — the exact gap that made
   * the #1569 usage-ingest incident invisible in our logs. Maps identically to a 400.
   */
  final case class DecodeFailure(message: String) extends ApiError

  /** 401. Plain-text body (matches `Response.unauthorized`). */
  final case class Unauthorized(message: String) extends ApiError

  /** 403. Plain-text body (matches `Response.forbidden`). */
  final case class Forbidden(message: String) extends ApiError

  /** 404. Plain-text body (matches `Response.notFound`). */
  final case class NotFound(message: String) extends ApiError

  /**
   * 503 + `{"status":"error","db":"<class>"}` + `Retry-After: 30` (via
   * [[ErrorMapper.dbErrorToResponse]]). The agent distinguishes this "retry later" 503 from a 4xx
   * "do not retry"; keeping DB failures as 503 (never a bare 500) is load-bearing.
   */
  final case class Db(cause: Throwable) extends ApiError

  /** 500. Plain-text body (matches `Response.internalServerError`). */
  final case class Internal(message: String) extends ApiError

  /**
   * An already-formed `Response` passed through unchanged. Bridges collaborators that still produce
   * `Response` on their error channel (e.g. [[RouterAuth.authenticate]]) into the typed pipeline
   * without re-deriving their status/body — their migration to a typed error is Stage 2+.
   */
  final case class Wrapped(response: Response) extends ApiError
}
