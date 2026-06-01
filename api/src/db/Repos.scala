package wifihaven.api.db

import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.shared.Schedule
import wifihaven.api.db.TypeMeta.given
import wifihaven.api.metrics.DbMetrics
import zio.*
import zio.interop.catz.*
import java.time.{Instant, LocalDate, LocalTime, ZoneId}

given Meta[List[String]] = Meta[Array[String]].imap(_.toList)(_.toArray)

case class DbUser(
    id: UserId,
    username: String,
    passwordHash: String,
    role: UserRole,
    createdAt: Instant,
    mustChangePassword: Boolean = false,
)
// #865: mac/deviceId/profileId became multi-valued so the SPA's column-header
// popovers can filter to a subset. Empty list = no filter on that column.
// #862: cursor-paged. `until` anchors the window's right edge (defaults to
// NOW() when unset). `cursorTs`/`cursorId` come from a decoded raw-log cursor
// — when set, the query is `(ts, id) < (cursorTs, cursorId)` (keyset).
// `cursorWs`/`cursorKey` carry an aggregated-series cursor on
// `(window_start DESC, group_key ASC)`. `offset` is gone — replaced by keyset
// pagination so concurrent inserts can't shift pages.
case class LogFilter(
    macs: List[String] = Nil,
    deviceIds: List[DeviceId] = Nil,
    profileIds: List[ProfileId] = Nil,
    blocked: Option[Boolean] = None,
    domain: Option[String] = None,
    location: Option[String] = None,
    hours: Int = 24,
    limit: Int = 200,
    until: Option[Instant] = None,
    cursorTs: Option[Instant] = None,
    cursorId: Option[Long] = None,
    cursorWs: Option[String] = None,
    cursorKey: Option[String] = None,
    // #969: when false (default), drop rows whose destination is IPv4 multicast
    // (224.0.0.0/4), IPv4 broadcast (255.255.255.255), or IPv6 multicast
    // (ff00::/8) from both the raw and aggregated views. Operators can pass
    // ?includeMulticast=true to see them for diagnostics.
    includeMulticast: Boolean = false,
)

case class TrafficRollupFilter(
    macs: Option[List[MacAddress]] = None, // None = no MAC restriction; Some(Nil) = match nothing
    host: Option[String] = None,
    since: Option[Instant] = None,
    until: Option[Instant] = None,
)

/**
 * One row per 5-min traffic_reports period (with ipv4/ipv6 hosts resolved to their attributed FQDN
 * when possible). Used by [[DashboardNowRoutes]] to compute per-device top hosts.
 */
case class TrafficRollupRow(
    routerId: RouterId,
    mac: MacAddress,
    host: HostId,
    date: LocalDate,
    periodStart: Instant,
    periodEnd: Instant,
    activeSeconds: Int,
    bytesIn: Long,
    bytesOut: Long,
)

trait UserRepo {
  def findByUsername(u: String): Task[Option[DbUser]]
  def findById(id: UserId): Task[Option[DbUser]]
  def create(u: String, h: String, r: String): Task[UserId]
  def updatePassword(id: UserId, h: String): Task[Unit]
  def updateUsername(id: UserId, u: String): Task[Unit]
  def updateRole(id: UserId, r: String): Task[Unit]
  def clearMustChangePassword(id: UserId): Task[Unit]
  def listAll: Task[List[DbUser]]
  def delete(id: UserId): Task[Unit]
}

trait UserProfileRepo {
  def listProfilesForUser(userId: UserId): Task[List[ProfileId]]
  def listProfilesForUsername(username: String): Task[List[ProfileId]]
  def listUsersForProfile(profileId: ProfileId): Task[List[UserId]]
  def listAllMappings: Task[List[(UserId, ProfileId)]] // (userId, profileId)
  def setProfilesForUser(userId: UserId, profileIds: List[ProfileId]): Task[Unit]
  def setUsersForProfile(profileId: ProfileId, userIds: List[UserId]): Task[Unit]
  def addLink(userId: UserId, profileId: ProfileId): Task[Unit]
  def removeLink(userId: UserId, profileId: ProfileId): Task[Unit]
  def hasAccess(userId: UserId, profileId: ProfileId): Task[Boolean]
}

trait ProfileRepo {
  def listAll: Task[List[Profile]]
  def findById(id: ProfileId): Task[Option[Profile]]
  def create(name: String, cats: List[BlocklistId]): Task[ProfileId]
  def update(p: Profile): Task[Unit]
  def delete(id: ProfileId): Task[Unit]
  def setPaused(id: ProfileId, paused: Boolean): Task[Unit]
}

trait ScheduleRepo {
  def listAll: Task[List[Schedule]]
  def listForProfile(pid: ProfileId): Task[List[Schedule]]
  def replaceForProfile(pid: ProfileId, scheds: List[ScheduleRequest]): Task[Unit]
}

trait HouseholdSettingsRepo {
  def get: Task[HouseholdSettings]
  def update(s: HouseholdSettings): Task[Unit]

  /** Insert the default row if missing, using `defaultZone` as the install-time tz. */
  def ensureDefault(defaultZone: ZoneId): Task[Unit]
}

trait TimeLimitRepo {
  def findForProfile(pid: ProfileId): Task[Option[TimeLimit]]
  def upsert(pid: ProfileId, mins: Int): Task[Unit]
  def delete(pid: ProfileId): Task[Unit]
  def listAll: Task[List[TimeLimit]]
}

/**
 * Post-#764 the `site_time_limits` table is dropped. This repo now synthesizes the legacy
 * `SiteTimeLimit` shape from `app_policy_assignments` rows with mode='time_limited', emitting one
 * row per (assignment × host) — mirroring the snapshot expansion in `PolicyService`. Synthetic ids
 * are 0L; callers that need stable ids should switch to AppRepo directly.
 */
trait SiteTimeLimitRepo {
  def listForProfile(pid: ProfileId): Task[List[SiteTimeLimit]]
  def listAll: Task[List[SiteTimeLimit]]
}

trait DeviceRepo {
  def listAll: Task[List[Device]]
  def findByMac(mac: MacAddress): Task[Option[Device]]
  def upsert(mac: MacAddress, name: String, pid: Option[ProfileId], ip: String): Task[DeviceId]
  def updateLastSeen(mac: MacAddress, ip: String): Task[Unit]

  /**
   * Update last_seen_ip/at only if the device row exists. Used by router ingest where we don't want
   * to create rows here (events does that).
   */
  def touchLastSeen(mac: MacAddress, ip: Option[IpAddress], at: Instant): Task[Int]

  /**
   * Insert a row for a previously unknown device with NULL profile_id, or refresh last_seen_ip/at
   * on an existing row. Used by /api/router/events.
   */
  def upsertUnknown(
      mac: MacAddress,
      name: String,
      ip: Option[IpAddress],
      at: Instant,
  ): Task[DeviceId]

  /**
   * Rename a device only if its current name is an auto-generated placeholder ("unknown" or
   * "device-XXXXXX"). Used by router ingest to upgrade names once a later DHCP lease carries a real
   * hostname (#249). Returns the number of rows updated (0 if the name was admin-curated).
   */
  def renameIfAutoGenerated(mac: MacAddress, newName: String): Task[Int]
  def updateProfile(mac: MacAddress, pid: ProfileId): Task[Unit]
  def delete(mac: MacAddress): Task[Unit]
}

/**
 * Generic admin-action feed (formerly device_alerts, #711). Every row is something the admin needs
 * to decide about; lifecycle is `pending → approved | denied`. The schema (see V33) supports a
 * second `access_request` kind alongside `new_device`; that kind's writers and side-effects land in
 * #960 — this trait only exposes the new_device path for now.
 */
trait AlertRepo {

  /**
   * #711: raise a new-device alert. Idempotent on `mac` — if a row already exists for this MAC and
   * kind, the existing one wins regardless of its status, so re-ingesting the same first-seen event
   * doesn't resurrect a decided alert or duplicate a pending one.
   */
  def raiseNewDevice(mac: MacAddress, firstSeenAt: Instant): Task[Unit]

  /**
   * #960: create an access-request alert. `profileId` is denormalised at insert time so the row
   * survives a later device→profile reassignment.
   */
  def createAccessRequest(
      mac: MacAddress,
      profileId: Option[ProfileId],
      host: Hostname,
      requestKind: AccessRequestKind,
      note: Option[String],
      createdAt: Instant,
  ): Task[AlertId]

  /**
   * Debounce probe for access-request creates: most recent pending access_request row for `(mac,
   * host)` since `since`, if any.
   */
  def findRecentAccessRequest(
      mac: MacAddress,
      host: Hostname,
      since: Instant,
  ): Task[Option[Alert]]

  def findById(id: AlertId): Task[Option[Alert]]

  /** Pending-only when `includeAll=false`. Ordered newest first. */
  def list(includeAll: Boolean): Task[List[Alert]]

  /**
   * Returns the number of rows that transitioned; 0 means the row was already decided.
   * `grantedMinutes` is recorded by extension approvals when #960 lands; for new_device rows it is
   * always None.
   */
  def decide(
      id: AlertId,
      newStatus: AlertStatus,
      decidedAt: Instant,
      decidedBy: String,
      grantedMinutes: Option[Int],
  ): Task[Int]
}

trait BlocklistRepo {
  def insertBatch(domains: List[(String, String)]): Task[Int]
  def clearCategory(cat: BlocklistId): Task[Unit]
  def listCategories: Task[List[BlocklistId]]
  def countByCategory: Task[List[(BlocklistId, Int)]]
  def loadCategory(cat: BlocklistId): Task[Set[Hostname]]
  def loadAll: Task[Map[BlocklistId, Set[Hostname]]]

  // #958: metadata-table operations backing the SPA management page and
  // the bundled-list startup seeder. `summaries` joins blocklists with
  // a count from blocklist_domains so the SPA renders host counts
  // without N round-trips.
  def upsertMeta(
      id: BlocklistId,
      name: String,
      description: Option[String],
      bundled: Boolean,
      source: Option[String],
      lastBuiltAt: java.time.Instant,
  ): Task[Unit]
  def summaries: Task[List[wifihaven.shared.BlocklistSummary]]
  def findMeta(id: BlocklistId): Task[Option[wifihaven.shared.BlocklistSummary]]
}

trait TimeUsageRepo {

  /**
   * Increment seconds + byte counters for (mac, host, date). Repeats are *additive* — the caller is
   * responsible for idempotency at the request level (traffic_reports unique key).
   *
   * `seconds` is the bucket-presence count (bucket duration credited in full to every host the
   * device touched in the bucket). `proportionalSeconds` is the same bucket duration weighted by
   * this host's byte share of the bucket (#715) — a fairer wall-clock attribution for per-host
   * screen-time UI. Daily-cap math reads neither directly: it goes through the presence-based
   * `totalMinutesByMac` over `traffic_reports`.
   */
  def incrementSecondsAndBytes(
      mac: MacAddress,
      host: HostId,
      date: LocalDate,
      seconds: Long,
      bytesIn: Long,
      bytesOut: Long,
      proportionalSeconds: Long = 0L,
  ): Task[Unit]

  /** Read seconds_used for a (mac, host, date) row. Returns 0 if no row. */
  def getSecondsUsed(mac: MacAddress, host: HostId, date: LocalDate): Task[Long]

  /** Read (seconds_used, bytes_in, bytes_out) for a (mac, host, date) row. */
  def getSecondsAndBytes(
      mac: MacAddress,
      host: HostId,
      date: LocalDate,
  ): Task[(Long, Long, Long)]

  /**
   * #715: read the byte-share-weighted proportional seconds for a (mac, host, date) row. Returns 0
   * if no row. Cap math doesn't read this; it's stored so the column stays in sync with
   * `seconds_used` for any consumer that wants a fairer per-host attribution.
   */
  def getProportionalSeconds(mac: MacAddress, host: HostId, date: LocalDate): Task[Long]
  def listForDevice(mac: MacAddress, date: LocalDate): Task[List[TimeUsage]]
  def listForDeviceMacs(macs: List[MacAddress], date: LocalDate): Task[List[TimeUsage]]
  def snapshotAll(date: LocalDate): Task[Map[(MacAddress, HostId), Int]]
}

trait TimeExtensionRepo {
  def getTotalExtension(mac: MacAddress, date: LocalDate): Task[Int]
  def grant(
      mac: MacAddress,
      date: LocalDate,
      mins: Int,
      by: String,
      note: Option[String],
  ): Task[TimeExtensionId]
  def listForDevice(mac: MacAddress, date: LocalDate): Task[List[TimeExtension]]
  def snapshotAll(date: LocalDate): Task[Map[MacAddress, Int]]
  // Profile-level extension methods (V5+)
  def grantForProfile(
      profileId: ProfileId,
      date: LocalDate,
      mins: Int,
      by: String,
      note: Option[String],
  ): Task[TimeExtensionId]
  def getProfileTotalExtension(profileId: ProfileId, date: LocalDate): Task[Int]
  def listForProfile(profileId: ProfileId, date: LocalDate): Task[List[TimeExtension]]
  def snapshotAllByProfile(date: LocalDate): Task[Map[ProfileId, Int]]
}

case class TrafficReportInsert(
    routerId: RouterId,
    mac: MacAddress,
    ip: Option[IpAddress],
    host: HostId,
    date: LocalDate,
    periodStart: Instant,
    periodEnd: Instant,
    activeSeconds: Int,
    bytesIn: Long,
    bytesOut: Long,
)

case class BlockEventInsert(
    mac: Option[MacAddress],
    host: HostId,
    reason: BlockReason,
)

case class ConnectionEventInsert(
    routerId: RouterId,
    mac: Option[MacAddress],
    host: HostId,
    destIp: Option[IpAddress],
    allowed: Boolean,
    reason: BlockReason,
    ts: Instant,
    // #338: client-supplied idempotency key. None → SQL COALESCEs to
    // gen_random_uuid() so older agents that don't ship an eventId keep
    // inserting cleanly (one fresh UUID per replay, no dedup possible).
    eventId: Option[java.util.UUID] = None,
    // #720: server-side FQDN attribution looked up from prior fqdn-typed
    // events sharing (router_id, dest_ip). NULL when host is already fqdn
    // or when no sibling resolution was found within the lookup window.
    resolvedHost: Option[Hostname] = None,
)

