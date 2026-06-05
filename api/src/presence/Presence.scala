package wifihaven.api.presence

import wifihaven.shared.HeartbeatFilter
import wifihaven.shared.types.*
import java.time.{Instant, LocalDate}

/**
 * One bucket-host tuple from traffic_reports, used to compute presence-based minutes (one count per
 * (mac, period_start), regardless of how many hosts the device touched in that window). `date` is
 * the local calendar day the bucket was attributed to by the agent (`traffic_reports.date`) —
 * carried alongside `periodStart` so range queries can be grouped per-day without a tz-derivation
 * step (see #723 weekly view).
 *
 * `bytes` is `bytes_in + bytes_out` for the row and `periodSeconds` is `period_end - period_start`;
 * both exist solely to feed the #714 heartbeat filter and are ignored by all other consumers.
 */
case class PresenceRow(
    mac: MacAddress,
    date: LocalDate,
    periodStart: Instant,
    host: HostId,
    activeSeconds: Int,
    bytes: Long,
    periodSeconds: Int,
)

/**
 * Bucket-deduplicated minute accounting from `traffic_reports`.
 *
 * Background: the router emits one usage record per (mac, dst_ip) per reporting window (~60 s),
 * each carrying the bucket duration in `active_seconds`. Summing `active_seconds` across hostnames
 * per mac over-counts wall-clock time — a device active in one bucket but connecting to
 * youtube.com, google.com and dropbox.com all in the same window would otherwise show 3× the actual
 * screen time. Presence collapses each bucket to one count per (mac, period_start) and then
 * aggregates by mac.
 *
 * `site_time_limits.exempt_from_daily` decides whether a per-site bucket counts toward the daily
 * total. A bucket is excluded from the daily total only when EVERY hostname in it matches an exempt
 * pattern; a single non-exempt hostname pulls the whole bucket back into the total. The per-site
 * (per-pattern) minutes are computed the same way but per pattern, so the per-app limit still ticks
 * independently for its own cap check.
 */
object Presence {

  /**
   * *.foo.com or foo.com — delegates to [[wifihaven.shared.types.HostMatch.matchesPattern]], shared
   * with PolicyService and UsageTraffic (#1085).
   */
  def matchesPattern(domain: String, pattern: String): Boolean =
    HostMatch.matchesPattern(domain, pattern)

  private def bucketSeconds(bucket: Iterable[PresenceRow]): Long =
    bucket.iterator.map(_.activeSeconds.toLong).maxOption.getOrElse(0L)

  /** Default idle gap `N` (seconds) for the session-stitch model; mirrors migration V52. */
  val DefaultContinuationSeconds: Int = 120

  // ── Session-stitch primitive (#1464, design §4) ──────────────────────────────
  //
  // Presence is no longer "credit a bucket": that under-counts the request-driven
  // apps whose `activeSeconds` bottoms out at the 10 s sample floor (~3.3× low on
  // prod, docs/design/presence-tuning.md §2). Instead we sessionize activity on
  // wall-clock timestamps so bucket size / report rate `R` is only the resolution
  // of the evidence, never a term in the formula (§2d). The primitive below is
  // shared by the daily-cap aggregates here and reused by the per-app surface
  // (#1465) — keep it cleanly factored.

  /** A wall-clock activity span in epoch seconds, `[startEpoch, endEpoch]` with `end ≥ start`. */
  final case class Span(startEpoch: Long, endEpoch: Long) {
    def seconds: Long = (endEpoch - startEpoch).max(0L)
  }

  /**
   * Each non-heartbeat `traffic_reports` row contributes its full `[period_start, period_end]`
   * interval as evidence of activity (design §4.1) — NOT its sampled `activeSeconds`, which is the
   * undercount driver. The trailing edge carries up to one report-interval of uncertainty until the
   * `connection_events`-anchored timing lands (#1466).
   */
  def spanOf(r: PresenceRow): Span = {
    val start = r.periodStart.getEpochSecond
    Span(start, start + r.periodSeconds.toLong.max(0L))
  }

