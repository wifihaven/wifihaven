package wifihaven.shared

import wifihaven.shared.types.*
import zio.json.*

import java.time.{LocalTime, ZoneId}
import java.util.UUID

given JsonCodec[UUID] =
  JsonCodec[String].transformOrFail(
    s => scala.util.Try(UUID.fromString(s)).toEither.left.map(_.getMessage),
    _.toString,
  )

// #334: schedules + daily-reset carry an IANA timezone. We serialize
// LocalTime as "HH:mm" (24-hour, zero-padded) and ZoneId as the IANA name
// (e.g. "America/Los_Angeles"). ZoneId.of validates the name; an unknown
// zone fails at the wire boundary with a clear error.
given JsonCodec[LocalTime] =
  JsonCodec[String].transformOrFail(
    s =>
      scala.util
        .Try(LocalTime.parse(s))
        .toEither
        .left
        .map(e => s"invalid time '$s': ${e.getMessage}"),
    t => "%02d:%02d".format(t.getHour, t.getMinute),
  )

given JsonCodec[ZoneId] =
  JsonCodec[String].transformOrFail(
    s =>
      scala.util
        .Try(ZoneId.of(s))
        .toEither
        .left
        .map(e => s"invalid timezone '$s': ${e.getMessage}"),
    _.getId,
  )

enum UserRole {
  case Admin, Adult, Child
}

object UserRole {
  def parse(s: String): Option[UserRole] = s.toLowerCase match {
    case "admin" => Some(Admin)
    case "adult" => Some(Adult)
    case "child" => Some(Child)
    case _       => None
  }
  def asString(r: UserRole): String      = r match {
    case Admin => "admin"
    case Adult => "adult"
    case Child => "child"
  }
  given JsonCodec[UserRole]              = JsonCodec[String].transformOrFail(
    s => parse(s).toRight(s"unknown role: $s"),
    asString,
  )
}

// #385: per-profile failover behaviour when the agent loses contact with the
// API for >5 min. Three modes (replacing the original binary Open/Closed
// from #311, which collapsed AllowAll and LastKnownGood into one):
//   BlockAll      — drop all forwarded traffic for the profile's devices
//                   (fail-safe; recommended default for child profiles).
//   AllowAll      — pass forwarded traffic with no enforcement; clears all
//                   per-MAC drop rules for the profile (only sensible for
//                   trusted profiles where the cached-snapshot defence is
//                   not worth the lockout risk).
//   LastKnownGood — keep enforcing the cached snapshot exactly as-is
//                   (recommended default for adult/admin profiles —
//                   preserves existing category/extra/schedule rules
//                   without auto-blocking everything).
enum FailureMode {
  case BlockAll, AllowAll, LastKnownGood
}

object FailureMode {
  def asString(m: FailureMode): String      = m match {
    case BlockAll      => "block-all"
    case AllowAll      => "allow-all"
    case LastKnownGood => "last-known-good"
  }
  def parse(s: String): Option[FailureMode] = s.toLowerCase match {
    case "block-all"       => Some(BlockAll)
    case "allow-all"       => Some(AllowAll)
    case "last-known-good" => Some(LastKnownGood)
    case _                 => None
  }
  given JsonCodec[FailureMode]              = JsonCodec[String].transformOrFail(
    s => parse(s).toRight(s"unknown failureMode: $s"),
    asString,
  )
}

case class Profile(
    id: ProfileId,
    name: String,
    blockedCategories: List[BlocklistId],
    extraBlocked: List[Hostname],
    extraAllowed: List[Hostname],
    paused: Boolean,
    failureMode: FailureMode = FailureMode.LastKnownGood,
    blockIpOnly: Boolean = false,
) derives JsonCodec

case class Schedule(
    id: ScheduleId,
    profileId: ProfileId,
    name: String,
    days: List[String],
    startLocal: LocalTime,
    endLocal: LocalTime,
    tz: ZoneId,
) derives JsonCodec

