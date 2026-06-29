package wifihaven.api.routes

import wifihaven.api.db.*
import zio.*
import zio.http.*

/**
 * Router-side ingest endpoints. See docs/architecture-openwrt.md §5.4 / §5.5.
 *   - `POST /api/router/usage` — periodic ~60 s traffic + time rollups.
 *   - `POST /api/router/events` — DHCP lease + first-seen-MAC + connection_attempt events.
 *
 * Both require a router bearer token resolved via [[RouterAuth]].
 *
 * #1846: these handlers are now thin transport adapters. They authenticate, read the body, and
 * delegate to the carrier-agnostic [[RouterIngestService]], which the websocket transport
 * ([[RouterWsRoutes]]) shares — so the decode + per-record-skip + persistence logic lives in
 * exactly one place (`AGENTS.md#single-source-of-truth`). The typed [[ApiError]] the service raises
 * is mapped centrally by [[ErrorMapper.errorToResponse]]; the [[wifihaven.api.ErrorBoundary]] logs
 * (4xx WARN / 5xx ERROR) + meters each error. Per-record skip / per-batch debug logging now lives
 * in the service (annotated with the same [[wifihaven.api.observability.LogContext]] router scope),
 * so the debuggability #1017/#1334 relied on is preserved.
 */
object RouterIngestRoutes {

  /**
   * Convenience overload that constructs the [[RouterIngestService]] from its repos. Kept so call
   * sites that only need the REST ingest routes (and the existing test suite, which is the
   * back-compat gate for the #1846 extraction) don't each have to build the service. Production
   * (`Main`) builds ONE service and shares it with the websocket transport.
   */
  def routes(
      auth: RouterAuth,
      routerRepo: RouterRepo,
      trafficRepo: TrafficReportRepo,
      timeUsageRepo: TimeUsageRepo,
      deviceRepo: DeviceRepo,
      connEventRepo: ConnectionEventRepo,
      alertRepo: AlertRepo,
      householdSettingsRepo: HouseholdSettingsRepo,
  ): Routes[Any, Response] =
    routes(
      auth,
      new RouterIngestService(
        routerRepo,
        trafficRepo,
        timeUsageRepo,
        deviceRepo,
        connEventRepo,
        alertRepo,
        householdSettingsRepo,
      ),
    )

  def routes(
      auth: RouterAuth,
      ingest: RouterIngestService,
  ): Routes[Any, Response] =
    Routes(
      Method.POST / "api" / "router" / "usage"  ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] =
            for {
              router <- auth.authenticate(req)
              body   <- req.body.asString.orElseFail(ApiError.BadRequest(""))
              _      <- ingest.ingestUsage(router, body)
            } yield Response.ok
          handle.tapError(logDbCause).mapError(ErrorMapper.errorToResponse)
        },
      Method.POST / "api" / "router" / "events" ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] =
            for {
              router <- auth.authenticate(req)
              body   <- req.body.asString.orElseFail(ApiError.BadRequest(""))
              _      <- ingest.ingestEvents(router, body)
            } yield Response.ok
          handle.tapError(logDbCause).mapError(ErrorMapper.errorToResponse)
        },
    )

  /**
   * #1570 / #2053 observability fix: when an ingest fails with a DB error, log the full PSQL cause
   * chain at ERROR before [[ErrorMapper.errorToResponse]] collapses it to the class-name-only 503
   * body the wire contract mandates. [[wifihaven.api.ErrorBoundary]] still logs the response-level
   * summary (route/status/body); this adds the diagnostic detail that body deliberately omits, so a
   * future partition/SQL failure shows the real reason (`no partition of relation … found for row`)
   * instead of a bare `BatchUpdateException`. Non-DB errors carry their detail in the body the
   * boundary already logs, so only the `Db` case needs this.
   */
  private def logDbCause(e: ApiError): UIO[Unit] = e match {
    case ApiError.Db(cause) =>
      ZIO.logError(s"router ingest DB failure: ${ErrorMapper.dbCauseChain(cause)}")
    case _                  => ZIO.unit
  }
}