trait RouterRepo {
  def listAll: Task[List[Router]]
  def findById(id: RouterId): Task[Option[Router]]
  def findByEnrollmentTokenHash(h: Sha256Hex): Task[Option[Router]]
  def findByTokenHash(h: Sha256Hex): Task[Option[Router]]
  def create(name: String, enrollmentTokenHash: Sha256Hex): Task[RouterId]
  def completeEnrollment(id: RouterId, tokenHash: Sha256Hex): Task[Unit]
  def touch(id: RouterId, etag: Option[ETag], agentVersion: Option[String]): Task[Unit]

  /**
   * #1204: routers whose last_seen_at is at or after `cutoff`. Drives the agent_connected_routers
   * gauge.
   */
  def countSeenSince(cutoff: Instant): Task[Int]

  def delete(id: RouterId): Task[Unit]
}

trait TrafficReportRepo {
  def insertBatch(reports: List[TrafficReportInsert]): Task[Int]
  def listForDevice(mac: MacAddress, date: LocalDate): Task[List[TrafficReport]]
  def listForRouter(routerId: RouterId, limit: Int): Task[List[TrafficReport]]

  /**
   * Per-5-min traffic_reports rows for aggregation: returned ordered by (router_id, mac, hostname,
   * date, period_start). Filters are AND-composed. `macs = Some(Nil)` returns an empty list (no
   * devices match).
   */
  def listTrafficRollupRows(f: TrafficRollupFilter): Task[List[TrafficRollupRow]]

  /**
   * Minimal projection used by presence-based minute accounting (see
   * [[wifihaven.api.presence.Presence]]). One row per (mac, period_start, hostname) for the given
   * macs/date; the caller deduplicates by `(mac, period_start)` so per-hostname rows in a single
   * bucket don't inflate total screen time.
   */
  def listPresenceRows(
      macs: List[MacAddress],
      date: LocalDate,
  ): Task[List[wifihaven.api.presence.PresenceRow]]

  /**
   * Range variant of [[listPresenceRows]] — inclusive `from`..`to`. Used by the #723 weekly profile
   * screen-time view to compute range-deduped per-mac / per-host totals AND per-period breakdown
   * from one query. Bucketing is UTC end-to-end (storage AND read); household-local-time
   * re-bucketing for the chart happens in the SPA against the UTC `periodStart` instants (#794).
   */
  def listPresenceRows(
      macs: List[MacAddress],
      from: LocalDate,
      to: LocalDate,
  ): Task[List[wifihaven.api.presence.PresenceRow]]

  /**
   * #1160: tail-load for the rollup read path. Same row shape as [[listPresenceRows]] but with an
   * additional `period_start >= since` filter, so the caller only pays for the slice of buckets the
   * rollup hasn't yet absorbed.
   */
  def listPresenceRowsSince(
      macs: List[MacAddress],
      date: LocalDate,
      since: Instant,
  ): Task[List[wifihaven.api.presence.PresenceRow]]

  /**
   * #1099: presence rows whose `period_start` falls in `[fromInstant, toInstant)` for the given
   * macs. Filtering on `period_start` (the table's RANGE partition key, V41) — rather than the
   * non-key `date` column the day/range variants use — lets Postgres prune to the covering weekly
   * partitions instead of scanning all of history. This is the difference between a sub-second read
   * and the multi-minute full-table scan that wedged the /profiles page (see issue #1099). The
   * query is bounded by a per-statement timeout ([[QueryTimeout.PresenceWindow]]) so a pathological
   * caller fails fast instead of holding a connection. Same row shape and semantics as
   * [[listPresenceRows]]; callers compute the window from the requested local day + zone.
   */
  def listPresenceRowsInWindow(
      macs: List[MacAddress],
      fromInstant: Instant,
      toInstant: Instant,
  ): Task[List[wifihaven.api.presence.PresenceRow]]

  /**
   * #846: raw rows in `[fromInstant, toInstant)` for the given macs. `macs = Nil` means "all macs"
   * (used by the Traffic Usage page in unfiltered mode). Returns Instants (not String date columns)
   * so callers can bucket without re-parsing.
   */
  def listRawInRange(
      macs: List[MacAddress],
      fromInstant: Instant,
      toInstant: Instant,
      cursor: Option[wifihaven.api.usage.RawTrafficCursorKey] = None,
      limit: Option[Int] = None,
  ): Task[List[wifihaven.api.usage.TrafficUsageDbRow]]

  /**
   * #846: earliest `period_start` across all rows. Used by Traffic Usage page to reject `from`
   * instants that fall outside the retention horizon — naïve until #809/#814 land.
   */
  def earliestPeriodStart: Task[Option[Instant]]

  /**
   * #766: per-host aggregates for one device over `[from, to)`, restricted to FQDN-typed rows
   * (after the IP→FQDN LATERAL resolve). One row per resolved hostname, with total `bytes_in +
   * bytes_out` and the hit count (number of 5-min buckets the host was observed in). Used by the
   * apps create/edit recently-visited-hosts picker; PSL apex collapse happens in Scala.
   */
  def listFqdnHostAggregatesForDevice(
      mac: MacAddress,
      fromInstant: Instant,
      toInstant: Instant,
  ): Task[List[(Hostname, Long, Long)]]
}

trait BlockEventRepo {
  def insertBatch(events: List[BlockEventInsert]): Task[Int]
  def recent(limit: Int): Task[List[BlockEvent]]
  def listForMac(mac: MacAddress, limit: Int): Task[List[BlockEvent]]
}

trait ConnectionEventRepo {
  def insertBatch(events: List[ConnectionEventInsert]): Task[Int]
  def recent(limit: Int): Task[List[ConnectionEvent]]
  def listForMac(mac: MacAddress, limit: Int): Task[List[ConnectionEvent]]
  def listForRouter(routerId: RouterId, limit: Int): Task[List[ConnectionEvent]]
  def query(f: LogFilter): Task[List[QueryLog]]
  // #846: multi-column grouping. `groupBy` is the set of column names from
  // {"domain","device","profile"}; the repo returns one row per
  // (window, *grouped-column-values*) with distinct-counts for the rest.
  def querySeries(
      f: LogFilter,
      bucketSeconds: Int,
      groupBy: Set[String],
  ): Task[List[ConnectionEventAggRow]]
  def stats: Task[DashboardStats]
  def topBlocked(hours: Int, limit: Int): Task[List[DomainCount]]

  /**
   * Latest `ts` per mac for events strictly newer than `since`. Used by the "now" dashboard to
   * detect devices that produced at least one connection attempt in the recent window.
   */
  def lastSeenByMacSince(since: Instant): Task[Map[MacAddress, Instant]]

  /**
   * #720 backfill: look up the most recent fqdn-typed connection_event with the given (router_id,
   * dest_ip) whose `ts` is at-or-after `since`. Returns the resolved hostname if any. Used at
   * ingest time to attach a resolution to a fresh ipv4/ipv6-typed event whose agent-side DNS cache
   * lost the race.
   */
  def findRecentFqdnFor(
      routerId: RouterId,
      destIp: IpAddress,
      since: Instant,
  ): Task[Option[Hostname]]

  /**
   * #720 backfill: patch the `resolved_host_value` column on existing ipv4/ipv6-typed rows for the
   * given (router_id, dest_ip) that landed at-or-after `since` and don't yet carry a resolution.
   * Called when a fqdn-typed event lands and "teaches" the API the IP→hostname mapping
   * retroactively. Returns the row count actually updated.
   */
  def backfillResolvedFor(
      routerId: RouterId,
      destIp: IpAddress,
      fqdn: Hostname,
      since: Instant,
  ): Task[Int]
}

class UserRepoLive(xa: Transactor[Task]) extends UserRepo {
  def findByUsername(u: String)             =
    DbMetrics.timed("user.findByUsername")(
      sql"SELECT id,username,password_hash,role,created_at,must_change_password FROM users WHERE username=$u"
        .query[(UserId, String, String, UserRole, Instant, Boolean)]
        .map { case (id, un, ph, role, ca, mcp) => DbUser(id, un, ph, role, ca, mcp) }
        .option
        .transact(xa),
    )
  def findById(id: UserId)                  =
    sql"SELECT id,username,password_hash,role,created_at,must_change_password FROM users WHERE id=$id"
      .query[(UserId, String, String, UserRole, Instant, Boolean)]
      .map { case (id, un, ph, role, ca, mcp) => DbUser(id, un, ph, role, ca, mcp) }
      .option
      .transact(xa)
  def create(u: String, h: String, r: String) =
    // Always force a password change on first login for admin-created users (#599).
    sql"INSERT INTO users(username,password_hash,role,must_change_password) VALUES($u,$h,$r,true) RETURNING id"
      .query[UserId]
      .unique
      .transact(xa)
  def updatePassword(id: UserId, h: String) =
    sql"UPDATE users SET password_hash=$h WHERE id=$id".update.run.transact(xa).unit
  def updateUsername(id: UserId, u: String) =
    sql"UPDATE users SET username=$u WHERE id=$id".update.run.transact(xa).unit
  def updateRole(id: UserId, r: String)     =
    sql"UPDATE users SET role=$r WHERE id=$id".update.run.transact(xa).unit
  def clearMustChangePassword(id: UserId)   =
    sql"UPDATE users SET must_change_password=false WHERE id=$id".update.run.transact(xa).unit
  def listAll                               =
    sql"SELECT id,username,password_hash,role,created_at,must_change_password FROM users ORDER BY id"
      .query[(UserId, String, String, UserRole, Instant, Boolean)]
      .map { case (id, un, ph, role, ca, mcp) => DbUser(id, un, ph, role, ca, mcp) }
      .to[List]
      .transact(xa)
  def delete(id: UserId) = sql"DELETE FROM users WHERE id=$id".update.run.transact(xa).unit
}

class UserProfileRepoLive(xa: Transactor[Task]) extends UserProfileRepo {
  def listProfilesForUser(userId: UserId)                          =
    sql"SELECT profile_id FROM user_profiles WHERE user_id=$userId ORDER BY profile_id"
      .query[ProfileId]
      .to[List]
      .transact(xa)
  def listProfilesForUsername(u: String)                           =
    sql"SELECT up.profile_id FROM user_profiles up JOIN users us ON us.id=up.user_id WHERE us.username=$u ORDER BY up.profile_id"
      .query[ProfileId]
      .to[List]
      .transact(xa)
  def listUsersForProfile(profileId: ProfileId)                    =
    sql"SELECT user_id FROM user_profiles WHERE profile_id=$profileId ORDER BY user_id"
      .query[UserId]
      .to[List]
      .transact(xa)
  def listAllMappings                                              =
    sql"SELECT user_id, profile_id FROM user_profiles"
      .query[(UserId, ProfileId)]
      .to[List]
      .transact(xa)
  def setProfilesForUser(userId: UserId, pids: List[ProfileId])    = {
    val del = sql"DELETE FROM user_profiles WHERE user_id=$userId".update.run
    val ins = pids.distinct.map(pid =>
      sql"INSERT INTO user_profiles(user_id,profile_id) VALUES($userId,$pid) ON CONFLICT DO NOTHING".update.run,
    )
    (del *> ins.foldLeft(FC.unit)(_ *> _.void)).transact(xa)
  }
  def setUsersForProfile(profileId: ProfileId, uids: List[UserId]) = {
    val del = sql"DELETE FROM user_profiles WHERE profile_id=$profileId".update.run
    val ins = uids.distinct.map(uid =>
      sql"INSERT INTO user_profiles(user_id,profile_id) VALUES($uid,$profileId) ON CONFLICT DO NOTHING".update.run,
    )
    (del *> ins.foldLeft(FC.unit)(_ *> _.void)).transact(xa)
  }
  def addLink(userId: UserId, pid: ProfileId)                      =
    sql"INSERT INTO user_profiles(user_id,profile_id) VALUES($userId,$pid) ON CONFLICT DO NOTHING".update.run
      .transact(xa)
      .unit
  def removeLink(userId: UserId, pid: ProfileId)                   =
    sql"DELETE FROM user_profiles WHERE user_id=$userId AND profile_id=$pid".update.run
      .transact(xa)
      .unit
  def hasAccess(userId: UserId, pid: ProfileId)                    =
    sql"SELECT 1 FROM user_profiles WHERE user_id=$userId AND profile_id=$pid"
      .query[Int]
      .option
      .transact(xa)
      .map(_.isDefined)
}

class ProfileRepoLive(xa: Transactor[Task]) extends ProfileRepo {
  private type R = (
      ProfileId,
      String,
      List[String],
      Boolean,
      String,
      Boolean,
      String,
  )
  private def toP(r: R)                             = Profile(
    r._1,
    r._2,
    r._3.map(BlocklistId.unsafe),
    r._4,
    FailureMode.parse(r._5).getOrElse(FailureMode.LastKnownGood),
    r._6,
    CrossDeviceOverlapMode.parse(r._7).getOrElse(CrossDeviceOverlapMode.Sum),
  )
  def listAll                                       =
    DbMetrics.timed("profile.listAll")(
      sql"SELECT id,name,blocked_categories,paused,failure_mode,block_ip_only,cross_device_overlap_mode FROM profiles ORDER BY id"
        .query[R]
        .map(toP)
        .to[List]
        .transact(xa),
    )
  def findById(id: ProfileId)                       =
    sql"SELECT id,name,blocked_categories,paused,failure_mode,block_ip_only,cross_device_overlap_mode FROM profiles WHERE id=$id"
      .query[R]
      .map(toP)
      .option
      .transact(xa)
  def create(name: String, cats: List[BlocklistId]) =
    sql"INSERT INTO profiles(name,blocked_categories) VALUES($name,${cats.map(_.value).toArray}) RETURNING id"
      .query[ProfileId]
      .unique
      .transact(xa)
  def update(p: Profile)                            =
    sql"""UPDATE profiles SET
            name=${p.name},
            blocked_categories=${p.blockedCategories.map(_.value).toArray},
            paused=${p.paused},
            failure_mode=${FailureMode.asString(p.failureMode)},
            block_ip_only=${p.blockIpOnly},
            cross_device_overlap_mode=${CrossDeviceOverlapMode.asString(p.crossDeviceOverlapMode)}
          WHERE id=${p.id}""".update.run
      .transact(xa)
      .unit
  def delete(id: ProfileId) = sql"DELETE FROM profiles WHERE id=$id".update.run.transact(xa).unit
  def setPaused(id: ProfileId, p: Boolean) =
    sql"UPDATE profiles SET paused=$p WHERE id=$id".update.run.transact(xa).unit
}