case class TimeLimit(
    id: TimeLimitId,
    profileId: ProfileId,
    dailyMinutes: Int,
) derives JsonCodec

case class SiteTimeLimit(
    id: SiteTimeLimitId,
    profileId: ProfileId,
    domainPattern: String,
    dailyMinutes: Int,
    label: String,
    exemptFromDaily: Boolean = true,
) derives JsonCodec

case class TimeUsage(
    id: TimeUsageId,
    deviceMac: MacAddress,
    host: HostId,
    date: String,
    minutesUsed: Int,
    lastSeenAt: String,
) derives JsonCodec

case class TimeExtension(
    id: TimeExtensionId,
    profileId: Option[ProfileId],
    deviceMac: Option[MacAddress],
    date: String,
    extraMinutes: Int,
    grantedBy: String,
    note: Option[String],
    createdAt: String,
) derives JsonCodec

case class Device(
    id: DeviceId,
    mac: MacAddress,
    name: String,
    profileId: Option[ProfileId],
    profileName: Option[String],
    lastSeenIp: Option[IpAddress],
    lastSeenAt: Option[String],
) derives JsonCodec

case class QueryLog(
    id: QueryLogId,
    mac: Option[MacAddress],
    deviceName: Option[String],
    profileId: Option[ProfileId],
    profileName: Option[String],
    host: HostId,
    qtype: Int,
    blocked: Boolean,
    reason: String,
    location: Option[String],
    ts: String,
) derives JsonCodec

case class LoginRequest(username: String, password: String) derives JsonCodec
// mustChangePassword: true when the server-side flag is set (e.g. freshly-seeded admin).
// The web uses this to redirect directly to the change-password page after login
// before the user can reach any other route.
case class LoginResponse(
    token: JwtToken,
    role: UserRole,
    username: String,
    mustChangePassword: Boolean = false,
) derives JsonCodec
case class ChangePasswordRequest(currentPassword: String, newPassword: String) derives JsonCodec
case class CreateUserRequest(
    username: String,
    password: String,
    role: UserRole,
    profileIds: List[ProfileId] = Nil,
) derives JsonCodec
case class UserSummary(
    id: UserId,
    username: String,
    role: UserRole,
    profileIds: List[ProfileId],
) derives JsonCodec
case class MeResponse(
    username: String,
    role: UserRole,
    profileIds: List[ProfileId],
) derives JsonCodec
case class SetUserProfilesRequest(profileIds: List[ProfileId]) derives JsonCodec
case class SetProfileUsersRequest(userIds: List[UserId]) derives JsonCodec

case class UpsertProfileRequest(
    name: String,
    blockedCategories: List[BlocklistId],
    extraBlocked: List[Hostname],
    extraAllowed: List[Hostname],
    paused: Boolean,
    schedules: List[ScheduleRequest],
    timeLimit: Option[Int],
    siteTimeLimits: List[SiteTimeLimitRequest],
    failureMode: Option[FailureMode] = None,
    blockIpOnly: Option[Boolean] = None,
) derives JsonCodec

case class ScheduleRequest(
    name: String,
    days: List[String],
    startLocal: LocalTime,
    endLocal: LocalTime,
    tz: ZoneId,
) derives JsonCodec

case class HouseholdSettings(
    dailyResetTime: LocalTime,
    dailyResetTz: ZoneId,
    heartbeatFilter: HeartbeatFilter,
) derives JsonCodec

case class UpdateHouseholdSettingsRequest(
    dailyResetTime: LocalTime,
    dailyResetTz: ZoneId,
    heartbeatFilter: HeartbeatFilter,
) derives JsonCodec

/**
 * #714: knobs for the server-side heartbeat filter applied at the Presence aggregation stage. The
 * filter drops a `traffic_reports` row from per-device/per-profile screen-time totals (NOT from
 * `time_usage`-derived per-site totals) when total bytes fall below `bytesThreshold`.
 */
case class HeartbeatFilter(
    enabled: Boolean,
    bytesThreshold: Int,
    heartbeatHostPatterns: List[String] = Nil,
) derives JsonCodec

