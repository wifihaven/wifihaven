package wifihaven.api.routes

import wifihaven.api.db.*
import wifihaven.api.metrics.AppMetrics
import wifihaven.api.observability.LogContext
import wifihaven.api.policy.PolicyService
import wifihaven.shared.*
import wifihaven.shared.types.*
import zio.{Clock as _, *}
import zio.json.*
import zio.json.ast.Json

import java.time.{Duration, Instant}

/**
 * #1846: transport-agnostic router ingest. The decode + per-record skip + persistence logic for the
 * `usage` and `events` flows used to live inline in [[RouterIngestRoutes]]'s handlers. It is
 * extracted here so the REST endpoints (`POST /api/router/usage`, `POST /api/router/events`) and
 * the new websocket transport (`/api/router/ws`, [[RouterWsRoutes]]) dispatch into ONE
 * implementation — there is no second copy of the ingest behavior
 * (`AGENTS.md#single-source-of-truth`). The methods take the already-resolved [[Router]] (both
 * transports authenticate the same way via [[RouterAuth]] before calling in) and the raw JSON body
 * / frame payload; they fail with a typed [[ApiError]] the caller maps to its transport's error
 * shape (a `400`/`401` Response for REST, an `ack reject` frame for ws). Behavior is byte-for-byte
 * what the routes did before the extraction.
 */
