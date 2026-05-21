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
   * @param rows         PresenceRow values for the day, single mac.
   * @param date         The local-calendar date the operator requested.
   * @param zone         Local zone for hour bucketing (UTC bars are useless for parents).
   * @param topN         Number of named-host stacks to expose; the long tail collapses to
   *                     `otherMins`.
   */
  def build(
      rows: List[PresenceRow],
      zone: ZoneId,
      topN: Int,
  ): (List[UsageHostTotal], List[UsageBucket]) = {
    // Group rows into 5-min buckets. activeSeconds is identical for every row
    // in a bucket (the router emits one batch per window) so .head is fine.
    val fiveMin = rows.groupBy(r => (r.periodStart)).toList.map { case (ps, bucket) =>
      val hour = ps.atZone(zone).getHour
      val secs = bucket.iterator.map(_.activeSeconds).maxOption.getOrElse(0)
      val hosts = bucket.iterator.map(_.host).toSet.toList
      (hour, secs, hosts)
    }

    // Day-total per host, in proportional seconds (= bucket secs / hosts-in-bucket).
    val perHostDaySecs = scala.collection.mutable.Map.empty[HostId, Double]
    for ((_, secs, hosts) <- fiveMin if hosts.nonEmpty) {
      val share = secs.toDouble / hosts.size
      for (h <- hosts) perHostDaySecs.updateWith(h)(p => Some(p.getOrElse(0.0) + share))
    }

    val ordered = perHostDaySecs.toList
      .map { case (h, s) => (h, (s / 60).toInt) }
      .sortBy { case (h, m) => (-m, h.value) }

    val topHostIds = ordered.take(topN).map(_._1).toSet
    val topHosts   = ordered.take(topN).map((h, m) => UsageHostTotal(h, m))

    // Stable host ordering within each bucket: top-host daily rank.
    val rank = topHosts.iterator.map(_.host).zipWithIndex.toMap

    val byHour = fiveMin.groupBy(_._1)
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
      val totalMins   = (total / 60).toInt
      val perHostSum  = perHostList.iterator.map(_.mins).sum
      UsageBucket(
        hour = hr,
        totalMins = totalMins,
        perHost = perHostList,
        otherMins = (totalMins - perHostSum).max(0),
      )
    }.toList

    (topHosts, buckets)
  }
}