object HeartbeatFilter {
  val Off: HeartbeatFilter =
    HeartbeatFilter(enabled = false, bytesThreshold = 0, heartbeatHostPatterns = Nil)
}

/**
 * #714: response body for `GET /api/time/heartbeat-explain/{mac}?date=`. Returns the live filter
 * config alongside per-row classification so the operator can tune thresholds against real data
 * before flipping `heartbeat_filter_enabled` on.
 */
case class HeartbeatExplainResponse(
    mac: MacAddress,
    date: String,
    filter: HeartbeatFilter,
    rows: List[HeartbeatExplainRow],
) derives JsonCodec

case class HeartbeatExplainRow(
    mac: MacAddress,
    periodStart: String,
    host: HostId,
    activeSeconds: Int,
    periodSeconds: Int,
    bytes: Long,
    classified: String,
    reasons: List[String],
) derives JsonCodec

case class SiteTimeLimitRequest(
    domainPattern: String,
    dailyMinutes: Int,
    label: String,
    exemptFromDaily: Boolean = true,
) derives JsonCodec

case class UpsertDeviceRequest(
    mac: MacAddress,
    name: String,
    profileId: ProfileId,
) derives JsonCodec

case class GrantExtensionRequest(
    profileId: ProfileId,
    extraMinutes: Int,
    note: Option[String],
) derives JsonCodec

case class DashboardStats(
    totalToday: Int,
    blockedToday: Int,
    totalHour: Int,
    blockedHour: Int,
    topBlocked: List[DomainCount],
    perDevice: List[DeviceStats],
) derives JsonCodec

case class DomainCount(host: HostId, count: Int) derives JsonCodec
case class DeviceStats(mac: MacAddress, deviceName: String, total: Int, blocked: Int)
    derives JsonCodec

case class SiteUsage(
    label: String,
    domainPattern: String,
    limitMins: Int,
    usedMins: Int,
    remainingMins: Int,
) derives JsonCodec

case class DeviceTimeStatus(
    deviceMac: MacAddress,
    deviceName: String,
    date: String,
    profileName: String,
    profileId: Option[ProfileId],
    dailyLimitMins: Option[Int],
    usedMins: Int,
    extensionMins: Int,
    remainingMins: Option[Int],
    siteUsage: List[SiteUsage],
) derives JsonCodec

case class DeviceUsageSummary(
    deviceMac: MacAddress,
    deviceName: String,
    usedMins: Int,
) derives JsonCodec

case class HostUsage(host: HostId, usedMins: Int) derives JsonCodec

case class ProfileTimeStatus(
    profileId: ProfileId,
    profileName: String,
    date: String,
    dailyLimitMins: Option[Int],
    usedMins: Int,
    extensionMins: Int,
    remainingMins: Option[Int],
    siteUsage: List[SiteUsage],
    devices: List[DeviceUsageSummary],
    hostUsage: List[HostUsage],
) derives JsonCodec

case class ProfileTimeDayTotal(date: String, usedMins: Int) derives JsonCodec

/**
 * Weekly screen-time roll-up (#723) — sibling shape to [[ProfileTimeStatus]]. `totalMins`,
 * `devices` and `hostUsage` are bucket-deduped across the full `from`..`to` range, so totals can be
 * lower than naively summing `perDay.usedMins` (a device touching the same 5-min bucket on two
 * hosts still only counts once for the range). `dailyLimitMins` is informational — the daily cap
 * does not weekly-aggregate.
 */
case class ProfileTimeStatusWeek(
    profileId: ProfileId,
    profileName: String,
    from: String,
    to: String,
    dailyLimitMins: Option[Int],
    totalMins: Int,
    perDay: List[ProfileTimeDayTotal],
    devices: List[DeviceUsageSummary],
    hostUsage: List[HostUsage],
) derives JsonCodec

/**
 * Per-device weekly screen-time roll-up. Mirrors [[ProfileTimeStatusWeek]] but scoped to a single
 * MAC across the `from`..`to` range with the same bucket-dedup semantics.
 */
