package wifihaven.api.routes

import wifihaven.api.db.*
import wifihaven.api.metrics.AppMetrics
import wifihaven.api.observability.LogContext
import wifihaven.api.policy.PolicyService
import wifihaven.shared.*
import wifihaven.shared.types.*
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*
import zio.json.ast.Json

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
          // #1570: fail with a typed ApiError; ErrorMapper.errorToResponse maps it and the
          // ErrorBoundary logs (4xx WARN / 5xx ERROR) + meters. This subsumes the bespoke
          // envelope/timestamp decode logging #1574 added for #1569 — the boundary now logs the 400
          // once, with the response-body snippet (the zio-json error names the failing field), so
          // the diagnostic gap stays closed without a second emitter.
          //
          // The per-RECORD skip path (below) is kept from #1574 and is NOT redundant with the
          // boundary: a batch carrying a bad record still returns 200, so the boundary never sees
          // it — the skip is logged + metered inline here.
          // #602: route/method are on the MDC via LoggingMiddleware. We add
          // `routerId` to the whole post-auth scope so every log emitted from
          // here (per-record skip warn, per-batch debug, per-record debug dump)
          // inherits the same router context without re-wrapping.
          val handle: ZIO[Any, ApiError, Response] =
            auth.authenticate(req).mapError(ApiError.Wrapped(_)).flatMap { router =>
              LogContext.annotate(LogContext.RouterId, router.id.toString) {
                for {
                  body <- req.body.asString.orElseFail(ApiError.BadRequest(""))
                  // #1569: decode the *envelope* (routerId, periodStart, periodEnd) + `records` as a
                  // raw JSON array, deferring per-record validation. A genuinely unparseable
                  // envelope (bad routerId/timestamps, not an object, `records` not an array,
                  // truncated body) still 400s — logged by the boundary.
                  raw  <- ZIO
                    .fromEither(body.fromJson[RawUsageReport])
                    .mapError(ApiError.DecodeFailure(_))
                  _    <- ZIO
                    .fail(ApiError.BadRequest("router_id mismatch"))
                    .when(raw.routerId != router.id)
                  ps   <- parseInstant(raw.periodStart)
                  pe   <- parseInstant(raw.periodEnd)
                  // #1569: decode each record individually. A single malformed record
                  // (e.g. a host value that fails Hostname validation — an Akamai/CDN
                  // CNAME target with underscores, see #1572) used to fail the WHOLE
                  // batch, dropping every valid record with it. Now the bad records are
                  // skipped, logged (bounded), and metered, and the valid ones ingest.
                  decoded  = raw.records.zipWithIndex.map((j, i) => (i, j.as[UsageRecord]))
                  rejected = decoded.collect { case (i, Left(err)) => (i, err) }
                  records  = decoded.collect { case (_, Right(r)) => r }
                  _        <- ZIO.foreachDiscard(rejected) { (i, err) =>
                    ZIO.logWarning(
                      s"router usage: skipping malformed record[$i] for router=${router.id}: $err",
                    )
                  }
                  _        <- AppMetrics.recordUsageRecordsRejected(rejected.size)
                  _        <- LogContext.annotateAll(
                    LogContext.BatchSize -> records.size.toString,
                    LogContext.Rejected  -> rejected.size.toString,
                  ) {
                    ZIO.logDebug(
                      s"router usage: router=${router.id} period=$ps..$pe records=${records.size} " +
                        s"rejected=${rejected.size}",
                    )
                  }
                  _        <- ZIO.foreachDiscard(records)(r =>
                    ZIO.logDebug(
                      s"  usage record: mac=${r.mac} ip=${r.ip.getOrElse("-")} " +
                        s"host=${r.host.value} secs=${r.activeSeconds} bIn=${r.bytesIn} bOut=${r.bytesOut}",
                    ),
                  )
                  settings <- householdSettingsRepo.get.mapError(ApiError.Db(_))
                  _        <- handleUsage(
                    router.id,
                    ps,
                    pe,
                    records,
                    settings,
                    trafficRepo,
                    timeUsageRepo,
                    deviceRepo,
                    connEventRepo,
                  )
                  _        <- routerRepo.touch(router.id, None, None).mapError(ApiError.Db(_))
                } yield Response.ok
              }
            }
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.POST / "api" / "router" / "events" ->
        handler { (req: Request) =>
          // #1570: typed errors + central mapping. The bespoke decode `logWarning`, the
          // routerId-mismatch `logWarning`, and the trailing "returning status=" `logInfo` are
          // gone — the ErrorBoundary now logs every 4xx (incl. this decode 400) at WARN with the
          // response-body snippet, so logging is uniform with the usage route (the #1569 dedup:
          // one emitter, not two).
          // #602: route/method via LoggingMiddleware; `routerId` annotates the
          // whole post-auth scope so every log here inherits it without re-wrapping.
          val handle: ZIO[Any, ApiError, Response] =
            auth.authenticate(req).mapError(ApiError.Wrapped(_)).flatMap { router =>
              LogContext.annotate(LogContext.RouterId, router.id.toString) {
                for {
                  body <- req.body.asString.orElseFail(ApiError.BadRequest(""))
                  // #1126: tolerate an empty/blank body as a no-op batch. A truncated
                  // or empty POST (network blip, retry-queue edge) used to fail
                  // RouterEventsRequest decoding with "Unexpected end of input" and
                  // 400, spamming the warn log and dropping the (harmless) batch. An
                  // empty events POST carries nothing to persist, so treat it as an
                  // accepted no-op rather than an error. Genuinely malformed non-empty
                  // bodies still 400 + warn (and the agent emitter no longer
                  // produces them — see conntrack.encode_events_body).
                  rep  <-
                    if body.trim.isEmpty then
                      ZIO.logDebug(
                        s"router events: empty body from router=${router.id}; treating as no-op batch",
                      ) *> ZIO.succeed(RouterEventsRequest(router.id, Nil))
                    else
                      ZIO
                        .fromEither(body.fromJson[RouterEventsRequest])
                        .mapError(ApiError.DecodeFailure(_))
                  _    <- ZIO
                    .fail(ApiError.BadRequest("router_id mismatch"))
                    .when(rep.routerId != router.id)
                  _    <- LogContext.annotate(LogContext.BatchSize, rep.events.size.toString) {
                    ZIO.logDebug(
                      s"router events: router=${router.id} batchSize=${rep.events.size}",
                    )
                  }
                  _    <- ZIO.foreachDiscard(rep.events)(e =>
                    ZIO.logDebug(
                      s"  event: type=${e.`type`} mac=${e.mac.getOrElse("-")} " +
                        s"ip=${e.ip.getOrElse("-")} host=${e.host.map(_.value).orElse(e.hostname.map(_.value)).getOrElse("-")} " +
                        s"destIp=${e.destIp.getOrElse("-")} allowed=${e.allowed.map(_.toString).getOrElse("-")} " +
                        s"reason=${e.reason.getOrElse("-")} ts=${e.ts}",
                    ),
                  )
                  _    <- handleEvents(router.id, rep.events, deviceRepo, connEventRepo, alertRepo)
                  _    <- routerRepo.touch(router.id, None, None).mapError(ApiError.Db(_))
                } yield Response.ok
              }
            }
          handle.mapError(ErrorMapper.errorToResponse)
        },
    )

  /**
   * #1569: the wire-decode envelope for `POST /api/router/usage`. Identical field shape to
   * [[UsageReport]] except `records` is held as a raw JSON array so each element can be decoded
   * into a [[UsageRecord]] individually — a single malformed record (e.g. a host value that fails
   * `Hostname` validation, see #1572) is then skipped + logged + metered instead of 400-ing the
   * whole batch and dropping every valid record with it. The envelope itself (routerId, timestamps,
   * records-is-an-array) must still parse or the request is a genuine 400. Decode-only; this is a
   * server-side parse aid, NOT a wire-contract type — unknown fields are still ignored.
   */
  private case class RawUsageReport(
      routerId: RouterId,
      periodStart: String,
      periodEnd: String,
      records: List[Json],
  ) derives JsonDecoder

  // #1570: a bad timestamp is a typed BadRequest (400) mapped centrally; the boundary logs it.
  // (The bespoke per-call warn log #1574 added is subsumed by the boundary — single emitter.)
  private def parseInstant(s: String): IO[ApiError, Instant] =
    ZIO.attempt(Instant.parse(s)).orElseFail(ApiError.BadRequest(s"invalid timestamp: $s"))

  private def handleUsage(
      routerId: RouterId,
      periodStart: Instant,
      periodEnd: Instant,
      records: List[UsageRecord],
      settings: HouseholdSettings,
      trafficRepo: TrafficReportRepo,
      timeUsageRepo: TimeUsageRepo,
      deviceRepo: DeviceRepo,
      connEventRepo: ConnectionEventRepo,
  ): IO[ApiError, Unit] = {
    // #1010: bucket usage by the household's logical "today" (TZ + non-midnight
    // reset_time), matching the read-side date used by PolicyService.snapshot.
    val date         = PolicyService.householdLocalDate(periodStart, settings)
    // #864: a healthy agent never reports a row with no bytes and no active
    // seconds. The read side already filters these (listRawInRange), so they're
    // dead weight; count them here at ingest so a silent return of the #858 agent
    // regression shows up as a rising rate rather than a per-request warn-log.
    val zeroByteRows =
      records.count(r => r.activeSeconds == 0L && r.bytesIn == 0L && r.bytesOut == 0L)
    for {
      _ <- AppMetrics.recordZeroByteFiltered(zeroByteRows)
      // #1585: write-time FQDN backfill. The router's per-(mac, dst_ip)
      // accumulator emits records whose host is the resolved fqdn when DNS
      // attribution won the race, but ipv4/ipv6 when it lost (DoH, hard-coded
      // IP, or just an ordering race). For each such race-loser we consult
      // recent fqdn connection_events for the same (router_id, dest_ip) and
      // rewrite the record to its resolved fqdn BEFORE inserting — so the
      // traffic_reports row lands under the human-readable host and the
      // read-side LATERAL join (#730 follow-up cleanup) can eventually go away.
      // #1511: resolve the whole batch's race-loser dest_ips in one round trip,
      // then rewrite each record in memory — collapses the per-record N+1 SELECT
      // that dominated the handler's ~1 s p95.
      candidateIps = records
        .collect {
          case r if r.host.isInstanceOf[HostId.IPv4] || r.host.isInstanceOf[HostId.IPv6] =>
            r.destIp
        }
        .flatten
        .distinct
      fqdnMap <- connEventRepo
        .findRecentFqdnForBatch(routerId, candidateIps, periodStart.minus(fqdnBackfillWindow))
        .mapError(ApiError.Db(_))
      rewritten <- ZIO.foreach(records)(r => backfillRecordWith(fqdnMap, r))
      // After rewrite, two distinct (mac, dst_ip) records can collapse to the
      // same (mac, host) at the same period — the existing primary key
      // (router_id, period_start, mac, host_type, host_value) makes the second
      // INSERT a silent no-op under ON CONFLICT DO NOTHING and the row's bytes
      // would be dropped. Aggregate (sum bytes; max activeSeconds — same merge
      // applyDelta uses below) before INSERT.
      aggregated = aggregateForInsert(rewritten)
      inserts    = aggregated.map(r =>
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
          r.destIp,
        ),
      )
      // Idempotency: ON CONFLICT DO NOTHING returns the count of NEW rows.
      // Only those rows should drive time_usage / device updates; replays return 0.
      newCount <- trafficRepo.insertBatch(inserts).mapError(ApiError.Db(_))
      // applyDelta groups by (mac, host); pass the rewritten records so the
      // time_usage credit lands under the resolved fqdn too. Unaggregated is
      // fine — applyDelta already collapses duplicates with the same merge.
      _        <- ZIO.when(newCount > 0)(
        applyDelta(routerId, periodEnd, rewritten, settings, timeUsageRepo, deviceRepo),
      )
    } yield ()
  }

  /**
   * #1585 + #1511: rewrite a race-loser ipv4/ipv6 UsageRecord to its resolved FQDN by consulting
   * recent fqdn-typed connection_events for the same (router_id, dest_ip). #1585 introduced the
   * write-time backfill; #1511 moved the per-record lookup off the wire — the resolved dest_ip →
   * FQDN map is now built once per batch by [[ConnectionEventRepo.findRecentFqdnForBatch]] and
   * consulted in memory here. Window symmetry with the events-side backfill (#720) is preserved via
   * [[fqdnBackfillWindow]]: five minutes covers conntrack/dns-tail jitter without inviting
   * attribution across reused-IP cloud endpoints. Emits `traffic_reports_backfill_total{result}`
   * per record: `skip` (not a candidate — already fqdn, or no dest_ip), `filled` (rewritten), or
   * `miss` (candidate, no fqdn found).
   */
  private def backfillRecordWith(
      fqdnMap: Map[IpAddress, Hostname],
      record: UsageRecord,
  ): IO[ApiError, UsageRecord] =
    (record.host, record.destIp) match {
      case (HostId.IPv4(_) | HostId.IPv6(_), Some(destIp)) =>
        fqdnMap.get(destIp) match {
          case Some(name) =>
            AppMetrics.recordTrafficBackfill("filled").as(record.copy(host = HostId.Fqdn(name)))
          case None       =>
            AppMetrics.recordTrafficBackfill("miss").as(record)
        }
      case _                                               =>
        AppMetrics.recordTrafficBackfill("skip").as(record)
    }

  /**
   * #1585: collapse (mac, host) duplicates before INSERT so the traffic_reports primary key's `ON
   * CONFLICT DO NOTHING` doesn't silently drop bytes from race-losers that the backfill just
   * rewrote into a sibling record's bucket. Bytes sum; activeSeconds takes the max (the agent emits
   * the bucket duration on every record, so summing would double-count the wall-clock); destIp / ip
   * take the first non-null. Single-host batches (the common case) pass through unchanged.
   */
  private def aggregateForInsert(records: List[UsageRecord]): List[UsageRecord] =
    records
      .groupBy(r => (r.mac, r.host))
      .view
      .map { case (_, rs) =>
        rs.reduce { (a, b) =>
          a.copy(
            ip = a.ip.orElse(b.ip),
            activeSeconds = math.max(a.activeSeconds, b.activeSeconds),
            bytesIn = a.bytesIn + b.bytesIn,
            bytesOut = a.bytesOut + b.bytesOut,
            destIp = a.destIp.orElse(b.destIp),
          )
        }
      }
      .toList

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
  ): IO[ApiError, Unit] = {
    // #1010: bucket time_usage by the household's logical "today" so the
    // cap-tracking read path (which uses the same household-local date) lines
    // up with the rows we wrote.
    val date       = PolicyService.householdLocalDate(periodEnd, settings)
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
    val perMacAgg  = records
      .groupBy(_.mac)
      .view
      .mapValues { rs =>
        val bucketSecs = rs.map(_.activeSeconds).maxOption.getOrElse(0L)
        val totalBytes = rs.map(r => r.bytesIn + r.bytesOut).sum
        (bucketSecs, totalBytes)
      }
      .toMap
    val grouped    = records
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
    // #1511: build the per-(mac, host) increments in memory and ship them as ONE batched upsert
    // instead of N sequential round trips. Same `seconds_used` / `proportional_seconds` /
    // `bytes_in` / `bytes_out` deltas as before — only the wire shape to PG changed.
    val increments = grouped.map { case ((mac, host), (secs, bIn, bOut)) =>
      // #715: bytes-share weighted attribution within the batch. `bucketSecs` is the wall-clock
      // duration of the bucket; the share is this host's (bytes_in + bytes_out) over the mac's
      // total bytes in the batch. When the mac has zero bytes (shouldn't happen — the agent only
      // emits a record when it saw bytes>0), fall back to crediting full bucket seconds so the
      // row still moves and matches `seconds_used`.
      val (bucketSecs, totalBytes) = perMacAgg.getOrElse(mac, (secs, bIn + bOut))
      val hostBytes                = bIn + bOut
      val proportionalSecs         =
        if (totalBytes > 0L) (bucketSecs.toDouble * hostBytes.toDouble / totalBytes.toDouble).round
        else bucketSecs
      TimeUsageIncrement(mac, host, date, secs, bIn, bOut, proportionalSecs)
    }
    timeUsageRepo
      .incrementSecondsAndBytesBatch(increments)
      .mapError(ApiError.Db(_)) *>
      // #1511: one batched UPDATE for all (mac, ip) last-seen touches in the batch — was N round
      // trips. Skips rows for unknown MACs the same way the per-row method did (UPDATE only).
      deviceRepo
        .touchLastSeenBatch(records.map(r => (r.mac, r.ip)).distinct, periodEnd)
        .mapError(ApiError.Db(_))
        .unit
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
  ): IO[ApiError, Unit] = {
    val connInserts = events.collect {
      case e if e.`type` == "connection_attempt" =>
        for {
          h    <- e.host.toRight("connection_attempt missing host")
          ts   <- scala.util.Try(Instant.parse(e.ts)).toEither.left.map(_.getMessage)
          allw <- e.allowed.toRight("connection_attempt missing allowed")
          // #962: router still sends free-form text on the wire (no router-side
          // change in this PR); convert to typed BlockReason at the API boundary.
          rsn = BlockReason.fromWire(e.reason.getOrElse(if allw then "allow" else "blocked"))
        } yield ConnectionEventInsert(routerId, e.mac, h, e.destIp, allw, rsn, ts, e.eventId)
    }
    for {
      // Surface JSON validation errors as 400.
      validated <- ZIO.foreach(connInserts)(e => ZIO.fromEither(e).mapError(ApiError.BadRequest(_)))
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
        .mapError(ApiError.Db(_))
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
  ): IO[ApiError, ConnectionEventInsert] =
    (ev.host, ev.destIp) match {
      case (HostId.IPv4(_) | HostId.IPv6(_), Some(destIp)) =>
        connEventRepo
          .findRecentFqdnFor(ev.routerId, destIp, ev.ts.minus(fqdnBackfillWindow))
          .mapError(ApiError.Db(_))
          .map(h => ev.copy(resolvedHost = h))
      case _                                               => ZIO.succeed(ev)
    }

  private def backfillFromFqdn(
      ev: ConnectionEventInsert,
      connEventRepo: ConnectionEventRepo,
  ): IO[ApiError, Unit] =
    (ev.host, ev.destIp) match {
      case (HostId.Fqdn(name), Some(destIp)) =>
        connEventRepo
          .backfillResolvedFor(ev.routerId, destIp, name, ev.ts.minus(fqdnBackfillWindow))
          .mapError(ApiError.Db(_))
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
  ): IO[ApiError, Unit] =
    if e.`type` != "dhcp_lease" && e.`type` != "first_seen_mac" then ZIO.unit
    else
      e.mac match {
        case None      => ZIO.unit
        case Some(mac) =>
          for {
            ts       <- ZIO
              .attempt(Instant.parse(e.ts))
              .orElseFail(ApiError.BadRequest(s"invalid ts: ${e.ts}"))
            existing <- deviceRepo.findByMac(mac).mapError(ApiError.Db(_))
            _        <- existing match {
              case Some(_) =>
                deviceRepo
                  .touchLastSeen(mac, e.ip, ts)
                  .mapError(ApiError.Db(_))
                  .unit *>
                  ZIO
                    .foreachDiscard(e.hostname)(h =>
                      deviceRepo
                        .renameIfAutoGenerated(mac, h.value)
                        .mapError(ApiError.Db(_)),
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
                  .mapError(ApiError.Db(_))
                  .unit *>
                  alertRepo
                    .raiseNewDevice(mac, ts)
                    .mapError(ApiError.Db(_))
            }
          } yield ()
      }
}
