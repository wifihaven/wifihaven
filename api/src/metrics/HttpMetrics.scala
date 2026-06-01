package wifihaven.api.metrics

import zio.*
import zio.http.*

/**
 * #1204: HTTP request metrics (`http_requests_total{route,method,status}` and
 * `http_request_duration_seconds{route,method}`).
 *
 * The hard requirement (§4) is that `route` is the *templated* path — `/api/devices/:mac`, never
 * the concrete id — because a per-id label would be unbounded and would leak device identity into
 * the metric registry. We get this for free by transforming each route individually and reading its
 * own `routePattern.pathCodec`: the only string that can ever become a label is the route template
 * the handler was registered under. A concrete path segment is physically unreachable here, so no
 * id can leak even by mistake.
 *
 * This mirrors zio-http's built-in `Middleware.metrics`, but emits the `route` label (the built-in
 * uses `path`, which §4.3 forbids) and routes every emission through [[AppMetrics.recordHttp]] /
 * the cardinality firewall. Timing wraps the handler's full exit (success *and* failure), so
 * 4xx/5xx responses produced on the error channel are still counted.
 *
 * Trade-off: a request that matches no route never reaches a transformed handler, so it is not
 * counted. That is the safe direction — an unmatched request carries a raw, attacker-controlled
 * path that must never become a label. Per-route templating keeps the label space bounded to the
 * ~40 registered templates.
 */
object HttpMetrics {

  private def renderTemplate(rp: RoutePattern[?]): String =
    rp.pathCodec.render(":", "")

  def instrument(routes: Routes[Any, Response]): Routes[Any, Response] =
    Routes.fromIterable(routes.routes.map(instrumentOne))

  private def instrumentOne(route: Route[Any, Response]): Route[Any, Response] = {
    val routeLabel = renderTemplate(route.routePattern)
    val method     = route.routePattern.method.render
    route.transform[Any] { handler =>
      Handler.fromFunctionZIO[Request] { req =>
        Clock.nanoTime.flatMap { start =>
          handler(req).either
            .flatMap { result =>
              val response = result.merge
              Clock.nanoTime.flatMap { end =>
                AppMetrics
                  .recordHttp(routeLabel, method, response.status.code, (end - start) / 1e9d)
                  .as(result)
              }
            }
            .flatMap(ZIO.fromEither(_))
        }
      }
    }
  }
}
