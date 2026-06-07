package wifihaven.api

import wifihaven.api.metrics.AppMetrics
import zio.*
import zio.http.*

/**
 * #1570: the single error-handling layer at the server boundary. Mirrors [[metrics.HttpMetrics]] —
 * it transforms each route individually so it can read the route's own templated path
 * (`routePattern.pathCodec`), then inspects the response the handler ultimately produced (success
 * OR error channel) and, when that response is an error (status >= 400), LOGS and METERS it
 * uniformly.
 *
 * This is what makes error handling consistent regardless of how a route produced the response:
 *   - migrated routes fail with a typed [[routes.ApiError]] that
 *     [[routes.ErrorMapper.errorToResponse]] maps; un-migrated routes still build a `Response`
 *     inline. Either way the boundary sees the final response and treats it the same.
 *
 * It closes the #1569 gap structurally: POST `/api/router/events` hand-logged decode failures but
 * POST `/api/router/usage` did not, so the incident's failing field was invisible in our logs. Now
 * every 4xx — including a decode 400 whose body is the zio-json error naming the failing field — is
 * logged once, here, at WARN; every 5xx at ERROR. The per-route bespoke logging is removed so there
 * is exactly one emitter (no double-logging, no drift).
 *
 * Logging policy:
 *   - 4xx (incl. decode/validation/auth) → WARN
 *   - 5xx (incl. the DB 503) → ERROR
 *   - context: templated `route`, `method`, `status`, and a bounded one-line snippet of the
 *     response body. The body of an error response is our own message (the decode error, the
 *     "router_id mismatch" text, the `{"status":"error","db":...}` JSON) — never request PII — and
 *     it carries the diagnostic detail, so a small truncated snippet is logged. Reading is guarded
 *     by `knownContentLength` (our error bodies are small, complete, in-memory strings) and any
 *     read failure degrades to no-snippet, so it can never disturb the response sent to the client.
 *
 * Metering: `api_errors_total{route,status}` via [[AppMetrics.recordHttpError]] (bounded labels
 * only — the templated route + the status code; never a per-mac/host/ip value).
 *
 * Like HttpMetrics, an unmatched request never reaches a transformed handler, so a raw
 * attacker-controlled path can never become a label.
 */
object ErrorBoundary {

  /** Max characters of an error-response body logged as context. Bounded — no PII dumps. */
  private val MaxSnippet = 256

  /** Don't even attempt to read a body larger than this as a snippet. */
  private val MaxReadBytes = 64L * 1024L

  private def renderTemplate(rp: RoutePattern[?]): String =
    rp.pathCodec.render(":", "")

  def observe(routes: Routes[Any, Response]): Routes[Any, Response] =
    Routes.fromIterable(routes.routes.map(observeOne))

  private def observeOne(route: Route[Any, Response]): Route[Any, Response] = {
    val routeLabel = renderTemplate(route.routePattern)
    val method     = route.routePattern.method.render
    route.transform[Any] { handler =>
      Handler.fromFunctionZIO[Request] { req =>
        handler(req).either.flatMap { result =>
          val response = result.merge
          val observe  =
            if response.status.code >= 400 then logAndMeter(routeLabel, method, response)
            else ZIO.unit
          observe *> ZIO.fromEither(result)
        }
      }
    }
  }

  private def logAndMeter(route: String, method: String, response: Response): UIO[Unit] = {
    val status = response.status.code
    for {
      snippet <- bodySnippet(response)
      base = s"$method $route -> $status"
      msg  = snippet.fold(base)(s => s"$base body=$s")
      _ <- if status >= 500 then ZIO.logError(msg) else ZIO.logWarning(msg)
      _ <- AppMetrics.recordHttpError(route, status)
    } yield ()
  }

  /**
   * Best-effort one-line, length-bounded snippet of the (error) response body. Only reads bodies
   * with a known, small content length — our 4xx/5xx bodies are complete in-memory strings, never
   * streams — and never fails: a read error degrades to no snippet rather than affecting the
   * response delivered to the client.
   */
  private def bodySnippet(response: Response): UIO[Option[String]] =
    response.body.knownContentLength match {
      case Some(len) if len > 0L && len <= MaxReadBytes =>
        response.body.asString
          .map(s => Some(truncate(s)))
          .catchAll(_ => ZIO.none)
      case _                                            => ZIO.none
    }

  private def truncate(s: String): String = {
    val oneLine = s.replaceAll("\\s+", " ").trim
    if oneLine.length > MaxSnippet then oneLine.take(MaxSnippet) + "…(truncated)" else oneLine
  }
}