final class RouterIngestService(
    routerRepo: RouterRepo,
    trafficRepo: TrafficReportRepo,
    timeUsageRepo: TimeUsageRepo,
    deviceRepo: DeviceRepo,
    connEventRepo: ConnectionEventRepo,
    alertRepo: AlertRepo,
    householdSettingsRepo: HouseholdSettingsRepo,
    // #1970 (S3): the SPA-websocket change-source bus. Each ingest is also a write site that may move
    // the browser's live surface, so it publishes a one-line change event (design §5.2.2) — a `now`
    // recompute trigger, the new connection-events head, an `alerts` stale nudge. Defaults to
    // [[SpaEventBus.noop]] so the REST/router-ws ingest call sites and tests that don't exercise the
    // push path are unchanged; `Main` wires the real bus. Publishing NEVER blocks or fails ingest
    // (sliding hub, UIO) — the push surface is a latency/UX layer over the authoritative write.
    bus: SpaEventBus = SpaEventBus.noop,
) {
  import RouterIngestService.*

  /**
   * Decode + persist a `POST /api/router/usage` body (or a `usage` ws frame payload) for an
   * already-authenticated router. Mirrors the prior inline handler exactly: a genuinely unparseable
   * envelope (bad routerId/timestamps, `records` not an array, truncated body) fails with a typed
   * 400; individual malformed records are skipped + logged + metered (#1569/#1757) and the valid
   * ones ingest. `last_seen_at` is touched on success.
   */
  def ingestUsage(router: Router, body: String): IO[ApiError, Unit] =
    LogContext.annotate(LogContext.RouterId, router.id.toString) {
      for {
        raw       <- ZIO
          .fromEither(body.fromJson[RawUsageReport])
          .mapError(ApiError.DecodeFailure(_))
        _         <- ZIO
          .fail(ApiError.BadRequest("router_id mismatch"))
          .when(raw.routerId != router.id)
        ps        <- parseInstant(raw.periodStart)
        pe        <- parseInstant(raw.periodEnd)
        decResult <- decodePerRecord[UsageRecord](
          raw.records,
          "router usage",
          "record",
          router.id,
          AppMetrics.recordUsageRecordsRejected(_),
        )
        (records, rejectedCount) = decResult
        _        <- LogContext.annotateAll(
          LogContext.BatchSize -> records.size.toString,
          LogContext.Rejected  -> rejectedCount.toString,
        ) {
          ZIO.logDebug(
            s"router usage: router=${router.id} period=$ps..$pe records=${records.size} " +
              s"rejected=$rejectedCount",
          )
        }
        _        <- ZIO.foreachDiscard(records)(r =>
          ZIO.logDebug(
            s"  usage record: mac=${r.mac} ip=${r.ip.getOrElse("-")} " +
              s"host=${r.host.value} secs=${r.activeSeconds} bIn=${r.bytesIn} bOut=${r.bytesOut}",
          ),
        )
        settings <- householdSettingsRepo.get.mapError(ApiError.Db(_))
        _        <- handleUsage(router.id, ps, pe, records, settings)
        _        <- routerRepo.touch(router.id, None, None).mapError(ApiError.Db(_))
        // #1970 (S3): newly-credited usage moves last_seen / per-device activity, so the dashboard
        // NOW surface may have changed — trigger a `now` recompute. #1971 (S4): the same ingest is
        // the `trafficUsage` write site — `UsageIngested` drives the live-edge head-bucket recompute
        // for subscribed `(groupBy,bucket,filter)` param-sets (design §5.3). #1974 (S6a): the new
        // minutes also move per-profile used/remaining + per-app minutes — `TimeStatusChanged` drives
        // the `timeStatus`/`appUsage` push (design §5.2). All one-liners that never block or fail
        // ingest (sliding hub, UIO).
        _        <- ZIO.when(records.nonEmpty)(
          bus.publish(SpaEvent.NowChanged) *> bus.publish(SpaEvent.UsageIngested(ps, pe)) *>
            bus.publish(SpaEvent.TimeStatusChanged),
        )
      } yield ()
    }

  /**
   * Decode + persist a `POST /api/router/events` body (or an `events` ws frame payload) for an
   * already-authenticated router. Mirrors the prior inline handler: an empty/blank body is an
   * accepted no-op batch (#1126); individual malformed events are skipped + metered (#1757); the
   * envelope itself must parse or it is a typed 400. `last_seen_at` is touched on success.
   */
  def ingestEvents(router: Router, body: String): IO[ApiError, Unit] =
    LogContext.annotate(LogContext.RouterId, router.id.toString) {
      for {
        raw       <-
          if body.trim.isEmpty then
            ZIO.logDebug(
              s"router events: empty body from router=${router.id}; treating as no-op batch",
            ) *> ZIO.succeed(RawEventsReport(router.id, Nil))
          else
            ZIO
              .fromEither(body.fromJson[RawEventsReport])
              .mapError(ApiError.DecodeFailure(_))
        _         <- ZIO
          .fail(ApiError.BadRequest("router_id mismatch"))
          .when(raw.routerId != router.id)
        decResult <- decodePerRecord[RouterEvent](
          raw.events,
          "router events",
          "event",
          router.id,
          AppMetrics.recordEventsRecordsRejected(_),
        )
        (events, rejectedCount) = decResult
        _ <- LogContext.annotateAll(
          LogContext.BatchSize -> events.size.toString,
          LogContext.Rejected  -> rejectedCount.toString,
        ) {
          ZIO.logDebug(
            s"router events: router=${router.id} batchSize=${events.size} " +
              s"rejected=$rejectedCount",
          )
        }
        _ <- ZIO.foreachDiscard(events)(e =>
          ZIO.logDebug(
            s"  event: type=${e.`type`} mac=${e.mac.getOrElse("-")} " +
              s"ip=${e.ip.getOrElse("-")} host=${e.host.map(_.value).orElse(e.hostname.map(_.value)).getOrElse("-")} " +
              s"destIp=${e.destIp.getOrElse("-")} allowed=${e.allowed.map(_.toString).getOrElse("-")} " +
              s"reason=${e.reason.getOrElse("-")} ts=${e.ts}",
          ),
        )
        _ <- handleEvents(router.id, events)
        _ <- routerRepo.touch(router.id, None, None).mapError(ApiError.Db(_))
      } yield ()
    }

  private def handleUsage(
      routerId: RouterId,
      periodStart: Instant,
      periodEnd: Instant,
      records: List[UsageRecord],
      settings: HouseholdSettings,
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
          // #2025: persist the real activity envelope when the agent supplied it
          // (and it parses). A malformed timestamp is dropped to None rather
          // than failing the batch — Presence.spanOf then falls back to the
          // [period_start, period_end] flush window for that row.
          parseInstantOpt(r.activeStart),
          parseInstantOpt(r.activeEnd),
        ),
      )
      // Idempotency: ON CONFLICT DO NOTHING returns the count of NEW rows.
      // Only those rows should drive time_usage / device updates; replays return 0.
      newCount <- trafficRepo.insertBatch(inserts).mapError(ApiError.Db(_))
      // applyDelta groups by (mac, host); pass the rewritten records so the
      // time_usage credit lands under the resolved fqdn too. Unaggregated is
      // fine — applyDelta already collapses duplicates with the same merge.
      _        <- ZIO.when(newCount > 0)(
        applyDelta(routerId, periodEnd, rewritten, settings),
      )
    } yield ()
  }

  /**
   * #1585 + #1511: rewrite a race-loser ipv4/ipv6 UsageRecord to its resolved FQDN by consulting
   * recent fqdn-typed connection_events for the same (router_id, dest_ip). Emits
   * `traffic_reports_backfill_total{result}` per record: `skip` / `filled` / `miss`.
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
            // #2025: union the two records' activity envelopes — earliest start,
            // latest end. The wire timestamps are fixed-width RFC3339 UTC ("…Z"),
            // so lexical min/max is chronological (same property the agent's
            // retry queue relies on for periodEnd ordering).
            activeStart = minIso(a.activeStart, b.activeStart),
            activeEnd = maxIso(a.activeEnd, b.activeEnd),
          )
        }
      }
      .toList

  /**
   * Apply seconds/byte deltas + device last_seen for a freshly-accepted batch. We only enter here
   * when at least one row was inserted; for partial overlap (rare retry edge case) we accept that
   * the new rows' increments fold in but already-stored ones don't double-count because the
   * traffic_reports unique key blocked them.
   */
  private def applyDelta(
      @scala.annotation.unused routerId: RouterId,
      periodEnd: Instant,
      records: List[UsageRecord],
      settings: HouseholdSettings,
  ): IO[ApiError, Unit] = {
    // #1010: bucket time_usage by the household's logical "today" so the
    // cap-tracking read path (which uses the same household-local date) lines
    // up with the rows we wrote.
    val date       = PolicyService.householdLocalDate(periodEnd, settings)
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
    // instead of N sequential round trips.
    val increments = grouped.map { case ((mac, host), (secs, bIn, bOut)) =>
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
      deviceRepo
        .touchLastSeenBatch(records.map(r => (r.mac, r.ip)).distinct, periodEnd)
        .mapError(ApiError.Db(_))
        .unit
  }

  private def handleEvents(
      routerId: RouterId,
      events: List[RouterEvent],
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
      enriched  <- ZIO.foreach(validated)(attachResolvedHost)
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
      _         <- ZIO.foreachDiscard(enriched)(backfillFromFqdn)
      _         <- ZIO.foreachDiscard(events)(applyDhcpOrFirstSeen)
      // #1970 (S3): a successful connection-events ingest is the `connectionEvents` write site —
      // push the new head (carrying the earliest inserted ts so SpaPush re-reads exactly the new
      // rows via the GET query) plus a `now` recompute trigger (a fresh connection = device
      // activity). Only when ≥1 row was genuinely new (replays insert 0). Never fails ingest.
      _         <- ZIO.when(inserted > 0) {
        val since = enriched.map(_.ts).min
        bus.publish(SpaEvent.ConnectionEventsIngested(since)) *> bus.publish(SpaEvent.NowChanged)
      }
    } yield ()
  }

  private def attachResolvedHost(
      ev: ConnectionEventInsert,
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
   * DHCP lease + first-seen-MAC: upsert into devices. For a known MAC refresh last_seen_ip /
   * last_seen_at (preserve profile) and, on a dhcp_lease with a real hostname, also upgrade the
   * name if it is still an auto-generated placeholder (#249). For an unknown MAC, create a row with
   * NULL profile_id and the supplied hostname (or autoGeneratedName(mac)).
   */
  private def applyDhcpOrFirstSeen(
      e: RouterEvent,
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
                    .mapError(ApiError.Db(_)) *>
                  // #1970 (S3): a raised alert is a class-(3) write site → nudge `stale{alerts}`
                  // so an open dashboard re-fetches the alerts list. Idempotent on `mac`, so a
                  // replayed event won't re-nudge a meaningful change. Never fails ingest.
                  bus.publish(SpaEvent.Stale(StaleTopic.Alerts))
            }
          } yield ()
      }
}