class ScheduleRepoLive(xa: Transactor[Task]) extends ScheduleRepo {
  private type R = (ScheduleId, ProfileId, String, List[String], LocalTime, LocalTime, ZoneId)
  private def toS(r: R)              = Schedule(r._1, r._2, r._3, r._4, r._5, r._6, r._7)
  def listAll                        =
    sql"SELECT id,profile_id,name,days,start_local,end_local,tz FROM schedules ORDER BY id"
      .query[R]
      .map(toS)
      .to[List]
      .transact(xa)
  def listForProfile(pid: ProfileId) =
    sql"SELECT id,profile_id,name,days,start_local,end_local,tz FROM schedules WHERE profile_id=$pid ORDER BY id"
      .query[R]
      .map(toS)
      .to[List]
      .transact(xa)
  def replaceForProfile(pid: ProfileId, ss: List[ScheduleRequest]) = {
    val del = sql"DELETE FROM schedules WHERE profile_id=$pid".update.run
    val ins = ss.map(s =>
      sql"INSERT INTO schedules(profile_id,name,days,start_local,end_local,tz) VALUES($pid,${s.name},${s.days.toArray},${s.startLocal},${s.endLocal},${s.tz})".update.run,
    )
    (del *> ins.foldLeft(FC.unit)(_ *> _.void)).transact(xa)
  }
}

class HouseholdSettingsRepoLive(xa: Transactor[Task]) extends HouseholdSettingsRepo {
  import zio.json.*
  import wifihaven.shared.UnmanagedMacPolicy

  def get: Task[HouseholdSettings] =
    sql"""SELECT daily_reset_time, daily_reset_tz,
                 heartbeat_filter_enabled, heartbeat_bytes_threshold,
                 heartbeat_host_patterns,
                 unmanaged_mac_policy::text
            FROM household_settings WHERE id=1"""
      .query[(LocalTime, ZoneId, Boolean, Int, List[String], String)]
      .unique
      .map { case (t, z, hbEnabled, hbBytes, hbHosts, ummJson) =>
        val umm = ummJson.fromJson[UnmanagedMacPolicy].getOrElse(UnmanagedMacPolicy.Default)
        HouseholdSettings(t, z, HeartbeatFilter(hbEnabled, hbBytes, hbHosts), umm)
      }
      .transact(xa)

  def update(s: HouseholdSettings): Task[Unit] = {
    val ummJson    = s.unmanagedMacPolicy.toJson
    // #1160: invalidate the time-used rollup atomically with the settings
    // update. Any change to the daily-reset boundary (tz / reset hour) or the
    // heartbeat filter changes the active-minute definition for every cached
    // day; deleting the cache forces the next rollup tick to refill from
    // first principles. The DELETE is wholesale because all three fields gate
    // the same aggregation — fine-grained invalidation would only add risk of
    // missing a code path that mutates the filter.
    val upd        =
      sql"""UPDATE household_settings
              SET daily_reset_time=${s.dailyResetTime},
                  daily_reset_tz=${s.dailyResetTz},
                  heartbeat_filter_enabled=${s.heartbeatFilter.enabled},
                  heartbeat_bytes_threshold=${s.heartbeatFilter.bytesThreshold},
                  heartbeat_host_patterns=${s.heartbeatFilter.heartbeatHostPatterns.toArray},
                  unmanaged_mac_policy=${ummJson}::jsonb,
                  updated_at=NOW()
            WHERE id=1""".update.run
    val invalidate = sql"DELETE FROM time_used_daily".update.run
    (upd *> invalidate).transact(xa).unit
  }

  def ensureDefault(defaultZone: ZoneId): Task[Unit] =
    sql"""INSERT INTO household_settings (id, daily_reset_time, daily_reset_tz)
          VALUES (1, '00:00', ${defaultZone})
          ON CONFLICT (id) DO NOTHING""".update.run.transact(xa).unit
}

class TimeLimitRepoLive(xa: Transactor[Task]) extends TimeLimitRepo {
  def findForProfile(pid: ProfileId)    =
    sql"SELECT id,profile_id,daily_minutes FROM time_limits WHERE profile_id=$pid"
      .query[(TimeLimitId, ProfileId, Int)]
      .map(TimeLimit.apply)
      .option
      .transact(xa)
  def upsert(pid: ProfileId, mins: Int) =
    sql"INSERT INTO time_limits(profile_id,daily_minutes) VALUES($pid,$mins) ON CONFLICT(profile_id) DO UPDATE SET daily_minutes=EXCLUDED.daily_minutes".update.run
      .transact(xa)
      .unit
  def delete(pid: ProfileId)            =
    sql"DELETE FROM time_limits WHERE profile_id=$pid".update.run.transact(xa).unit
  def listAll                           = sql"SELECT id,profile_id,daily_minutes FROM time_limits"
    .query[(TimeLimitId, ProfileId, Int)]
    .map(TimeLimit.apply)
    .to[List]
    .transact(xa)
}

class SiteTimeLimitRepoLive(xa: Transactor[Task]) extends SiteTimeLimitRepo {
  // (profileId, host, dailyMinutes, slug, exemptFromDaily)
  private type R = (ProfileId, String, Int, String, Boolean)
  private def toS(r: R) =
    SiteTimeLimit(SiteTimeLimitId(0L), r._1, r._2, r._3, s"app:${r._4}", r._5)

  def listForProfile(pid: ProfileId) =
    sql"""SELECT apa.profile_id, ah.host, COALESCE(apa.daily_minutes, 0), a.slug, apa.exempt_from_daily
            FROM app_policy_assignments apa
            JOIN apps a       ON a.id = apa.app_id
            JOIN app_hosts ah ON ah.app_id = apa.app_id
           WHERE apa.profile_id = $pid AND apa.mode = 'time_limited'
           ORDER BY apa.id, ah.host"""
      .query[R]
      .map(toS)
      .to[List]
      .transact(xa)

  def listAll =
    sql"""SELECT apa.profile_id, ah.host, COALESCE(apa.daily_minutes, 0), a.slug, apa.exempt_from_daily
            FROM app_policy_assignments apa
            JOIN apps a       ON a.id = apa.app_id
            JOIN app_hosts ah ON ah.app_id = apa.app_id
           WHERE apa.mode = 'time_limited'
           ORDER BY apa.id, ah.host"""
      .query[R]
      .map(toS)
      .to[List]
      .transact(xa)
}

class DeviceRepoLive(xa: Transactor[Task]) extends DeviceRepo {
  def listAll                                                                          =
    DbMetrics.timed("device.listAll")(
      sql"SELECT d.id,d.mac,d.name,d.profile_id,p.name,d.last_seen_ip,d.last_seen_at::TEXT FROM devices d LEFT JOIN profiles p ON p.id=d.profile_id ORDER BY d.name"
        .query[
          (
              DeviceId,
              MacAddress,
              String,
              Option[ProfileId],
              Option[String],
              Option[IpAddress],
              Option[String],
          ),
        ]
        .map(r => Device(r._1, r._2, r._3, r._4, r._5, r._6, r._7))
        .to[List]
        .transact(xa),
    )
  def findByMac(mac: MacAddress)                                                       =
    sql"SELECT d.id,d.mac,d.name,d.profile_id,p.name,d.last_seen_ip,d.last_seen_at::TEXT FROM devices d LEFT JOIN profiles p ON p.id=d.profile_id WHERE d.mac=$mac"
      .query[
        (
            DeviceId,
            MacAddress,
            String,
            Option[ProfileId],
            Option[String],
            Option[IpAddress],
            Option[String],
        ),
      ]
      .map(r => Device(r._1, r._2, r._3, r._4, r._5, r._6, r._7))
      .option
      .transact(xa)
  def upsert(mac: MacAddress, name: String, pid: Option[ProfileId], ip: String) =
    // #708: pid=None writes NULL (device unassigned). Devices without a profile
    // are a supported state — same shape auto-discovery produces.
    sql"INSERT INTO devices(mac,name,profile_id,last_seen_ip,last_seen_at) VALUES($mac,$name,$pid,NULLIF($ip,''),NOW()) ON CONFLICT(mac) DO UPDATE SET name=EXCLUDED.name,profile_id=EXCLUDED.profile_id RETURNING id"
      .query[DeviceId]
      .unique
      .transact(xa)
  def updateLastSeen(mac: MacAddress, ip: String)                                      =
    sql"UPDATE devices SET last_seen_ip=$ip,last_seen_at=NOW() WHERE mac=$mac".update.run
      .transact(xa)
      .unit
  def touchLastSeen(mac: MacAddress, ip: Option[IpAddress], at: Instant)               =
    sql"UPDATE devices SET last_seen_ip=COALESCE($ip,last_seen_ip),last_seen_at=$at WHERE mac=$mac".update.run
      .transact(xa)
  def upsertUnknown(mac: MacAddress, name: String, ip: Option[IpAddress], at: Instant) =
    sql"""INSERT INTO devices(mac,name,profile_id,last_seen_ip,last_seen_at)
          VALUES($mac,$name,NULL,$ip,$at)
          ON CONFLICT(mac) DO UPDATE
          SET last_seen_ip=COALESCE(EXCLUDED.last_seen_ip,devices.last_seen_ip),
              last_seen_at=EXCLUDED.last_seen_at
          RETURNING id"""
      .query[DeviceId]
      .unique
      .transact(xa)
  def renameIfAutoGenerated(mac: MacAddress, newName: String)                          = {
    val autoNamePattern = "^device-[0-9a-fA-F]+$"
    sql"""UPDATE devices SET name=$newName
          WHERE mac=$mac AND (name='unknown' OR name ~ $autoNamePattern)""".update.run
      .transact(xa)
  }
  def updateProfile(mac: MacAddress, pid: ProfileId)                                   =
    sql"UPDATE devices SET profile_id=$pid WHERE mac=$mac".update.run.transact(xa).unit
  def delete(mac: MacAddress) = sql"DELETE FROM devices WHERE mac=$mac".update.run.transact(xa).unit
}

class AlertRepoLive(xa: Transactor[Task]) extends AlertRepo {
  // Row tuple for the joined SELECT used by every read path. Order MUST
  // match `baseSelect` below — the Doobie codec is positional.
  private type R = (
      AlertId,
      String,         // kind
      String,         // status
      MacAddress,
      Option[String], // device name
      Option[ProfileId],
      Option[String], // profile name
      Option[Hostname],
      Option[String], // request_kind
      Option[String], // note
      Option[Int],    // granted_minutes
      String,         // created_at
      Option[String], // decided_at
      Option[String], // decided_by
  )

  // Reads parse the kind/status strings; values are presumed canonical
  // because the DB CHECK constraints enforce the enum. A parse failure
  // means a schema drift and should crash loudly, not silently degrade.
  private def toAlert(r: R): Alert = Alert(
    id = r._1,
    kind = AlertKind
      .parse(r._2)
      .getOrElse(throw new IllegalStateException(s"DB has unknown alert kind: ${r._2}")),
    status = AlertStatus
      .parse(r._3)
      .getOrElse(throw new IllegalStateException(s"DB has unknown alert status: ${r._3}")),
    mac = r._4,
    deviceName = r._5,
    profileId = r._6,
    profileName = r._7,
    host = r._8,
    requestKind = r._9.map(s =>
      AccessRequestKind
        .parse(s)
        .getOrElse(throw new IllegalStateException(s"DB has unknown request_kind: $s")),
    ),
    note = r._10,
    grantedMinutes = r._11,
    createdAt = r._12,
    decidedAt = r._13,
    decidedBy = r._14,
  )

  private val baseSelect = fr"""
    SELECT a.id, a.kind, a.status, a.mac, d.name, a.profile_id, p.name,
           a.host, a.request_kind, a.note, a.granted_minutes,
           a.created_at::TEXT, a.decided_at::TEXT, a.decided_by
      FROM alerts a
      LEFT JOIN devices  d ON d.mac = a.mac
      LEFT JOIN profiles p ON p.id = a.profile_id
  """

  def raiseNewDevice(mac: MacAddress, firstSeenAt: Instant): Task[Unit] =
    // ON CONFLICT-style idempotency: insert only if no row with this mac
    // already exists for kind='new_device'. We can't use a UNIQUE index
    // because the same mac may legitimately have multiple access_request
    // rows over time. WHERE NOT EXISTS keeps this race-free under the
    // SERIALIZABLE-equivalent semantics of a single statement insert.
    sql"""INSERT INTO alerts (kind, status, mac, created_at)
          SELECT 'new_device', 'pending', $mac, $firstSeenAt
          WHERE NOT EXISTS (
            SELECT 1 FROM alerts WHERE mac = $mac AND kind = 'new_device'
          )""".update.run.transact(xa).unit

  def createAccessRequest(
      mac: MacAddress,
      profileId: Option[ProfileId],
      host: Hostname,
      requestKind: AccessRequestKind,
      note: Option[String],
      createdAt: Instant,
  ): Task[AlertId] = {
    val rkStr = AccessRequestKind.asString(requestKind)
    sql"""INSERT INTO alerts (kind, status, mac, profile_id, host, request_kind, note, created_at)
          VALUES ('access_request', 'pending', $mac, $profileId, $host, $rkStr, $note, $createdAt)
          RETURNING id"""
      .query[AlertId]
      .unique
      .transact(xa)
  }

  def findRecentAccessRequest(
      mac: MacAddress,
      host: Hostname,
      since: Instant,
  ): Task[Option[Alert]] =
    (baseSelect ++ fr"""WHERE a.kind = 'access_request'
                           AND a.mac = $mac
                           AND a.host = $host
                           AND a.created_at >= $since
                         ORDER BY a.created_at DESC
                         LIMIT 1""")
      .query[R]
      .map(toAlert)
      .option
      .transact(xa)

  def findById(id: AlertId): Task[Option[Alert]] =
    (baseSelect ++ fr"WHERE a.id = ${id.value}")
      .query[R]
      .map(toAlert)
      .option
      .transact(xa)

  def list(includeAll: Boolean): Task[List[Alert]] = {
    val filter = if includeAll then fr"" else fr"WHERE a.status = 'pending'"
    (baseSelect ++ filter ++ fr"ORDER BY a.created_at DESC")
      .query[R]
      .map(toAlert)
      .to[List]
      .transact(xa)
  }

  def decide(
      id: AlertId,
      newStatus: AlertStatus,
      decidedAt: Instant,
      decidedBy: String,
      grantedMinutes: Option[Int],
  ): Task[Int] = {
    val statusStr = AlertStatus.asString(newStatus)
    sql"""UPDATE alerts
             SET status = $statusStr,
                 decided_at = $decidedAt,
                 decided_by = $decidedBy,
                 granted_minutes = $grantedMinutes
           WHERE id = ${id.value} AND status = 'pending'""".update.run
      .transact(xa)
  }
}

