package wifihaven.api.usage

import wifihaven.api.presence.PresenceRow
import wifihaven.shared.*
import wifihaven.shared.types.*

import java.time.ZoneId

/**
 * Hourly per-device usage timeline used by `GET /api/usage/series` (#716, #721).
 *
 * Two facts the routine has to reconcile:
 *   - Bucket-deduped wall-clock minutes per (mac, period_start) — what the daily cap counts.
 *   - Per-host minutes within those buckets — currently bucket-presence (overlapping across hosts),
 *     which over-counts (#715).
 *
 * The output proportionally allocates each 5-min bucket's wall-clock duration across the hosts
 * present in that bucket — bytes-weighted (#715) — so each hour's `perHost` + `otherMins` sums
 * exactly to `totalMins`. When the bucket has zero total bytes (an edge case — agent only emits
 * records with bytes>0), we fall back to even-share so the per-host stack still adds up.
 */
object UsageSeries {

  /**
   * Build the per-hour timeline for one device.
   *
   * @param rows
   *   PresenceRow values for the day, single mac.
   * @param date
   *   The local-calendar date the operator requested.
   * @param zone
   *   Local zone for hour bucketing (UTC bars are useless for parents).
   * @param topN
   *   Number of named-host stacks to expose; the long tail collapses to `otherMins`.
   */
  def build(
      rows: List[PresenceRow],
      zone: ZoneId,
      topN: Int,
  ): (List[UsageHostTotal], List[UsageBucket]) = {
    // Group rows into 5-min buckets. activeSeconds is identical for every row
    // in a bucket (the router emits one batch per window) so max() is fine.
    // Track per-host bytes (collapsing multiple rows for the same host within a
    // bucket) so we can do #715 byte-share-weighted allocation.
    val fiveMin = rows.groupBy(r => r.periodStart).toList.map { case (ps, bucket) =>
      val hour   = ps.atZone(zone).getHour
      val secs   = bucket.iterator.map(_.activeSeconds).maxOption.getOrElse(0)
      val byHost = bucket.iterator
        .map(r => r.host -> r.bytes)
        .toList
        .groupMapReduce(_._1)(_._2)(_ + _)
      (hour, secs, byHost)
    }

    // Day-total per host, byte-share-weighted (even-share fallback if the bucket
    // recorded zero bytes — agent only emits bytes>0 rows in practice).
    val perHostDaySecs = scala.collection.mutable.Map.empty[HostId, Double]
    for ((_, secs, byHost) <- fiveMin if byHost.nonEmpty) {
      val totalBytes = byHost.valuesIterator.sum
      if (totalBytes > 0L)
        for ((h, b) <- byHost) {
          val share = secs.toDouble * b.toDouble / totalBytes.toDouble
          perHostDaySecs.updateWith(h)(p => Some(p.getOrElse(0.0) + share))
        }
      else {
        val share = secs.toDouble / byHost.size
        for (h <- byHost.keys) perHostDaySecs.updateWith(h)(p => Some(p.getOrElse(0.0) + share))
      }
    }

    // Drop hosts whose proportional day-total floors to 0 — they'd render
    // as "host.com 0m" in the legend and as invisible stacks. Whatever
    // seconds they did accrue still land in otherMins via the per-bucket
    // residual fold below.
    val ordered = perHostDaySecs.toList
      .map { case (h, s) => (h, (s / 60).toInt) }
      .filter { case (_, m) => m > 0 }
      .sortBy { case (h, m) => (-m, h.value) }

    val topHostIds = ordered.take(topN).map(_._1).toSet
    val topHosts   = ordered.take(topN).map((h, m) => UsageHostTotal(h, m))

    // Stable host ordering within each bucket: top-host daily rank.
    val rank = topHosts.iterator.map(_.host).zipWithIndex.toMap

    val byHour  = fiveMin.groupBy(_._1)
    val buckets = (0 until 24).map { hr =>
      val hrBuckets = byHour.getOrElse(hr, Nil)
      val total     = hrBuckets.iterator.map((_, s, _) => s.toLong).sum
      val perHost   = scala.collection.mutable.Map.empty[HostId, Double]
      var other     = 0.0
      for ((_, secs, byHost) <- hrBuckets if byHost.nonEmpty) {
        val totalBytes = byHost.valuesIterator.sum
        if (totalBytes > 0L)
          for ((h, b) <- byHost) {
            val share = secs.toDouble * b.toDouble / totalBytes.toDouble
            if (topHostIds.contains(h)) perHost.updateWith(h)(p => Some(p.getOrElse(0.0) + share))
            else other += share
          }
        else {
          val share = secs.toDouble / byHost.size
          for (h <- byHost.keys)
            if (topHostIds.contains(h)) perHost.updateWith(h)(p => Some(p.getOrElse(0.0) + share))
            else other += share
        }
      }
      // Floor per-host minutes (consistent with how the daily cap reports
      // wall-clock minutes) and drop hosts that round to zero — keeps the
      // legend useful for sparse heartbeat traffic. The flooring residual,
      // including the 0-min hosts, lands in otherMins so the invariant
      // sum(perHost.mins) + otherMins == totalMins holds for the chart.
      val perHostList = perHost.iterator
        .map { case (h, s) => UsageBucketHost(h, (s / 60).toInt) }
        .filter(_.mins > 0)
        .toList
        .sortBy(u => rank.getOrElse(u.host, Int.MaxValue))
      val totalMins = (total / 60).toInt
      val perHostSum = perHostList.iterator.map(_.mins).sum
      UsageBucket(
        hour = hr,
        totalMins = totalMins,
        perHost = perHostList,
        otherMins = (totalMins - perHostSum).max(0),
      )
    }.toList

    (topHosts, buckets)
  }

