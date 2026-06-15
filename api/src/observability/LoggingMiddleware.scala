package wifihaven.api.observability

import zio.*
import zio.http.*

/**
 * #602: per-request log-context middleware. Mirrors `metrics.HttpMetrics` / `ErrorBoundary` — it
 * transforms each route individually so it can read the route's own templated path
 * (`routePattern.pathCodec`), then wraps the handler invocation in `LogContext.annotateAll` so the
 * templated `route` and `method` are attached as ZIO log annotations (→ SLF4J MDC) for the entire
 * handler scope. Every `ZIO.log*` call inside the handler — including those emitted by repos,
 * services, and child fibers it forks — inherits the annotation via FiberRef without the handler
 * (or any callee) needing to wrap anything.
 *
 * This is the SSoT for the per-request `route` and `method` context: handlers do NOT add them.
 * `ErrorBoundary` adds `status`; handler bodies attach only TRULY per-handler data they had to
 * compute locally (`routerId` after auth, `etag` after snapshot resolve, `mac`, `profileId`, etc.).
 *
 * Cardinality safety: the only string that becomes a label is `routePattern.pathCodec.render(...)`
 * — the bounded route template, never the raw request path. An unmatched request never reaches a
 * transformed handler, so an attacker-controlled path can never become a value. Matches the
 * existing `HttpMetrics` safety rationale.
 */
object LoggingMiddleware {

  private def renderTemplate(rp: RoutePattern[?]): String =
    rp.pathCodec.render(":", "")

  def annotate(routes: Routes[Any, Response]): Routes[Any, Response] =
    Routes.fromIterable(routes.routes.map(annotateOne))

  private def annotateOne(route: Route[Any, Response]): Route[Any, Response] = {
    val routeLabel = renderTemplate(route.routePattern)
    val method     = route.routePattern.method.render
    route.transform[Any] { handler =>
      Handler.fromFunctionZIO[Request] { req =>
        LogContext.annotateAll(
          LogContext.Route  -> routeLabel,
          LogContext.Method -> method,
        )(handler(req))
      }
    }
  }
}