object RouterIngestService {

  /**
   * #1569: the wire-decode envelope for `usage` ingest. Identical field shape to [[UsageReport]]
   * except `records` is held as a raw JSON array so each element can be decoded into a
   * [[UsageRecord]] individually — a single malformed record (e.g. a host value that fails
   * `Hostname` validation, see #1572) is then skipped + logged + metered instead of failing the
   * whole batch. Decode-only; NOT a wire-contract type — unknown fields are still ignored.
   */
  private case class RawUsageReport(
      routerId: RouterId,
      periodStart: String,
      periodEnd: String,
      records: List[Json],
  ) derives JsonDecoder

  /**
   * #1757: the wire-decode envelope for `events` ingest, the [[RouterEventsRequest]] analog of
   * [[RawUsageReport]]. `events` is held as a raw JSON array so a single malformed event is skipped
   * + metered rather than failing the whole batch. Decode-only; unknown fields are still ignored.
   */
  private case class RawEventsReport(
      routerId: RouterId,
      events: List[Json],
  ) derives JsonDecoder

  // #1570: a bad timestamp is a typed BadRequest (400) mapped centrally; the boundary logs it.
  private def parseInstant(s: String): IO[ApiError, Instant] =
    ZIO.attempt(Instant.parse(s)).orElseFail(ApiError.BadRequest(s"invalid timestamp: $s"))