class BlocklistRepoLive(xa: Transactor[Task]) extends BlocklistRepo {
  def insertBatch(ds: List[(String, String)]) = Update[(String, String)](
    "INSERT INTO blocklist_domains(domain,category) VALUES(?,?) ON CONFLICT DO NOTHING",
  ).updateMany(ds).transact(xa)
  def clearCategory(cat: BlocklistId)         =
    sql"DELETE FROM blocklist_domains WHERE category=${cat.value}".update.run.transact(xa).unit
  def listCategories  = sql"SELECT DISTINCT category FROM blocklist_domains ORDER BY category"
    .query[BlocklistId]
    .to[List]
    .transact(xa)
  def countByCategory =
    sql"SELECT category,COUNT(*)::INT FROM blocklist_domains GROUP BY category ORDER BY category"
      .query[(BlocklistId, Int)]
      .to[List]
      .transact(xa)
  def loadCategory(cat: BlocklistId) =
    sql"SELECT domain FROM blocklist_domains WHERE category=${cat.value}"
      .query[Hostname]
      .to[List]
      .transact(xa)
      .map(_.toSet)
  def loadAll                        = sql"SELECT category,domain FROM blocklist_domains"
    .query[(BlocklistId, Hostname)]
    .to[List]
    .transact(xa)
    .map(_.groupBy(_._1).map((k, vs) => k -> vs.map(_._2).toSet))

  def upsertMeta(
      id: BlocklistId,
      name: String,
      description: Option[String],
      bundled: Boolean,
      source: Option[String],
      lastBuiltAt: java.time.Instant,
  ) =
    sql"""INSERT INTO blocklists (id, display_name, description, bundled, source_url, last_built_at)
          VALUES (${id.value}, $name, $description, $bundled, $source, $lastBuiltAt)
          ON CONFLICT (id) DO UPDATE SET
            display_name  = EXCLUDED.display_name,
            description   = EXCLUDED.description,
            bundled       = EXCLUDED.bundled,
            source_url    = EXCLUDED.source_url,
            last_built_at = EXCLUDED.last_built_at""".update.run
      .transact(xa)
      .unit

  def summaries =
    sql"""SELECT b.id, b.display_name, b.description, b.bundled, b.source_url,
                 COALESCE(c.n, 0)::INT AS host_count, b.last_built_at
          FROM blocklists b
          LEFT JOIN (
            SELECT category, COUNT(*) AS n
            FROM blocklist_domains
            GROUP BY category
          ) c ON c.category = b.id
          ORDER BY b.id"""
      .query[wifihaven.shared.BlocklistSummary]
      .to[List]
      .transact(xa)

  def findMeta(id: BlocklistId) =
    sql"""SELECT b.id, b.display_name, b.description, b.bundled, b.source_url,
                 COALESCE(c.n, 0)::INT AS host_count, b.last_built_at
          FROM blocklists b
          LEFT JOIN (
            SELECT category, COUNT(*) AS n
            FROM blocklist_domains
            WHERE category = ${id.value}
            GROUP BY category
          ) c ON c.category = b.id
          WHERE b.id = ${id.value}"""
      .query[wifihaven.shared.BlocklistSummary]
      .option
      .transact(xa)
}

class TimeUsageRepoLive(xa: Transactor[Task]) extends TimeUsageRepo {
  def incrementSecondsAndBytes(
      mac: MacAddress,
      host: HostId,
      d: LocalDate,
      seconds: Long,
      bytesIn: Long,
      bytesOut: Long,
      proportionalSeconds: Long = 0L,
  ): Task[Unit] =
    sql"""INSERT INTO time_usage(device_mac,host_type,host_value,date,seconds_used,proportional_seconds,bytes_in,bytes_out,last_seen_at)
          VALUES($mac,${host.kind},${host.value},$d,$seconds,$proportionalSeconds,$bytesIn,$bytesOut,NOW())
          ON CONFLICT(device_mac,host_type,host_value,date) DO UPDATE
          SET seconds_used=time_usage.seconds_used+EXCLUDED.seconds_used,
              proportional_seconds=time_usage.proportional_seconds+EXCLUDED.proportional_seconds,
              bytes_in=time_usage.bytes_in+EXCLUDED.bytes_in,
              bytes_out=time_usage.bytes_out+EXCLUDED.bytes_out,
              last_seen_at=NOW()""".update.run
      .transact(xa)
      .unit
  def getProportionalSeconds(mac: MacAddress, host: HostId, d: LocalDate): Task[Long]           =
    sql"SELECT COALESCE(proportional_seconds,0) FROM time_usage WHERE device_mac=$mac AND host_type=${host.kind} AND host_value=${host.value} AND date=$d"
      .query[Long]
      .option
      .transact(xa)
      .map(_.getOrElse(0L))
  def getSecondsUsed(mac: MacAddress, host: HostId, d: LocalDate): Task[Long]                   =
    sql"SELECT COALESCE(seconds_used,0) FROM time_usage WHERE device_mac=$mac AND host_type=${host.kind} AND host_value=${host.value} AND date=$d"
      .query[Long]
      .option
      .transact(xa)
      .map(_.getOrElse(0L))
  def getSecondsAndBytes(mac: MacAddress, host: HostId, d: LocalDate): Task[(Long, Long, Long)] =
    sql"SELECT COALESCE(seconds_used,0),COALESCE(bytes_in,0),COALESCE(bytes_out,0) FROM time_usage WHERE device_mac=$mac AND host_type=${host.kind} AND host_value=${host.value} AND date=$d"
      .query[(Long, Long, Long)]
      .option
      .transact(xa)
      .map(_.getOrElse((0L, 0L, 0L)))
  // TODO(#730): remove this read-side join once usage records carry dest_ip.
  // The LATERAL subquery promotes ipv4-typed time_usage rows to their resolved
  // fqdn by looking up the most recent connection_events row for the same
  // (mac, dest_ip) that has a resolved_host_value. The partial index added in
  // V22 (idx_conn_events_mac_dest_resolved) keeps the join cheap.
  def listForDevice(mac: MacAddress, d: LocalDate)                                              =
    sql"""SELECT tu.id,
                 tu.device_mac,
                 CASE WHEN tu.host_type IN ('ipv4','ipv6') AND ce.resolved_host_value IS NOT NULL
                      THEN 'fqdn' ELSE tu.host_type END,
                 COALESCE(CASE WHEN tu.host_type IN ('ipv4','ipv6') THEN ce.resolved_host_value END,
                          tu.host_value),
                 tu.date::TEXT,
                 (COALESCE(tu.seconds_used,0)/60)::INT,
                 tu.last_seen_at::TEXT
          FROM time_usage tu
          LEFT JOIN LATERAL (
            SELECT resolved_host_value
            FROM connection_events
            WHERE mac          = tu.device_mac
              AND dest_ip      = tu.host_value
              AND resolved_host_value IS NOT NULL
              AND ts >= $d::TIMESTAMPTZ
              AND ts <  ($d::DATE + INTERVAL '1 day')::TIMESTAMPTZ
            ORDER BY ts DESC LIMIT 1
          ) ce ON tu.host_type IN ('ipv4','ipv6')
          WHERE tu.device_mac = $mac AND tu.date = $d
          ORDER BY tu.seconds_used DESC"""
      .query[(TimeUsageId, MacAddress, HostId, String, Int, String)]
      .map(TimeUsage.apply)
      .to[List]
      .transact(xa)
  // TODO(#730): remove this read-side join once usage records carry dest_ip.
  def listForDeviceMacs(macs: List[MacAddress], d: LocalDate)                                   =
    if macs.isEmpty then ZIO.succeed(Nil)
    else {
      val arr = macs.map(_.value).toArray
      sql"""SELECT tu.id,
                   tu.device_mac,
                   CASE WHEN tu.host_type IN ('ipv4','ipv6') AND ce.resolved_host_value IS NOT NULL
                        THEN 'fqdn' ELSE tu.host_type END,
                   COALESCE(CASE WHEN tu.host_type IN ('ipv4','ipv6') THEN ce.resolved_host_value END,
                            tu.host_value),
                   tu.date::TEXT,
                   (COALESCE(tu.seconds_used,0)/60)::INT,
                   tu.last_seen_at::TEXT
            FROM time_usage tu
            LEFT JOIN LATERAL (
              SELECT resolved_host_value
              FROM connection_events
              WHERE mac          = tu.device_mac
                AND dest_ip      = tu.host_value
                AND resolved_host_value IS NOT NULL
                AND ts >= $d::TIMESTAMPTZ
                AND ts <  ($d::DATE + INTERVAL '1 day')::TIMESTAMPTZ
              ORDER BY ts DESC LIMIT 1
            ) ce ON tu.host_type IN ('ipv4','ipv6')
            WHERE tu.device_mac = ANY($arr) AND tu.date = $d
            ORDER BY tu.device_mac, tu.seconds_used DESC"""
        .query[(TimeUsageId, MacAddress, HostId, String, Int, String)]
        .map(TimeUsage.apply)
        .to[List]
        .transact(xa)
    }
  // TODO(#730): remove this read-side join once usage records carry dest_ip.
  def snapshotAll(d: LocalDate)                                                                 =
    sql"""SELECT tu.device_mac,
                 CASE WHEN tu.host_type IN ('ipv4','ipv6') AND ce.resolved_host_value IS NOT NULL
                      THEN 'fqdn' ELSE tu.host_type END,
                 COALESCE(CASE WHEN tu.host_type IN ('ipv4','ipv6') THEN ce.resolved_host_value END,
                          tu.host_value),
                 (COALESCE(tu.seconds_used,0)/60)::INT
          FROM time_usage tu
          LEFT JOIN LATERAL (
            SELECT resolved_host_value
            FROM connection_events
            WHERE mac          = tu.device_mac
              AND dest_ip      = tu.host_value
              AND resolved_host_value IS NOT NULL
              AND ts >= $d::TIMESTAMPTZ
              AND ts <  ($d::DATE + INTERVAL '1 day')::TIMESTAMPTZ
            ORDER BY ts DESC LIMIT 1
          ) ce ON tu.host_type IN ('ipv4','ipv6')
          WHERE tu.date = $d"""
      .query[(MacAddress, HostId, Int)]
      .to[List]
      .transact(xa)
      .map(_.map((m, host, mins) => (m, host) -> mins).toMap)
}

class TimeExtensionRepoLive(xa: Transactor[Task]) extends TimeExtensionRepo {
  def getTotalExtension(mac: MacAddress, d: LocalDate)                                           =
    sql"SELECT COALESCE(SUM(extra_minutes),0)::INT FROM time_extensions WHERE device_mac=$mac AND date=$d"
      .query[Int]
      .unique
      .transact(xa)
  def grant(mac: MacAddress, d: LocalDate, mins: Int, by: String, note: Option[String])          =
    sql"INSERT INTO time_extensions(device_mac,date,extra_minutes,granted_by,note) VALUES($mac,$d,$mins,$by,$note) RETURNING id"
      .query[TimeExtensionId]
      .unique
      .transact(xa)
  def listForDevice(mac: MacAddress, d: LocalDate)                                               =
    sql"SELECT id,profile_id,device_mac,date::TEXT,extra_minutes,granted_by,note,created_at::TEXT FROM time_extensions WHERE device_mac=$mac AND date=$d ORDER BY created_at"
      .query[
        (
            TimeExtensionId,
            Option[ProfileId],
            Option[MacAddress],
            String,
            Int,
            String,
            Option[String],
            String,
        ),
      ]
      .map(TimeExtension.apply)
      .to[List]
      .transact(xa)
  def snapshotAll(d: LocalDate)                                                                  =
    sql"SELECT device_mac,SUM(extra_minutes)::INT FROM time_extensions WHERE date=$d AND device_mac IS NOT NULL GROUP BY device_mac"
      .query[(MacAddress, Int)]
      .to[List]
      .transact(xa)
      .map(_.toMap)
  def grantForProfile(pid: ProfileId, d: LocalDate, mins: Int, by: String, note: Option[String]) =
    sql"INSERT INTO time_extensions(profile_id,date,extra_minutes,granted_by,note) VALUES($pid,$d,$mins,$by,$note) RETURNING id"
      .query[TimeExtensionId]
      .unique
      .transact(xa)
  def getProfileTotalExtension(pid: ProfileId, d: LocalDate)                                     =
    sql"SELECT COALESCE(SUM(extra_minutes),0)::INT FROM time_extensions WHERE profile_id=$pid AND date=$d"
      .query[Int]
      .unique
      .transact(xa)
  def listForProfile(pid: ProfileId, d: LocalDate)                                               =
    sql"SELECT id,profile_id,device_mac,date::TEXT,extra_minutes,granted_by,note,created_at::TEXT FROM time_extensions WHERE profile_id=$pid AND date=$d ORDER BY created_at"
      .query[
        (
            TimeExtensionId,
            Option[ProfileId],
            Option[MacAddress],
            String,
            Int,
            String,
            Option[String],
            String,
        ),
      ]
      .map(TimeExtension.apply)
      .to[List]
      .transact(xa)
  def snapshotAllByProfile(d: LocalDate)                                                         =
    sql"SELECT profile_id,SUM(extra_minutes)::INT FROM time_extensions WHERE date=$d AND profile_id IS NOT NULL GROUP BY profile_id"
      .query[(ProfileId, Int)]
      .to[List]
      .transact(xa)
      .map(_.toMap)
}

