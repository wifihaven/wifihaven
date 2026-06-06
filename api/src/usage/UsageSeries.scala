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
 *   - **Per-host / per-app / per-device** stacks come from the corresponding session spans
 *     (`Presence.hostSessionSpans` / `deviceSessionSpans`), clipped to hours. A day-total of an
 *     entity equals its `proportionalHostSeconds` presence, so per-app contributions reconcile with
 *     the per-app surface and explain the total (modulo within-device overlap — the total is the
 *     device union, not the sum of per-app spans, by design §4.3, so stacks may exceed the bar;
 *     `otherMins` clamps the rendered residual non-negative).
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

  // ── #1079 / #1492 unified by-app axis ───────────────────────────────────────
  //
  // Each host's session spans are attributed to its owning app (if any — `appOfHost`) or surface
  // as a first-class host entry. Per-app minutes are the sum of the app's member-host span seconds
  // (matching the per-app presence surface, #1465); the per-hour total comes from the same session
  // union the other axes use, so the app axis reconciles with the headline too.
  case class AppInfo(id: AppId, slug: String, name: String, icon: Option[String])

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
      appOfHost: HostId => Option[AppInfo],
  ): (List[UsageEntityTotal], List[UsageEntityBucket]) = {
    type Key = Either[AppInfo, HostId]

    val deviceSpans = Presence.deviceSessionSpans(rows, exemptPatterns, filter, continuationSeconds)
    val totalByHour = totalSecondsByHour(deviceSpans, overlap, date, zone)

    // Per-host spans → grouped into entity keys (app rolls up its hosts; non-app host stands alone).
    val hostSpans = Presence.hostSessionSpans(rows, overlap, filter, continuationSeconds)
    val entitySpans: Map[Key, List[Span]] = hostSpans.iterator
      .map { case (h, spans) => appOfHost(h).map(Left(_)).getOrElse(Right(h)) -> spans }
      .toList
      .groupMapReduce(_._1)(_._2)(_ ++ _)

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