  /**
   * The idle gap actually used: the configured `N`, raised to the `2 × R` collapse guard (design
   * §4.4 invariant 1). `R` is read from the data (`period_seconds`), never assumed to be 60 s. When
   * `N < R` contiguous reporting windows stop merging and presence collapses to ~0 (§2d (ii));
   * clamping up to `2 × R` keeps a misconfigured / coarse-reporting fleet from silently zeroing
   * out.
   */
  def effectiveGap(rows: Iterable[PresenceRow], continuationSeconds: Int): Long = {
    val r = rows.iterator.map(_.periodSeconds.toLong).maxOption.getOrElse(0L)
    continuationSeconds.toLong.max(2L * r)
  }

  /**
   * Session-stitch one time-ordered activity stream (design §4.1): fold spans into sessions,
   * merging while the wall-clock idle gap to the next span is ≤ `gapSeconds`; a larger gap ends the
   * session. A session's presence is its `[first, last]` span — every span in between counts as
   * continuous. Caller is responsible for streaming one logical `(device, app)` partition.
   */
  def stitch(spans: List[Span], gapSeconds: Long): List[Span] =
    spans
      .sortBy(s => (s.startEpoch, s.endEpoch))
      .foldLeft(List.empty[Span]) {
        case (head :: tail, s) if s.startEpoch - head.endEpoch <= gapSeconds =>
          head.copy(endEpoch = head.endEpoch.max(s.endEpoch)) :: tail
        case (acc, s)                                                        => s :: acc
      }
      .reverse

  /**
   * Overlap-merge spans (gap 0) and sum their wall-clock seconds — the within-device/profile union.
   */
  def unionSeconds(spans: List[Span]): Long =
    spans
      .sortBy(_.startEpoch)
      .foldLeft(List.empty[Span]) {
        case (head :: tail, s) if s.startEpoch <= head.endEpoch =>
          head.copy(endEpoch = head.endEpoch.max(s.endEpoch)) :: tail
        case (acc, s)                                           => s :: acc
      }
      .map(_.seconds)
      .sum

  /**
   * The non-heartbeat, non-exempt rows that bear on the daily cap. A row is dropped if it is a
   * heartbeat (#714) or if its host matches an `exemptFromDaily` site pattern. Because each row
   * spans its whole reporting window, dropping the exempt rows reproduces the old "bucket counts
   * iff at least one non-exempt host" semantic exactly: a window with a non-exempt host keeps that
   * host's full span; a window of only exempt hosts contributes nothing. IP-literal hosts are never
   * exempt (patterns only match FQDNs).
   */
  private def countedRows(
      rows: List[PresenceRow],
      exemptPatterns: List[String],
      filter: HeartbeatFilter,
  ): List[PresenceRow] = {
    def isExempt(h: HostId) =
      h.asFqdn.exists(fqdn => exemptPatterns.exists(p => matchesPattern(fqdn.value, p)))
    rows.filterNot(r => isHeartbeat(r, filter)).filterNot(r => isExempt(r.host))
  }

  /** Per-`(device, app)` sessions for a counted row-set, where `app = host` (design §4.1). */
  private def sessionSpans(rows: List[PresenceRow], gap: Long): List[Span] =
    rows.groupBy(r => (r.mac, r.host)).values.flatMap(hr => stitch(hr.map(spanOf), gap)).toList

  /**
   * Per-mac total active-seconds for the day (design §4.3): per-`(device, app)` session-stitch on
   * the idle gap, then union those sessions within the device (two apps in the same minute count
   * once). A row counts iff it is non-heartbeat and not on an `exemptFromDaily` host. The cross-
   * device combination (`Sum` adds these; `Dedup` unions instead) lives in the caller / in
   * [[dedupedTotalSeconds]].
   *
   * Surfaces raw seconds rather than floor-divided minutes so callers that need the precision (see
   * #516 e2e test) can ceil-divide themselves; minute-resolution callers should use
   * [[totalMinutesByMac]].
   */
  def totalSecondsByMac(
      rows: List[PresenceRow],
      exemptPatterns: List[String],
      filter: HeartbeatFilter = HeartbeatFilter.Off,
      continuationSeconds: Int = DefaultContinuationSeconds,
  ): Map[MacAddress, Long] = {
    val counted = countedRows(rows, exemptPatterns, filter)
    val gap     = effectiveGap(counted, continuationSeconds)
    counted
      .groupBy(_.mac)
      .view
      .mapValues(macRows => unionSeconds(sessionSpans(macRows, gap)))
      .filter(_._2 > 0L)
      .toMap
  }

