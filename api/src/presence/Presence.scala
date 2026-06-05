package wifihaven.api.presence

import wifihaven.shared.{CrossDeviceOverlapMode, HeartbeatFilter}
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
   * Default idle/continuation gap `N` in seconds (design §4.1, recommended 120 s, constraint `N ≥ 2
   * × R`).
   *
   * NOTE (#1465 / #1464 coordination): this constant is provisional. The design (§4.6) puts the
   * configurable knob in `household_settings.presence_continuation_seconds` (migration V52, #1463),
   * and the sibling rollup PR #1464 threads it from settings into the daily-cap path and the
   * `time_used_daily` invalidation hook. Until that lands, the per-app/per-host surfaces here read
   * the default; the call sites pass `continuationSeconds` explicitly once #1464 wires settings
   * through. Reconcile this constant with #1464's session primitive at merge.
   */
  val DefaultContinuationSeconds: Long = 120L

  /** A half-open activity interval `[start, end)` in epoch seconds. */
  final case class Interval(start: Long, end: Long) {
    def seconds: Long = math.max(0L, end - start)
  }

  /**
   * A `traffic_reports` row's activity interval: `[period_start, period_start + period_seconds)`.
   */
  private def rowInterval(r: PresenceRow): Interval = {
    val s = r.periodStart.getEpochSecond
    Interval(s, s + r.periodSeconds.toLong)
  }

  /**
   * §4.1 session-stitch: fold time-ordered activity intervals into sessions, bridging idle gaps of
   * at most `gapSeconds` (the continuation gap `N`). Returns the disjoint merged session intervals
   * in start order. Two intervals join when the later one starts no more than `N` after the earlier
   * one ends; a gap longer than `N` closes the session. Overlapping or touching intervals always
   * merge (gap ≤ 0 ≤ N), so `gapSeconds = 0` yields a plain interval union.
   *
   * Reading the gap from `period_start` (never assuming a fixed report rate `R`) is what makes the
   * §4.4-1 invariant hold: when `N < R` contiguous-but-point-anchored windows do not merge and
   * presence correctly reflects the data instead of a rate-assumed constant.
   *
   * NOTE: provisional in #1465 pending #1464, which introduces the canonical primitive in this same
   * file — reconcile at merge (see [[DefaultContinuationSeconds]]).
   */
  def stitchSessions(intervals: Iterable[Interval], gapSeconds: Long): List[Interval] =
    intervals.toList
      .sortBy(_.start)
      .foldLeft(List.empty[Interval]) {
        case (cur :: rest, iv) if iv.start - cur.end <= gapSeconds =>
          Interval(cur.start, math.max(cur.end, iv.end)) :: rest
        case (acc, iv)                                             =>
          iv :: acc
      }
      .reverse

  private def sessionSeconds(intervals: Iterable[Interval], gapSeconds: Long): Long =
    stitchSessions(intervals, gapSeconds).iterator.map(_.seconds).sum

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
   * #714 heartbeat classification. As of #1465 the filter applies to every presence surface — the
   * daily total (`totalSecondsByMac`/`totalMinutesByMac`), the per-site breakdown
   * (`patternMinutesByMac`) and the per-host/per-app breakdown (`hostMinutes`,
   * `proportionalHostSeconds`) — so keepalives no longer inflate per-site time or per-app presence
   * (design §4.2). Each surface takes a `filter` argument (default `Off`); callers pass
   * `settings.heartbeatFilter`. A row is classified as a heartbeat if the filter is enabled and
   * total bytes are below `bytesThreshold` (one TCP keepalive ≈ 60 bytes; a few HTTP/2 PINGs ≈ a
   * few hundred) or its FQDN matches a heartbeat host pattern.
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
   * patterns. Heartbeat rows are stripped first (#1465) so a keepalive run never ticks a per-site
   * cap.
   */
  def patternMinutesByMac(
      rows: List[PresenceRow],
      patterns: List[String],
      filter: HeartbeatFilter = HeartbeatFilter.Off,
  ): Map[(MacAddress, String), Int] = {
    val buckets =
      rows.filterNot(r => isHeartbeat(r, filter)).groupBy(r => (r.mac, r.periodStart)).toList
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
   * daily cap still counts the bucket once via `totalMinutesByMac`. Heartbeat rows are stripped
   * first (#1465) so keepalive-only hosts don't pad the per-host breakdown.
   */
  def hostMinutes(
      rows: List[PresenceRow],
      filter: HeartbeatFilter = HeartbeatFilter.Off,
  ): Map[HostId, Int] = {
    val accum = scala.collection.mutable.Map.empty[HostId, Long]
    for (
      (_, bucket) <- rows
        .filterNot(r => isHeartbeat(r, filter))
        .groupBy(r => (r.mac, r.periodStart))
    ) {
      val secs  = bucketSeconds(bucket)
      val hosts = bucket.iterator.map(_.host).toSet
      for (h <- hosts)
        accum.updateWith(h)(prev => Some(prev.getOrElse(0L) + secs))
    }
    accum.view.mapValues(s => (s / 60).toInt).toMap
  }

  /**
   * #1465: per-host (per-app) presence seconds via session-stitch (design §4.2) — the successor to
   * the #715 byte-share attribution. For each host (the "app"):
   *
   *   1. Per device, union *that host's* sessions on that device — non-heartbeat activity stitched
   *      on the idle gap `continuationSeconds` (one device can't be on the host twice at once). 2.
   *      Combine across the profile's devices by `overlap`: `Sum` (default) adds the per-device
   *      per-host seconds — same host on two devices double-counts; `Dedup` unions that host's
   *      intervals across devices so overlap counts once.
   *
   * Heartbeat rows are dropped *before* stitching (§4.4-2), so a keepalive window can neither start
   * nor extend a session; the idle gap bridges across a keepalive only when real sessions sit
   * within `N` on each side. This replaces byte-share weighting as the keepalive-stripping
   * mechanism, so a chatty poller no longer shows up at all rather than being weighted down to ~0.
   *
   * This is what a future per-app time limit (#127/#301/#64) reads. The per-profile daily total is
   * NOT the sum of these per-host numbers — within a device, different hosts overlap; the total has
   * its own (per-device, all-host) union (see `totalSecondsByMac`/`dedupedTotalSeconds`).
   *
   * `continuationSeconds` defaults to [[DefaultContinuationSeconds]]; callers thread the configured
   * `household_settings.presence_continuation_seconds` once #1464 wires it (see the note on
   * [[DefaultContinuationSeconds]]).
   */
  def proportionalHostSeconds(
      rows: List[PresenceRow],
      overlap: CrossDeviceOverlapMode = CrossDeviceOverlapMode.Sum,
      filter: HeartbeatFilter = HeartbeatFilter.Off,
      continuationSeconds: Long = DefaultContinuationSeconds,
  ): Map[HostId, Long] = {
    // per (device, host) → that host's stitched sessions on that device.
    val perDeviceHost: Map[(MacAddress, HostId), List[Interval]] =
      rows
        .filterNot(r => isHeartbeat(r, filter))
        .groupBy(r => (r.mac, r.host))
        .view
        .mapValues(rs => stitchSessions(rs.map(rowInterval), continuationSeconds))
        .toMap
    overlap match {
      case CrossDeviceOverlapMode.Sum   =>
        // add per-device per-host session seconds across devices.
        perDeviceHost.iterator
          .map { case ((_, h), ivs) => h -> ivs.iterator.map(_.seconds).sum }
          .toList
          .groupMapReduce(_._1)(_._2)(_ + _)
      case CrossDeviceOverlapMode.Dedup =>
        // union that host's intervals across devices (overlap counts once).
        perDeviceHost.iterator
          .map { case ((_, h), ivs) => h -> ivs }
          .toList
          .groupMapReduce(_._1)(_._2)(_ ++ _)
          .view
          .mapValues(ivs => sessionSeconds(ivs, 0L))
          .toMap
    }
  }

  /** Convenience: floor-divided minute view of [[proportionalHostSeconds]]. */
  def proportionalHostMinutes(
      rows: List[PresenceRow],
      overlap: CrossDeviceOverlapMode = CrossDeviceOverlapMode.Sum,
      filter: HeartbeatFilter = HeartbeatFilter.Off,
      continuationSeconds: Long = DefaultContinuationSeconds,
  ): Map[HostId, Int] =
    proportionalHostSeconds(rows, overlap, filter, continuationSeconds).view
      .mapValues(s => (s / 60).toInt)
      .toMap
}
