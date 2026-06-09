package wifihaven.api.usage

import wifihaven.api.presence.{Presence, PresenceRow}
import wifihaven.api.presence.Presence.Span
import wifihaven.shared.*
import wifihaven.shared.types.*

import java.time.{LocalDate, ZoneId}

/**
 * Hourly usage timeline used by `GET /api/usage/series` (#716, #721, #722).
 *
 * #1492: this series is now **presence-based** — sourced from the same session-stitch model
 * (#1464/#1465) that produces the headline time-used total, not the old bucket-max byte-share
 * allocation. Previously each hour's minutes were `Σ max(activeSeconds)` per `(mac, period_start)`
 * bucket while the daily cap used `Presence.totalSecondsByMac` / `dedupedTotalSeconds`; the two
 * disagreed (a kid showing 56m used whose graph plotted something else). Now:
 *
 *   - **Total** per hour comes from the per-device session union (the daily-cap building block),
 *     combined across devices by the profile's `crossDeviceOverlapMode`, clipped to local hours.
 *     `presenceTotalMins` carries the day-level number floored once, so it equals the headline
 *     exactly (summing per-hour floors would lose fractions).
 *   - **Per-host / per-device** stacks come from the corresponding session spans
 *     (`Presence.hostSessionSpans` / `deviceSessionSpans`), clipped to hours.
 *   - **Per-app** stacks (the `groupBy=app` axis, #1079/#1517) come from the single per-app
 *     primitive `Presence.appSpansForProfile` (#1514/#1532) — the app's host-set gap-bridged +
 *     overlap-deduped as one stream — so an app's series total equals the per-app cap aggregate and
 *     the #1510 per-app rollup, not the per-host span sum. See `buildEntries`.
 *
 * In all cases the per-hour `totalMins` is the device session union (the daily-cap building block),
 * so stacks may exceed the bar (the total is the union, not the sum of per-app/-host spans, by
 * design §4.3); `otherMins` clamps the rendered residual non-negative.
 */
object UsageSeries {

  // ── span → hour/day minute helpers (#1492) ──────────────────────────────────

  private def hourMins(spans: List[Span], date: LocalDate, zone: ZoneId): Map[Int, Int] =
    Presence.secondsByLocalHour(spans, date, zone).view.mapValues(s => (s / 60).toInt).toMap

  private def daySecs(spans: List[Span]): Long = spans.iterator.map(_.seconds).sum

  /** The per-hour profile/device total seconds, honoring `crossDeviceOverlapMode`. */
  private def totalSecondsByHour(
      deviceSpans: Map[MacAddress, List[Span]],
      overlap: CrossDeviceOverlapMode,
      date: LocalDate,
      zone: ZoneId,
  ): Map[Int, Long] =
    overlap match {
      case CrossDeviceOverlapMode.Sum   =>
        // Add each device's union seconds per hour — overlap across devices double-counts.
        deviceSpans.valuesIterator
          .map(spans => Presence.secondsByLocalHour(spans, date, zone))
          .foldLeft(Map.empty[Int, Long]) { (acc, m) =>
            m.foldLeft(acc) { case (a, (h, s)) => a.updatedWith(h)(p => Some(p.getOrElse(0L) + s)) }
          }
      case CrossDeviceOverlapMode.Dedup =>
        // Union all devices' spans first, then clip — overlap across devices counts once.
        Presence.secondsByLocalHour(
          Presence.mergeSpans(deviceSpans.valuesIterator.flatten.toList),
          date,
          zone,
        )
    }

  /** Day total seconds matching `totalSecondsByHour` (and the headline `usedSecondsForProfile`). */
  private def totalDaySeconds(
      deviceSpans: Map[MacAddress, List[Span]],
      overlap: CrossDeviceOverlapMode,
  ): Long =
    overlap match {
      case CrossDeviceOverlapMode.Sum   => deviceSpans.valuesIterator.map(daySecs).sum
      case CrossDeviceOverlapMode.Dedup =>
        Presence.unionSeconds(deviceSpans.valuesIterator.flatten.toList)
    }

