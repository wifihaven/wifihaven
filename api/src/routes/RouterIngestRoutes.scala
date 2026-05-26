package wifihaven.api.routes

import wifihaven.api.db.*
import wifihaven.api.policy.PolicyService
import wifihaven.shared.*
import wifihaven.shared.types.*
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*

import java.time.{Duration, Instant}

/**
 * Router-side ingest endpoints. See docs/architecture-openwrt.md §5.4 / §5.5.
 *   - `POST /api/router/usage` — periodic ~60 s traffic + time rollups.
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
      alertRepo: AlertRepo,
      householdSettingsRepo: HouseholdSettingsRepo,
  ): Routes[Any, Response] =
    Routes(
      Method.POST / "api" / "router" / "usage"  ->
        handler { (req: Request) =>
          for {
            router   <- auth.authenticate(req)
            body     <- req.body.asString.orElseFail(Response.badRequest(""))
            rep      <- ZIO
              .fromEither(body.fromJson[UsageReport])
              .mapError(e => Response.badRequest(e))
            _        <- ZIO
              .fail(Response.badRequest("router_id mismatch"))
              .when(rep.routerId != router.id)
            ps       <- parseInstant(rep.periodStart)
            pe       <- parseInstant(rep.periodEnd)
            _        <- ZIO.logDebug(
              s"router usage: router=${router.id} period=$ps..$pe records=${rep.records.size}",
            )
            _        <- ZIO.foreachDiscard(rep.records)(r =>
              ZIO.logDebug(
                s"  usage record: mac=${r.mac} ip=${r.ip.getOrElse("-")} " +
                  s"host=${r.host.value} secs=${r.activeSeconds} bIn=${r.bytesIn} bOut=${r.bytesOut}",
              ),
            )
            settings <- householdSettingsRepo.get.mapError(ErrorMapper.dbErrorToResponse)
            _        <- handleUsage(
              router.id,
              ps,
              pe,
              rep.records,
              settings,
              trafficRepo,
              timeUsageRepo,
              deviceRepo,
            )
            _        <- routerRepo.touch(router.id, None).mapError(ErrorMapper.dbErrorToResponse)
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
            _      <- handleEvents(router.id, rep.events, deviceRepo, connEventRepo, alertRepo)
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
      settings: HouseholdSettings,
      trafficRepo: TrafficReportRepo,
      timeUsageRepo: TimeUsageRepo,
      deviceRepo: DeviceRepo,
  ): IO[Response, Unit] = {
    // #1010: bucket usage by the household's logical "today" (TZ + non-midnight
    // reset_time), matching the read-side date used by PolicyService.snapshot.
    val date    = PolicyService.householdLocalDate(periodStart, settings)
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
        applyDelta(routerId, periodEnd, records, settings, timeUsageRepo, deviceRepo),
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
      settings: HouseholdSettings,
      timeUsageRepo: TimeUsageRepo,
      deviceRepo: DeviceRepo,
  ): IO[Response, Unit] = {
    // #1010: bucket time_usage by the household's logical "today" so the
    // cap-tracking read path (which uses the same household-local date) lines
    // up with the rows we wrote.
    val date      = PolicyService.householdLocalDate(periodEnd, settings)
    // A batch carries one record per (mac, dst_ip) but time_usage is keyed
    // by (mac, hostname, date), and activeSeconds is the bucket duration
    // (the report window, ~60 s — same value on every record that saw bytes>0). Two
    // records with the same (mac, hostname) describe the *same* window of
    // active time, not two windows back-to-back, so the seconds delta is
    // the max of activeSeconds, not the sum. Bytes still sum because they
    // count distinct flows.
    // Per-mac aggregates needed for #715 proportional attribution: bucket duration
    // (one batch == one period, so max activeSeconds across the mac is the bucket
    // duration) and total bytes across the mac in this batch (denominator for the
    // bytes-share weight).
    val perMacAgg = records
      .groupBy(_.mac)
      .view
      .mapValues { rs =>
        val bucketSecs = rs.map(_.activeSeconds).maxOption.getOrElse(0L)
        val totalBytes = rs.map(r => r.bytesIn + r.bytesOut).sum
        (bucketSecs, totalBytes)
      }
      .toMap
    val grouped   = records
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
      // #715: bytes-share weighted attribution within the batch. `bucketSecs` is
      // the wall-clock duration of the bucket; the share is this host's
      // (bytes_in + bytes_out) over the mac's total bytes in the batch. When the
      // mac has zero bytes (shouldn't happen — the agent only emits a record
      // when it saw bytes>0), fall back to crediting full bucket seconds so the
      // row still moves and matches `seconds_used`.
      val (bucketSecs, totalBytes) = perMacAgg.getOrElse(mac, (secs, bIn + bOut))
      val hostBytes                = bIn + bOut
      val proportionalSecs         =
        if (totalBytes > 0L) (bucketSecs.toDouble * hostBytes.toDouble / totalBytes.toDouble).round
        else bucketSecs
      timeUsageRepo
        .incrementSecondsAndBytes(mac, host, date, secs, bIn, bOut, proportionalSecs)
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

  /**
   * #720: how far back the API will look for a sibling fqdn-typed event to attribute an
   * ipv4/ipv6-typed event to. Matches the spirit of #583 Option 4 — a small race window, not a
   * long-running IP→host cache. Five minutes covers the conntrack/dns-tail jitter (and any client
   * DNS TTL skew) without inviting attribution across reused-IP cloud endpoints.
   */
  private val fqdnBackfillWindow: Duration = Duration.ofMinutes(5)

  private def handleEvents(
      routerId: RouterId,
      events: List[RouterEvent],
      deviceRepo: DeviceRepo,
      connEventRepo: ConnectionEventRepo,
      alertRepo: AlertRepo,
  ): IO[Response, Unit] = {
    val connInserts = events.collect {
      case e if e.`type` == "connection_attempt" =>
        for {
          h    <- e.host.toRight("connection_attempt missing host")
          ts   <- scala.util.Try(Instant.parse(e.ts)).toEither.left.map(_.getMessage)
          allw <- e.allowed.toRight("connection_attempt missing allowed")
          rsn = e.reason.getOrElse(if allw then "allow" else "blocked")
        } yield ConnectionEventInsert(routerId, e.mac, h, e.destIp, allw, rsn, ts, e.eventId)
    }
    for {
      // Surface JSON validation errors as 400.
      validated <- ZIO.foreach(connInserts)(e =>
        ZIO.fromEither(e).mapError(m => Response.badRequest(m)),
      )
      // #720: for each ipv4/ipv6-typed insert with a dest_ip, consult the
      // existing connection_events for a sibling fqdn-typed event we can
      // attribute it to. This handles the "fqdn observed first, ipv4 race-
      // loser arrives later" arrival order at insert time.
      enriched  <- ZIO.foreach(validated)(attachResolvedHost(_, connEventRepo))
      // #338: ON CONFLICT DO NOTHING on event_id dedups replays from the
      // retry queue (#330). Insert returns count of new rows; the diff is
      // duplicates collapsed on conflict.
      inserted  <- connEventRepo
        .insertBatch(enriched)
        .mapError(ErrorMapper.dbErrorToResponse)
        .when(enriched.nonEmpty)
        .map(_.getOrElse(0))
      _         <- ZIO
        .logInfo(
          s"router events: router=$routerId dedup'd ${enriched.size - inserted} of " +
            s"${enriched.size} connection_attempt events (replay)",
        )
        .when(inserted < enriched.size)
      // #720: for each fqdn-typed event we just persisted, patch prior
      // unresolved ipv4/ipv6 rows in the same window. This handles the
      // reverse arrival order ("ipv4 race-loser observed first, fqdn lands
      // moments later").
      _         <- ZIO.foreachDiscard(enriched)(backfillFromFqdn(_, connEventRepo))
      _         <- ZIO.foreachDiscard(events)(applyDhcpOrFirstSeen(_, deviceRepo, alertRepo))
    } yield ()
  }

  private def attachResolvedHost(
      ev: ConnectionEventInsert,
      connEventRepo: ConnectionEventRepo,
  ): IO[Response, ConnectionEventInsert] =
    (ev.host, ev.destIp) match {
      case (HostId.IPv4(_) | HostId.IPv6(_), Some(destIp)) =>
        connEventRepo
          .findRecentFqdnFor(ev.routerId, destIp, ev.ts.minus(fqdnBackfillWindow))
          .mapError(ErrorMapper.dbErrorToResponse)
          .map(h => ev.copy(resolvedHost = h))
      case _                                               => ZIO.succeed(ev)
    }

  private def backfillFromFqdn(
      ev: ConnectionEventInsert,
      connEventRepo: ConnectionEventRepo,
  ): IO[Response, Unit] =
    (ev.host, ev.destIp) match {
      case (HostId.Fqdn(name), Some(destIp)) =>
        connEventRepo
          .backfillResolvedFor(ev.routerId, destIp, name, ev.ts.minus(fqdnBackfillWindow))
          .mapError(ErrorMapper.dbErrorToResponse)
          .unit
      case _                                 => ZIO.unit
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
      alertRepo: AlertRepo,
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
                // #711: a brand-new MAC. Insert the device row, then raise a
                // notification for the admin. The alert repo is idempotent on
                // `mac`, so a router replaying the same event won't resurrect
                // a previously-dismissed alert.
                deviceRepo
                  .upsertUnknown(
                    mac,
                    e.hostname.map(_.value).getOrElse(autoGeneratedName(mac.value)),
                    e.ip,
                    ts,
                  )
                  .mapError(ErrorMapper.dbErrorToResponse)
                  .unit *>
                  alertRepo
                    .raiseNewDevice(mac, ts)
                    .mapError(ErrorMapper.dbErrorToResponse)
            }
          } yield ()
      }
}
