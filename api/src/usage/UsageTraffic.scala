package wifihaven.api.usage

import wifihaven.shared.*
import wifihaven.shared.types.*

import java.time.{Duration, Instant, ZoneId}
import java.time.temporal.{ChronoUnit, TemporalAdjusters}

/**
 * #769: per-host attribution to an app, sourced from `app_hosts` joined with `apps`. The aggregator
 * uses this to bucket rows by app slug; hosts not in any app fall into the synthetic `__other__`
 * bucket with `name="Other"`. Mapping is built once per request and held in memory — apps are
 * household-scoped and typically number in the tens, so the full join table fits comfortably.
 */
case class AppMembership(
    slug: String,
    name: String,
    icon: Option[String],
    appId: Option[AppId],
)

object AppMembership {
  val Other: AppMembership = AppMembership("__other__", "Other", None, None)
}

/**
 * #846: lightweight projection of a traffic_reports row for the Traffic Usage page. Carries
 * `Instant` fields directly (vs the wire-shaped String `TrafficReport`) so callers don't re-parse
 * strings just to bucket.
 */
case class TrafficUsageDbRow(
    mac: MacAddress,
    host: HostId,
    periodStart: Instant,
    periodEnd: Instant,
    activeSeconds: Int,
    bytesIn: Long,
    bytesOut: Long,
)

// #862: keyset cursor key for raw traffic_reports paging. Mirrors the secondary
// sort columns on `listRawInRange` so `(ts, mac, host) < (cursorTs, cursorMac,
// cursorHost)` is stable under concurrent inserts.
case class RawTrafficCursorKey(ts: Instant, mac: String, host: String)

/**
 * #846 — Traffic Usage page. Two views over `traffic_reports`:
 *   - Raw: one row per (period_start, mac, host) — the underlying 5-minute bucket as-stored.
 *   - Aggregated: rolled up into wider windows (10m / 1h / 12h / 1d / 1w) grouped by `domain` (the
 *     bucket's host as-is). Per-apex / per-app grouping is reserved for when PSL/apps tracks land —
 *     endpoint rejects them up-front with a typed message.
 *
 * Bucket selection comes from the SPA. `raw` is the storage-native 5m bucket. `1m` is reserved for
 * a future router-side cadence bump (#846 mentions); endpoint rejects with a typed message.
 * Rollup-table-backed paths (#809) are not yet wired — every bucket reads on-the-fly from
 * `traffic_reports`. The on-the-fly path has a window cap so wide ranges return 503 rather than
 * melting the DB.
 */
object UsageTraffic {

  enum Bucket(val code: String) {
    case Raw        extends Bucket("raw")
    case OneMin     extends Bucket("1m")
    case TenMin     extends Bucket("10m")
    case OneHour    extends Bucket("1h")
    case TwelveHour extends Bucket("12h")
    case OneDay     extends Bucket("1d")
    case OneWeek    extends Bucket("1w")
  }

  object Bucket {
    def parse(s: String): Option[Bucket] = values.find(_.code == s)
  }

  // #846 audit: groupBy is composable. Domain / Device / Profile can be
  // combined (e.g. groupBy=device,domain). Apex deferred to #856 (needs
  // PSL), App deferred to #857 (needs apps track) — both are accepted
  // wire values but rejected at the route layer with typed errors.
  enum GroupBy(val code: String) {
    case Domain  extends GroupBy("domain")
    case Device  extends GroupBy("device")
    case Profile extends GroupBy("profile")
    case Apex    extends GroupBy("apex")
    case App     extends GroupBy("app")
  }

  object GroupBy {
    def parse(s: String): Option[GroupBy] = values.find(_.code == s)
  }

  // #862: stable group key for keyset cursor on the aggregated path. Must
  // mirror the order used by buildAggregate's row builder so the cursor
  // compare is deterministic. Same separator as the SQL chr(31) || concat in
  // ConnectionEventRepo.querySeries.
  def aggGroupKey(
      r: wifihaven.shared.TrafficUsageAggregateRow,
      effectiveGroupBy: Set[GroupBy],
  ): String = {
    val sep = ""
    List(GroupBy.Domain, GroupBy.Device, GroupBy.Profile).iterator
      .filter(effectiveGroupBy.contains)
      .flatMap(g => r.groups.get(g.code))
      .mkString(sep)
  }

  /**
   * Hard cap for on-the-fly aggregation paths. Until rollup tables (#809) land, wider windows risk
   * >30s queries; spec for #846 says reject with 503 in that case.
   */
  val maxOnTheFlyDuration: Duration = Duration.ofDays(31)

