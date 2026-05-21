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
 * The output proportionally allocates each 5-min bucket's wall-clock duration across the distinct
 * hosts present in that bucket (even-share), so each hour's `perHost` + `otherMins` sums exactly to
 * `totalMins`. Bytes-weighted allocation is a #715 follow-up.
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
    // in a bucket (the router emits one batch per window) so .head is fine.
    val fiveMin = rows.groupBy(r => r.periodStart).toList.map { case (ps, bucket) =>
      val hour  = ps.atZone(zone).getHour
      val secs  = bucket.iterator.map(_.activeSeconds).maxOption.getOrElse(0)
      val hosts = bucket.iterator.map(_.host).toSet.toList
      (hour, secs, hosts)
    }

    // Day-total per host, in proportional seconds (= bucket secs / hosts-in-bucket).
    val perHostDaySecs = scala.collection.mutable.Map.empty[HostId, Double]
    for ((_, secs, hosts) <- fiveMin if hosts.nonEmpty) {
      val share = secs.toDouble / hosts.size
      for (h <- hosts) perHostDaySecs.updateWith(h)(p => Some(p.getOrElse(0.0) + share))
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
      for ((_, secs, hosts) <- hrBuckets if hosts.nonEmpty) {
        val share = secs.toDouble / hosts.size
        for (h <- hosts)
          if (topHostIds.contains(h)) perHost.updateWith(h)(p => Some(p.getOrElse(0.0) + share))
          else other += share
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
  ): (List[UsageHostTotal], List[UsageBucket], List[UsageDeviceTotal], List[UsageDeviceBucket]) = {
    // Group by (mac, periodStart) — same 5-min bucketing as the per-device build,
    // but now we keep the mac so we can later stack-by-device.
    val fiveMin = rows.groupBy(r => (r.mac, r.periodStart)).toList.map { case ((mac, ps), bucket) =>
      val hour  = ps.atZone(zone).getHour
      val secs  = bucket.iterator.map(_.activeSeconds).maxOption.getOrElse(0)
      val hosts = bucket.iterator.map(_.host).toSet.toList
      (hour, mac, secs, hosts)
    }

    // ── Per-host day totals (proportional within each bucket) ─────────────
    val perHostDaySecs = scala.collection.mutable.Map.empty[HostId, Double]
    for ((_, _, secs, hosts) <- fiveMin if hosts.nonEmpty) {
      val share = secs.toDouble / hosts.size
      for (h <- hosts) perHostDaySecs.updateWith(h)(p => Some(p.getOrElse(0.0) + share))
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
    for ((_, mac, secs, _) <- fiveMin)
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
      val totalSecs = hrBuckets.iterator.map((_, _, s, _) => s.toLong).sum
      val totalMins = (totalSecs / 60).toInt

      // Per-host stack within the hour (even-share within each 5-min bucket).
      val perHost   = scala.collection.mutable.Map.empty[HostId, Double]
      var hostOther = 0.0
      for ((_, _, secs, hosts) <- hrBuckets if hosts.nonEmpty) {
        val share = secs.toDouble / hosts.size
        for (h <- hosts)
          if (topHostIds.contains(h)) perHost.updateWith(h)(p => Some(p.getOrElse(0.0) + share))
          else hostOther += share
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
      for ((_, mac, secs, _) <- hrBuckets)
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
}