class RouterRepoLive(xa: Transactor[Task]) extends RouterRepo {
  private type R =
    (
        RouterId,
        String,
        Option[Sha256Hex],
        Option[Sha256Hex],
        Option[Instant],
        Option[ETag],
        Instant,
        Option[String],
    )
  private def toR(r: R)                                                     =
    Router(
      r._1,
      r._2,
      r._3,
      r._4,
      r._5.map(_.toString),
      r._6,
      r._7.toString,
      r._8,
    )
  private val cols                                                          =
    fr"id,name,enrollment_token_hash,token_hash,last_seen_at,last_etag,created_at,agent_version"
  def listAll                                                               =
    (fr"SELECT " ++ cols ++ fr" FROM routers ORDER BY created_at")
      .query[R]
      .map(toR)
      .to[List]
      .transact(xa)
  def findById(id: RouterId)                                                =
    (fr"SELECT " ++ cols ++ fr" FROM routers WHERE id=$id")
      .query[R]
      .map(toR)
      .option
      .transact(xa)
  def findByEnrollmentTokenHash(h: Sha256Hex)                               =
    (fr"SELECT " ++ cols ++ fr" FROM routers WHERE enrollment_token_hash=$h")
      .query[R]
      .map(toR)
      .option
      .transact(xa)
  def findByTokenHash(h: Sha256Hex)                                         =
    DbMetrics.timed("router.findByTokenHash")(
      (fr"SELECT " ++ cols ++ fr" FROM routers WHERE token_hash=$h")
        .query[R]
        .map(toR)
        .option
        .transact(xa),
    )
  def create(name: String, enrollmentTokenHash: Sha256Hex)                  =
    sql"INSERT INTO routers(name,enrollment_token_hash) VALUES($name,$enrollmentTokenHash) RETURNING id"
      .query[RouterId]
      .unique
      .transact(xa)
  def completeEnrollment(id: RouterId, tokenHash: Sha256Hex)                =
    sql"UPDATE routers SET token_hash=$tokenHash, enrollment_token_hash=NULL, last_seen_at=NOW() WHERE id=$id".update.run
      .transact(xa)
      .unit
  def touch(id: RouterId, etag: Option[ETag], agentVersion: Option[String]) =
    sql"""UPDATE routers
          SET last_seen_at=NOW(),
              last_etag=COALESCE($etag,last_etag),
              agent_version=COALESCE($agentVersion,agent_version)
          WHERE id=$id""".update.run
      .transact(xa)
      .unit
  def countSeenSince(cutoff: Instant)                                       =
    DbMetrics.timed("router.countSeenSince")(
      sql"SELECT count(*) FROM routers WHERE last_seen_at >= $cutoff"
        .query[Int]
        .unique
        .transact(xa),
    )
  def delete(id: RouterId)                                                  =
    sql"DELETE FROM routers WHERE id=$id".update.run.transact(xa).unit
}

class TrafficReportRepoLive(xa: Transactor[Task]) extends TrafficReportRepo {
  private type R =
    (
        TrafficReportId,
        RouterId,
        MacAddress,
        Option[IpAddress],
        HostId,
        String,
        String,
        String,
        Int,
        Long,
        Long,
    )
  private def toT(r: R)                               =
    TrafficReport(r._1, r._2, r._3, r._4, r._5, r._6, r._7, r._8, r._9, r._10, r._11)
  def insertBatch(reports: List[TrafficReportInsert]) =
    DbMetrics.timed("traffic.insertBatch")(
      Update[TrafficReportInsert](
        "INSERT INTO traffic_reports(router_id,mac,ip,host_type,host_value,date,period_start,period_end,active_seconds,bytes_in,bytes_out) VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(router_id,period_start,mac,host_type,host_value) DO NOTHING",
      ).updateMany(reports).transact(xa),
    )
  // TODO(#730): remove this read-side join once usage records carry dest_ip.
  // Promotes ipv4/ipv6-typed traffic_reports rows to their resolved fqdn at
  // SELECT time via the same LATERAL join used in TimeUsageRepoLive.
  def listForDevice(mac: MacAddress, date: LocalDate) =
    sql"""SELECT tr.id, tr.router_id, tr.mac, tr.ip,
                 CASE WHEN tr.host_type IN ('ipv4','ipv6') AND ce.resolved_host_value IS NOT NULL
                      THEN 'fqdn' ELSE tr.host_type END,
                 COALESCE(CASE WHEN tr.host_type IN ('ipv4','ipv6') THEN ce.resolved_host_value END,
                          tr.host_value),
                 tr.date::TEXT, tr.period_start::TEXT, tr.period_end::TEXT,
                 tr.active_seconds, tr.bytes_in, tr.bytes_out
          FROM traffic_reports tr
          LEFT JOIN LATERAL (
            SELECT resolved_host_value
            FROM connection_events
            WHERE mac          = tr.mac
              AND dest_ip      = tr.host_value
              AND resolved_host_value IS NOT NULL
              AND ts >= $date::TIMESTAMPTZ
              AND ts <  ($date::DATE + INTERVAL '1 day')::TIMESTAMPTZ
            ORDER BY ts DESC LIMIT 1
          ) ce ON tr.host_type IN ('ipv4','ipv6')
          WHERE tr.mac = $mac AND tr.date = $date
          ORDER BY tr.period_start"""
      .query[R]
      .map(toT)
      .to[List]
      .transact(xa)
  // TODO(#730): remove this read-side join once usage records carry dest_ip.
  def listForRouter(routerId: RouterId, limit: Int)   =
    sql"""SELECT tr.id, tr.router_id, tr.mac, tr.ip,
                 CASE WHEN tr.host_type IN ('ipv4','ipv6') AND ce.resolved_host_value IS NOT NULL
                      THEN 'fqdn' ELSE tr.host_type END,
                 COALESCE(CASE WHEN tr.host_type IN ('ipv4','ipv6') THEN ce.resolved_host_value END,
                          tr.host_value),
                 tr.date::TEXT, tr.period_start::TEXT, tr.period_end::TEXT,
                 tr.active_seconds, tr.bytes_in, tr.bytes_out
          FROM traffic_reports tr
          LEFT JOIN LATERAL (
            SELECT resolved_host_value
            FROM connection_events
            WHERE mac          = tr.mac
              AND dest_ip      = tr.host_value
              AND resolved_host_value IS NOT NULL
              AND ts >= tr.date::TIMESTAMPTZ
              AND ts <  (tr.date + INTERVAL '1 day')::TIMESTAMPTZ
            ORDER BY ts DESC LIMIT 1
          ) ce ON tr.host_type IN ('ipv4','ipv6')
          WHERE tr.router_id = $routerId
          ORDER BY tr.period_start DESC LIMIT $limit"""
      .query[R]
      .map(toT)
      .to[List]
      .transact(xa)

  def listPresenceRows(macs: List[MacAddress], date: LocalDate) =
    listPresenceRowsBetween(macs, date, date, None)

  def listPresenceRows(macs: List[MacAddress], from: LocalDate, to: LocalDate) =
    listPresenceRowsBetween(macs, from, to, None)

  def listPresenceRowsSince(macs: List[MacAddress], date: LocalDate, since: Instant) =
    listPresenceRowsBetween(macs, date, date, Some(since))

  def listPresenceRowsInWindow(
      macs: List[MacAddress],
      fromInstant: Instant,
      toInstant: Instant,
  ): Task[List[wifihaven.api.presence.PresenceRow]] = {
    type Row = (MacAddress, LocalDate, Instant, HostId, Int, Long, Long, Instant, Instant)
    macs match {
      case Nil => ZIO.succeed(List.empty[wifihaven.api.presence.PresenceRow])
      case ms  =>
        val nel = cats.data.NonEmptyList.fromListUnsafe(ms.map(_.value))
        // Filter on period_start (the partition key) so Postgres can prune to the
        // single day-partition(s) the window touches. The old day path filtered on
        // tr.date — not the partition key — which defeated pruning and let one
        // profile's read scan the whole table for 90s (#1099). Same SELECT/LATERAL
        // shape as listPresenceRowsBetween, so the row set is identical.
        val q   =
          fr"""SELECT tr.mac, tr.date, tr.period_start,
                      CASE WHEN tr.host_type IN ('ipv4','ipv6') AND ce.resolved_host_value IS NOT NULL
                           THEN 'fqdn' ELSE tr.host_type END,
                      COALESCE(CASE WHEN tr.host_type IN ('ipv4','ipv6') THEN ce.resolved_host_value END,
                               tr.host_value),
                      tr.active_seconds, tr.bytes_in, tr.bytes_out, tr.period_start, tr.period_end
               FROM traffic_reports tr
               LEFT JOIN LATERAL (
                 SELECT resolved_host_value
                 FROM connection_events
                 WHERE mac          = tr.mac
                   AND dest_ip      = tr.host_value
                   AND resolved_host_value IS NOT NULL
                   AND ts >= tr.date::TIMESTAMPTZ
                   AND ts <  (tr.date + INTERVAL '1 day')::TIMESTAMPTZ
                 ORDER BY ts DESC LIMIT 1
               ) ce ON tr.host_type IN ('ipv4','ipv6')
               WHERE tr.period_start >= $fromInstant AND tr.period_start < $toInstant
                 AND (tr.active_seconds > 0 OR tr.bytes_in > 0 OR tr.bytes_out > 0)
                 AND """ ++ Fragments.in(fr"tr.mac", nel)
        val cio =
          q.query[Row]
            .map { case (m, d, ps, host, secs, bin, bout, pStart, pEnd) =>
              val periodSeconds = math.max(0L, pEnd.getEpochSecond - pStart.getEpochSecond).toInt
              wifihaven.api.presence.PresenceRow(m, d, ps, host, secs, bin + bout, periodSeconds)
            }
            .to[List]
        // Bound each per-request read: a pathological window fails fast with a typed
        // QueryTimeoutException (→ 503) instead of holding a pool connection open and
        // wedging the whole instance (#1099).
        QueryTimeout.bounded(xa, QueryTimeout.PresenceWindow)(cio)
    }
  }

  // TODO(#730): remove this read-side join once usage records carry dest_ip.
  private def listPresenceRowsBetween(
      macs: List[MacAddress],
      from: LocalDate,
      to: LocalDate,
      since: Option[Instant] = None,
  ) = {
    type Row = (MacAddress, LocalDate, Instant, HostId, Int, Long, Long, Instant, Instant)
    macs match {
      case Nil => ZIO.succeed(List.empty[wifihaven.api.presence.PresenceRow])
      case ms  =>
        val nel = cats.data.NonEmptyList.fromListUnsafe(ms.map(_.value))
        val q   =
          fr"""SELECT tr.mac, tr.date, tr.period_start,
                      CASE WHEN tr.host_type IN ('ipv4','ipv6') AND ce.resolved_host_value IS NOT NULL
                           THEN 'fqdn' ELSE tr.host_type END,
                      COALESCE(CASE WHEN tr.host_type IN ('ipv4','ipv6') THEN ce.resolved_host_value END,
                               tr.host_value),
                      tr.active_seconds, tr.bytes_in, tr.bytes_out, tr.period_start, tr.period_end
               FROM traffic_reports tr
               LEFT JOIN LATERAL (
                 SELECT resolved_host_value
                 FROM connection_events
                 WHERE mac          = tr.mac
                   AND dest_ip      = tr.host_value
                   AND resolved_host_value IS NOT NULL
                   AND ts >= tr.date::TIMESTAMPTZ
                   AND ts <  (tr.date + INTERVAL '1 day')::TIMESTAMPTZ
                 ORDER BY ts DESC LIMIT 1
               ) ce ON tr.host_type IN ('ipv4','ipv6')
               WHERE tr.date BETWEEN $from AND $to
                 AND (tr.active_seconds > 0 OR tr.bytes_in > 0 OR tr.bytes_out > 0)
                 AND """ ++ Fragments.in(fr"tr.mac", nel) ++
            since.fold(fr"")(s => fr"AND tr.period_start >= $s")
        q.query[Row]
          .map { case (m, d, ps, host, secs, bin, bout, pStart, pEnd) =>
            val periodSeconds = math.max(0L, pEnd.getEpochSecond - pStart.getEpochSecond).toInt
            wifihaven.api.presence.PresenceRow(m, d, ps, host, secs, bin + bout, periodSeconds)
          }
          .to[List]
          .transact(xa)
    }
  }

  // #846: raw row range pull. Wide range × all-macs scans traffic_reports — caller
  // (UsageTrafficService) enforces a window cap that keeps this from melting prod.
  // TODO(#730): remove this read-side join once usage records carry dest_ip.
  def listRawInRange(
      macs: List[MacAddress],
      fromInstant: Instant,
      toInstant: Instant,
      cursor: Option[wifihaven.api.usage.RawTrafficCursorKey] = None,
      limit: Option[Int] = None,
  ) = {
    type Row =
      (MacAddress, HostId, Instant, Instant, Int, Long, Long)
    val baseSelect =
      fr"""SELECT tr.mac,
                  CASE WHEN tr.host_type IN ('ipv4','ipv6') AND ce.resolved_host_value IS NOT NULL
                       THEN 'fqdn' ELSE tr.host_type END,
                  COALESCE(CASE WHEN tr.host_type IN ('ipv4','ipv6') THEN ce.resolved_host_value END,
                           tr.host_value),
                  tr.period_start, tr.period_end,
                  tr.active_seconds, tr.bytes_in, tr.bytes_out
           FROM traffic_reports tr
           LEFT JOIN LATERAL (
             SELECT resolved_host_value
             FROM connection_events
             WHERE mac          = tr.mac
               AND dest_ip      = tr.host_value
               AND resolved_host_value IS NOT NULL
               AND ts >= tr.date::TIMESTAMPTZ
               AND ts <  (tr.date + INTERVAL '1 day')::TIMESTAMPTZ
             ORDER BY ts DESC LIMIT 1
           ) ce ON tr.host_type IN ('ipv4','ipv6')
           WHERE tr.period_start >= $fromInstant AND tr.period_start < $toInstant
             AND (tr.active_seconds > 0 OR tr.bytes_in > 0 OR tr.bytes_out > 0) """
    val macFilter  = macs match {
      case Nil => fr""
      case ms  =>
        val nel = cats.data.NonEmptyList.fromListUnsafe(ms.map(_.value))
        fr"AND " ++ Fragments.in(fr"tr.mac", nel)
    }
    // #862: keyset cursor on (period_start DESC, mac, host_value). Stable
    // tiebreak so concurrent inserts can't shift pages mid-scroll.
    val byCursor   = cursor match {
      case Some(c) =>
        fr"AND (tr.period_start, tr.mac::TEXT, COALESCE(CASE WHEN tr.host_type IN ('ipv4','ipv6') THEN ce.resolved_host_value END, tr.host_value)) < (${c.ts}::TIMESTAMPTZ, ${c.mac}, ${c.host})"
      case None    => fr""
    }
    // #846 audit: newest-first ordering so the SPA renders most-recent at top.
    // #862: add stable secondary keys for keyset cursor.
    val limitFr    = limit.fold(fr"")(n => fr"LIMIT $n")
    val select     = baseSelect ++ macFilter ++ byCursor ++
      fr"ORDER BY tr.period_start DESC, tr.mac ASC, COALESCE(CASE WHEN tr.host_type IN ('ipv4','ipv6') THEN ce.resolved_host_value END, tr.host_value) ASC " ++ limitFr
    select
      .query[Row]
      .map { case (m, h, ps, pe, secs, bi, bo) =>
        wifihaven.api.usage.TrafficUsageDbRow(m, h, ps, pe, secs, bi, bo)
      }
      .to[List]
      .transact(xa)
  }