  /**
   * Build the per-hour timeline for one device (#716/#721). Presence-based: the device's session
   * union is the total; per-host stacks are the per-host session spans. No exempt patterns are
   * applied here (the device timeline has no profile site-limit context).
   */
  def build(
      rows: List[PresenceRow],
      date: LocalDate,
      zone: ZoneId,
      topN: Int,
      filter: HeartbeatFilter = HeartbeatFilter.Off,
      continuationSeconds: Int = Presence.DefaultContinuationSeconds,
  ): (List[UsageHostTotal], List[UsageBucket], Int) = {
    val deviceSpans = Presence.deviceSessionSpans(rows, Nil, filter, continuationSeconds)
    val totalByHour =
      totalSecondsByHour(deviceSpans, CrossDeviceOverlapMode.Sum, date, zone)
    val totalMins   = (totalDaySeconds(deviceSpans, CrossDeviceOverlapMode.Sum) / 60).toInt

    val hostSpans  =
      Presence.hostSessionSpans(rows, CrossDeviceOverlapMode.Sum, filter, continuationSeconds)
    val ordered    = hostSpans.toList
      .map { case (h, spans) => (h, (daySecs(spans) / 60).toInt) }
      .filter(_._2 > 0)
      .sortBy { case (h, m) => (-m, h.value) }
    val topHostIds = ordered.take(topN).map(_._1)
    val topHosts   = topHostIds.map { h => UsageHostTotal(h, ordered.toMap.apply(h)) }
    val rank       = topHostIds.zipWithIndex.toMap
    val topHourly  = topHostIds.map(h => h -> hourMins(hostSpans(h), date, zone)).toMap

    val buckets = (0 until 24).map { hr =>
      val totalMinsHr = (totalByHour.getOrElse(hr, 0L) / 60).toInt
      val perHost     = topHostIds
        .map(h => UsageBucketHost(h, topHourly(h).getOrElse(hr, 0)))
        .filter(_.mins > 0)
        .sortBy(u => rank.getOrElse(u.host, Int.MaxValue))
      val perSum      = perHost.iterator.map(_.mins).sum
      UsageBucket(
        hour = hr,
        totalMins = totalMinsHr,
        perHost = perHost,
        otherMins = (totalMinsHr - perSum).max(0),
      )
    }.toList

    (topHosts, buckets, totalMins)
  }

