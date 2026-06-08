package wifihaven.api.routes

import wifihaven.api.metrics.{AppMetrics, RouterMetricsService}
import wifihaven.shared.RouterMetricsBatch
import zio.*
import zio.http.*
import zio.json.*

/**
 * #1205 / design §3 — `POST /api/router/metrics`: the router → API metrics push transport. A thin
 * adapter over the carrier-agnostic [[RouterMetricsService]]; reuses [[RouterAuth]] (the existing
 * per-router bearer token — no new credential) and cross-checks the body `routerId` against the
 * token's router, mirroring `/api/router/usage` and `/api/router/events`.
 *
 * A malformed batch is a `400` (logged, not retried — a bad metrics batch is not worth a retry
 * storm); a wrong/missing token is `401`; a body whose `routerId` doesn't match the token is `403`.
 *
 * #1570: the handler fails with a typed [[ApiError]] mapped centrally by
 * [[ErrorMapper.errorToResponse]]; the [[wifihaven.api.ErrorBoundary]] logs (4xx WARN) + meters
 * each error. Each case reproduces the EXACT status + body the hand-rolled code produced (the 403
 * keeps its `text/plain` `router_id mismatch` body via [[ApiError.Wrapped]]). The bespoke
 * `recordRouterMetricsBatch` counters are NOT error-mapping and stay inline. Auth (RouterAuth)
 * still returns `Response` and is bridged via [[ApiError.Wrapped]].
 */
object RouterMetricsRoutes {

  def routes(auth: RouterAuth, svc: RouterMetricsService): Routes[Any, Response] =
    Routes(
      Method.POST / "api" / "router" / "metrics" ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            router <- auth.authenticate(req).mapError(ApiError.Wrapped(_))
            body   <- req.body.asString.orElseFail(ApiError.BadRequest(""))
            batch  <- ZIO
              .fromEither(body.fromJson[RouterMetricsBatch])
              .mapError(ApiError.DecodeFailure(_))
              .tapError(_ => AppMetrics.recordRouterMetricsBatch("malformed"))
            _      <- (AppMetrics.recordRouterMetricsBatch("router_mismatch") *>
              ZIO.fail(
                ApiError.Wrapped(Response.text("router_id mismatch").status(Status.Forbidden)),
              ))
              .when(batch.routerId != router.id)
            _      <- svc.ingest(batch)
            _      <- AppMetrics.recordRouterMetricsBatch("ok")
          } yield Response.ok
          handle.mapError(ErrorMapper.errorToResponse)
        },
    )
}