case class DeviceTimeStatusWeek(
    deviceMac: MacAddress,
    deviceName: String,
    from: String,
    to: String,
    profileName: String,
    profileId: Option[ProfileId],
    dailyLimitMins: Option[Int],
    totalMins: Int,
    perDay: List[ProfileTimeDayTotal],
    hostUsage: List[HostUsage],
) derives JsonCodec

case class ProfileDetail(
    profile: Profile,
    schedules: List[Schedule],
    timeLimit: Option[TimeLimit],
    siteTimeLimits: List[SiteTimeLimit],
) derives JsonCodec

// #716 / #721 — per-device hourly usage timeline. The endpoint returns 24
// buckets for the requested local date (in the requested `tz`, UTC by
// default). Each bucket's `totalMins` is the device's bucket-deduplicated
// wall-clock minutes — the same number the daily cap sees. Per-host minutes
// are proportionally allocated within each 5-min sub-bucket so the stack of
// `perHost.mins + otherMins` sums to `totalMins` — a sketch of #715
// proposal 2 (bytes-weighted is a follow-up). Hosts beyond `topN` are
// collapsed into `otherMins`.
case class UsageHostTotal(host: HostId, dayMins: Int) derives JsonCodec
case class UsageBucketHost(host: HostId, mins: Int) derives JsonCodec
case class UsageBucket(
    hour: Int,
    totalMins: Int,
    perHost: List[UsageBucketHost],
    otherMins: Int,
) derives JsonCodec

// #722 — profile-mode adds parallel per-device aggregates so the SPA can
// toggle stack-by-device vs stack-by-host without a second round-trip.
case class UsageDeviceTotal(deviceMac: MacAddress, deviceName: String, dayMins: Int)
    derives JsonCodec
case class UsageBucketDevice(deviceMac: MacAddress, deviceName: String, mins: Int) derives JsonCodec
case class UsageDeviceBucket(
    hour: Int,
    totalMins: Int,
    perDevice: List[UsageBucketDevice],
    otherMins: Int,
) derives JsonCodec

case class UsageSeriesResponse(
    deviceMac: Option[MacAddress] = None,
    deviceName: Option[String] = None,
    profileId: Option[ProfileId] = None,
    profileName: Option[String] = None,
    date: String,
    tz: String,
    topHosts: List[UsageHostTotal],
    buckets: List[UsageBucket],
    topDevices: List[UsageDeviceTotal] = Nil,
    bucketsByDevice: List[UsageDeviceBucket] = Nil,
) derives JsonCodec

// ── Dashboard "Now" ────────────────────────────────────────────────────────

case class DashboardNowHost(host: HostId, activeSeconds: Long) derives JsonCodec

case class DashboardNowCurrentSession(
    host: HostId,
    startedAt: String,
    durationSeconds: Long,
) derives JsonCodec

case class DashboardNowDevice(
    id: DeviceId,
    name: String,
    mac: MacAddress,
    lastSeenSeconds: Long,
    topHosts: List[DashboardNowHost],
    currentSession: Option[DashboardNowCurrentSession],
) derives JsonCodec

case class DashboardNowProfile(
    id: ProfileId,
    name: String,
    paused: Boolean,
    activeDevices: List[DashboardNowDevice],
) derives JsonCodec

case class DashboardNow(
    asOf: String,
    profiles: List[DashboardNowProfile],
) derives JsonCodec

case class CachedProfile(
    profile: Profile,
    schedules: List[Schedule],
    timeLimit: Option[Int],
    siteTimeLimits: List[SiteTimeLimit],
)

case class DnsCache(
    deviceProfiles: Map[MacAddress, CachedProfile],
    blocklists: Map[BlocklistId, Set[Hostname]],
    defaultProfile: Option[CachedProfile],
)

object DnsCache {
  val empty: DnsCache = DnsCache(Map.empty, Map.empty, None)
}

