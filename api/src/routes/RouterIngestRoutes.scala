package familydns.api.routes

import familydns.api.db.*
import familydns.shared.*
import familydns.shared.types.*
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*

import java.time.{Instant, ZoneOffset}

/**
 * Router-side ingest endpoints. See docs/architecture-openwrt.md §5.4 / §5.5.
 *   - `POST /api/router/usage` — periodic 5-minute traffic + time rollups.
 *   - `POST /api/router/events` — DHCP lease + first-seen-MAC + connection_attempt events.
 *
 * Both require a router bearer token resolved via [[RouterAuth]].
 */
object RouterIngestRoutes {

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
          for {
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
            _      <- ZIO.logDebug(
              s"router usage: router=${router.id} period=$ps..$pe records=${rep.records.size}",
            )
            _      <- ZIO.foreachDiscard(rep.records)(r =>
              ZIO.logDebug(
                s"  usage record: mac=${r.mac} ip=${r.ip.getOrElse("-")} " +
                  s"host=${r.host.value} secs=${r.activeSeconds} bIn=${r.bytesIn} bOut=${r.bytesOut}",
              ),
            )
            _ <- handleUsage(router.id, ps, pe, rep.records, trafficRepo, timeUsageRepo, deviceRepo)
            _ <- routerRepo.touch(router.id, None).mapError(ErrorMapper.dbErrorToResponse)
          } yield Response.ok
        },
      Method.POST / "api" / "router" / "events" ->
        handler { (req: Request) =>
          val handle = for {
            router <- auth.authenticate(req)
            body   <- req.body.asString.orElseFail(Response.badRequest(""))
            rep    <- ZIO
              .fromEither(body.fromJson[RouterEventsRequest])
              .tapError(e =>
                ZIO.logWarning(
                  s"router events: deserialization failed for router=${router.id} bodyLen=${body.length} err=$e",
                ),
              )
              .mapError(e => Response.badRequest(e))
            _      <- ZIO
              .logWarning(
                s"router events: routerId mismatch token=${router.id} body=${rep.routerId}",
              )
              .zipRight(ZIO.fail(Response.badRequest("router_id mismatch")))
              .when(rep.routerId != router.id)
            _      <- ZIO.logInfo(
              s"router events: router=${router.id} batchSize=${rep.events.size}",
            )
            _      <- ZIO.foreachDiscard(rep.events)(e =>
              ZIO.logDebug(
                s"  event: type=${e.`type`} mac=${e.mac.getOrElse("-")} " +
                  s"ip=${e.ip.getOrElse("-")} host=${e.host.map(_.value).orElse(e.hostname.map(_.value)).getOrElse("-")} " +
                  s"destIp=${e.destIp.getOrElse("-")} allowed=${e.allowed.map(_.toString).getOrElse("-")} " +
                  s"reason=${e.reason.getOrElse("-")} ts=${e.ts}",
              ),
            )
            _      <- handleEvents(router.id, rep.events, deviceRepo, connEventRepo)
            _      <- routerRepo.touch(router.id, None).mapError(ErrorMapper.dbErrorToResponse)
          } yield Response.ok
          handle.tapError(r => ZIO.logInfo(s"router events: returning status=${r.status.code}"))
        },
    )

  private def parseInstant(s: String): IO[Response, Instant] =
    ZIO.attempt(Instant.parse(s)).orElseFail(Response.badRequest(s"invalid timestamp: $s"))

  private def handleUsage(
      routerId: RouterId,
      periodStart: Instant,
      periodEnd: Instant,
      records: List[UsageRecord],
      trafficRepo: TrafficReportRepo,
      timeUsageRepo: TimeUsageRepo,
      deviceRepo: DeviceRepo,
  ): IO[Response, Unit] = {
    val date    = periodStart.atZone(ZoneOffset.UTC).toLocalDate
    val inserts = records.map(r =>
      TrafficReportInsert(
        routerId,
        r.mac,
        r.ip,
        r.host,
        date,
        periodStart,
        periodEnd,
        r.activeSeconds.toInt,
        r.bytesIn,
        r.bytesOut,
      ),
    )
    for {
      // Idempotency: ON CONFLICT DO NOTHING returns the count of NEW rows.
      // Only those rows should drive time_usage / device updates; replays return 0.
      newCount <- trafficRepo.insertBatch(inserts).mapError(ErrorMapper.dbErrorToResponse)
      _        <- ZIO.when(newCount > 0)(
        applyDelta(routerId, periodEnd, records, timeUsageRepo, deviceRepo),
      )
    } yield ()
  }

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
      @scala.annotation.unused routerId: RouterId,
      periodEnd: Instant,
      records: List[UsageRecord],
      timeUsageRepo: TimeUsageRepo,
      deviceRepo: DeviceRepo,
  ): IO[Response, Unit] = {
    val date    = periodEnd.atZone(ZoneOffset.UTC).toLocalDate
    // A batch carries one record per (mac, dst_ip) but time_usage is keyed
    // by (mac, hostname, date), and activeSeconds is the bucket duration
    // (the 5-min window — same value on every record that saw bytes>0). Two
    // records with the same (mac, hostname) describe the *same* window of
    // active time, not two windows back-to-back, so the seconds delta is
    // the max of activeSeconds, not the sum. Bytes still sum because they
    // count distinct flows.
    val grouped = records
      .groupBy(r => (r.mac, r.host))
      .view
      .mapValues { rs =>
        (
          rs.map(_.activeSeconds).maxOption.getOrElse(0L),
          rs.map(_.bytesIn).sum,
          rs.map(_.bytesOut).sum,
        )
      }
      .toList
    ZIO.foreachDiscard(grouped) { case ((mac, host), (secs, bIn, bOut)) =>
      timeUsageRepo
        .incrementSecondsAndBytes(mac, host, date, secs, bIn, bOut)
        .mapError(ErrorMapper.dbErrorToResponse)
    } *>
      // For each unique mac in the batch, touch last_seen on the existing row (no-op if unknown).
      ZIO.foreachDiscard(records.map(r => (r.mac, r.ip)).distinct) { (mac, ip) =>
        deviceRepo
          .touchLastSeen(mac, ip, periodEnd)
          .mapError(ErrorMapper.dbErrorToResponse)
          .unit
      }
  }

  private def handleEvents(
      routerId: RouterId,
      events: List[RouterEvent],
      deviceRepo: DeviceRepo,
      connEventRepo: ConnectionEventRepo,
  ): IO[Response, Unit] = {
    val connInserts = events.collect {
      case e if e.`type` == "connection_attempt" =>
        for {
          h    <- e.host.toRight("connection_attempt missing host")
          ts   <- scala.util.Try(Instant.parse(e.ts)).toEither.left.map(_.getMessage)
          allw <- e.allowed.toRight("connection_attempt missing allowed")
          rsn = e.reason.getOrElse(if allw then "allow" else "blocked")
        } yield ConnectionEventInsert(routerId, e.mac, h, e.destIp, allw, rsn, ts)
    }
    for {
      // Surface JSON validation errors as 400.
      validated <- ZIO.foreach(connInserts)(e =>
        ZIO.fromEither(e).mapError(m => Response.badRequest(m)),
      )
      _         <- connEventRepo
        .insertBatch(validated)
        .mapError(ErrorMapper.dbErrorToResponse)
        .when(validated.nonEmpty)
      _         <- ZIO.foreachDiscard(events)(applyDhcpOrFirstSeen(_, deviceRepo))
    } yield ()
  }

  /**
   * Default name for a device whose DHCP lease did not provide a hostname (option 12) — e.g. iOS
   * Private Address clients. The last 3 octets of the MAC (lowercase, no separators) keeps the
   * placeholder disambiguable in the admin UI rather than collapsing every such device into the
   * literal string "unknown" (#249).
   */
  private[routes] def autoGeneratedName(mac: String): String = {
    val hex = mac.toLowerCase.replace(":", "").replace("-", "")
    "device-" + (if hex.length >= 6 then hex.takeRight(6) else hex)
  }

  /**
   * DHCP lease + first-seen-MAC: upsert into devices. For a known MAC refresh last_seen_ip /
   * last_seen_at (preserve profile) and, on a dhcp_lease with a real hostname, also upgrade the
   * name if it is still an auto-generated placeholder (#249 race: first_seen_mac landed before
   * dnsmasq wrote the lease, so the row got named "unknown" / "device-XXYYZZ"). For an unknown MAC,
   * create a row with NULL profile_id and the supplied hostname (or autoGeneratedName(mac)).
   */
  private def applyDhcpOrFirstSeen(
      e: RouterEvent,
      deviceRepo: DeviceRepo,
  ): IO[Response, Unit] =
    if e.`type` != "dhcp_lease" && e.`type` != "first_seen_mac" then ZIO.unit
    else
      e.mac match {
        case None      => ZIO.unit
        case Some(mac) =>
          for {
            ts       <- ZIO
              .attempt(Instant.parse(e.ts))
              .orElseFail(Response.badRequest(s"invalid ts: ${e.ts}"))
            existing <- deviceRepo.findByMac(mac).mapError(ErrorMapper.dbErrorToResponse)
            _        <- existing match {
              case Some(_) =>
                deviceRepo
                  .touchLastSeen(mac, e.ip, ts)
                  .mapError(ErrorMapper.dbErrorToResponse)
                  .unit *>
                  ZIO
                    .foreachDiscard(e.hostname)(h =>
                      deviceRepo
                        .renameIfAutoGenerated(mac, h.value)
                        .mapError(ErrorMapper.dbErrorToResponse),
                    )
              case None    =>
                deviceRepo
                  .upsertUnknown(
                    mac,
                    e.hostname.map(_.value).getOrElse(autoGeneratedName(mac.value)),
                    e.ip,
                    ts,
                  )
                  .mapError(ErrorMapper.dbErrorToResponse)
                  .unit
            }
          } yield ()
      }
}
