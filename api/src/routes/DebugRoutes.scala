package familydns.api.routes

import familydns.api.db.*
import familydns.shared.Clock
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*

/**
 * Read-only JSON dumps of the underlying tables, intended for diagnosing router/agent ingest issues
 * live (#228). Mounted ONLY when the API is started with `FAMILYDNS_DEBUG=1`; access is further
 * restricted to loopback callers on the API host.
 *
 * Endpoints: GET /api/debug/devices -> all device rows GET /api/debug/events?limit -> recent
 * connection_events (default 50, max 500) GET /api/debug/time_usage -> today's per-(mac, domain)
 * usage
 *
 * Auth: none. Loopback check uses BOTH `req.remoteAddress` (when present) AND the `Host` header.
 * Either being non-loopback returns 403. Every hit logs at INFO so accidentally-leaking installs
 * are obvious in production.
 */
object DebugRoutes {

  private val DefaultLimit = 50
  private val MaxLimit     = 500

  def routes(
      enabled: Boolean,
      deviceRepo: DeviceRepo,
      connEventRepo: ConnectionEventRepo,
      timeUsageRepo: TimeUsageRepo,
      clock: Clock,
  ): Routes[Any, Response] =
    if !enabled then Routes.empty
    else
      Routes(
        Method.GET / "api" / "debug" / "devices"    -> handler { (req: Request) =>
          guardLoopback(req, "/api/debug/devices") {
            deviceRepo.listAll
              .mapBoth(
                ErrorMapper.dbErrorToResponse,
                xs => Response.json(xs.toJson),
              )
          }
        },
        Method.GET / "api" / "debug" / "events"     -> handler { (req: Request) =>
          guardLoopback(req, "/api/debug/events") {
            val limit = req.url
              .queryParam("limit")
              .flatMap(_.toIntOption)
              .map(_.max(1).min(MaxLimit))
              .getOrElse(DefaultLimit)
            connEventRepo
              .recent(limit)
              .mapBoth(
                ErrorMapper.dbErrorToResponse,
                xs => Response.json(xs.toJson),
              )
          }
        },
        Method.GET / "api" / "debug" / "time_usage" -> handler { (req: Request) =>
          guardLoopback(req, "/api/debug/time_usage") {
            for {
              today <- clock.today
              snap  <- timeUsageRepo
                .snapshotAll(today)
                .mapError(ErrorMapper.dbErrorToResponse)
            } yield Response.json(
              snap
                .map { case ((mac, domain), mins) =>
                  TimeUsageRow(mac, domain, today.toString, mins)
                }
                .toList
                .toJson,
            )
          }
        },
      )

  private case class TimeUsageRow(mac: String, domain: String, date: String, minutesUsed: Int)
      derives JsonCodec

  /**
   * Reject non-loopback callers. We check both the connection's remote address (when zio-http
   * surfaces one) and the `Host` request header. If either signal looks non-loopback, refuse. Logs
   * every hit (allowed or refused) at INFO so that an accidentally-enabled debug build is loud in
   * production logs.
   */
  private def guardLoopback[A](req: Request, path: String)(
      inner: IO[Response, Response],
  ): IO[Response, Response] = {
    val remoteOk  = req.remoteAddress.forall(_.isLoopbackAddress)
    // Read the raw Host header value via `headerOrFail` — zio-http's typed
    // Header.Host parser is strict and rejects "::1" / "[::1]". For a
    // loopback gate we want the raw text so we can normalize it ourselves.
    val hostRaw   = req.headers.get("Host").map(_.trim)
    val hostOk    = hostRaw.exists(isLoopbackHost)
    val remoteStr = req.remoteAddress.map(_.getHostAddress).getOrElse("unknown")
    val hostStr   = hostRaw.getOrElse("unknown")
    if remoteOk && hostOk then
      ZIO.logInfo(s"debug endpoint hit: path=$path remote=$remoteStr host=$hostStr") *> inner
    else
      ZIO.logWarning(
        s"debug endpoint refused (non-loopback): path=$path remote=$remoteStr host=$hostStr",
      ) *>
        ZIO.fail(Response.status(Status.Forbidden))
  }

  private def isLoopbackHost(raw: String): Boolean = {
    // Host header may be:  "localhost", "127.0.0.1", "127.0.0.1:8080",
    // "[::1]", "[::1]:8080", or "::1" (technically invalid but tolerated).
    val s        = raw.trim.toLowerCase
    val stripped =
      if s.startsWith("[") then
        // bracketed IPv6 — drop "[", then drop everything from "]" on
        s.drop(1).takeWhile(_ != ']')
      else if s.count(_ == ':') == 1 then
        // single colon → host:port (IPv4 or hostname)
        s.takeWhile(_ != ':')
      else
        // bare IPv6 like "::1" — keep as-is
        s
    stripped == "localhost" || stripped == "127.0.0.1" || stripped == "::1"
  }
}
