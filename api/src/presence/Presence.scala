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

  /**
   * #1666: minimum bytes for a `traffic_reports` row to *anchor* a per-app session in
   * [[appSpansForProfile]]. A TCP keepalive is ~60 bytes and an HTTP/2 PING is a few hundred at
   * most, so 256 admits any substantive request (a 304, a JSON poll, an asset fetch) while a pure-
   * keepalive cadence on an app's host produces no anchor and the session is dropped.
   *
   * The check is per-session — not per-row — so row-level attribution-beats-suppression (#1506) is
   * preserved: an app-attributed low-byte row still contributes to a session anchored by another
   * substantive row in the same session. Only sessions with NO anchor are dropped. This is the
   * single surface the guard lives on (the canonical per-app primitive); the rollup, the cap and
   * the per-app graph all inherit it (single-source-of-truth).
   */
  val AppSessionAnchorBytes: Long = 256L

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
      appHostPatterns: List[String] = Nil,
  ): List[PresenceRow] = {
    def isExempt(h: HostId) = HostMatch.matchesAny(h, exemptPatterns)
    // #1506: app attribution beats background/byte suppression; exempt-from-daily filtering is a
    // separate concern and still applies afterward (an exempt app's host is still excluded from the
    // daily total even though it is no longer suppressed as a heartbeat).
    rows.filterNot(r => isHeartbeat(r, filter, appHostPatterns)).filterNot(r => isExempt(r.host))
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
      appHostPatterns: List[String] = Nil,
  ): Map[MacAddress, Long] = {
    val counted = countedRows(rows, exemptPatterns, filter, appHostPatterns)
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
      appHostPatterns: List[String] = Nil,
  ): Map[MacAddress, Int] =
    totalSecondsByMac(rows, exemptPatterns, filter, continuationSeconds, appHostPatterns).view
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
      appHostPatterns: List[String] = Nil,
  ): Long = {
    val counted = countedRows(rows, exemptPatterns, filter, appHostPatterns)
    val gap     = effectiveGap(counted, continuationSeconds)
    unionSeconds(sessionSpans(counted, gap))
  }

  def dedupedTotalMinutes(
      rows: List[PresenceRow],
      exemptPatterns: List[String],
      filter: HeartbeatFilter = HeartbeatFilter.Off,
      continuationSeconds: Int = DefaultContinuationSeconds,
      appHostPatterns: List[String] = Nil,
  ): Int =
    (dedupedTotalSeconds(
      rows,
      exemptPatterns,
      filter,
      continuationSeconds,
      appHostPatterns,
    ) / 60).toInt

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
      appHostPatterns: List[String] = Nil,
  ): Map[MacAddress, List[Span]] = {
    val counted = countedRows(rows, exemptPatterns, filter, appHostPatterns)
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
   * `settings.heartbeatFilter`. A row is classified as a heartbeat if its FQDN is on the unified
   * device-infra list (see below) OR — when the filter is enabled — total bytes are below
   * `bytesThreshold` (one TCP keepalive ≈ 60 bytes; a few HTTP/2 PINGs ≈ a few hundred).
   *
   * #1503/#1525: the FQDN check consults the canonical [[InfraHosts]] background set — the same
   * device-level infra `PolicyService.infraAllowHosts` carves out of the block, plus the
   * suppress-only tier folded in from the retired `household_settings.heartbeat_host_patterns`
   * column (#1525). Maintaining a suppression list and an allow list as two hand-curated copies let
   * them drift (the #1499 ~93%-background over-count leak). Suppression keys purely on host
   * *identity* and applies regardless of `filter.enabled` — device infra is never engagement,
   * mirroring the allow side consuming the list unconditionally. Because it keys on identity (never
   * on low bytes / short activity) it cannot re-open the #1446 undercount: a genuine app's sparse,
   * low-byte requests are not on the list, so they still count. The `bytesThreshold` keepalive
   * floor is unchanged and still gated on `filter.enabled`.
   *
   * #1506: ATTRIBUTION BEATS SUPPRESSION. `appHostPatterns` is the union of the host-sets of the
   * apps active for the profile/MAC being counted (from #1505
   * [[TimeStatusService.groupAppLimits]]). A row whose host matches any of those patterns is
   * attributed to a real app, so it can NEVER be dropped as background infra OR as a
   * sub-threshold-byte keepalive — it must count toward that app. This is the runtime guard for the
   * boundary [[InfraHosts]] documents (device-level infra only; per-app asset hosts must attribute
   * and count) and closes the #1499 over-suppression seam: an asset/CDN host an app genuinely
   * depends on that happens to match a background pattern is rescued by its app membership. Only
   * hosts attributed to NO active app fall through to background/byte suppression, so device infra
   * with nothing behind it stays suppressed exactly as before. Callers that have no app context
   * pass `Nil` (the default), preserving prior behavior. This is the single app-aware predicate
   * every counting surface routes through — do not re-derive suppression elsewhere (the #1532
   * divergence lesson).
   */
  def isHeartbeat(
      row: PresenceRow,
      filter: HeartbeatFilter,
      appHostPatterns: List[String] = Nil,
  ): Boolean =
    suppressedAsBackground(row.host, appHostPatterns) ||
      (!isAppAttributed(
        row,
        appHostPatterns,
      ) && filter.enabled && row.bytes < filter.bytesThreshold)

  /**
   * #1559: host-keyed "drop unless app-attributed" predicate — the ranking-side analogue of
   * [[isHeartbeat]]'s suppression branch, without the PresenceRow byte-floor. A host is suppressed
   * as device-level background iff it is on the [[InfraHosts.isBackground]] list AND no active
   * app's host-set claims it (attribution beats suppression, same #1506 contract every counting
   * surface routes through). The SINGLE host-keyed entry point: `isHeartbeat` reuses this for its
   * suppression branch and `DashboardNowRoutes.dropBackground` calls it directly, so the rule
   * cannot diverge between counting and ranking (#1532).
   *
   * An empty `appHostPatterns` (no app context) reduces to plain `InfraHosts.isBackground` —
   * preserves prior behavior for callers without app-attribution data.
   */
  def suppressedAsBackground(host: HostId, appHostPatterns: List[String]): Boolean =
    InfraHosts.isBackground(host) && !HostMatch.matchesAny(host, appHostPatterns)

  /**
   * #1506: whether the row's FQDN is attributed to one of the active apps' host-sets — the
   * predicate that lets attribution win over suppression in [[isHeartbeat]]. Keyed on host identity
   * via the shared [[matchesPattern]] (so apex patterns match subdomains, same as the app-presence
   * surfaces); IP-literal hosts never match patterns. An empty `appHostPatterns` (no app context)
   * is never attributed.
   */
  def isAppAttributed(row: PresenceRow, appHostPatterns: List[String]): Boolean =
    HostMatch.matchesAny(row.host, appHostPatterns)

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

  /**
   * #1507: aggregate per-host bytes / bucket-count for rows the canonical [[isHeartbeat]] predicate
   * suppresses — the same rows excluded from session-stitch counting. Lets the dashboard surface
   * "background / infra (no engaged time)" without re-implementing the predicate (the
   * single-source-of-truth lesson; see AGENTS.md §1532). `appHostPatterns` flows straight into
   * [[isHeartbeat]] so app-attributed hosts (after #1506) are NOT reported as suppressed — they're
   * counted as engagement. IP-literal hosts never appear because the suppression list keys on
   * FQDNs.
   *
   * #1560 will collapse the per-device span and suppression-list call sites into one entry point;
   * until then, callers should pass the same `appHostPatterns` they pass to [[deviceSessionSpans]]
   * / [[hostSessionSpans]] so the two views can't disagree.
   */
  case class SuppressedHostRow(host: HostId, bytes: Long, buckets: Int, reason: String)

  def suppressedHostUsage(
      rows: List[PresenceRow],
      filter: HeartbeatFilter,
      appHostPatterns: List[String] = Nil,
  ): List[SuppressedHostRow] =
    rows
      .filter(r => isHeartbeat(r, filter, appHostPatterns))
      .groupBy(_.host)
      .iterator
      .map { case (h, rs) =>
        val isInfra = InfraHosts.isBackground(h)
        val reason  = if (isInfra) "infra" else "bytes-below-threshold"
        SuppressedHostRow(h, rs.iterator.map(_.bytes).sum, rs.size, reason)
      }
      .toList
      .sortBy(r => (-r.bytes, r.host.value))

  def classifyRows(rows: List[PresenceRow], filter: HeartbeatFilter): List[Classified] =
    rows.map { r =>
      val rsns = scala.collection.mutable.ListBuffer.empty[String]
      // #1503/#1525: device-level background infra is always suppressed, independent of
      // filter.enabled — keyed on host identity via the unified InfraHosts background set.
      for {
        fqdn <- r.host.asFqdn
        p    <- InfraHosts.matchedBackgroundPattern(fqdn.value)
      } rsns += s"infra:$p"
      // The byte-floor keepalive heuristic is still operator-gated.
      if filter.enabled && r.bytes < filter.bytesThreshold then
        rsns += s"bytes<${filter.bytesThreshold}"
      val label = if rsns.nonEmpty then "heartbeat" else "active"
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
      matching = active.filter(r => HostMatch.matchesAny(r.host, List(pat)))
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
            active.filter(r => HostMatch.matchesAny(r.host, List(pat)))
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
   * #1505 (+ #1504): profile-level engaged seconds per **app host-set group**, combined across the
   * profile's devices per `overlap`. A *group* is `(key, patterns)` — an app's `app:<slug>` label
   * and its full host-set. This is the host-set-scoped counterpart of [[patternSecondsForProfile]]:
   * a row counts toward a group when **any** pattern in the group matches its host, so the app's
   * apex and its off-domain asset/CDN hosts aggregate into **one** budget rather than each host
   * ticking its own. Built on the same #1464 session-stitch primitive ([[sessionSpans]] /
   * [[unionSeconds]]) as the daily total and the per-pattern path, so the per-app limit ticks on
   * engaged wall-clock time — not the legacy bucket-max floor.
   *
   * `Sum` adds each device's per-group session seconds (the same app on two screens double-counts);
   * `Dedup` unions a group's matching-host sessions across every device so simultaneous use on two
   * screens counts once — the same cross-device contract as the daily total. Within a device a
   * window is counted once even if it touched several of the group's hosts (the union dedups it).
   * `rows` must already be scoped to the profile's devices. Seconds are summed before any
   * floor-division so the minute view rounds floor-of-sum, matching the daily total. Heartbeat rows
   * are stripped first (#1465); a group with no matching activity is absent from the result.
   *
   * #1514: now a thin alias for [[appSecondsForProfile]] — the single per-app time computation
   * (#1532). The one behavioural change is that the app's host-set is session-stitched as one
   * stream per device, so an idle gap `< N` between two *different* hosts of the same app bridges
   * (the apex + off-domain assets count as one engaged span) instead of each host stitching in
   * isolation and only overlap-merging afterward. See [[appSpansForProfile]] for the rationale.
   */
  def patternGroupSecondsForProfile(
      rows: List[PresenceRow],
      groups: List[(String, List[String])],
      overlap: CrossDeviceOverlapMode = CrossDeviceOverlapMode.Sum,
      filter: HeartbeatFilter = HeartbeatFilter.Off,
      continuationSeconds: Int = DefaultContinuationSeconds,
  ): Map[String, Long] =
    appSecondsForProfile(rows, groups, overlap, filter, continuationSeconds)

  /** Floor-divided minute view of [[patternGroupSecondsForProfile]] (per app host-set group). */
  def patternGroupMinutesForProfile(
      rows: List[PresenceRow],
      groups: List[(String, List[String])],
      overlap: CrossDeviceOverlapMode = CrossDeviceOverlapMode.Sum,
      filter: HeartbeatFilter = HeartbeatFilter.Off,
      continuationSeconds: Int = DefaultContinuationSeconds,
  ): Map[String, Int] =
    patternGroupSecondsForProfile(rows, groups, overlap, filter, continuationSeconds).view
      .mapValues(s => (s / 60).toInt)
      .filter(_._2 != 0)
      .toMap

  /**
   * #1514: per-app session spans, **gap-bridged across the app's whole host-set**, combined across
   * the profile's devices per `overlap`. A *group* is `(key, patterns)` — an app's `app:<slug>`
   * label and its full host-set ([[TimeStatusService.groupAppLimits]]).
   *
   * This is the span/union analogue of [[patternSecondsForProfile]] lifted from one pattern to an
   * app's whole host-set, and the canonical per-app presence primitive every per-app consumer reads
   * through (the rollup #1516, the per-app graph #1517, the per-app cap #1515) — there is exactly
   * one per-app time computation, this one (#1532). [[patternGroupSecondsForProfile]] /
   * [[patternGroupMinutesForProfile]] delegate here.
   *
   * The distinction from a naive per-host union: within a device the app's matching rows are
   * **session-stitched as one stream** (all hosts together) on the idle gap, so an idle gap `< N`
   * between two *different* hosts of the same app — the apex going quiet while an off-domain asset
   * / CDN host carries the next request (#1505) — **bridges into one engaged span** rather than
   * splitting into two. [[sessionSpans]] stitches per `(mac, host)` and would leave that cross-host
   * gap unbridged; here we stitch the union of the app's hosts per device. Built on the shared
   * #1464 primitive ([[spanOf]] / [[stitch]] / [[mergeSpans]]) and the same `N ≥ 2 × R` collapse
   * guard ([[effectiveGap]]), so the per-app surface stays rate-independent and consistent with the
   * daily cap and the per-host surface.
   *
   * Cross-device combination matches the daily total / per-host contract: `Sum` keeps each device's
   * per-app stitched spans separate (concatenated, so summing their seconds double-counts a host
   * used on two screens at once); `Dedup` unions a group's per-device spans so simultaneous use
   * counts once. Heartbeat rows are stripped before stitching (#1465), so a keepalive can neither
   * start nor bridge a session. `rows` must already be scoped to the profile's devices. A group
   * with no matching activity is absent from the result.
   */
  def appSpansForProfile(
      rows: List[PresenceRow],
      groups: List[(String, List[String])],
      overlap: CrossDeviceOverlapMode = CrossDeviceOverlapMode.Sum,
      filter: HeartbeatFilter = HeartbeatFilter.Off,
      continuationSeconds: Int = DefaultContinuationSeconds,
  ): Map[String, List[Span]] =
    appSpansForProfileWithDropCount(rows, groups, overlap, filter, continuationSeconds)._1

  /**
   * #1676: span form of [[appSpansForProfile]] that ALSO returns the count of per-(mac, app)
   * sessions silently dropped by the #1666 anchor-row requirement. The canonical primitive — the
   * no-count alias above projects to the spans only. The count is the single observability surface
   * for the phantom-suppression guard: a sustained rise means the threshold is too aggressive (real
   * sessions vanishing); a flat zero while phantom inflation returns means the threshold is too
   * lax. Consumers emit `presence_app_sessions_dropped_total` from this count (see
   * `AppMetrics.recordAppSessionsDropped`).
   *
   * Drops are counted per-(mac, app, session) — one un-anchored stitched session on one device for
   * one app is one drop. A session that survives the guard contributes zero to the count regardless
   * of how many rows it spans.
   */
  def appSpansForProfileWithDropCount(
      rows: List[PresenceRow],
      groups: List[(String, List[String])],
      overlap: CrossDeviceOverlapMode = CrossDeviceOverlapMode.Sum,
      filter: HeartbeatFilter = HeartbeatFilter.Off,
      continuationSeconds: Int = DefaultContinuationSeconds,
  ): (Map[String, List[Span]], Int) = {
    // #1506: the active apps ARE these groups, so their union host-set is the app-attribution set —
    // a host on an app's host-set that also matches a background pattern (an off-domain asset / CDN
    // host, #1505) attributes to the app and counts here instead of being suppressed as infra.
    val appHostPatterns = groups.flatMap(_._2)
    val active          = rows.filterNot(r => isHeartbeat(r, filter, appHostPatterns))
    val gap             = effectiveGap(active, continuationSeconds)
    def matchesGroup(r: PresenceRow, pats: List[String]): Boolean =
      HostMatch.matchesAny(r.host, pats)
    var droppedTotal                                              = 0
    val out = groups.iterator.flatMap { case (key, pats) =>
      val matching  = active.filter(r => matchesGroup(r, pats))
      // Per device, stitch the union of the app's hosts as ONE stream so a gap < N
      // between different hosts of the same app bridges (vs sessionSpans' per-(mac,host) split).
      // #1666: drop sessions that contain NO anchor row (bytes >= AppSessionAnchorBytes). A pure-
      // keepalive cadence on a real-app host would otherwise stitch into a continuous phantom span
      // via the idle-gap bridge because attribution-beats-suppression (#1506) prevents the row-
      // level byte-floor filter from catching it. The check is per-SESSION not per-row, so any
      // session with at least one substantive request keeps its full bridged span.
      val perDevice =
        matching
          .groupBy(_.mac)
          .valuesIterator
          .map { rs =>
            val sessions = stitch(rs.map(spanOf), gap)
            if sessions.isEmpty then sessions
            else {
              val kept = sessions.filter { s =>
                rs.exists { r =>
                  if r.bytes < AppSessionAnchorBytes then false
                  else {
                    val rs0 = spanOf(r)
                    rs0.startEpoch <= s.endEpoch && rs0.endEpoch >= s.startEpoch
                  }
                }
              }
              droppedTotal += (sessions.size - kept.size)
              kept
            }
          }
          .toList
      val spans     = overlap match {
        case CrossDeviceOverlapMode.Sum   => perDevice.flatten
        case CrossDeviceOverlapMode.Dedup => mergeSpans(perDevice.flatten)
      }
      if spans.nonEmpty then Some(key -> spans) else None
    }.toMap
    (out, droppedTotal)
  }

  /**
   * #1514: per-app engaged seconds, gap-bridged across the app's whole host-set — the headline
   * scalar form of [[appSpansForProfile]] (see it for the union-across-host-set + cross-host gap
   * bridge + cross-device contract). Summing the span seconds reproduces the right mode for both:
   * under `Sum` the spans are kept per device so the sum double-counts cross-device overlap; under
   * `Dedup` they are pre-merged so the sum is the union. A group with no activity is absent.
   */
  def appSecondsForProfile(
      rows: List[PresenceRow],
      groups: List[(String, List[String])],
      overlap: CrossDeviceOverlapMode = CrossDeviceOverlapMode.Sum,
      filter: HeartbeatFilter = HeartbeatFilter.Off,
      continuationSeconds: Int = DefaultContinuationSeconds,
  ): Map[String, Long] =
    appSecondsForProfileWithDropCount(rows, groups, overlap, filter, continuationSeconds)._1

  /**
   * #1676: per-app engaged seconds + the count of per-(mac, app) sessions dropped by the #1666
   * anchor-row guard. The canonical primitive — the no-count alias above projects to the map only.
   * Per [[AGENTS.md §single-source-of-truth]] the span→seconds projection lives in exactly one
   * place; consumers that want both numbers call this and never re-derive the seconds locally.
   */
  def appSecondsForProfileWithDropCount(
      rows: List[PresenceRow],
      groups: List[(String, List[String])],
      overlap: CrossDeviceOverlapMode = CrossDeviceOverlapMode.Sum,
      filter: HeartbeatFilter = HeartbeatFilter.Off,
      continuationSeconds: Int = DefaultContinuationSeconds,
  ): (Map[String, Long], Int) = {
    val (spans, dropped) =
      appSpansForProfileWithDropCount(rows, groups, overlap, filter, continuationSeconds)
    val secs             = spans.view
      .mapValues(_.iterator.map(_.seconds).sum)
      .filter(_._2 > 0L)
      .toMap
    (secs, dropped)
  }

  /** Floor-divided minute view of [[appSecondsForProfile]] (per app host-set group). */
  def appMinutesForProfile(
      rows: List[PresenceRow],
      groups: List[(String, List[String])],
      overlap: CrossDeviceOverlapMode = CrossDeviceOverlapMode.Sum,
      filter: HeartbeatFilter = HeartbeatFilter.Off,
      continuationSeconds: Int = DefaultContinuationSeconds,
  ): Map[String, Int] =
    appSecondsForProfile(rows, groups, overlap, filter, continuationSeconds).view
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

  /**
   * #1898 (shared-hosts S3): outcome tally for the shared-host reporting-attribution metric,
   * counted per shared-host stitched span. Bounded — exactly three buckets, no per-mac/host/app
   * cardinality: `attributed` (exactly one co-present qualifier), `split` (≥2 qualifiers, bytes
   * split equally), `other` (no qualifier → surfaces in the "Other"/orphan bucket).
   */
  final case class SharedHostAttributionCounts(attributed: Long, split: Long, other: Long) {
    def +(o: SharedHostAttributionCounts): SharedHostAttributionCounts =
      SharedHostAttributionCounts(
        attributed + o.attributed,
        split + o.split,
        other + o.other,
      )
  }
  object SharedHostAttributionCounts                                                       {
    val zero: SharedHostAttributionCounts = SharedHostAttributionCounts(0L, 0L, 0L)
  }

  /**
   * Strict (positive-length) temporal intersection — `[start, end)` half-open, so spans that merely
   * touch at an endpoint do NOT overlap. A shared row may refine WITHIN a distinctive session but
   * never extend or create one (design §"Allocation rule").
   */
  private def spansOverlap(a: Span, b: Span): Boolean =
    a.startEpoch.max(b.startEpoch) < a.endEpoch.min(b.endEpoch)

  /**
   * #1898 (shared-hosts S3): allocate ONE shared host's stitched session-seconds to app(s) by
   * TEMPORAL CO-PRESENCE with each candidate app's DISTINCTIVE spans (the #1897
   * [[wifihaven.api.policy.TimeStatusService.distinctiveSpansByApp]] seam), returning the
   * proportional seconds keyed by `Some(appId)` for each qualifying app and `None` for the
   * unattributed "Other" bucket, plus the per-span outcome tally for the metric.
   *
   * This is NOT the rejected #842/#715 Path 1 argmax-foreground heuristic: a shared span is
   * credited to an app iff that app's OWN distinctive session overlaps it — a presence predicate,
   * never a byte magnitude comparison — and when several apps qualify the seconds split EQUALLY
   * among them (deterministic, symmetric, picks no winner). A span overlapping no candidate's
   * distinctive span is credited to NONE (`None` key).
   *
   * The host's rows are stitched the SAME way [[proportionalHostSeconds]] stitches them (same
   * [[spanOf]]/[[stitch]], the same [[effectiveGap]] over the full active set, and the same
   * per-device → `overlap`-mode combination), so summing the returned allocation reproduces
   * `proportionalHostSeconds(host)` exactly — equal split conserves seconds and the integer
   * remainder rides the lowest-`appId` qualifier so nothing is lost.
   *
   * `rows` is the full profile presence batch (used to size the gap consistently with
   * `proportionalHostSeconds`); only `sharedHost`'s rows are stitched. `candidateDistinctiveSpans`
   * must already be restricted to the apps that LIST this host as shared.
   */
  def allocateSharedHostSeconds(
      rows: List[PresenceRow],
      sharedHost: HostId,
      candidateDistinctiveSpans: Map[AppId, List[Span]],
      overlap: CrossDeviceOverlapMode = CrossDeviceOverlapMode.Sum,
      filter: HeartbeatFilter = HeartbeatFilter.Off,
      continuationSeconds: Int = DefaultContinuationSeconds,
  ): (Map[Option[AppId], Long], SharedHostAttributionCounts) = {
    val active    = rows.filterNot(r => isHeartbeat(r, filter))
    val gap       = effectiveGap(active, continuationSeconds)
    val hostRows  = active.filter(_.host == sharedHost)
    // Per device, stitch THIS host's sessions (one device can't be on the host twice at once), then
    // combine across devices exactly as `proportionalHostSeconds` does: `Sum` keeps per-device spans
    // separate; `Dedup` unions them so simultaneous cross-device use counts once.
    val perDevice =
      hostRows.groupBy(_.mac).valuesIterator.map(rs => stitch(rs.map(spanOf), gap)).toList
    val hostSpans = overlap match {
      case CrossDeviceOverlapMode.Sum   => perDevice.flatten
      case CrossDeviceOverlapMode.Dedup => mergeSpans(perDevice.flatten)
    }

    val alloc  = scala.collection.mutable.Map.empty[Option[AppId], Long]
    var counts = SharedHostAttributionCounts.zero
    hostSpans.foreach { s =>
      if (s.seconds > 0L) {
        val qualifiers = candidateDistinctiveSpans.iterator
          .collect {
            case (appId, dSpans) if dSpans.exists(d => spansOverlap(d, s)) => appId
          }
          .toList
          .sortBy(_.value)
        qualifiers match {
          case Nil       =>
            alloc.updateWith(None)(p => Some(p.getOrElse(0L) + s.seconds))
            counts = counts.copy(other = counts.other + 1L)
          case oneOrMore =>
            val n    = oneOrMore.size.toLong
            val base = s.seconds / n
            val rem  = s.seconds % n
            // Equal split; the indivisible remainder rides the first (lowest-appId) qualifiers so the
            // allocation sums to the span's seconds exactly (no winner-by-magnitude — purely by id).
            oneOrMore.zipWithIndex.foreach { case (appId, i) =>
              val share = base + (if (i.toLong < rem) 1L else 0L)
              if (share > 0L) {
                alloc.updateWith(Some(appId))(p => Some(p.getOrElse(0L) + share))
                ()
              }
            }
            counts =
              if (n == 1L) counts.copy(attributed = counts.attributed + 1L)
              else counts.copy(split = counts.split + 1L)
        }
      }
    }
    (alloc.toMap, counts)
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