  /**
   * Profile-mode build (#722). Aggregates `rows` across one profile's devices for one local day.
   *
   * Returns four parallel views of the same activity:
   *   - `topHosts` / `bucketsByHost` — proportionally allocated within each (mac, period_start)
   *     bucket, same semantics as the per-device build above.
   *   - `topDevices` / `bucketsByDevice` — minutes attributed to whichever device's mac the bucket
   *     belongs to (one bucket → one device); `sum(perDevice) + otherMins == totalMins`.
   *
   * Profile total semantics match `Routes.buildProfileTimeStatus`: per-mac bucket-deduped minutes
   * are summed across devices (overlap is not deduped at the profile level). Per-host stacks use
   * the same even-share within each (mac, period_start) bucket as the per-device build.
   *
   * @param deviceNames
   *   Display names keyed by mac. Devices missing from the map fall back to the mac string.
   * @param topN
   *   Number of named-host / named-device entries; the long tail collapses to `otherMins`.
   */
  def buildProfile(
      rows: List[PresenceRow],
      deviceNames: Map[MacAddress, String],
      zone: ZoneId,
      topN: Int,
      overlap: CrossDeviceOverlapMode = CrossDeviceOverlapMode.Sum,
  ): (List[UsageHostTotal], List[UsageBucket], List[UsageDeviceTotal], List[UsageDeviceBucket]) = {
    // Group by (mac, periodStart) — same 5-min bucketing as the per-device build,
    // but now we keep the mac so we can later stack-by-device.
    val fiveMin = rows.groupBy(r => (r.mac, r.periodStart)).toList.map { case ((mac, ps), bucket) =>
      val hour   = ps.atZone(zone).getHour
      val secs   = bucket.iterator.map(_.activeSeconds).maxOption.getOrElse(0)
      val byHost = bucket.iterator
        .map(r => r.host -> r.bytes)
        .toList
        .groupMapReduce(_._1)(_._2)(_ + _)
      (hour, mac, ps, secs, byHost)
    }

    // ── Per-host day totals (#715 byte-share within each (mac, bucket)) ────
    val perHostDaySecs = scala.collection.mutable.Map.empty[HostId, Double]
    for ((_, _, _, secs, byHost) <- fiveMin if byHost.nonEmpty) {
      val totalBytes = byHost.valuesIterator.sum
      if (totalBytes > 0L)
        for ((h, b) <- byHost) {
          val share = secs.toDouble * b.toDouble / totalBytes.toDouble
          perHostDaySecs.updateWith(h)(p => Some(p.getOrElse(0.0) + share))
        }
      else {
        val share = secs.toDouble / byHost.size
        for (h <- byHost.keys) perHostDaySecs.updateWith(h)(p => Some(p.getOrElse(0.0) + share))
      }
    }
    val orderedHosts = perHostDaySecs.toList
      .map { case (h, s) => (h, (s / 60).toInt) }
      .filter { case (_, m) => m > 0 }
      .sortBy { case (h, m) => (-m, h.value) }
    val topHostIds = orderedHosts.take(topN).map(_._1).toSet
    val topHosts = orderedHosts.take(topN).map((h, m) => UsageHostTotal(h, m))
    val hostRank = topHosts.iterator.map(_.host).zipWithIndex.toMap

    // ── Per-device day totals (one bucket → one device) ───────────────────
    val perDeviceDaySecs = scala.collection.mutable.Map.empty[MacAddress, Long]
    for ((_, mac, _, secs, _) <- fiveMin)
      perDeviceDaySecs.updateWith(mac)(p => Some(p.getOrElse(0L) + secs.toLong))
    val orderedDevices = perDeviceDaySecs.toList
      .map { case (m, s) => (m, (s / 60).toInt) }
      .filter { case (_, m) => m > 0 }
      .sortBy { case (mac, m) => (-m, mac.value) }
    val topDeviceMacs  = orderedDevices.take(topN).map(_._1).toSet
    val topDevices     = orderedDevices.take(topN).map { case (mac, mins) =>
      UsageDeviceTotal(mac, deviceNames.getOrElse(mac, mac.value), mins)
    }
    val deviceRank     = topDevices.iterator.map(_.deviceMac).zipWithIndex.toMap

    // ── Per-hour aggregation, both views ──────────────────────────────────
    val byHour          = fiveMin.groupBy(_._1)
    val bucketsByHost   = scala.collection.mutable.ArrayBuffer.empty[UsageBucket]
    val bucketsByDevice = scala.collection.mutable.ArrayBuffer.empty[UsageDeviceBucket]
    for (hr <- 0 until 24) {
      val hrBuckets = byHour.getOrElse(hr, Nil)
      // #751: in Dedup mode the per-hour total collapses to the union of
      // periodStarts (one bucket → one wall-clock contribution regardless of
      // how many of the profile's devices were active in it). The per-host
      // and per-device stacks below stay summed (they're the contribution
      // breakdown, not a strict reconcile-to-total), so in Dedup mode the
      // stacks can exceed totalMins; the otherMins clamp keeps the rendered
      // chart non-negative.
      val totalSecs = overlap match {
        case CrossDeviceOverlapMode.Sum   =>
          hrBuckets.iterator.map((_, _, _, s, _) => s.toLong).sum
        case CrossDeviceOverlapMode.Dedup =>
          hrBuckets.iterator
            .map { case (_, _, ps, s, _) => ps -> s.toLong }
            .toList
            .groupMapReduce(_._1)(_._2)(_ max _)
            .valuesIterator
            .sum
      }
      val totalMins = (totalSecs / 60).toInt

      // Per-host stack within the hour (#715 byte-share within each 5-min bucket).
      val perHost   = scala.collection.mutable.Map.empty[HostId, Double]
      var hostOther = 0.0
      for ((_, _, _, secs, byHost) <- hrBuckets if byHost.nonEmpty) {
        val totalBytes = byHost.valuesIterator.sum
        if (totalBytes > 0L)
          for ((h, b) <- byHost) {
            val share = secs.toDouble * b.toDouble / totalBytes.toDouble
            if (topHostIds.contains(h)) perHost.updateWith(h)(p => Some(p.getOrElse(0.0) + share))
            else hostOther += share
          }
        else {
          val share = secs.toDouble / byHost.size
          for (h <- byHost.keys)
            if (topHostIds.contains(h)) perHost.updateWith(h)(p => Some(p.getOrElse(0.0) + share))
            else hostOther += share
        }
      }
      val perHostList = perHost.iterator
        .map { case (h, s) => UsageBucketHost(h, (s / 60).toInt) }
        .filter(_.mins > 0)
        .toList
        .sortBy(u => hostRank.getOrElse(u.host, Int.MaxValue))
      val perHostSum = perHostList.iterator.map(_.mins).sum
      bucketsByHost += UsageBucket(
        hour = hr,
        totalMins = totalMins,
        perHost = perHostList,
        otherMins = (totalMins - perHostSum).max(0),
      )

      // Per-device stack within the hour (each bucket attributes to its mac).
      val perDevice = scala.collection.mutable.Map.empty[MacAddress, Long]
      var devOther  = 0L
      for ((_, mac, _, secs, _) <- hrBuckets)
        if (topDeviceMacs.contains(mac))
          perDevice.updateWith(mac)(p => Some(p.getOrElse(0L) + secs.toLong))
        else devOther += secs.toLong
      val perDeviceList = perDevice.iterator
        .map { case (mac, s) =>
          UsageBucketDevice(mac, deviceNames.getOrElse(mac, mac.value), (s / 60).toInt)
        }
        .filter(_.mins > 0)
        .toList
        .sortBy(u => deviceRank.getOrElse(u.deviceMac, Int.MaxValue))
      val perDeviceSum  = perDeviceList.iterator.map(_.mins).sum
      bucketsByDevice += UsageDeviceBucket(
        hour = hr,
        totalMins = totalMins,
        perDevice = perDeviceList,
        otherMins = (totalMins - perDeviceSum).max(0),
      )
    }

    (topHosts, bucketsByHost.toList, topDevices, bucketsByDevice.toList)
  }

