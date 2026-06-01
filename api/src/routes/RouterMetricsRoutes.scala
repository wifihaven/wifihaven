package wifihaven.api.routes

import wifihaven.api.metrics.RouterMetricsService
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
 */
object RouterMetricsRoutes {

  def routes(auth: RouterAuth, svc: RouterMetricsService): Routes[Any, Response] =
    Routes(
      Method.POST / "api" / "router" / "metrics" ->
        handler { (req: Request) =>
          for {
            router <- auth.authenticate(req)
            body   <- req.body.asString.orElseFail(Response.badRequest(""))
            batch  <- ZIO
              .fromEither(body.fromJson[RouterMetricsBatch])
              .mapError(e => Response.badRequest(e))
            _      <- ZIO
              .fail(Response.text("router_id mismatch").status(Status.Forbidden))
              .when(batch.routerId != router.id)
            _      <- svc.ingest(batch)
          } yield Response.ok
        },
    )
}