  /**
   * Per-mac total minutes for the day. A device's minutes are the union of its per-app sessions
   * (design §4.3); IP-literal hosts are never exempt (patterns only match FQDNs).
   */
  def totalMinutesByMac(
      rows: List[PresenceRow],
      exemptPatterns: List[String],
      filter: HeartbeatFilter = HeartbeatFilter.Off,
      continuationSeconds: Int = DefaultContinuationSeconds,
  ): Map[MacAddress, Int] =
    totalSecondsByMac(rows, exemptPatterns, filter, continuationSeconds).view
      .mapValues(s => (s / 60).toInt)
      .filter(_._2 != 0)
      .toMap

  /**
   * #751 / #1464: profile-scoped session union across ALL of the profile's devices — the `Dedup`
   * cross-device mode. Sessions are stitched per `(device, app)` then unioned across every device
   * and app, so one human on two screens at the same instant counts once. Use [[totalSecondsByMac]]
   * + sum when the operator wants per-device totals added (the `Sum` mode).
   */
  def dedupedTotalSeconds(
      rows: List[PresenceRow],
      exemptPatterns: List[String],
      filter: HeartbeatFilter = HeartbeatFilter.Off,
      continuationSeconds: Int = DefaultContinuationSeconds,
  ): Long = {
    val counted = countedRows(rows, exemptPatterns, filter)
    val gap     = effectiveGap(counted, continuationSeconds)
    unionSeconds(sessionSpans(counted, gap))
  }

  def dedupedTotalMinutes(
      rows: List[PresenceRow],
      exemptPatterns: List[String],
      filter: HeartbeatFilter = HeartbeatFilter.Off,
      continuationSeconds: Int = DefaultContinuationSeconds,
  ): Int =
    (dedupedTotalSeconds(rows, exemptPatterns, filter, continuationSeconds) / 60).toInt

  /**
   * #714 heartbeat classification, applied ONLY inside `totalSecondsByMac`/`totalMinutesByMac`.
   * Per-site (`patternMinutesByMac`) and per-host (`hostMinutes`) breakdowns intentionally do not
   * filter — heartbeats keep counting for per-site time for now; the operator wants to evaluate
   * that separately. A row is classified as a heartbeat if the filter is enabled and total bytes
   * are below `bytesThreshold` (one TCP keepalive ≈ 60 bytes; a few HTTP/2 PINGs ≈ a few hundred).
   */
  def isHeartbeat(row: PresenceRow, filter: HeartbeatFilter): Boolean =
    filter.enabled && (
      row.bytes < filter.bytesThreshold ||
        row.host.asFqdn.exists(fqdn =>
          filter.heartbeatHostPatterns.exists(p => matchesPattern(fqdn.value, p)),
        )
    )

  /**
   * #714: per-row heartbeat classification for the explain debug surface. Wraps each row with the
   * classification verdict and the specific reasons it tripped, so the operator can tune thresholds
   * against real prod data.
   */
  case class Classified(
      row: PresenceRow,
      classified: String,
      reasons: List[String],
  )

  def classifyRows(rows: List[PresenceRow], filter: HeartbeatFilter): List[Classified] =
    rows.map { r =>
      val rsns  = scala.collection.mutable.ListBuffer.empty[String]
      if filter.enabled then {
        if r.bytes < filter.bytesThreshold then rsns += s"bytes<${filter.bytesThreshold}"
        for {
          fqdn <- r.host.asFqdn
          p    <- filter.heartbeatHostPatterns
          if matchesPattern(fqdn.value, p)
        } rsns += s"host:$p"
      }
      val label = if filter.enabled && rsns.nonEmpty then "heartbeat" else "active"
      Classified(r, label, rsns.toList)
    }