  /**
   * Align an instant to the start of its bucket in the given zone. Sub-day buckets use UTC
   * alignment (the source rows are at UTC 5-min boundaries already so this is a no-op for those);
   * day / week use the household-local zone so calendar boundaries match human intuition.
   */
  def floorTo(instant: Instant, bucket: Bucket, zone: ZoneId): Instant = bucket match {
    case Bucket.Raw        =>
      instant.truncatedTo(ChronoUnit.MINUTES).minusSeconds(instant.getEpochSecond % 300)
    case Bucket.OneMin     => instant.truncatedTo(ChronoUnit.MINUTES)
    case Bucket.TenMin     =>
      val sec = instant.getEpochSecond
      Instant.ofEpochSecond(sec - (sec % 600))
    case Bucket.OneHour    => instant.truncatedTo(ChronoUnit.HOURS)
    case Bucket.TwelveHour =>
      val zdt = instant.atZone(zone)
      val h   = zdt.getHour
      zdt.truncatedTo(ChronoUnit.HOURS).withHour(if h < 12 then 0 else 12).toInstant
    case Bucket.OneDay     => instant.atZone(zone).truncatedTo(ChronoUnit.DAYS).toInstant
    case Bucket.OneWeek    =>
      val zdt = instant.atZone(zone).truncatedTo(ChronoUnit.DAYS)
      zdt
        .`with`(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        .toInstant
  }

  def stepOf(bucket: Bucket): Duration = bucket match {
    case Bucket.Raw        => Duration.ofMinutes(5)
    case Bucket.OneMin     => Duration.ofMinutes(1)
    case Bucket.TenMin     => Duration.ofMinutes(10)
    case Bucket.OneHour    => Duration.ofHours(1)
    case Bucket.TwelveHour => Duration.ofHours(12)
    case Bucket.OneDay     => Duration.ofDays(1)
    case Bucket.OneWeek    => Duration.ofDays(7)
  }

  /**
   * Build raw rows for the page. Each `TrafficReport` becomes one `TrafficUsageRawRow`, decorated
   * with device + profile names looked up from the supplied maps.
   */
  def buildRaw(
      rows: List[TrafficUsageDbRow],
      deviceByMac: Map[MacAddress, Device],
      profileNameById: Map[ProfileId, String],
  ): List[TrafficUsageRawRow] =
    rows.map { r =>
      val dev      = deviceByMac.get(r.mac)
      val profId   = dev.flatMap(_.profileId)
      val profName = profId.flatMap(profileNameById.get)
      TrafficUsageRawRow(
        mac = r.mac,
        deviceName = dev.map(_.name),
        profileId = profId,
        profileName = profName,
        host = r.host,
        bytesIn = r.bytesIn,
        bytesOut = r.bytesOut,
        activeSeconds = r.activeSeconds,
        periodStart = r.periodStart.toString,
        periodEnd = r.periodEnd.toString,
      )
    }

  /**
   * Bucket rows into windows and group by the chosen key. Only `Domain` is supported here; `Apex` /
   * `App` are rejected at the route layer.
   *
   * `activeSeconds` is summed straight across rows in the window — same simple sum used elsewhere
   * in #715-pre code (the bucket-dedup-per-mac correction is intentionally NOT applied here;
   * per-host columns are inherently overlapping and the operator's interest in this page is "bytes
   * by host within window", with seconds as a rough proxy).
   */
  /**
   * Multi-column aggregation. `groupBy` is the set of columns to roll up on; the response carries
   * one row per (window, *grouped-column-values*) tuple. For columns NOT in `groupBy`, the row
   * carries the count of distinct values in `distinct*` fields so the SPA can render "{n} devices"
   * / "{n} profiles" in their place (drill-down deferred to #859).
   *
   * Device/profile attribution uses the device list passed in — rows whose MAC has no matching
   * device get attributed to a synthetic device row keyed by the MAC string; their profile is
   * "(unassigned)".
   */
  def buildAggregate(
      rows: List[TrafficUsageDbRow],
      bucket: Bucket,
      zone: ZoneId,
      groupBy: Set[GroupBy],
      deviceByMac: Map[MacAddress, Device],
      profileNameById: Map[ProfileId, String],
      // #769: host → membership list. A host in two apps appears under both
      // when grouping by app. Hosts absent from the map (or with an empty
      // list) bucket to __other__.
      appsByHost: Map[String, List[AppMembership]] = Map.empty,
  ): List[TrafficUsageAggregateRow] = {
    val step = stepOf(bucket)

    def deviceLabel(mac: MacAddress): String              =
      deviceByMac.get(mac).map(_.name).getOrElse(mac.value)
    def profileLabel(mac: MacAddress): String             =
      deviceByMac
        .get(mac)
        .flatMap(_.profileId)
        .flatMap(profileNameById.get)
        .getOrElse("(unassigned)")
    def membershipsFor(host: String): List[AppMembership] =
      appsByHost.getOrElse(host, Nil) match {
        case Nil => List(AppMembership.Other)
        case xs  => xs
      }

    val wantsApp = groupBy.contains(GroupBy.App)

    // #769: when grouping by app and a host is in N apps, the row is
    // attributed to all N. Pre-expand so the rest of the pipeline doesn't
    // have to special-case fan-out. When NOT grouping by app, we leave rows
    // un-expanded — distinctApps is still computed from `appsByHost`.
    val expanded: List[(TrafficUsageDbRow, Option[AppMembership])] =
      if (wantsApp)
        rows.flatMap(r => membershipsFor(r.host.value).map(m => (r, Some(m))))
      else
        rows.map(r => (r, None))

    val grouped = expanded
      .groupBy { case (r, appOpt) =>
        val windowStart = floorTo(r.periodStart, bucket, zone)
        val keyParts    = scala.collection.mutable.LinkedHashMap.empty[String, String]
        // Stable ordering: domain, device, profile, app.
        if (groupBy.contains(GroupBy.Domain)) keyParts += ("domain"   -> r.host.value)
        if (groupBy.contains(GroupBy.Device)) keyParts += ("device"   -> deviceLabel(r.mac))
        if (groupBy.contains(GroupBy.Profile)) keyParts += ("profile" -> profileLabel(r.mac))
        appOpt.foreach(a => keyParts += ("app" -> a.slug))
        (windowStart, keyParts.toMap)
      }

    grouped.toList
      .map { case ((windowStart, groupsMap), bucketPairs) =>
        val bucketRows    = bucketPairs.map(_._1)
        val devices       = bucketRows.iterator.map(r => deviceLabel(r.mac)).toSet
        val profiles      = bucketRows.iterator.map(r => profileLabel(r.mac)).toSet
        val domains       = bucketRows.iterator.map(_.host.value).toSet
        // distinctApps spans all memberships contributing to the bucket. When
        // grouping by app the bucket is per-membership so this is always 1;
        // when not grouping by app we expand each row to its memberships and
        // count the slugs so the SPA can render "{n} apps" in the un-drilled
        // state.
        val appsInBucket  =
          if (wantsApp) bucketPairs.iterator.map(_._2.get.slug).toSet
          else bucketRows.iterator.flatMap(r => membershipsFor(r.host.value).map(_.slug)).toSet
        val totalBytesIn  = bucketRows.iterator.map(_.bytesIn).sum
        val totalBytesOut = bucketRows.iterator.map(_.bytesOut).sum
        val totalSeconds  = bucketRows.iterator.map(_.activeSeconds.toLong).sum
        // sole* are populated only when the column is NOT in the groupBy set
        // AND there's exactly one distinct value contributing — so the SPA can
        // render the value in place of the "1" count.
        def soleOf(k: GroupBy, vals: Set[String]): Option[String] =
          if (!groupBy.contains(k) && vals.size == 1) vals.headOption else None
        // The unique app membership for this bucket when grouping by app —
        // every row in the bucket is the same membership by construction.
        val rowApp      = if (wantsApp) bucketPairs.head._2 else None
        val soleAppName =
          if (wantsApp) None
          else if (appsInBucket.size == 1) {
            val slug = appsInBucket.head
            // Look up display name from any contributing host.
            bucketRows.iterator
              .flatMap(r => membershipsFor(r.host.value))
              .find(_.slug == slug)
              .map(_.name)
          } else None
        TrafficUsageAggregateRow(
          groups = groupsMap,
          windowStart = windowStart.toString,
          windowEnd = windowStart.plus(step).toString,
          totalBytesIn = totalBytesIn,
          totalBytesOut = totalBytesOut,
          totalSeconds = totalSeconds,
          distinctDevices = devices.size,
          distinctProfiles = profiles.size,
          distinctDomains = domains.size,
          distinctApps = appsInBucket.size,
          soleDevice = soleOf(GroupBy.Device, devices),
          soleProfile = soleOf(GroupBy.Profile, profiles),
          soleDomain = soleOf(GroupBy.Domain, domains),
          soleApp = soleAppName,
          appId = rowApp.flatMap(_.appId),
          appName = rowApp.map(_.name),
          appIcon = rowApp.flatMap(_.icon),
        )
      }
      // #846 audit: newest window first, then biggest-bytes-first within window.
      .sortBy(r => (r.windowStart, r.totalBytesIn + r.totalBytesOut))(
        Ordering.Tuple2(Ordering.String.reverse, Ordering.Long.reverse),
      )
  }
}