  def earliestPeriodStart =
    sql"SELECT MIN(period_start) FROM traffic_reports"
      .query[Option[Instant]]
      .unique
      .transact(xa)

  // #766: per-device FQDN-only host aggregates. Bare-IP rows that fail the
  // LATERAL FQDN resolve are filtered (host IS NULL). Indexed by
  // (mac, period_start) — see V25.
  def listFqdnHostAggregatesForDevice(
      mac: MacAddress,
      fromInstant: Instant,
      toInstant: Instant,
  ) = {
    type Row = (String, Long, Long)
    sql"""SELECT host, SUM(bytes)::BIGINT AS bytes, COUNT(*)::BIGINT AS hits
          FROM (
            SELECT COALESCE(
                     CASE WHEN tr.host_type IN ('ipv4','ipv6') THEN ce.resolved_host_value END,
                     CASE WHEN tr.host_type = 'fqdn' THEN tr.host_value END
                   ) AS host,
                   (tr.bytes_in + tr.bytes_out) AS bytes
            FROM traffic_reports tr
            LEFT JOIN LATERAL (
              SELECT resolved_host_value
              FROM connection_events
              WHERE mac          = tr.mac
                AND dest_ip      = tr.host_value
                AND resolved_host_value IS NOT NULL
                AND ts >= tr.date::TIMESTAMPTZ
                AND ts <  (tr.date + INTERVAL '1 day')::TIMESTAMPTZ
              ORDER BY ts DESC LIMIT 1
            ) ce ON tr.host_type IN ('ipv4','ipv6')
            WHERE tr.mac = $mac
              AND tr.period_start >= $fromInstant
              AND tr.period_start <  $toInstant
              AND (tr.bytes_in + tr.bytes_out) > 0
          ) sub
          WHERE host IS NOT NULL
          GROUP BY host
          ORDER BY bytes DESC"""
      .query[Row]
      .to[List]
      .transact(xa)
      .map(_.flatMap { case (h, b, hits) =>
        Hostname.parse(h).toOption.map(hn => (hn, b, hits))
      })
  }

  // TODO(#730): remove this read-side join once usage records carry dest_ip.
  def listTrafficRollupRows(f: TrafficRollupFilter) = {
    type Row = (RouterId, MacAddress, HostId, LocalDate, Instant, Instant, Int, Long, Long)
    val base    =
      fr"""SELECT tr.router_id, tr.mac,
                  CASE WHEN tr.host_type IN ('ipv4','ipv6') AND ce.resolved_host_value IS NOT NULL
                       THEN 'fqdn' ELSE tr.host_type END,
                  COALESCE(CASE WHEN tr.host_type IN ('ipv4','ipv6') THEN ce.resolved_host_value END,
                           tr.host_value),
                  tr.date, tr.period_start, tr.period_end,
                  tr.active_seconds, tr.bytes_in, tr.bytes_out
           FROM traffic_reports tr
           LEFT JOIN LATERAL (
             SELECT resolved_host_value
             FROM connection_events
             WHERE mac          = tr.mac
               AND dest_ip      = tr.host_value
               AND resolved_host_value IS NOT NULL
               AND ts >= tr.date::TIMESTAMPTZ
               AND ts <  (tr.date + INTERVAL '1 day')::TIMESTAMPTZ
             ORDER BY ts DESC LIMIT 1
           ) ce ON tr.host_type IN ('ipv4','ipv6')
           WHERE 1=1"""
    val byMacs  = f.macs match {
      case None      => fr""
      case Some(Nil) => fr"AND FALSE"
      case Some(ms)  =>
        val nel = cats.data.NonEmptyList.fromListUnsafe(ms.map(_.value))
        fr"AND " ++ Fragments.in(fr"tr.mac", nel)
    }
    val byHost  = f.host.fold(fr"")(h => fr"AND tr.host_value ILIKE ${s"%$h%"}")
    val bySince = f.since.fold(fr"")(s => fr"AND tr.period_end > $s")
    val byUntil = f.until.fold(fr"")(u => fr"AND tr.period_start < $u")
    val sql_    = base ++ byMacs ++ byHost ++ bySince ++ byUntil ++
      fr"ORDER BY tr.router_id, tr.mac, tr.host_type, tr.host_value, tr.date, tr.period_start"
    sql_
      .query[Row]
      .map { r =>
        TrafficRollupRow(
          routerId = r._1,
          mac = r._2,
          host = r._3,
          date = r._4,
          periodStart = r._5,
          periodEnd = r._6,
          activeSeconds = r._7,
          bytesIn = r._8,
          bytesOut = r._9,
        )
      }
      .to[List]
      .transact(xa)
  }
}

class BlockEventRepoLive(xa: Transactor[Task]) extends BlockEventRepo {
  private type R = (BlockEventId, Option[MacAddress], HostId, BlockReason, String)
  private def toB(r: R)                           = BlockEvent(r._1, r._2, r._3, r._4, r._5)
  // #1176/#1179: dual-write `reason_text` (pre-V40 TEXT wire format) alongside
  // `reason` (post-V40 JSONB). Reads still come from `reason`; the parallel
  // column exists so a Render auto-rollback that lands on a post-V44 image
  // binding the column as TEXT can still serve.
  def insertBatch(events: List[BlockEventInsert]) =
    Update[(BlockEventInsert, String)](
      "INSERT INTO block_events(mac,host_type,host_value,reason,reason_text) " +
        "VALUES(?,?,?,?,?)",
    ).updateMany(events.map(e => (e, BlockReason.asWire(e.reason)))).transact(xa)
  def recent(limit: Int)                          =
    sql"SELECT id,mac,host_type,host_value,reason,ts::TEXT FROM block_events ORDER BY ts DESC LIMIT $limit"
      .query[R]
      .map(toB)
      .to[List]
      .transact(xa)
  def listForMac(mac: MacAddress, limit: Int)     =
    sql"SELECT id,mac,host_type,host_value,reason,ts::TEXT FROM block_events WHERE mac=$mac ORDER BY ts DESC LIMIT $limit"
      .query[R]
      .map(toB)
      .to[List]
      .transact(xa)
}

class ConnectionEventRepoLive(xa: Transactor[Task]) extends ConnectionEventRepo {
  private type R =
    (
        ConnectionEventId,
        RouterId,
        Option[MacAddress],
        HostId,
        Option[IpAddress],
        Boolean,
        BlockReason,
        String,
    )
  private def toC(r: R) =
    ConnectionEvent(r._1, r._2, r._3, r._4, r._5, r._6, r._7, r._8)

  // #338: idempotent insert. Client-supplied eventId is the dedup key for
  // retry-queue replays (#330). NULL → server-generated via gen_random_uuid()
  // (older agents pre-eventId support). ON CONFLICT DO NOTHING collapses
  // replays; updateMany returns count of rows actually inserted.
  // #720: resolved_host_value carries an API-side FQDN attribution for
  // ipv4/ipv6-typed events; ingest sets it from sibling fqdn events.
  // #1176/#1179: dual-write `reason_text` (pre-V40 TEXT wire format) alongside
  // `reason` (post-V40 JSONB). See BlockEventRepoLive for the why.
  def insertBatch(events: List[ConnectionEventInsert]) =
    Update[(ConnectionEventInsert, String)](
      "INSERT INTO connection_events(router_id,mac,host_type,host_value,dest_ip,allowed,reason,ts,event_id,resolved_host_value,reason_text) " +
        "VALUES(?,?,?,?,?,?,?,?,COALESCE(?, gen_random_uuid()),?,?) " +
        // #806: unique key widened to (event_id, ts) for ts-range partitioning;
        // a replay carries the same router-supplied ts so dedup is unchanged.
        "ON CONFLICT (event_id, ts) DO NOTHING",
    ).updateMany(events.map(e => (e, BlockReason.asWire(e.reason)))).transact(xa)

  // #720: read paths coalesce a populated resolved_host_value into the host
  // tuple so callers see ('fqdn', resolved) instead of ('ipv4', literal-ip).
  // The raw columns remain untouched on disk; this is purely a render-time
  // promotion. Done in SQL so doobie's tuple decoder receives the already-
  // resolved values.
  private val selectCols: Fragment =
    fr"""id,
         router_id,
         mac,
         CASE WHEN resolved_host_value IS NOT NULL THEN 'fqdn' ELSE host_type END AS host_type,
         COALESCE(resolved_host_value, host_value) AS host_value,
         dest_ip,
         allowed,
         reason,
         ts::TEXT"""

  def recent(limit: Int) =
    (fr"SELECT" ++ selectCols ++ fr"FROM connection_events ORDER BY ts DESC LIMIT $limit")
      .query[R]
      .map(toC)
      .to[List]
      .transact(xa)

  def listForMac(mac: MacAddress, limit: Int) =
    (fr"SELECT" ++ selectCols ++ fr"FROM connection_events WHERE mac=$mac ORDER BY ts DESC LIMIT $limit")
      .query[R]
      .map(toC)
      .to[List]
      .transact(xa)

  def listForRouter(routerId: RouterId, limit: Int) =
    (fr"SELECT" ++ selectCols ++ fr"FROM connection_events WHERE router_id=$routerId ORDER BY ts DESC LIMIT $limit")
      .query[R]
      .map(toC)
      .to[List]
      .transact(xa)

  def findRecentFqdnFor(routerId: RouterId, destIp: IpAddress, since: Instant) =
    sql"""SELECT host_value FROM connection_events
          WHERE router_id = $routerId
            AND dest_ip   = $destIp
            AND host_type = 'fqdn'
            AND ts >= $since
          ORDER BY ts DESC
          LIMIT 1"""
      .query[Hostname]
      .option
      .transact(xa)

  def backfillResolvedFor(
      routerId: RouterId,
      destIp: IpAddress,
      fqdn: Hostname,
      since: Instant,
  ) =
    sql"""UPDATE connection_events
          SET resolved_host_value = ${fqdn.value}
          WHERE router_id = $routerId
            AND dest_ip   = $destIp
            AND host_type IN ('ipv4','ipv6')
            AND resolved_host_value IS NULL
            AND ts >= $since""".update.run.transact(xa)

  // ── Dashboard / log API ──────────────────────────────────────────────────

  // #969: SSDP / UPnP discovery (239.255.255.250) and the rest of the IPv4
  // multicast range, IPv4 broadcast, and IPv6 multicast aren't real per-device
  // connections an operator can act on. Filter pre-aggregation in both /api/logs
  // and /series so counts stay correct. The router-side fix is deferred behind
  // Gate 2 (#654); until then, this read-side filter is the floor.
  //
  // host_type is a strict enum ('fqdn'|'ipv4'|'ipv6') so the regex/literal
  // checks only run on the relevant variant — fqdn rows short-circuit cheaply.
  // We filter on the raw ce.host_value (the actual destination on the wire),
  // not the resolved FQDN: a multicast IP that somehow got a resolved name is
  // still a multicast packet.
  private val multicastFilterSql: Fragment =
    fr"""AND NOT (
           (ce.host_type = 'ipv4' AND (
             ce.host_value ~ '^(22[4-9]|23[0-9])\.'
             OR ce.host_value = '255.255.255.255'
           ))
           OR (ce.host_type = 'ipv6' AND ce.host_value ~* '^ff')
         )"""

  def query(f: LogFilter) = {
    // location is sourced from routers.name until routers.location lands (#136)
    // #720: coalesce resolved_host_value into the returned host tuple so
    // race-loser ipv4 rows show up under their resolved FQDN in the log UI
    // and in domain ILIKE filters.
    val base   =
      fr"""SELECT ce.id, ce.mac, d.name, d.profile_id, p.name,
                  CASE WHEN ce.resolved_host_value IS NOT NULL THEN 'fqdn' ELSE ce.host_type END,
                  COALESCE(ce.resolved_host_value, ce.host_value),
                  1, NOT ce.allowed, ce.reason, r.name,
                  to_char(ce.ts AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
           FROM connection_events ce
           LEFT JOIN devices d  ON d.mac    = ce.mac
           LEFT JOIN profiles p ON p.id     = d.profile_id
           LEFT JOIN routers r  ON r.id     = ce.router_id
           WHERE 1=1"""
    // #862: window anchor moves from "now" to `until` (defaults to NOW()).
    val anchor = f.until.fold(fr"NOW()")(u => fr"$u::TIMESTAMPTZ")
    val window =
      fr"AND ce.ts > " ++ anchor ++ fr"- make_interval(hours => ${f.hours}) AND ce.ts <= " ++ anchor
    // #862: keyset cursor on (ts DESC, id DESC).
    val byCur  = (f.cursorTs, f.cursorId) match {
      case (Some(ts), Some(id)) => fr"AND (ce.ts, ce.id) < ($ts, $id)"
      case _                    => fr""
    }
    // #865: multi-valued mac/deviceId/profileId via IN (...).
    val byMac  = cats.data.NonEmptyList
      .fromList(f.macs)
      .fold(fr"")(nel => fr"AND " ++ Fragments.in(fr"ce.mac", nel))
    val byDev  = cats.data.NonEmptyList
      .fromList(f.deviceIds)
      .fold(fr"")(nel => fr"AND " ++ Fragments.in(fr"d.id", nel))
    val byPid  = cats.data.NonEmptyList
      .fromList(f.profileIds)
      .fold(fr"")(nel => fr"AND " ++ Fragments.in(fr"d.profile_id", nel))
    val byBl   = f.blocked.fold(fr"")(b => fr"AND ce.allowed = ${!b}")
    val byDom  = f.domain.fold(fr"")(d =>
      // #720: domain filter has to look through the resolution too — otherwise
      // a search for "youtube.com" would miss race-loser ipv4 rows we just
      // attributed.
      fr"AND COALESCE(ce.resolved_host_value, ce.host_value) ILIKE ${s"%$d%"}",
    )
    val byLoc  = f.location.fold(fr"")(l => fr"AND r.name = $l")
    val byMc   = if (f.includeMulticast) fr"" else multicastFilterSql
    (base ++ window ++ byCur ++ byMac ++ byDev ++ byPid ++ byBl ++ byDom ++ byLoc ++ byMc ++
      fr"ORDER BY ce.ts DESC, ce.id DESC LIMIT ${f.limit}")
      .query[
        (
            QueryLogId,
            Option[MacAddress],
            Option[String],
            Option[ProfileId],
            Option[String],
            HostId,
            Int,
            Boolean,
            BlockReason,
            Option[String],
            String,
        ),
      ]
      .map(QueryLog.apply)
      .to[List]
      .transact(xa)
  }