  /**
   * Per-(mac, pattern) minutes, counting each bucket once per device per pattern when any host in
   * the bucket matches the pattern. Two hosts that both match the same pattern in one bucket still
   * only contribute one bucket's worth of time; the same host matching two patterns contributes one
   * bucket's worth to each (per-pattern caps are independent). IP-literal hosts never match
   * patterns.
   */
  def patternMinutesByMac(
      rows: List[PresenceRow],
      patterns: List[String],
  ): Map[(MacAddress, String), Int] = {
    val buckets = rows.groupBy(r => (r.mac, r.periodStart)).toList
    val accum   = scala.collection.mutable.Map.empty[(MacAddress, String), Long]
    for {
      pat                <- patterns
      ((mac, _), bucket) <- buckets
      if bucket.exists(r => r.host.asFqdn.exists(fqdn => matchesPattern(fqdn.value, pat)))
    } accum.updateWith((mac, pat))(prev => Some(prev.getOrElse(0L) + bucketSeconds(bucket)))
    accum.view.mapValues(s => (s / 60).toInt).toMap
  }

  /**
   * Per-host minutes across all macs, attributing each bucket's duration to every distinct host in
   * the bucket once. Used for the per-profile "what did they spend time on today" breakdown (#262).
   * Note: summing across hosts can exceed the device's daily total — by design, the same bucket of
   * activity contributes its full duration to each host the device touched in that window. The
   * daily cap still counts the bucket once via `totalMinutesByMac`.
   */
  def hostMinutes(rows: List[PresenceRow]): Map[HostId, Int] = {
    val accum = scala.collection.mutable.Map.empty[HostId, Long]
    for ((_, bucket) <- rows.groupBy(r => (r.mac, r.periodStart))) {
      val secs  = bucketSeconds(bucket)
      val hosts = bucket.iterator.map(_.host).toSet
      for (h <- hosts)
        accum.updateWith(h)(prev => Some(prev.getOrElse(0L) + secs))
    }
    accum.view.mapValues(s => (s / 60).toInt).toMap
  }

  /**
   * #715: per-host seconds attributed by byte share within each (mac, period_start) bucket. Each
   * bucket's wall-clock duration is split across the hosts present in proportion to their share of
   * the bucket's total bytes (bytes_in + bytes_out). Summing across hosts within one mac's bucket ≈
   * the bucket duration, so this is a much fairer "wall-clock attention" number than
   * [[hostMinutes]] (which credits every host the device touched with the bucket's full duration).
   *
   * Note: when a bucket carries multiple rows for the same host (e.g. two ipv4-typed rows resolving
   * to the same fqdn via the read-side LATERAL join), their bytes are summed before the share is
   * computed so the same host doesn't get a double weight. If the bucket has zero total bytes (an
   * edge case — the agent only emits records with bytes>0), the bucket is skipped entirely.
   */
  def proportionalHostSeconds(rows: List[PresenceRow]): Map[HostId, Double] = {
    val accum = scala.collection.mutable.Map.empty[HostId, Double]
    for ((_, bucket) <- rows.groupBy(r => (r.mac, r.periodStart))) {
      val secs   = bucketSeconds(bucket).toDouble
      val byHost = bucket.iterator
        .map(r => r.host -> r.bytes)
        .toList
        .groupMapReduce(_._1)(_._2)(_ + _)
      val total  = byHost.valuesIterator.sum
      if (secs > 0.0 && total > 0L)
        for ((h, b) <- byHost) {
          val share = b.toDouble / total.toDouble
          accum.updateWith(h)(prev => Some(prev.getOrElse(0.0) + secs * share))
        }
    }
    accum.toMap
  }

  /** Convenience: floor-divided minute view of [[proportionalHostSeconds]]. */
  def proportionalHostMinutes(rows: List[PresenceRow]): Map[HostId, Int] =
    proportionalHostSeconds(rows).view.mapValues(s => (s / 60).toInt).toMap
}
