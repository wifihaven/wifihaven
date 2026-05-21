package wifihaven.api.presence

import wifihaven.shared.HeartbeatFilter
import wifihaven.shared.types.*
import java.time.Instant

/**
 * One bucket-host tuple from traffic_reports, used to compute presence-based minutes (one count per
 * (mac, period_start), regardless of how many hosts the device touched in that window).
 *
 * `bytes` is `bytes_in + bytes_out` for the row and `periodSeconds` is `period_end - period_start`;
 * both exist solely to feed the #714 heartbeat filter and are ignored by all other consumers.
 */
case class PresenceRow(
    mac: MacAddress,
    periodStart: Instant,
    host: HostId,
    activeSeconds: Int,
    bytes: Long,
    periodSeconds: Int,
)

/**
 * Bucket-deduplicated minute accounting from `traffic_reports`.
 *
 * Background: the router emits one usage record per (mac, dst_ip) per 5-min window, each carrying
 * the bucket duration in `active_seconds`. Summing `active_seconds` across hostnames per mac
 * over-counts wall-clock time — a device active in one bucket but connecting to youtube.com,
 * google.com and dropbox.com all in the same 5 minutes would otherwise show 15 minutes of screen
 * time instead of 5. Presence collapses each bucket to one count per (mac, period_start) and then
 * aggregates by mac.
 *
 * `site_time_limits.exempt_from_daily` decides whether a per-site bucket counts toward the daily
 * total. A bucket is excluded from the daily total only when EVERY hostname in it matches an exempt
 * pattern; a single non-exempt hostname pulls the whole 5-min window back into the total. The
 * per-site (per-pattern) minutes are computed the same way but per pattern, so the per-app limit
 * still ticks independently for its own cap check.
 */
object Presence {

  /**
   * *.foo.com or foo.com — same semantics as the matchers in PolicyService and Routes.
   */
  def matchesPattern(domain: String, pattern: String): Boolean =
    if pattern.startsWith("*.") then domain.endsWith(pattern.drop(1)) || domain == pattern.drop(2)
    else domain == pattern || domain.endsWith(s".$pattern")

  private def bucketSeconds(bucket: Iterable[PresenceRow]): Long =
    bucket.iterator.map(_.activeSeconds.toLong).maxOption.getOrElse(0L)

  /**
   * Per-mac total active-seconds for the day, summing each 5-min bucket's max activeSeconds
   * (bucket-deduplicated across hosts) once. A bucket counts iff at least one host in the bucket is
   * NOT in `exemptPatterns`. IP-literal hosts are never exempt (patterns only match FQDNs).
   *
   * Surfaces raw seconds rather than floor-divided minutes so callers that need the precision (see
   * #516 e2e test) can ceil-divide themselves; minute-resolution callers should use
   * [[totalMinutesByMac]].
   */
  def totalSecondsByMac(
      rows: List[PresenceRow],
      exemptPatterns: List[String],
      filter: HeartbeatFilter = HeartbeatFilter.Off,
  ): Map[MacAddress, Long] = {
    def isExempt(h: HostId) =
      h.asFqdn.exists(fqdn => exemptPatterns.exists(p => matchesPattern(fqdn.value, p)))
    rows.iterator
      .filterNot(r => isHeartbeat(r, filter))
      .toList
      .groupBy(r => (r.mac, r.periodStart))
      .toList
      .collect {
        case ((mac, _), bucket) if bucket.exists(r => !isExempt(r.host)) =>
          mac -> bucketSeconds(bucket)
      }
      .groupMapReduce(_._1)(_._2)(_ + _)
  }

  /**
   * Per-mac total minutes for the day, counting each 5-min bucket once. A bucket counts iff at
   * least one host in the bucket is NOT in `exemptPatterns` — i.e. the device was active on
   * something that bears on the daily cap. IP-literal hosts are never exempt (patterns only match
   * FQDNs).
   */
  def totalMinutesByMac(
      rows: List[PresenceRow],
      exemptPatterns: List[String],
      filter: HeartbeatFilter = HeartbeatFilter.Off,
  ): Map[MacAddress, Int] =
    totalSecondsByMac(rows, exemptPatterns, filter).view.mapValues(s => (s / 60).toInt).toMap

  /**
   * #714 heartbeat classification, applied ONLY inside `totalSecondsByMac`/`totalMinutesByMac`.
   * Per-site (`patternMinutesByMac`) and per-host (`hostMinutes`) breakdowns intentionally do not
   * filter — heartbeats keep counting for per-site time for now; the operator wants to evaluate
   * that separately. A row is classified as a heartbeat if the filter is enabled AND EITHER:
   *
   *   - total bytes < `bytesThreshold` (one TCP keepalive ≈ 60 bytes; a few HTTP/2 PINGs ≈ a few
   *     hundred), OR
   *   - `activeSeconds * 100 / periodSeconds` < `activeFractionPct` (heartbeats land in one sample
   *     in the period; real interactive use lights up many).
   *
   * Rows with `periodSeconds <= 0` are never classified as heartbeats — defensive against bad
   * router clocks; treat as active.
   */
  def isHeartbeat(row: PresenceRow, filter: HeartbeatFilter): Boolean =
    filter.enabled && (
      row.bytes < filter.bytesThreshold ||
        (row.periodSeconds > 0 &&
          row.activeSeconds.toLong * 100 < filter.activeFractionPct.toLong * row.periodSeconds.toLong)
    )

  /**
   * #714: per-row heartbeat classification for the explain debug surface. Wraps each row with the
   * classification verdict and the specific reasons it tripped, so the operator can tune
   * thresholds against real prod data.
   */
  case class Classified(
      row: PresenceRow,
      classified: String,
      reasons: List[String],
  )

  def classifyRows(rows: List[PresenceRow], filter: HeartbeatFilter): List[Classified] =
    rows.map { r =>
      val rsns = scala.collection.mutable.ListBuffer.empty[String]
      if filter.enabled then {
        if r.bytes < filter.bytesThreshold then rsns += s"bytes<${filter.bytesThreshold}"
        if r.periodSeconds > 0 &&
          r.activeSeconds.toLong * 100 < filter.activeFractionPct.toLong * r.periodSeconds.toLong
        then rsns += s"activeFraction<${filter.activeFractionPct}%"
      }
      val label = if filter.enabled && rsns.nonEmpty then "heartbeat" else "active"
      Classified(r, label, rsns.toList)
    }

  /**
   * Per-(mac, pattern) minutes, counting each bucket once per device per pattern when any host in
   * the bucket matches the pattern. Two hosts that both match the same pattern in one bucket still
   * only contribute 5 minutes; the same host matching two patterns contributes 5 minutes to each
   * (per-pattern caps are independent). IP-literal hosts never match patterns.
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
   * Note: summing across hosts can exceed the device's daily total — by design, the same 5-min
   * bucket of activity contributes 5 minutes to each host the device touched in that window. The
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
}