  // #847 + #846: multi-column aggregation over connection_events. Buckets are
  // computed in UTC via date_bin — UI re-renders boundaries in household-local
  // tz for display. No rollup table exists yet (#809 in flight); for now even
  // wide buckets compute on-the-fly from connection_events.
  //
  // #846 audit: emit ISO 8601 with T separator + trailing Z so JS Date.parse
  // accepts these without a per-field workaround. Postgres TIMESTAMPTZ::TEXT
  // defaults to "2026-05-21 22:00:00+00" (space separator) which `new Date(...)`
  // rejects as Invalid Date.
  def querySeries(f: LogFilter, bucketSeconds: Int, groupBy: Set[String]) = {
    // #862: inline `bucketSeconds` as a SQL literal (it's a server-controlled
    // enum value, not user input) so the SELECT and GROUP BY copies of the
    // date_bin expression are byte-identical. Postgres matches the SELECT
    // expression against GROUP BY by parse tree; if doobie assigns different
    // parameter positions to the two copies, the match fails and PG raises
    // "ce.ts must appear in GROUP BY".
    val bucketIv    = Fragment.const(s"make_interval(secs => $bucketSeconds)")
    val domainExpr  = fr"COALESCE(ce.resolved_host_value, ce.host_value)"
    val deviceExpr  = fr"COALESCE(d.name, ce.mac::TEXT)"
    val profileExpr = fr"COALESCE(p.name, '(unassigned)')"

    // #917: strictly additive — empty set = no drill, one row per window.
    // Multi-group composes freely across {domain, device, profile, app}.
    val wantsDomain  = groupBy.contains("domain")
    val wantsDevice  = groupBy.contains("device")
    val wantsProfile = groupBy.contains("profile")
    val wantsApp     = groupBy.contains("app")

    // #862: the window bucket uses the full date_bin/to_char expression (not
    // the SELECT alias) so the HAVING clause for the keyset cursor below can
    // reference the same expression — PG doesn't recognize SELECT aliases in
    // HAVING.
    val winExpr = fr"""to_char(date_bin($bucketIv, ce.ts, TIMESTAMP '2000-01-01 00:00:00')
                          AT TIME ZONE 'UTC',
                          'YYYY-MM-DD"T"HH24:MI:SS"Z"')"""

    // Shared FROM + filter fragments. Extracted ahead of the SELECT so the
    // #857 host-resolution probe below can reuse the exact same window + filter
    // predicates as the aggregation, guaranteeing both see the same host set.
    val fromJoins = fr"""FROM connection_events ce
           LEFT JOIN devices d  ON d.mac = ce.mac
           LEFT JOIN profiles p ON p.id  = d.profile_id
           LEFT JOIN routers r  ON r.id  = ce.router_id"""
    // #862: window anchor moves from "now" to `until` (defaults to NOW()).
    val anchor    = f.until.fold(fr"NOW()")(u => fr"$u::TIMESTAMPTZ")
    val window    =
      fr"AND ce.ts > " ++ anchor ++ fr"- make_interval(hours => ${f.hours}) AND ce.ts <= " ++ anchor
    val byMac     = cats.data.NonEmptyList
      .fromList(f.macs)
      .fold(fr"")(nel => fr"AND " ++ Fragments.in(fr"ce.mac", nel))
    val byDev     = cats.data.NonEmptyList
      .fromList(f.deviceIds)
      .fold(fr"")(nel => fr"AND " ++ Fragments.in(fr"d.id", nel))
    val byPid     = cats.data.NonEmptyList
      .fromList(f.profileIds)
      .fold(fr"")(nel => fr"AND " ++ Fragments.in(fr"d.profile_id", nel))
    val byBl      = f.blocked.fold(fr"")(b => fr"AND ce.allowed = ${!b}")
    val byDom     = f.domain.fold(fr"")(d =>
      fr"AND COALESCE(ce.resolved_host_value, ce.host_value) ILIKE ${s"%$d%"}",
    )
    val byLoc     = f.location.fold(fr"")(l => fr"AND r.name = $l")
    val byMc      = if (f.includeMulticast) fr"" else multicastFilterSql
    val filters   = window ++ byMac ++ byDev ++ byPid ++ byBl ++ byDom ++ byLoc ++ byMc

    // #857: resolve each in-window host to its app(s) in Scala via
    // HostMatch.lookupApex — the single host→apex matcher shared with the
    // traffic-usage aggregator (#1085) and PolicyService. We deliberately do
    // NOT match in SQL: app_hosts stores apex-form hosts ("youtube.com"), but
    // connection_events carry FQDNs ("www.youtube.com"), so an exact SQL join
    // would drop subdomains into __other__. Instead we fetch the apex→app
    // inventory + the distinct in-window hosts, run lookupApex per host, and
    // feed the resolved concrete (fqdn → app) pairs back into the aggregation
    // as a VALUES join. A host in N apps yields N pairs (fan-out preserved).
    // #1091 can later swap this read to a denormalized app_id column without
    // changing semantics, since lookupApex stays the source of truth.
    val resolveAppPairs: ConnectionIO[List[(String, (String, String, Option[String], Long))]] =
      if (!wantsApp)
        List.empty[(String, (String, String, Option[String], Long))].pure[ConnectionIO]
      else
        for {
          memberships <-
            fr"""SELECT ah.host, a.slug, a.name, a.icon, a.id
                 FROM app_hosts ah JOIN apps a ON a.id = ah.app_id"""
              .query[(String, String, String, Option[String], Long)]
              .to[List]
          hosts       <-
            (fr"SELECT DISTINCT " ++ domainExpr ++ fr" " ++ fromJoins ++ fr"WHERE 1=1" ++ filters)
              .query[Option[String]]
              .to[List]
        } yield {
          val byApex: Map[String, List[(String, String, Option[String], Long)]] =
            memberships.groupMap(_._1)(m => (m._2, m._3, m._4, m._5))
          hosts.flatten.distinct.flatMap { h =>
            HostMatch.lookupApex(h, byApex).getOrElse(Nil).map(row => (h, row))
          }
        }

    resolveAppPairs
      .flatMap { appPairs =>
        val hasAppMap = wantsApp && appPairs.nonEmpty

        // App expressions. With resolved pairs we COALESCE off the VALUES alias
        // `am`; when grouping by app but nothing matched, everything collapses to
        // __other__; otherwise NULL (un-drilled path keeps its constant shape).
        val appSlugExpr =
          if (hasAppMap) fr"COALESCE(am.slug, '__other__')"
          else if (wantsApp) fr"'__other__'::TEXT"
          else fr"NULL::TEXT"
        val appNameExpr =
          if (hasAppMap) fr"COALESCE(am.name, 'Other')"
          else if (wantsApp) fr"'Other'::TEXT"
          else fr"NULL::TEXT"
        val appIconExpr = if (hasAppMap) fr"am.icon" else fr"NULL::TEXT"
        val appIdExpr   = if (hasAppMap) fr"am.app_id" else fr"NULL::BIGINT"

        // Always SELECT all group expressions (NULL when not requested) so
        // the result tuple has a constant shape and doobie can decode it.
        val selDomain  = if (wantsDomain) domainExpr else fr"NULL::TEXT"
        val selDevice  = if (wantsDevice) deviceExpr else fr"NULL::TEXT"
        val selProfile = if (wantsProfile) profileExpr else fr"NULL::TEXT"
        val selAppSlug = appSlugExpr
        val selAppName = appNameExpr
        val selAppIcon = appIconExpr
        val selAppId   = appIdExpr

        // GROUP BY only the requested columns, plus the window bucket. App needs
        // (slug, name, icon, id) so the GROUP BY can carry through metadata; in
        // practice (slug) alone uniquely identifies the row but name/icon/id are
        // functionally dependent so adding them is safe and avoids MAX() casts.
        // Gated on hasAppMap: when grouping by app with no matches the app
        // columns are constants, which we leave out of GROUP BY (a constant
        // SELECT is fine ungrouped) so PG never sees a constant in GROUP BY.
        val groupByParts = List(
          Some(winExpr),
          Option.when(wantsDomain)(domainExpr),
          Option.when(wantsDevice)(deviceExpr),
          Option.when(wantsProfile)(profileExpr),
          Option.when(hasAppMap)(appSlugExpr),
          Option.when(hasAppMap)(appNameExpr),
          Option.when(hasAppMap)(appIconExpr),
          Option.when(hasAppMap)(appIdExpr),
        ).flatten
        val groupByCols  = groupByParts.reduce(_ ++ fr"," ++ _)

        // #862: stable lexicographic group key for keyset paging. Concatenates the
        // SELECTed group values (or empty string when not requested) with a
        // delimiter unlikely to collide with hostnames / device names.
        val groupKeyParts = List(
          Option.when(wantsDomain)(fr"COALESCE(" ++ domainExpr ++ fr", '')"),
          Option.when(wantsDevice)(fr"COALESCE(" ++ deviceExpr ++ fr", '')"),
          Option.when(wantsProfile)(fr"COALESCE(" ++ profileExpr ++ fr", '')"),
        ).flatten
        val groupKeyExpr  = groupKeyParts match {
          case Nil   => fr"''"
          case parts => parts.reduce((a, b) => a ++ fr"|| chr(31) ||" ++ b)
        }

        // #857: resolved (fqdn → app) pairs as a VALUES table. Explicit casts on
        // every row so PG never has to infer a column type from an all-NULL icon.
        val appJoin = if (hasAppMap) {
          val values = appPairs
            .map { case (h, (slug, name, icon, id)) =>
              fr"($h::TEXT, $slug::TEXT, $name::TEXT, $icon::TEXT, $id::BIGINT)"
            }
            .reduce((a, b) => a ++ fr"," ++ b)
          fr"LEFT JOIN (VALUES" ++ values ++ fr") AS am(host, slug, name, icon, app_id) ON am.host = " ++
            domainExpr
        } else fr""

        val base    =
          fr"""SELECT """ ++ selDomain ++ fr"AS grp_domain," ++
            selDevice ++ fr"AS grp_device," ++
            selProfile ++ fr"AS grp_profile," ++
            selAppSlug ++ fr"AS grp_app_slug," ++
            selAppName ++ fr"AS grp_app_name," ++
            selAppIcon ++ fr"AS grp_app_icon," ++
            selAppId ++ fr"""AS grp_app_id,
                  to_char(date_bin($bucketIv, ce.ts, TIMESTAMP '2000-01-01 00:00:00')
                          AT TIME ZONE 'UTC',
                          'YYYY-MM-DD"T"HH24:MI:SS"Z"') AS window_start,
                  COUNT(*) FILTER (WHERE ce.allowed)::INT      AS count_succeeded,
                  COUNT(*) FILTER (WHERE NOT ce.allowed)::INT  AS count_blocked,
                  to_char(MAX(ce.ts) AT TIME ZONE 'UTC',
                          'YYYY-MM-DD"T"HH24:MI:SS"Z"')  AS last_seen,
                  mode() WITHIN GROUP (ORDER BY """ ++ deviceExpr ++ fr""") AS top_device,
                  COUNT(DISTINCT """ ++ deviceExpr ++ fr""")::INT          AS distinct_devices,
                  COUNT(DISTINCT """ ++ profileExpr ++ fr""")::INT         AS distinct_profiles,
                  COUNT(DISTINCT """ ++ domainExpr ++ fr""")::INT          AS distinct_domains,
                  COUNT(DISTINCT """ ++ appSlugExpr ++ fr""")::INT         AS distinct_apps,
                  CASE WHEN COUNT(DISTINCT """ ++ deviceExpr ++ fr""") = 1
                       THEN MAX(""" ++ deviceExpr ++ fr""") END            AS sole_device,
                  CASE WHEN COUNT(DISTINCT """ ++ profileExpr ++ fr""") = 1
                       THEN MAX(""" ++ profileExpr ++ fr""") END           AS sole_profile,
                  CASE WHEN COUNT(DISTINCT """ ++ domainExpr ++ fr""") = 1
                       THEN MAX(""" ++ domainExpr ++ fr""") END            AS sole_domain,
                  CASE WHEN COUNT(DISTINCT """ ++ appSlugExpr ++ fr""") = 1
                       THEN MAX(""" ++ appNameExpr ++ fr""") END           AS sole_app
           """ ++ fromJoins ++ appJoin ++ fr"""WHERE 1=1"""
        // #862: keyset cursor on (window_start DESC, group_key ASC). HAVING uses
        // the full window/groupkey expressions (PG doesn't allow SELECT aliases
        // in HAVING).
        // Tuple lex `(a,b) < (x,y)` only matches when both columns sort the same
        // direction. Our order is (window_start DESC, group_key ASC), so we
        // split into an OR: strictly-older window, OR same window with a
        // strictly-greater key.
        val having  = (f.cursorWs, f.cursorKey) match {
          case (Some(ws), Some(k)) =>
            fr"HAVING " ++ winExpr ++ fr"< $ws OR (" ++ winExpr ++ fr"= $ws AND " ++
              groupKeyExpr ++ fr"> $k)"
          case _                   => fr""
        }
        // #862: stable secondary order so keyset cursor is deterministic. Was
        // COUNT(*) DESC which is unstable under inserts. When groupBy is empty
        // (#917 one-row-per-window mode), there's no secondary key and PG rejects
        // a literal '' in ORDER BY ("non-integer constant in ORDER BY"), so we
        // drop the second clause.
        val orderBy =
          if (groupKeyParts.isEmpty) fr"ORDER BY window_start DESC"
          else fr"ORDER BY window_start DESC, " ++ groupKeyExpr ++ fr" ASC"
        (base ++ filters ++
          fr"GROUP BY " ++ groupByCols ++ fr" " ++ having ++
          orderBy ++ fr"LIMIT ${f.limit}")
          .query[
            (
                Option[String], // grp_domain
                Option[String], // grp_device
                Option[String], // grp_profile
                Option[String], // grp_app_slug
                Option[String], // grp_app_name
                Option[String], // grp_app_icon
                Option[Long],   // grp_app_id
                String,         // window_start
                Int,            // count_succeeded
                Int,            // count_blocked
                String,         // last_seen
                Option[String], // top_device
                Int,            // distinct_devices
                Int,            // distinct_profiles
                Int,            // distinct_domains
                Int,            // distinct_apps
                Option[String], // sole_device   (null when distinct != 1)
                Option[String], // sole_profile
                Option[String], // sole_domain
                Option[String], // sole_app
            ),
          ]
          .map {
            case (
                  gd,
                  gv,
                  gp,
                  gas,
                  gan,
                  gai,
                  gid,
                  ws,
                  sc,
                  bl,
                  ls,
                  td,
                  dd,
                  dpr,
                  dm,
                  dap,
                  sde,
                  spr,
                  sdo,
                  sap,
                ) =>
              val groupMap = scala.collection.mutable.LinkedHashMap.empty[String, String]
              gd.foreach(v => groupMap += ("domain" -> v))
              gv.foreach(v => groupMap += ("device" -> v))
              gp.foreach(v => groupMap += ("profile" -> v))
              gas.foreach(v => groupMap += ("app" -> v))
              // Only surface sole* when the column is NOT in groupBy — when it IS,
              // the value is already in `groups`.
              ConnectionEventAggRow(
                groups = groupMap.toMap,
                windowStart = ws,
                countSucceeded = sc,
                countBlocked = bl,
                lastSeen = ls,
                topDevice = td,
                distinctDevices = dd,
                distinctProfiles = dpr,
                distinctDomains = dm,
                distinctApps = dap,
                soleDevice = if (wantsDevice) None else sde,
                soleProfile = if (wantsProfile) None else spr,
                soleDomain = if (wantsDomain) None else sdo,
                soleApp = if (wantsApp) None else sap,
                // appId is the BIGINT primary key for the apps table; the
                // synthetic "__other__" bucket has no row in apps so app_id is
                // NULL on the wire.
                appId = if (wantsApp) gid.map(AppId(_)) else None,
                appName = if (wantsApp) gan else None,
                appIcon = if (wantsApp) gai else None,
              )
          }
          .to[List]
      }
      .transact(xa)
  }

