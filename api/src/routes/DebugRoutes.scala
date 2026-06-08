package wifihaven.api.routes

import wifihaven.api.cache.TimeStatusCache
import wifihaven.api.db.*
import wifihaven.shared.Clock
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*

/**
 * Read-only JSON dumps of the underlying tables, intended for diagnosing router/agent ingest issues
 * live (#228). Mounted ONLY when the API is started with `WIFIHAVEN_DEBUG=1`; access is further
 * restricted to loopback callers on the API host.
 *
 * Endpoints: GET /api/debug/devices -> all device rows GET /api/debug/events?limit -> recent
 * connection_events (default 50, max 500) GET /api/debug/time_usage -> today's per-(mac, domain)
 * usage
 *
 * Auth: none. Loopback check uses BOTH `req.remoteAddress` (when present) AND the `Host` header.
 * Either being non-loopback returns 403. Every hit logs at INFO so accidentally-leaking installs
 * are obvious in production.
 *
 * #1570: handlers fail with a typed [[ApiError]] mapped centrally by
 * [[ErrorMapper.errorToResponse]]; the [[wifihaven.api.ErrorBoundary]] logs (4xx WARN / 5xx ERROR)
 * + meters each error. Each case reproduces the EXACT status + body the hand-rolled code produced —
 * the loopback refusal keeps its empty-body 403 via [[ApiError.Wrapped]], DB failures stay 503 via
 * [[ApiError.Db]].
 */
object DebugRoutes {

  private val DefaultLimit = 50
  private val MaxLimit     = 500

  def routes(
      enabled: Boolean,
      deviceRepo: DeviceRepo,
      connEventRepo: ConnectionEventRepo,
      timeUsageRepo: TimeUsageRepo,
      trafficRepo: TrafficReportRepo,
      clock: Clock,
      timeStatusCache: TimeStatusCache = TimeStatusCache.makeUnsafe(),
  ): Routes[Any, Response] =
    if !enabled then Routes.empty
    else
      Routes(
        Method.GET / "api" / "debug" / "devices"                -> handler { (req: Request) =>
          guardLoopback(req, "/api/debug/devices") {
            deviceRepo.listAll
              .mapBoth(
                ApiError.Db(_),
                xs => Response.json(xs.toJson),
              )
          }.mapError(ErrorMapper.errorToResponse)
        },
        Method.GET / "api" / "debug" / "events"                 -> handler { (req: Request) =>
          guardLoopback(req, "/api/debug/events") {
            val limit = req.url
              .queryParam("limit")
              .flatMap(_.toIntOption)
              .map(_.max(1).min(MaxLimit))
              .getOrElse(DefaultLimit)
            connEventRepo
              .recent(limit)
              .mapBoth(
                ApiError.Db(_),
                xs => Response.json(xs.toJson),
              )
          }.mapError(ErrorMapper.errorToResponse)
        },
        Method.GET / "api" / "debug" / "cache-stats"            -> handler { (req: Request) =>
          guardLoopback(req, "/api/debug/cache-stats") {
            timeStatusCache.snapshot.map { s =>
              Response.json(
                CacheStatsResponse(
                  hits = s.hits,
                  misses = s.misses,
                  hitRate = s.hitRate,
                  todaySize = s.todaySize,
                  pastSize = s.pastSize,
                ).toJson,
              )
            }
          }.mapError(ErrorMapper.errorToResponse)
        },
        Method.POST / "api" / "debug" / "cache-stats" / "reset" -> handler { (req: Request) =>
          guardLoopback(req, "/api/debug/cache-stats/reset") {
            timeStatusCache.invalidateAll.as(Response.ok)
          }.mapError(ErrorMapper.errorToResponse)
        },
        Method.GET / "api" / "debug" / "time_usage"             -> handler { (req: Request) =>
          guardLoopback(req, "/api/debug/time_usage") {
            for {
              today <- clock.today
              snap  <- timeUsageRepo
                .snapshotAll(today)
                .mapError(ApiError.Db(_))
              // Per-host minutes from `time_usage` over-count wall-clock time when a
              // single 5-min agent bucket touches multiple hosts (each host row holds
              // ~60s for that bucket, summing them inflates "online minutes"). Surface
              // the bucket-deduplicated per-mac total alongside each row so callers
              // measuring device-online time can read a single value instead of summing.
              // (#474)
              macs = snap.keys.map(_._1).toList.distinct
              presence <- trafficRepo
                .listPresenceRows(macs, today)
                .mapError(ApiError.Db(_))
              // Surface raw active-seconds (sum of max-per-bucket activeSeconds) as well as
              // the floor-divided minute count. The e2e D2 minute-granularity test (#516)
              // ceil-divides this to get tight bounds; bucket-counting via floor(/60) drifts
              // when activity straddles 5-min agent buckets.
              totalSecs = wifihaven.api.presence.Presence
                .totalSecondsByMac(presence, exemptPatterns = Nil)
            } yield Response.json(
              snap
                .map { case ((mac, host), mins) =>
                  val secs = totalSecs.getOrElse(mac, 0L)
                  TimeUsageRow(
                    mac.value,
                    host.value,
                    today.toString,
                    mins,
                    (secs / 60).toInt,
                    secs,
                  )
                }
                .toList
                .toJson,
            )
          }.mapError(ErrorMapper.errorToResponse)
        },
      )

  private case class CacheStatsResponse(
      hits: Long,
      misses: Long,
      hitRate: Double,
      todaySize: Long,
      pastSize: Long,
  ) derives JsonCodec

  private case class TimeUsageRow(
      mac: String,
      host: String,
      date: String,
      minutesUsed: Int,
      // Per-(mac, day) bucket-deduplicated online minutes (Presence-based, #474).
      // Same value on every row for the same mac; callers should take this once
      // per mac rather than summing `minutesUsed` across hosts.
      deviceTotalMinutes: Int,
      // Same as deviceTotalMinutes but expressed as raw seconds (no floor /60).
      // Callers needing tight minute bounds (e.g. e2e D2 test, #516) should
      // ceil-divide this rather than reading deviceTotalMinutes which floors.
      deviceTotalActiveSeconds: Long,
  ) derives JsonCodec

  /**
   * Reject non-loopback callers. We check both the connection's remote address (when zio-http
   * surfaces one) and the `Host` request header. If either signal looks non-loopback, refuse. Logs
   * every hit (allowed or refused) at INFO so that an accidentally-enabled debug build is loud in
   * production logs.
   *
   * #1570: the refusal fails with [[ApiError.Wrapped]] carrying the EXACT empty-body 403 the
   * hand-rolled `Response.status(Status.Forbidden)` produced — `ApiError.Forbidden` would attach a
   * body, so Wrapped preserves the byte-identical response. The hit/refusal INFO/WARN logs here are
   * the loopback-gate audit trail (not error mapping) and stay inline.
   */
  private def guardLoopback(req: Request, path: String)(
      inner: IO[ApiError, Response],
  ): IO[ApiError, Response] = {
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
        ZIO.fail(ApiError.Wrapped(Response.status(Status.Forbidden)))
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