  /**
   * Profile-mode build (#722, #1492). Aggregates `rows` across one profile's devices for one local
   * day, presence-based.
   *
   * Returns the per-host and per-device hourly views plus `presenceTotalMins` — the day-level
   * session-stitch total (floored once) that equals the headline time-used number. The per-hour
   * `totalMins` is identical across both bucket arrays (both derive from the same session union).
   */
  def buildProfile(
      rows: List[PresenceRow],
      deviceNames: Map[MacAddress, String],
      date: LocalDate,
      zone: ZoneId,
      topN: Int,
      overlap: CrossDeviceOverlapMode,
      exemptPatterns: List[String],
      filter: HeartbeatFilter,
      continuationSeconds: Int,
  ): (
      List[UsageHostTotal],
      List[UsageBucket],
      List[UsageDeviceTotal],
      List[UsageDeviceBucket],
      Int,
  ) = {
    val deviceSpans = Presence.deviceSessionSpans(rows, exemptPatterns, filter, continuationSeconds)
    val totalByHour = totalSecondsByHour(deviceSpans, overlap, date, zone)
    val totalMins   = (totalDaySeconds(deviceSpans, overlap) / 60).toInt

    // ── Per-host stacks (presence spans, profile-combined per overlap) ─────
    val hostSpans     = Presence.hostSessionSpans(rows, overlap, filter, continuationSeconds)
    val orderedHosts  = hostSpans.toList
      .map { case (h, spans) => (h, (daySecs(spans) / 60).toInt) }
      .filter(_._2 > 0)
      .sortBy { case (h, m) => (-m, h.value) }
    val hostMinByHost = orderedHosts.toMap
    val topHostIds    = orderedHosts.take(topN).map(_._1)
    val topHosts      = topHostIds.map(h => UsageHostTotal(h, hostMinByHost(h)))
    val hostRank      = topHostIds.zipWithIndex.toMap
    val hostHourly    = topHostIds.map(h => h -> hourMins(hostSpans(h), date, zone)).toMap

    // ── Per-device stacks (each device's session union) ───────────────────
    val orderedDevices = deviceSpans.toList
      .map { case (mac, spans) => (mac, (daySecs(spans) / 60).toInt) }
      .filter(_._2 > 0)
      .sortBy { case (mac, m) => (-m, mac.value) }
    val devMinByMac    = orderedDevices.toMap
    val topDeviceMacs  = orderedDevices.take(topN).map(_._1)
    val topDevices     = topDeviceMacs.map { mac =>
      UsageDeviceTotal(mac, deviceNames.getOrElse(mac, mac.value), devMinByMac(mac))
    }
    val deviceRank     = topDeviceMacs.zipWithIndex.toMap
    val deviceHourly = topDeviceMacs.map(mac => mac -> hourMins(deviceSpans(mac), date, zone)).toMap

    // ── Per-hour assembly ─────────────────────────────────────────────────
    val bucketsByHost   = scala.collection.mutable.ArrayBuffer.empty[UsageBucket]
    val bucketsByDevice = scala.collection.mutable.ArrayBuffer.empty[UsageDeviceBucket]
    for (hr <- 0 until 24) {
      val totalMinsHr = (totalByHour.getOrElse(hr, 0L) / 60).toInt

      val perHost = topHostIds
        .map(h => UsageBucketHost(h, hostHourly(h).getOrElse(hr, 0)))
        .filter(_.mins > 0)
        .sortBy(u => hostRank.getOrElse(u.host, Int.MaxValue))
      bucketsByHost += UsageBucket(
        hour = hr,
        totalMins = totalMinsHr,
        perHost = perHost,
        otherMins = (totalMinsHr - perHost.iterator.map(_.mins).sum).max(0),
      )

      val perDevice = topDeviceMacs
        .map(mac =>
          UsageBucketDevice(
            mac,
            deviceNames.getOrElse(mac, mac.value),
            deviceHourly(mac).getOrElse(hr, 0),
          ),
        )
        .filter(_.mins > 0)
        .sortBy(u => deviceRank.getOrElse(u.deviceMac, Int.MaxValue))
      bucketsByDevice += UsageDeviceBucket(
        hour = hr,
        totalMins = totalMinsHr,
        perDevice = perDevice,
        otherMins = (totalMinsHr - perDevice.iterator.map(_.mins).sum).max(0),
      )
    }

    (topHosts, bucketsByHost.toList, topDevices, bucketsByDevice.toList, totalMins)
  }

  // ── #1079 / #1492 / #1517 unified by-app axis ────────────────────────────────
  //
  // Each host's session spans are attributed to its owning app (if any — `axis.appOf`) or surface
  // as a first-class host entry. An app entity's spans are the **gap-bridged union of its whole
  // host-set** from the single per-app presence primitive ([[Presence.appSpansForProfile]],
  // #1514/#1532) — NOT the concatenation of the app's per-host stitched spans, which neither
  // bridges the idle gap between two different hosts of the same app (the apex going quiet while an
  // off-domain asset / CDN host carries the next request, #1505) nor dedups their within-app
  // overlap, and so disagreed with the per-app cap aggregate ([[Presence.appSecondsForProfile]]) and
  // the #1510 per-app daily rollup. Reading the same primitive the cap and rollup read makes the
  // app's series total reconcile with both by construction (#1517 — the #1492 "graphs reconcile
  // with the headline total" invariant, extended to the app dimension). The per-hour total still
  // comes from the device session union the other axes use, so the app axis reconciles with the
  // headline too.
  case class AppInfo(id: AppId, slug: String, name: String, icon: Option[String])

  /**
   * The by-app axis input for [[buildEntries]]. `appOf` attributes a host to its owning app (apex-
   * aware, lowest-appId tiebreak — #1061/#1085); `patternsBySlug` is each app's full host-set
   * (apex-form patterns from `app_hosts`) so the per-app spans are computed over the SAME host-set
   * the cap aggregate and rollup use ([[Presence.appSpansForProfile]] / `groupAppLimits`), keyed by
   * `apps.slug`. The two are built together from one `app_hosts` snapshot ([[loadAppLookup]]).
   */
  final case class AppAxis(
      appOf: HostId => Option[AppInfo],
      patternsBySlug: Map[String, List[String]],
  )

  object AppAxis {
    val empty: AppAxis = AppAxis(_ => None, Map.empty)
  }