  // #2025: best-effort parse of an OPTIONAL activity-envelope timestamp. Unlike
  // the envelope timestamps above, a malformed/absent value here is NOT a 400 —
  // it degrades to None so the row simply falls back to its flush window in
  // Presence.spanOf. The additive wire field must never harden an old/odd agent
  // into a rejected batch.
  private def parseInstantOpt(s: Option[String]): Option[Instant] =
    s.flatMap(v => scala.util.Try(Instant.parse(v)).toOption)

  // #2025: lexical min/max over optional RFC3339 UTC ("…Z") timestamps — used to
  // union two collapsed records' activity envelopes (earliest start, latest end)
  // before insert. Fixed-width "…Z" makes lexical order chronological. `None`
  // is absorbing-free: a present value always wins over an absent one.
  private def minIso(a: Option[String], b: Option[String]): Option[String] =
    (a, b) match {
      case (Some(x), Some(y)) => Some(if (x <= y) x else y)
      case _                  => a.orElse(b)
    }

  private def maxIso(a: Option[String], b: Option[String]): Option[String] =
    (a, b) match {
      case (Some(x), Some(y)) => Some(if (x >= y) x else y)
      case _                  => a.orElse(b)
    }

  /**
   * #1569 / #1757: shared per-record skip helper used by both the `usage` (records → UsageRecord)
   * and `events` (events → RouterEvent) flows. Decodes each `Json` element into `T` individually,
   * logs each failure (bounded — one warn per bad slot, with the JSON index) and increments the
   * supplied rejection metric, then returns the decoded list plus the rejected count. Bad records
   * are skipped, NOT failed — the envelope itself must still parse upstream.
   */
  private def decodePerRecord[T](
      raw: List[Json],
      logPrefix: String,
      itemNoun: String,
      routerId: RouterId,
      recordMetric: Int => UIO[Unit],
  )(implicit decoder: JsonDecoder[T]): UIO[(List[T], Int)] = {
    val decoded  = raw.zipWithIndex.map((j, i) => (i, j.as[T]))
    val rejected = decoded.collect { case (i, Left(err)) => (i, err) }
    val ok       = decoded.collect { case (_, Right(t)) => t }
    ZIO.foreachDiscard(rejected) { (i, err) =>
      ZIO.logWarning(
        s"$logPrefix: skipping malformed $itemNoun[$i] for router=$routerId: $err",
      )
    } *> recordMetric(rejected.size).as((ok, rejected.size))
  }

  /**
   * #720: how far back the API will look for a sibling fqdn-typed event to attribute an
   * ipv4/ipv6-typed event to. Five minutes covers the conntrack/dns-tail jitter without inviting
   * attribution across reused-IP cloud endpoints.
   */
  private val fqdnBackfillWindow: Duration = Duration.ofMinutes(5)

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
}
