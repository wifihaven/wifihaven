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
