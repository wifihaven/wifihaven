package familydns.api.routes

import familydns.api.db.*
import familydns.shared.*
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*

import java.time.{Instant, ZoneOffset}
import java.util.UUID

/**
 * Router-side ingest endpoints. See docs/architecture-openwrt.md §5.4 / §5.5.
 *   - `POST /api/router/usage` — periodic 5-minute traffic + time rollups.
 *   - `POST /api/router/events` — DHCP lease + first-seen-MAC + connection_attempt events.
 *
 * Both require a router bearer token resolved via [[RouterAuth]].
 */
object RouterIngestRoutes:

  def routes(
      auth: RouterAuth,
      routerRepo: RouterRepo,
      trafficRepo: TrafficReportRepo,
      timeUsageRepo: TimeUsageRepo,
      deviceRepo: DeviceRepo,
      connEventRepo: ConnectionEventRepo,
  ): Routes[Any, Response] =
    Routes(
      Method.POST / "api" / "router" / "usage"  ->
        handler { (req: Request) =>
          for
            router <- auth.authenticate(req)
            body   <- req.body.asString.orElseFail(Response.badRequest(""))
            rep    <- ZIO
              .fromEither(body.fromJson[UsageReport])
              .mapError(e => Response.badRequest(e))
            _      <- ZIO
              .fail(Response.badRequest("router_id mismatch"))
              .when(rep.routerId != router.id)
            ps     <- parseInstant(rep.periodStart)
            pe     <- parseInstant(rep.periodEnd)
            _ <- handleUsage(router.id, ps, pe, rep.records, trafficRepo, timeUsageRepo, deviceRepo)
            _ <- routerRepo.touch(router.id, None).orElseFail(Response.internalServerError(""))
          yield Response.ok
        },
      Method.POST / "api" / "router" / "events" ->
        handler { (req: Request) =>
          for
            router <- auth.authenticate(req)
            body   <- req.body.asString.orElseFail(Response.badRequest(""))
            rep    <- ZIO
              .fromEither(body.fromJson[RouterEventsRequest])
              .mapError(e => Response.badRequest(e))
            _      <- ZIO
              .fail(Response.badRequest("router_id mismatch"))
              .when(rep.routerId != router.id)
            _      <- handleEvents(router.id, rep.events, deviceRepo, connEventRepo)
            _      <- routerRepo.touch(router.id, None).orElseFail(Response.internalServerError(""))
          yield Response.ok
        },
    )

  private def parseInstant(s: String): IO[Response, Instant] =
    ZIO.attempt(Instant.parse(s)).orElseFail(Response.badRequest(s"invalid timestamp: $s"))

  private def handleUsage(
      routerId: UUID,
      periodStart: Instant,
      periodEnd: Instant,
      records: List[UsageRecord],
      trafficRepo: TrafficReportRepo,
      timeUsageRepo: TimeUsageRepo,
      deviceRepo: DeviceRepo,
  ): IO[Response, Unit] =
    val date    = periodStart.atZone(ZoneOffset.UTC).toLocalDate
    val inserts = records.map(r =>
      TrafficReportInsert(
        routerId,
        r.mac,
        r.ip,
        r.hostname,
        date,
        periodStart,
        periodEnd,
        r.activeSeconds.toInt,
        r.bytesIn,
        r.bytesOut,
      ),
    )
    for
      // Idempotency: ON CONFLICT DO NOTHING returns the count of NEW rows.
      // Only those rows should drive time_usage / device updates; replays return 0.
      newCount <- trafficRepo.insertBatch(inserts).orElseFail(Response.internalServerError(""))
      _        <- ZIO.when(newCount > 0)(
        applyDelta(routerId, periodEnd, records, timeUsageRepo, deviceRepo),
      )
    yield ()

  /**
   * Apply seconds/byte deltas + device last_seen for a freshly-accepted batch. We only enter here
   * when at least one row was inserted; for partial overlap (rare retry edge case) we accept that
   * the new rows' increments fold in but already-stored ones don't double-count because the
   * traffic_reports unique key blocked them.
   *
   * NOTE: a true partial-overlap retry would still double-count the *new* records' time_usage
   * because we can't tell from the batch insert which sub-rows were new. This is acceptable: the
   * agent's contract is to retry the whole batch unchanged, so partial overlap shouldn't happen in
   * practice.
   */
  private def applyDelta(
      @scala.annotation.unused routerId: UUID,
      periodEnd: Instant,
      records: List[UsageRecord],
      timeUsageRepo: TimeUsageRepo,
      deviceRepo: DeviceRepo,
  ): IO[Response, Unit] =
    val date = periodEnd.atZone(ZoneOffset.UTC).toLocalDate
    ZIO.foreachDiscard(records) { r =>
      timeUsageRepo
        .incrementSecondsAndBytes(r.mac, r.hostname, date, r.activeSeconds, r.bytesIn, r.bytesOut)
        .orElseFail(Response.internalServerError(""))
    } *>
      // For each unique mac in the batch, touch last_seen on the existing row (no-op if unknown).
      ZIO.foreachDiscard(records.map(r => (r.mac, r.ip)).distinct) { (mac, ip) =>
        deviceRepo
          .touchLastSeen(mac, ip, periodEnd)
          .orElseFail(Response.internalServerError(""))
          .unit
      }

  private def handleEvents(
      routerId: UUID,
      events: List[RouterEvent],
      deviceRepo: DeviceRepo,
      connEventRepo: ConnectionEventRepo,
  ): IO[Response, Unit] =
    val connInserts = events.collect {
      case e if e.`type` == "connection_attempt" =>
        for
          h    <- e.hostname.toRight("connection_attempt missing hostname")
          ts   <- scala.util.Try(Instant.parse(e.ts)).toEither.left.map(_.getMessage)
          allw <- e.allowed.toRight("connection_attempt missing allowed")
          rsn = e.reason.getOrElse(if allw then "allow" else "blocked")
        yield ConnectionEventInsert(routerId, e.mac, h, e.destIp, allw, rsn, ts)
    }
    for
      // Surface JSON validation errors as 400.
      validated <- ZIO.foreach(connInserts)(e =>
        ZIO.fromEither(e).mapError(m => Response.badRequest(m)),
      )
      _         <- connEventRepo
        .insertBatch(validated)
        .orElseFail(Response.internalServerError(""))
        .when(validated.nonEmpty)
      _         <- ZIO.foreachDiscard(events)(applyDhcpOrFirstSeen(_, deviceRepo))
    yield ()

  /**
   * DHCP lease + first-seen-MAC: upsert into devices. For a known MAC just refresh last_seen_ip /
   * last_seen_at (preserve profile). For an unknown MAC create a row with NULL profile_id and the
   * supplied hostname (or "unknown").
   */
  private def applyDhcpOrFirstSeen(
      e: RouterEvent,
      deviceRepo: DeviceRepo,
  ): IO[Response, Unit] =
    if e.`type` != "dhcp_lease" && e.`type` != "first_seen_mac" then ZIO.unit
    else
      e.mac match
        case None      => ZIO.unit
        case Some(mac) =>
          for
            ts       <- ZIO
              .attempt(Instant.parse(e.ts))
              .orElseFail(Response.badRequest(s"invalid ts: ${e.ts}"))
            existing <- deviceRepo.findByMac(mac).orElseFail(Response.internalServerError(""))
            _        <- existing match
              case Some(_) =>
                deviceRepo
                  .touchLastSeen(mac, e.ip, ts)
                  .orElseFail(Response.internalServerError(""))
                  .unit
              case None    =>
                deviceRepo
                  .upsertUnknown(mac, e.hostname.getOrElse("unknown"), e.ip, ts)
                  .orElseFail(Response.internalServerError(""))
                  .unit
          yield ()
