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
 * (per-pattern) minutes are computed independently from the same #1464 session-stitch primitive
 * (#1504: [[patternSecondsForProfile]]), so the per-app limit ticks on engaged wall-clock time for
 * its own cap check rather than the legacy bucket-max floor.
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
   * Overlap-merge spans (gap 0) into a minimal, sorted, non-overlapping set. Shared by
   * [[unionSeconds]] and the hour-clipped decomposition (#1492) so the union semantics are
   * identical whether the caller wants total seconds or a per-hour breakdown.
   */
  def mergeSpans(spans: List[Span]): List[Span] =
    spans
      .sortBy(_.startEpoch)
      .foldLeft(List.empty[Span]) {
        case (head :: tail, s) if s.startEpoch <= head.endEpoch =>
          head.copy(endEpoch = head.endEpoch.max(s.endEpoch)) :: tail
        case (acc, s)                                           => s :: acc
      }
      .reverse

  /**
   * Overlap-merge spans (gap 0) and sum their wall-clock seconds — the within-device/profile union.
   */
  def unionSeconds(spans: List[Span]): Long =
    mergeSpans(spans).map(_.seconds).sum

  /**
   * #1492: distribute spans across the 24 local-hour buckets of `date` in `zone`, in seconds. Each
   * span is clipped to every hour it overlaps; spans are summed, so the caller controls overlap
   * semantics by pre-merging (union — one contribution) or concatenating (sum — each counted). The
   * seconds outside `[date 00:00, date+1 00:00)` are dropped, so summing the map reconciles with
   * [[unionSeconds]] of the same (merged) spans whenever those spans lie within the day window —
   * which the day-scoped presence query guarantees. Hours with no overlap are absent.
   */
  def secondsByLocalHour(
      spans: List[Span],
      date: LocalDate,
      zone: java.time.ZoneId,
  ): Map[Int, Long] = {
    val dayStart = date.atStartOfDay(zone).toInstant.getEpochSecond
    val acc      = scala.collection.mutable.Map.empty[Int, Long]
    for {
      s  <- spans
      hr <- 0 until 24
    } {
      val hourStart = dayStart + hr.toLong * 3600L
      val hourEnd   = hourStart + 3600L
      val overlap   = (s.endEpoch.min(hourEnd) - s.startEpoch.max(hourStart)).max(0L)
      if (overlap > 0L) acc(hr) = acc.getOrElse(hr, 0L) + overlap
    }
    acc.toMap
  }

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
   * #1492: per-mac merged daily session spans — the per-device building block of the daily cap, in
   * span form. Counts exactly the rows [[totalSecondsByMac]] does (non-heartbeat, non-exempt),
   * stitches per `(device, app)` on the idle gap, then unions across apps within the device.
   * `unionSeconds` of a mac's spans equals `totalSecondsByMac(...)` for that mac, so an
   * hour-clipped decomposition of these spans reconciles with the headline daily total — this is
   * what makes the per-profile usage graph presence-based (#1492) instead of bucket-max.
   */
  def deviceSessionSpans(
      rows: List[PresenceRow],
      exemptPatterns: List[String],
      filter: HeartbeatFilter = HeartbeatFilter.Off,
      continuationSeconds: Int = DefaultContinuationSeconds,
  ): Map[MacAddress, List[Span]] = {
    val counted = countedRows(rows, exemptPatterns, filter)
    val gap     = effectiveGap(counted, continuationSeconds)
    counted
      .groupBy(_.mac)
      .view
      .mapValues(macRows => mergeSpans(sessionSpans(macRows, gap)))
      .filter(_._2.nonEmpty)
      .toMap
  }

  /**
   * #1492: per-host (per-app) profile-level session spans — the span form of
   * [[proportionalHostSeconds]]. `Sum` keeps each device's stitched spans of a host separate (the
   * same host on two devices double-counts when their seconds are summed); `Dedup` unions a host's
   * spans across devices so simultaneous use on two screens counts once. Summing a host's span
   * seconds reproduces [[proportionalHostSeconds]] exactly, so the hour-clipped per-app breakdown
   * stays consistent with the per-app presence surface (#1465). Heartbeat rows are dropped before
   * stitching (§4.4-2), same as [[proportionalHostSeconds]].
   */
  def hostSessionSpans(
      rows: List[PresenceRow],
      overlap: CrossDeviceOverlapMode = CrossDeviceOverlapMode.Sum,
      filter: HeartbeatFilter = HeartbeatFilter.Off,
      continuationSeconds: Int = DefaultContinuationSeconds,
  ): Map[HostId, List[Span]] = {
    val active        = rows.filterNot(r => isHeartbeat(r, filter))
    val gap           = effectiveGap(active, continuationSeconds)
    val perDeviceHost =
      active.groupBy(r => (r.mac, r.host)).view.mapValues(rs => stitch(rs.map(spanOf), gap)).toMap
    val byHost        = perDeviceHost.iterator
      .map { case ((_, h), spans) => h -> spans }
      .toList
      .groupMapReduce(_._1)(_._2)(_ ++ _)
    overlap match {
      case CrossDeviceOverlapMode.Sum   => byHost
      case CrossDeviceOverlapMode.Dedup => byHost.view.mapValues(mergeSpans).toMap
    }
  }

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
   * #1504: per-(device, pattern) engaged seconds via session-stitch — the per-site counterpart of
   * the #1464 daily-total fix. For each site-limit pattern, take this device's non-heartbeat rows
   * whose host matches the pattern, stitch them per `(device, host)` on the idle gap, then union
   * within the device (two matching hosts active in the same minute count once). Built on the same
   * [[spanOf]] / [[stitch]] / [[unionSeconds]] primitive as [[totalSecondsByMac]] /
   * [[proportionalHostSeconds]], so the per-site count no longer inherits the legacy bucket-max
   * undercount (`max(activeSeconds)` bottoming at the ~10 s sample floor, which made ~16 min of
   * real presence read ~0). IP-literal hosts never match patterns; heartbeat rows are stripped
   * first (#1465) so a keepalive run never ticks a per-site cap. A pattern with no matching
   * activity is absent from the result.
   */
  def patternSecondsByMac(
      rows: List[PresenceRow],
      patterns: List[String],
      filter: HeartbeatFilter = HeartbeatFilter.Off,
      continuationSeconds: Int = DefaultContinuationSeconds,
  ): Map[(MacAddress, String), Long] = {
    val active = rows.filterNot(r => isHeartbeat(r, filter))
    val gap    = effectiveGap(active, continuationSeconds)
    val accum  = scala.collection.mutable.Map.empty[(MacAddress, String), Long]
    for {
      pat <- patterns
      matching = active.filter(r => r.host.asFqdn.exists(fqdn => matchesPattern(fqdn.value, pat)))
      (mac, macRows) <- matching.groupBy(_.mac)
      secs = unionSeconds(sessionSpans(macRows, gap))
      if secs > 0L
    } accum.update((mac, pat), secs)
    accum.toMap
  }

  /** Floor-divided minute view of [[patternSecondsByMac]] (per device per pattern). */
  def patternMinutesByMac(
      rows: List[PresenceRow],
      patterns: List[String],
      filter: HeartbeatFilter = HeartbeatFilter.Off,
      continuationSeconds: Int = DefaultContinuationSeconds,
  ): Map[(MacAddress, String), Int] =
    patternSecondsByMac(rows, patterns, filter, continuationSeconds).view
      .mapValues(s => (s / 60).toInt)
      .filter(_._2 != 0)
      .toMap

  /**
   * #1504: profile-level engaged seconds per site-limit pattern, combined across the profile's
   * devices per `overlap`. `Sum` adds each device's per-pattern session seconds (the same site on
   * two screens double-counts); `Dedup` unions a pattern's matching-host sessions across every
   * device so simultaneous use on two screens counts once — exactly the cross-device contract the
   * daily total uses ([[totalSecondsByMac]] + sum vs [[dedupedTotalSeconds]]). `rows` must already
   * be scoped to the profile's devices. Seconds are summed before any floor-division so the
   * per-site minutes round the same way the daily total does (floor-of-sum, not sum-of-floors).
   */
  def patternSecondsForProfile(
      rows: List[PresenceRow],
      patterns: List[String],
      overlap: CrossDeviceOverlapMode = CrossDeviceOverlapMode.Sum,
      filter: HeartbeatFilter = HeartbeatFilter.Off,
      continuationSeconds: Int = DefaultContinuationSeconds,
  ): Map[String, Long] =
    overlap match {
      case CrossDeviceOverlapMode.Sum   =>
        patternSecondsByMac(rows, patterns, filter, continuationSeconds).foldLeft(
          Map.empty[String, Long],
        ) { case (acc, ((_, pat), secs)) => acc.updated(pat, acc.getOrElse(pat, 0L) + secs) }
      case CrossDeviceOverlapMode.Dedup =>
        val active = rows.filterNot(r => isHeartbeat(r, filter))
        val gap    = effectiveGap(active, continuationSeconds)
        patterns.iterator.flatMap { pat =>
          val matching =
            active.filter(r => r.host.asFqdn.exists(fqdn => matchesPattern(fqdn.value, pat)))
          val secs     = unionSeconds(sessionSpans(matching, gap))
          if secs > 0L then Some(pat -> secs) else None
        }.toMap
    }

  /** Floor-divided minute view of [[patternSecondsForProfile]]. */
  def patternMinutesForProfile(
      rows: List[PresenceRow],
      patterns: List[String],
      overlap: CrossDeviceOverlapMode = CrossDeviceOverlapMode.Sum,
      filter: HeartbeatFilter = HeartbeatFilter.Off,
      continuationSeconds: Int = DefaultContinuationSeconds,
  ): Map[String, Int] =
    patternSecondsForProfile(rows, patterns, overlap, filter, continuationSeconds).view
      .mapValues(s => (s / 60).toInt)
      .filter(_._2 != 0)
      .toMap

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
   *      spans across devices so overlap counts once.
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
   * Built on the shared #1464 session primitive ([[spanOf]] / [[stitch]] / [[unionSeconds]]) and
   * the same [[effectiveGap]] `N ≥ 2 × R` collapse guard, so the per-app surface stays
   * rate-independent and consistent with the daily cap. `continuationSeconds` defaults to
   * [[DefaultContinuationSeconds]]; callers thread the configured
   * `household_settings.presence_continuation_seconds`.
   */
  def proportionalHostSeconds(
      rows: List[PresenceRow],
      overlap: CrossDeviceOverlapMode = CrossDeviceOverlapMode.Sum,
      filter: HeartbeatFilter = HeartbeatFilter.Off,
      continuationSeconds: Int = DefaultContinuationSeconds,
  ): Map[HostId, Long] = {
    val active = rows.filterNot(r => isHeartbeat(r, filter))
    val gap    = effectiveGap(active, continuationSeconds)
    // per (device, host) → that host's stitched sessions on that device.
    val perDeviceHost: Map[(MacAddress, HostId), List[Span]] =
      active.groupBy(r => (r.mac, r.host)).view.mapValues(rs => stitch(rs.map(spanOf), gap)).toMap
    overlap match {
      case CrossDeviceOverlapMode.Sum   =>
        // add per-device per-host session seconds across devices.
        perDeviceHost.iterator
          .map { case ((_, h), spans) => h -> spans.iterator.map(_.seconds).sum }
          .toList
          .groupMapReduce(_._1)(_._2)(_ + _)
          .filter(_._2 > 0L)
      case CrossDeviceOverlapMode.Dedup =>
        // union that host's spans across devices (overlap counts once).
        perDeviceHost.iterator
          .map { case ((_, h), spans) => h -> spans }
          .toList
          .groupMapReduce(_._1)(_._2)(_ ++ _)
          .view
          .mapValues(unionSeconds)
          .filter(_._2 > 0L)
          .toMap
    }
  }

  /** Convenience: floor-divided minute view of [[proportionalHostSeconds]]. */
  def proportionalHostMinutes(
      rows: List[PresenceRow],
      overlap: CrossDeviceOverlapMode = CrossDeviceOverlapMode.Sum,
      filter: HeartbeatFilter = HeartbeatFilter.Off,
      continuationSeconds: Int = DefaultContinuationSeconds,
  ): Map[HostId, Int] =
    proportionalHostSeconds(rows, overlap, filter, continuationSeconds).view
      .mapValues(s => (s / 60).toInt)
      .filter(_._2 != 0)
      .toMap
}
