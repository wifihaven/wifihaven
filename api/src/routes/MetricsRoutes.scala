package wifihaven.api.routes

import wifihaven.api.MetricsConfig
import zio.*
import zio.http.*
import zio.metrics.connectors.prometheus.PrometheusPublisher

/**
 * #1242: Prometheus exposition endpoint. `GET /metrics` renders the registry snapshot held by the
 * connector's [[PrometheusPublisher]] as `text/plain; version=0.0.4`.
 *
 * Auth is config-gated: when `cfg.scrapeToken` is set, the request must carry `Authorization:
 * Bearer <token>` (the API is internet-facing on Render). When unset, the endpoint is open — the
 * self-hosted default, where the API isn't publicly exposed. When `cfg.enabled` is false the route
 * isn't mounted at all.
 *
 * #2266: when `cfg.requireToken` is true (the cloud-only mandatory-token flag) an unset token is
 * fail-CLOSED — every request is 401 rather than the loopback-open default.
 * `AppConfig.validateRequired` already crashes boot on `requireToken=true` + empty token, so this
 * is defense in depth: the route must never fall open just because that guard was bypassed.
 */
object MetricsRoutes {

  // Prometheus text exposition format version 0.0.4.
  private val ContentType = "text/plain; version=0.0.4; charset=utf-8"

  def routes(cfg: MetricsConfig, publisher: PrometheusPublisher): Routes[Any, Response] =
    if !cfg.enabled then Routes.empty
    else
      Routes(
        Method.GET / "metrics" ->
          handler { (req: Request) =>
            val handle: ZIO[Any, ApiError, Response] =
              if authorized(req, cfg) then
                publisher.get.map(text =>
                  Response(status = Status.Ok, body = Body.fromCharSequence(text))
                    .removeHeader("content-type")
                    .addHeader("content-type", ContentType),
                )
              else ZIO.fail(ApiError.Unauthorized("missing or invalid metrics scrape token"))
            handle.mapError(ErrorMapper.errorToResponse)
          },
      )

  private def authorized(req: Request, cfg: MetricsConfig): Boolean =
    cfg.scrapeTokenOpt match {
      // #2266: no token + requireToken → fail closed (deny all); no token + !requireToken → the
      // loopback-open self-hosted default (allow). A configured token is always enforced.
      case None           => !cfg.requireToken
      case Some(expected) => bearer(req).contains(expected)
    }

  private def bearer(req: Request): Option[String] =
    req
      .header(Header.Authorization)
      .map(_.renderedValue)
      .collect { case v if v.startsWith("Bearer ") => v.drop("Bearer ".length) }
}