  def stats =
    for {
      tt  <- sql"SELECT COUNT(*)::INT FROM connection_events WHERE ts > NOW()-INTERVAL '24 hours'"
        .query[Int]
        .unique
        .transact(xa)
      bt  <-
        sql"SELECT COUNT(*)::INT FROM connection_events WHERE ts > NOW()-INTERVAL '24 hours' AND NOT allowed"
          .query[Int]
          .unique
          .transact(xa)
      th  <- sql"SELECT COUNT(*)::INT FROM connection_events WHERE ts > NOW()-INTERVAL '1 hour'"
        .query[Int]
        .unique
        .transact(xa)
      bh  <-
        sql"SELECT COUNT(*)::INT FROM connection_events WHERE ts > NOW()-INTERVAL '1 hour' AND NOT allowed"
          .query[Int]
          .unique
          .transact(xa)
      top <- sql"""SELECT CASE WHEN resolved_host_value IS NOT NULL THEN 'fqdn' ELSE host_type END,
                          COALESCE(resolved_host_value, host_value),
                          COUNT(*)::INT
                   FROM connection_events
                   WHERE NOT allowed AND ts > NOW()-INTERVAL '24 hours'
                   GROUP BY 1, 2 ORDER BY COUNT(*) DESC LIMIT 10"""
        .query[(HostId, Int)]
        .map(DomainCount.apply)
        .to[List]
        .transact(xa)
      dev <- sql"""SELECT ce.mac::TEXT,
                          COALESCE(d.name, ce.mac),
                          COUNT(*)::INT,
                          SUM(CASE WHEN NOT ce.allowed THEN 1 ELSE 0 END)::INT
                   FROM connection_events ce
                   LEFT JOIN devices d ON d.mac = ce.mac
                   WHERE ce.ts > NOW()-INTERVAL '24 hours'
                     AND ce.mac IS NOT NULL
                   GROUP BY ce.mac, d.name
                   ORDER BY COUNT(*) DESC LIMIT 20"""
        .query[(MacAddress, String, Int, Int)]
        .map(DeviceStats.apply)
        .to[List]
        .transact(xa)
    } yield DashboardStats(tt, bt, th, bh, top, dev)

  def topBlocked(hours: Int, lim: Int) =
    sql"""SELECT CASE WHEN resolved_host_value IS NOT NULL THEN 'fqdn' ELSE host_type END,
                 COALESCE(resolved_host_value, host_value),
                 COUNT(*)::INT
          FROM connection_events
          WHERE NOT allowed AND ts > NOW() - make_interval(hours => $hours)
          GROUP BY 1, 2 ORDER BY COUNT(*) DESC LIMIT $lim"""
      .query[(HostId, Int)]
      .map(DomainCount.apply)
      .to[List]
      .transact(xa)

  def lastSeenByMacSince(since: Instant): Task[Map[MacAddress, Instant]] =
    sql"""SELECT mac, MAX(ts)
          FROM connection_events
          WHERE mac IS NOT NULL AND ts > $since
          GROUP BY mac"""
      .query[(MacAddress, Instant)]
      .to[List]
      .transact(xa)
      .map(_.toMap)
}

// ── #761: apps ────────────────────────────────────────────────────────────
//
// CRUD over the apps / app_hosts / app_policy_assignments tables. No business
// logic here: slug derivation, host canonicalization, template seeding, and
// snapshot expansion all live in #762 / #763. Repo just round-trips rows.

trait AppRepo {
  def listAll: Task[List[App]]
  def findById(id: AppId): Task[Option[App]]
  def findBySlug(slug: String): Task[Option[App]]
  def findByTemplateId(templateId: AppTemplateId): Task[Option[App]]
  def create(
      name: String,
      slug: String,
      templateId: Option[AppTemplateId],
      icon: Option[String],
      iconType: IconType = IconType.Emoji,
  ): Task[AppId]
  def update(a: App): Task[Unit]
  def delete(id: AppId): Task[Unit]

  /**
   * Replace the full set of hosts for an app. Wipes prior rows; canonicalization is the caller's
   * responsibility.
   */
  def setHosts(appId: AppId, hosts: List[Hostname]): Task[Unit]
  def getHosts(appId: AppId): Task[List[Hostname]]

  /**
   * Current `app_hosts_version` — a global counter bumped by every host-set mutation ([[setHosts]],
   * [[delete]]). The rollup writer stamps rolled rows with the value it read here; a read path
   * compares it against the value stamped on rollup rows to decide whether the pre-attributed
   * `app_id`s are fresh or must be recomputed in process (#1091).
   */
  def currentHostsVersion: Task[Long]

  /**
   * #769: full (host, app_id) inventory across all apps. Used by the group-by-app aggregation paths
   * for Connection Events + Traffic Usage to bucket rows into their owning app, with `__other__`
   * for hosts not in any app. One row per (host, app) pair — a host that's in two apps yields two
   * entries.
   */
  def listAllHostMappings: Task[List[AppHost]]

  /** Upsert a (app, profile) assignment. Existing row at that key is overwritten. */
  def upsertAssignment(
      appId: AppId,
      profileId: ProfileId,
      mode: AppMode,
      dailyMinutes: Option[Int],
      exemptFromDaily: Boolean,
  ): Task[AppPolicyAssignmentId]
  def deleteAssignment(appId: AppId, profileId: ProfileId): Task[Unit]
  def listAssignmentsForApp(appId: AppId): Task[List[AppPolicyAssignment]]
  def listAssignmentsForProfile(profileId: ProfileId): Task[List[AppPolicyAssignment]]
}

class AppRepoLive(xa: Transactor[Task]) extends AppRepo {
  private type R =
    (AppId, String, String, Option[AppTemplateId], Option[String], IconType, Instant)
  private def toApp(r: R) = App(r._1, r._2, r._3, r._4, r._5, r._6, r._7)

  def listAll =
    sql"SELECT id,name,slug,template_id,icon,icon_type,created_at FROM apps ORDER BY id"
      .query[R]
      .map(toApp)
      .to[List]
      .transact(xa)

  def findById(id: AppId) =
    sql"SELECT id,name,slug,template_id,icon,icon_type,created_at FROM apps WHERE id=$id"
      .query[R]
      .map(toApp)
      .option
      .transact(xa)

  def findBySlug(slug: String) =
    sql"SELECT id,name,slug,template_id,icon,icon_type,created_at FROM apps WHERE slug=$slug"
      .query[R]
      .map(toApp)
      .option
      .transact(xa)

  def findByTemplateId(templateId: AppTemplateId) =
    sql"SELECT id,name,slug,template_id,icon,icon_type,created_at FROM apps WHERE template_id=$templateId"
      .query[R]
      .map(toApp)
      .option
      .transact(xa)

  def create(
      name: String,
      slug: String,
      templateId: Option[AppTemplateId],
      icon: Option[String],
      iconType: IconType = IconType.Emoji,
  ) =
    sql"""INSERT INTO apps(name,slug,template_id,icon,icon_type)
          VALUES($name,$slug,$templateId,$icon,$iconType) RETURNING id"""
      .query[AppId]
      .unique
      .transact(xa)

  def update(a: App) =
    sql"""UPDATE apps SET
            name=${a.name},
            slug=${a.slug},
            template_id=${a.templateId},
            icon=${a.icon},
            icon_type=${a.iconType}
          WHERE id=${a.id}""".update.run.transact(xa).unit

  // Every host-set mutation bumps the global app_hosts_version so rollup rows
  // attributed at an older version are detected as stale on read (#1091).
  private val bumpHostsVersion =
    sql"UPDATE app_hosts_version SET version = version + 1".update.run

  def delete(id: AppId) =
    (sql"DELETE FROM apps WHERE id=$id".update.run *> bumpHostsVersion).transact(xa).unit

  def setHosts(appId: AppId, hosts: List[Hostname]) = {
    val del = sql"DELETE FROM app_hosts WHERE app_id=$appId".update.run
    val ins = hosts.distinct.map(h =>
      sql"INSERT INTO app_hosts(app_id,host) VALUES($appId,$h) ON CONFLICT DO NOTHING".update.run,
    )
    (del *> ins.foldLeft(FC.unit)(_ *> _.void) *> bumpHostsVersion).transact(xa).unit
  }

  def getHosts(appId: AppId) =
    sql"SELECT host FROM app_hosts WHERE app_id=$appId ORDER BY host"
      .query[Hostname]
      .to[List]
      .transact(xa)

  def currentHostsVersion =
    sql"SELECT version FROM app_hosts_version".query[Long].unique.transact(xa)

  def listAllHostMappings =
    sql"SELECT app_id, host FROM app_hosts"
      .query[(AppId, Hostname)]
      .map { case (id, h) => AppHost(id, h) }
      .to[List]
      .transact(xa)

  def upsertAssignment(
      appId: AppId,
      profileId: ProfileId,
      mode: AppMode,
      dailyMinutes: Option[Int],
      exemptFromDaily: Boolean,
  ) =
    sql"""INSERT INTO app_policy_assignments
            (app_id, profile_id, mode, daily_minutes, exempt_from_daily)
          VALUES ($appId, $profileId, ${AppMode.asString(mode)}, $dailyMinutes, $exemptFromDaily)
          ON CONFLICT (app_id, profile_id) DO UPDATE SET
            mode = EXCLUDED.mode,
            daily_minutes = EXCLUDED.daily_minutes,
            exempt_from_daily = EXCLUDED.exempt_from_daily
          RETURNING id"""
      .query[AppPolicyAssignmentId]
      .unique
      .transact(xa)

  def deleteAssignment(appId: AppId, profileId: ProfileId) =
    sql"DELETE FROM app_policy_assignments WHERE app_id=$appId AND profile_id=$profileId".update.run
      .transact(xa)
      .unit

  private type AR =
    (AppPolicyAssignmentId, AppId, ProfileId, AppMode, Option[Int], Boolean)
  private def toAssignment(r: AR) =
    AppPolicyAssignment(r._1, r._2, r._3, r._4, r._5, r._6)

  def listAssignmentsForApp(appId: AppId) =
    sql"""SELECT id,app_id,profile_id,mode,daily_minutes,exempt_from_daily
          FROM app_policy_assignments WHERE app_id=$appId ORDER BY id"""
      .query[AR]
      .map(toAssignment)
      .to[List]
      .transact(xa)

  def listAssignmentsForProfile(profileId: ProfileId) =
    sql"""SELECT id,app_id,profile_id,mode,daily_minutes,exempt_from_daily
          FROM app_policy_assignments WHERE profile_id=$profileId ORDER BY id"""
      .query[AR]
      .map(toAssignment)
      .to[List]
      .transact(xa)
}

object Repos {
  val userRepo              = ZLayer.fromFunction(UserRepoLive(_))
  val userProfileRepo       = ZLayer.fromFunction(UserProfileRepoLive(_))
  val profileRepo           = ZLayer.fromFunction(ProfileRepoLive(_))
  val scheduleRepo          = ZLayer.fromFunction(ScheduleRepoLive(_))
  val householdSettingsRepo = ZLayer.fromFunction(HouseholdSettingsRepoLive(_))
  val timeLimitRepo         = ZLayer.fromFunction(TimeLimitRepoLive(_))
  val siteTimeLimitRepo     = ZLayer.fromFunction(SiteTimeLimitRepoLive(_))
  val deviceRepo            = ZLayer.fromFunction(DeviceRepoLive(_))
  val blocklistRepo         = ZLayer.fromFunction(BlocklistRepoLive(_))
  val timeUsageRepo         = ZLayer.fromFunction(TimeUsageRepoLive(_))
  val timeExtRepo           = ZLayer.fromFunction(TimeExtensionRepoLive(_))
  val routerRepo            = ZLayer.fromFunction(RouterRepoLive(_))
  val trafficReportRepo     = ZLayer.fromFunction(TrafficReportRepoLive(_))
  val blockEventRepo        = ZLayer.fromFunction(BlockEventRepoLive(_))
  val connEventRepo         = ZLayer.fromFunction(ConnectionEventRepoLive(_))
  val alertRepo             = ZLayer.fromFunction(AlertRepoLive(_))
  val appRepo               = ZLayer.fromFunction(AppRepoLive(_))
  val rollupRepo            = ZLayer.fromFunction(RollupRepoLive(_))
  val timeUsedRollupRepo    = ZLayer.fromFunction(TimeUsedRollupRepoLive(_))
  val all                   =
    userRepo ++ userProfileRepo ++ profileRepo ++ scheduleRepo ++ householdSettingsRepo ++ timeLimitRepo ++ siteTimeLimitRepo ++ deviceRepo ++ blocklistRepo ++ timeUsageRepo ++ timeExtRepo ++ routerRepo ++ trafficReportRepo ++ blockEventRepo ++ connEventRepo ++ alertRepo ++ appRepo ++ rollupRepo ++ timeUsedRollupRepo
}