case class TimeUsageSnapshot(
    domainUsage: Map[(String, String, String), Int],
    totalUsage: Map[(String, String), Int],
    extensions: Map[(String, String), Int],
)

object TimeUsageSnapshot {
  val empty: TimeUsageSnapshot = TimeUsageSnapshot(Map.empty, Map.empty, Map.empty)
}

case class Router(
    id: RouterId,
    name: String,
    enrollmentTokenHash: Option[Sha256Hex],
    tokenHash: Option[Sha256Hex],
    lastSeenAt: Option[String],
    lastEtag: Option[ETag],
    createdAt: String,
) derives JsonCodec

case class TrafficReport(
    id: TrafficReportId,
    routerId: RouterId,
    mac: MacAddress,
    ip: Option[IpAddress],
    host: HostId,
    date: String,
    periodStart: String,
    periodEnd: String,
    activeSeconds: Int,
    bytesIn: Long,
    bytesOut: Long,
) derives JsonCodec

case class Session(
    mac: MacAddress,
    deviceName: Option[String],
    profileId: Option[ProfileId],
    profileName: Option[String],
    host: HostId,
    routerId: RouterId,
    date: String,
    startedAt: String,
    endedAt: String,
    durationSeconds: Long,
    bytesIn: Long,
    bytesOut: Long,
    periodCount: Int,
) derives JsonCodec

case class SessionPage(
    sessions: List[Session],
    nextCursor: Option[String],
) derives JsonCodec

case class BlockEvent(
    id: BlockEventId,
    mac: Option[MacAddress],
    host: HostId,
    reason: String,
    ts: String,
) derives JsonCodec

case class ConnectionEvent(
    id: ConnectionEventId,
    routerId: RouterId,
    mac: Option[MacAddress],
    host: HostId,
    destIp: Option[IpAddress],
    allowed: Boolean,
    reason: String,
    ts: String,
) derives JsonCodec

case class UsageRecord(
    mac: MacAddress,
    ip: Option[IpAddress],
    host: HostId,
    activeSeconds: Long,
    bytesIn: Long,
    bytesOut: Long,
) derives JsonCodec

case class UsageReport(
    routerId: RouterId,
    periodStart: String,
    periodEnd: String,
    records: List[UsageRecord],
) derives JsonCodec

/**
 * Router event payload. `type` discriminates:
 *   - "connection_attempt": (mac, host, destIp, allowed, reason, ts)
 *   - "dhcp_lease": (mac, ip, hostname, ts) — `hostname` here is the DHCP-advertised name, which by
 *     construction is an FQDN-shaped label (or absent).
 *   - "first_seen_mac": (mac, ip, hostname, ts) — same.
 *
 * The split is deliberate: `host` (the *contacted* identity) can be either an FQDN or an IP literal
 * per §391; `hostname` (the *device's own* DHCP name) is always a label or absent.
 */
case class RouterEvent(
    `type`: String,
    mac: Option[MacAddress] = None,
    ip: Option[IpAddress] = None,
    hostname: Option[Hostname] = None,
    host: Option[HostId] = None,
    destIp: Option[IpAddress] = None,
    allowed: Option[Boolean] = None,
    reason: Option[String] = None,
    ts: String,
    // #338: client-supplied idempotency key for connection_attempt events.
    // Absent on dhcp_lease / first_seen_mac (those drive idempotent device
    // upserts, no per-row dedup needed) and absent from agents predating the
    // change — the API falls back to a server-generated UUID so older agents
    // keep working (capability tag for #376's future registry:
    // "event-idempotency-keys").
    eventId: Option[UUID] = None,
) derives JsonCodec

case class RouterEventsRequest(
    routerId: RouterId,
    events: List[RouterEvent],
) derives JsonCodec

// ── Router enrollment & policy snapshot ───────────────────────────────────

case class CreateRouterRequest(name: String) derives JsonCodec
case class CreateRouterResponse(
    routerId: RouterId,
    name: String,
    enrollmentToken: EnrollmentToken,
) derives JsonCodec