  private def entityRefOf(key: Either[AppInfo, HostId]): UsageEntityRef =
    key match {
      case Left(a)  =>
        UsageEntityRef(
          kind = "app",
          id = a.slug,
          name = a.name,
          appId = Some(a.id),
          appIcon = a.icon,
          host = None,
        )
      case Right(h) =>
        UsageEntityRef(
          kind = "host",
          id = h.value,
          name = h.value,
          appId = None,
          appIcon = None,
          host = Some(h),
        )
    }

  private def sortKey(k: Either[AppInfo, HostId]): (Int, String) = k match {
    case Left(a)  => (0, a.slug)
    case Right(h) => (1, h.value)
  }

  def buildEntries(
      rows: List[PresenceRow],
      date: LocalDate,
      zone: ZoneId,
      topN: Int,
      overlap: CrossDeviceOverlapMode,
      exemptPatterns: List[String],
      filter: HeartbeatFilter,
      continuationSeconds: Int,
      axis: AppAxis,
  ): (List[UsageEntityTotal], List[UsageEntityBucket]) = {
    type Key = Either[AppInfo, HostId]

    val deviceSpans = Presence.deviceSessionSpans(rows, exemptPatterns, filter, continuationSeconds)
    val totalByHour = totalSecondsByHour(deviceSpans, overlap, date, zone)

    // Per-host spans classify which apps are present and which hosts surface standalone.
    val hostSpans = Presence.hostSessionSpans(rows, overlap, filter, continuationSeconds)
    val appsPresent: Map[String, AppInfo] =
      hostSpans.keysIterator.flatMap(axis.appOf).map(a => a.slug -> a).toMap

    // #1517: an app's spans are the gap-bridged union of its WHOLE host-set via the single per-app
    // primitive (#1514/#1532) — same groups/overlap/filter/continuation the per-app cap aggregate
    // (`TimeStatusService.appDayStates` / `appSecondsByApp`) and the #1510 rollup read, so the per-
    // app series total equals the cap and the rollup by construction (not the per-host concat, which
    // neither bridges cross-host idle gaps nor dedups within-app overlap).
    val appSpans: Map[String, List[Span]] =
      Presence.appSpansForProfile(
        rows,
        appsPresent.valuesIterator
          .map(a => a.slug -> axis.patternsBySlug.getOrElse(a.slug, Nil))
          .toList,
        overlap,
        filter,
        continuationSeconds,
      )

    val entitySpans: Map[Key, List[Span]] = {
      val appEntries: Iterator[(Key, List[Span])]  =
        appsPresent.iterator.map { case (slug, info) =>
          (Left(info): Key) -> appSpans.getOrElse(slug, Nil)
        }
      // Non-app hosts surface standalone with their own per-host spans.
      val hostEntries: Iterator[(Key, List[Span])] =
        hostSpans.iterator.collect {
          case (h, spans) if axis.appOf(h).isEmpty => (Right(h): Key) -> spans
        }
      (appEntries ++ hostEntries).toMap
    }

    val ordered    = entitySpans.toList
      .map { case (k, spans) => (k, (daySecs(spans) / 60).toInt) }
      .filter(_._2 > 0)
      .sortBy { case (k, m) => (-m, sortKey(k)) }
    val minByKey   = ordered.toMap
    val topKeys    = ordered.take(topN).map(_._1)
    val topEntries = topKeys.map(k => UsageEntityTotal(entityRefOf(k), minByKey(k)))
    val keyHourly  = topKeys.map(k => k -> hourMins(entitySpans(k), date, zone)).toMap

    val buckets = (0 until 24).map { hr =>
      val totalMinsHr = (totalByHour.getOrElse(hr, 0L) / 60).toInt
      // topKeys is already in display rank order, so the filtered list stays ranked.
      val perEntity   = topKeys
        .map(k => UsageBucketEntity(entityRefOf(k), keyHourly(k).getOrElse(hr, 0)))
        .filter(_.mins > 0)
      val perSum      = perEntity.iterator.map(_.mins).sum
      UsageEntityBucket(
        hour = hr,
        totalMins = totalMinsHr,
        perEntity = perEntity,
        otherMins = (totalMinsHr - perSum).max(0),
      )
    }.toList

    (topEntries, buckets)
  }
}
