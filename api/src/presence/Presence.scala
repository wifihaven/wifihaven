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

  /**
   * Per-mac total active-seconds for the day, summing each bucket's max activeSeconds
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
   * Per-mac total minutes for the day, counting each bucket once. A bucket counts iff at least one
   * host in the bucket is NOT in `exemptPatterns` — i.e. the device was active on something that
   * bears on the daily cap. IP-literal hosts are never exempt (patterns only match FQDNs).
   */
  def totalMinutesByMac(
      rows: List[PresenceRow],
      exemptPatterns: List[String],
      filter: HeartbeatFilter = HeartbeatFilter.Off,
  ): Map[MacAddress, Int] =
    totalSecondsByMac(rows, exemptPatterns, filter).view.mapValues(s => (s / 60).toInt).toMap

  /**
   * #751: profile-scoped active-bucket union across multiple macs. Each `period_start` instant
   * counts once regardless of how many of the profile's devices were active in it — the right
   * semantic when one profile = one human with multiple devices. A bucket counts iff at least one
   * (mac, host) row in the bucket is non-heartbeat AND non-exempt; its contribution is the max
   * `activeSeconds` across all macs present in that bucket (the longest-active device sets the
   * wall-clock floor). Use [[totalSecondsByMac]] + sum when the operator wants per-device totals
   * added (the `sum` mode).
   */
  def dedupedTotalSeconds(
      rows: List[PresenceRow],
      exemptPatterns: List[String],
      filter: HeartbeatFilter = HeartbeatFilter.Off,
  ): Long = {
    def isExempt(h: HostId) =
      h.asFqdn.exists(fqdn => exemptPatterns.exists(p => matchesPattern(fqdn.value, p)))
    rows.iterator
      .filterNot(r => isHeartbeat(r, filter))
      .toList
      .groupBy(_.periodStart)
      .iterator
      .collect {
        case (_, bucket) if bucket.exists(r => !isExempt(r.host)) =>
          bucketSeconds(bucket)
      }
      .sum
  }

  def dedupedTotalMinutes(
      rows: List[PresenceRow],
      exemptPatterns: List[String],
      filter: HeartbeatFilter = HeartbeatFilter.Off,
  ): Int =
    (dedupedTotalSeconds(rows, exemptPatterns, filter) / 60).toInt

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
