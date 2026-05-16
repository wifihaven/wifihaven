package familydns.api.presence

import familydns.shared.types.*
import java.time.Instant

/**
 * One bucket-host tuple from traffic_reports, used to compute presence-based minutes (one count per
 * (mac, period_start), regardless of how many hosts the device touched in that window).
 */
case class PresenceRow(
    mac: MacAddress,
    periodStart: Instant,
    host: HostId,
    activeSeconds: Int,
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
   * Per-mac total minutes for the day, counting each 5-min bucket once. A bucket counts iff at
   * least one host in the bucket is NOT in `exemptPatterns` — i.e. the device was active on
   * something that bears on the daily cap. IP-literal hosts are never exempt (patterns only match
   * FQDNs).
   */
  def totalMinutesByMac(
      rows: List[PresenceRow],
      exemptPatterns: List[String],
  ): Map[MacAddress, Int] = {
    def isExempt(h: HostId) =
      h.asFqdn.exists(fqdn => exemptPatterns.exists(p => matchesPattern(fqdn.value, p)))
    rows
      .groupBy(r => (r.mac, r.periodStart))
      .toList
      .collect {
        case ((mac, _), bucket) if bucket.exists(r => !isExempt(r.host)) =>
          mac -> bucketSeconds(bucket)
      }
      .groupMapReduce(_._1)(_._2)(_ + _)
      .view
      .mapValues(s => (s / 60).toInt)
      .toMap
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
   * the bucket once. Used for the per-profile "what did they spend time on today" breakdown
   * (#262). Note: summing across hosts can exceed the device's daily total — by design, the same
   * 5-min bucket of activity contributes 5 minutes to each host the device touched in that
   * window. The daily cap still counts the bucket once via `totalMinutesByMac`.
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