  // ── #1079 unified by-app axis ──────────────────────────────────────────────
  //
  // For each (mac, period_start) bucket, attribute its byte-share-weighted
  // seconds to either the host's owning app (if it has one — `appOfHost`) or
  // to the host itself as a first-class entry. Day totals, top-N cap, and a
  // single 'Other' long-tail bucket are computed over the resulting mixed
  // entity list. 'Other' is strictly the long tail — non-app hosts that fit
  // inside top-N surface individually, not lumped together.
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
      zone: ZoneId,
      topN: Int,
      appOfHost: HostId => Option[AppInfo],
  ): (List[UsageEntityTotal], List[UsageEntityBucket]) = {
    // (hour, bucketSeconds, byHostBytes) — group rows into 5-min buckets per
    // (mac, period_start) so two devices in the same wall-clock window don't
    // collapse onto each other.
    type Key = Either[AppInfo, HostId]

    val fiveMin = rows.groupBy(r => (r.mac, r.periodStart)).toList.map { case ((_, ps), bucket) =>
      val hour   = ps.atZone(zone).getHour
      val secs   = bucket.iterator.map(_.activeSeconds).maxOption.getOrElse(0)
      val byHost = bucket.iterator
        .map(r => r.host -> r.bytes)
        .toList
        .groupMapReduce(_._1)(_._2)(_ + _)
      (hour, secs, byHost)
    }

    // Day totals per entity (byte-share-weighted; even-share if zero bytes).
    val perEntityDaySecs = scala.collection.mutable.Map.empty[Key, Double]
    for ((_, secs, byHost) <- fiveMin if byHost.nonEmpty) {
      val totalBytes = byHost.valuesIterator.sum
      if (totalBytes > 0L)
        for ((h, b) <- byHost) {
          val key   = appOfHost(h).map(Left(_)).getOrElse(Right(h))
          val share = secs.toDouble * b.toDouble / totalBytes.toDouble
          perEntityDaySecs.updateWith(key)(p => Some(p.getOrElse(0.0) + share))
        }
      else {
        val share = secs.toDouble / byHost.size
        for (h <- byHost.keys) {
          val key = appOfHost(h).map(Left(_)).getOrElse(Right(h))
          perEntityDaySecs.updateWith(key)(p => Some(p.getOrElse(0.0) + share))
        }
      }
    }

    val ordered = perEntityDaySecs.toList
      .map { case (k, s) => (k, (s / 60).toInt) }
      .filter { case (_, m) => m > 0 }
      .sortBy { case (k, m) => (-m, sortKey(k)) }

    val topKeys    = ordered.take(topN).map(_._1)
    val topKeySet  = topKeys.toSet
    val topEntries = ordered.take(topN).map { case (k, m) =>
      UsageEntityTotal(entityRefOf(k), m)
    }
    val rank       = topKeys.iterator.zipWithIndex.toMap

    val byHour  = fiveMin.groupBy(_._1)
    val buckets = (0 until 24).map { hr =>
      val hrBuckets = byHour.getOrElse(hr, Nil)
      val total     = hrBuckets.iterator.map { case (_, s, _) => s.toLong }.sum
      val perEntity = scala.collection.mutable.Map.empty[Key, Double]
      var other     = 0.0
      for ((_, secs, byHost) <- hrBuckets if byHost.nonEmpty) {
        val totalBytes = byHost.valuesIterator.sum
        if (totalBytes > 0L)
          for ((h, b) <- byHost) {
            val key   = appOfHost(h).map(Left(_)).getOrElse(Right(h))
            val share = secs.toDouble * b.toDouble / totalBytes.toDouble
            if (topKeySet.contains(key))
              perEntity.updateWith(key)(p => Some(p.getOrElse(0.0) + share))
            else other += share
          }
        else {
          val share = secs.toDouble / byHost.size
          for (h <- byHost.keys) {
            val key = appOfHost(h).map(Left(_)).getOrElse(Right(h))
            if (topKeySet.contains(key))
              perEntity.updateWith(key)(p => Some(p.getOrElse(0.0) + share))
            else other += share
          }
        }
      }
      val perEntityListSorted = perEntity.iterator
        .map { case (k, s) => (k, (s / 60).toInt) }
        .filter(_._2 > 0)
        .toList
        .sortBy { case (k, _) => rank.getOrElse(k, Int.MaxValue) }
        .map { case (k, m) => UsageBucketEntity(entityRefOf(k), m) }
      val totalMins = (total / 60).toInt
      val perSum    = perEntityListSorted.iterator.map(_.mins).sum
      // Residual minutes either come from the long tail OR from per-host
      // flooring drift on the top entries. We attribute drift back to the
      // top entry that lost the fractional second only when no actual tail
      // contributed in this hour — otherwise drift collapses into the tail.
      val otherMins =
        if (other > 0.0) (totalMins - perSum).max(0)
        else 0
      UsageEntityBucket(
        hour = hr,
        totalMins = totalMins,
        perEntity = perEntityListSorted,
        otherMins = otherMins,
      )
    }.toList

    (topEntries, buckets)
  }
}