case class RouterSummary(
    id: RouterId,
    name: String,
    enrolled: Boolean,
    lastSeenAt: Option[String],
    lastEtag: Option[ETag],
    createdAt: String,
) derives JsonCodec

case class RegisterRouterRequest(
    enrollmentToken: EnrollmentToken,
    platformVersion: Option[String] = None,
    agentVersion: Option[String] = None,
) derives JsonCodec

case class RegisterRouterResponse(
    routerId: RouterId,
    routerToken: RouterToken,
) derives JsonCodec

case class RouterDecisionRequest(mac: MacAddress, hostname: Hostname) derives JsonCodec
case class RouterDecisionResponse(
    decision: ConnectionDecision,
    reason: String,
    expiresAt: Option[String],
) derives JsonCodec

// ── Policy snapshot (target shape per docs/architecture.md §0.2, #354) ────
//
// Diverges from architecture.md §0.2 in one place: `failureMode` is per-profile
// (carried in ProfilePolicy) rather than top-level. The DB has it as a
// per-profile column and we keep it that way until there's a reason to consolidate.

case class Blocklist(version: BlocklistVersion, url: BlocklistUrl) derives JsonCodec

case class BlockRules(
    blocked: Boolean,
    blockReason: Option[MacBlockReason],
    extraBlocked: List[Hostname],
    extraAllowed: List[Hostname],
    blocklistIds: List[BlocklistId],
    blockIpOnly: Boolean,
) derives JsonCodec

object BlockRules {
  val allowAll: BlockRules = BlockRules(
    blocked = false,
    blockReason = None,
    extraBlocked = Nil,
    extraAllowed = Nil,
    blocklistIds = List.empty[BlocklistId],
    blockIpOnly = false,
  )
}

case class DevicePolicy(
    profileId: Option[ProfileId],
    name: String,
    rules: Option[BlockRules],
) derives JsonCodec

case class ProfilePolicy(
    name: String,
    rules: BlockRules,
    failureMode: FailureMode,
) derives JsonCodec

case class PolicySnapshot(
    etag: ETag,
    generatedAt: String,
    devices: Map[MacAddress, DevicePolicy],
    profiles: Map[ProfileId, ProfilePolicy],
    blocklists: Map[BlocklistId, Blocklist],
) derives JsonCodec

// ── Block reasons (snapshot + router-emitted) ─────────────────────────────
//
// MacBlockReason is the subset that can appear in a snapshot — the API
// pre-evaluates the policy and emits one of these in BlockRules.blockReason.
// The other BlockReason variants are emitted by the router agent at
// packet-drop time and never appear in the snapshot. The split is
// type-enforced: BlockRules.blockReason is typed Option[MacBlockReason], so a
// router-only reason cannot leak into the snapshot field.

sealed trait BlockReason

sealed trait MacBlockReason extends BlockReason
object MacBlockReason {
  case object Paused    extends MacBlockReason
  case object Schedule  extends MacBlockReason
  case object TimeLimit extends MacBlockReason
  case object Manual    extends MacBlockReason

  def asString(r: MacBlockReason): String      = r match {
    case Paused    => "Paused"
    case Schedule  => "Schedule"
    case TimeLimit => "TimeLimit"
    case Manual    => "Manual"
  }
  def parse(s: String): Option[MacBlockReason] = s match {
    case "Paused"    => Some(Paused)
    case "Schedule"  => Some(Schedule)
    case "TimeLimit" => Some(TimeLimit)
    case "Manual"    => Some(Manual)
    case _           => None
  }

  given JsonCodec[MacBlockReason] = JsonCodec[String].transformOrFail(
    s => parse(s).toRight(s"unknown blockReason: $s"),
    asString,
  )
}

object BlockReason {
  case class Host(host: Hostname)                        extends BlockReason
  case class Category(host: Hostname, list: BlocklistId) extends BlockReason
  case class IpOnly(dstIp: IpAddress)                    extends BlockReason
}
