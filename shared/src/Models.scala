package familydns.shared

import zio.json.*

import java.util.UUID

given JsonCodec[UUID] =
  JsonCodec[String].transformOrFail(
    s => scala.util.Try(UUID.fromString(s)).toEither.left.map(_.getMessage),
    _.toString,
  )

enum UserRole derives JsonCodec {
  case Admin, Adult, Child
}

// #311: per-profile failover behaviour when the agent loses contact with the
// API for >5 min. Closed = drop all forwarded traffic for the profile's
// devices (fail-safe for kids); Open = keep enforcing the cached snapshot
// exactly as-is (avoids locking adults out during ISP blips).
//
// Wire format is lowercase "open" / "closed" so the lua agent can read the
// JSON field directly. The default in DB and in UpsertProfileRequest is
// Closed — the pessimistic choice. The web UI's "new profile" form likewise
// defaults to Closed; admins can override to Open per profile.
enum FailureMode {
  case Open, Closed
}

object FailureMode {
  def asString(m: FailureMode): String      = m match {
    case Open   => "open"
    case Closed => "closed"
  }
  def parse(s: String): Option[FailureMode] = s.toLowerCase match {
    case "open"   => Some(Open)
    case "closed" => Some(Closed)
    case _        => None
  }
  given JsonCodec[FailureMode]              = JsonCodec[String].transformOrFail(
    s => parse(s).toRight(s"unknown failureMode: $s"),
    asString,
  )
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
}

case class Profile(
    id: Long,
    name: String,
    blockedCategories: List[String],
    extraBlocked: List[String],
    extraAllowed: List[String],
    paused: Boolean,
    failureMode: FailureMode = FailureMode.Closed,
) derives JsonCodec

case class Schedule(
    id: Long,
    profileId: Long,
    name: String,
    days: List[String],
    blockFrom: String,
    blockUntil: String,
) derives JsonCodec

case class TimeLimit(
    id: Long,
    profileId: Long,
    dailyMinutes: Int,
) derives JsonCodec

case class SiteTimeLimit(
    id: Long,
    profileId: Long,
    domainPattern: String,
    dailyMinutes: Int,
    label: String,
    exemptFromDaily: Boolean = true,
) derives JsonCodec

case class TimeUsage(
    id: Long,
    deviceMac: String,
    domain: String,
    date: String,
    minutesUsed: Int,
    lastSeenAt: String,
) derives JsonCodec

case class TimeExtension(
    id: Long,
    profileId: Option[Long],
    deviceMac: Option[String],
    date: String,
    extraMinutes: Int,
    grantedBy: String,
    note: Option[String],
    createdAt: String,
) derives JsonCodec

case class Device(
    id: Long,
    mac: String,
    name: String,
    profileId: Option[Long],
    profileName: Option[String],
    lastSeenIp: Option[String],
    lastSeenAt: Option[String],
) derives JsonCodec

case class QueryLog(
    id: Long,
    mac: Option[String],
    deviceName: Option[String],
    profileId: Option[Long],
    profileName: Option[String],
    domain: String,
    qtype: Int,
    blocked: Boolean,
    reason: String,
    location: Option[String],
    ts: String,
) derives JsonCodec

case class LoginRequest(username: String, password: String) derives JsonCodec
case class LoginResponse(token: String, role: String, username: String) derives JsonCodec
case class ChangePasswordRequest(currentPassword: String, newPassword: String) derives JsonCodec
case class CreateUserRequest(
    username: String,
    password: String,
    role: String,
    profileIds: List[Long] = Nil,
) derives JsonCodec
case class UserSummary(
    id: Long,
    username: String,
    role: String,
    profileIds: List[Long],
) derives JsonCodec
case class MeResponse(
    username: String,
    role: String,
    profileIds: List[Long],
) derives JsonCodec
case class SetUserProfilesRequest(profileIds: List[Long]) derives JsonCodec
case class SetProfileUsersRequest(userIds: List[Long]) derives JsonCodec

case class UpsertProfileRequest(
    name: String,
    blockedCategories: List[String],
    extraBlocked: List[String],
    extraAllowed: List[String],
    paused: Boolean,
    schedules: List[ScheduleRequest],
    timeLimit: Option[Int],
    siteTimeLimits: List[SiteTimeLimitRequest],
    // #311: optional on the wire; server defaults to Closed when omitted.
    failureMode: Option[FailureMode] = None,
) derives JsonCodec

case class ScheduleRequest(
    name: String,
    days: List[String],
    blockFrom: String,
    blockUntil: String,
) derives JsonCodec

case class SiteTimeLimitRequest(
    domainPattern: String,
    dailyMinutes: Int,
    label: String,
    exemptFromDaily: Boolean = true,
) derives JsonCodec

case class UpsertDeviceRequest(
    mac: String,
    name: String,
    profileId: Long,
) derives JsonCodec

case class GrantExtensionRequest(
    profileId: Long,
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

case class DomainCount(domain: String, count: Int) derives JsonCodec
case class DeviceStats(mac: String, deviceName: String, total: Int, blocked: Int) derives JsonCodec

case class SiteUsage(
    label: String,
    domainPattern: String,
    limitMins: Int,
    usedMins: Int,
    remainingMins: Int,
) derives JsonCodec

case class DeviceTimeStatus(
    deviceMac: String,
    deviceName: String,
    date: String,
    profileName: String,
    profileId: Option[Long],
    dailyLimitMins: Option[Int],
    usedMins: Int,
    extensionMins: Int,
    remainingMins: Option[Int],
    siteUsage: List[SiteUsage],
) derives JsonCodec

case class DeviceUsageSummary(
    deviceMac: String,
    deviceName: String,
    usedMins: Int,
) derives JsonCodec

case class ProfileTimeStatus(
    profileId: Long,
    profileName: String,
    date: String,
    dailyLimitMins: Option[Int],
    usedMins: Int,
    extensionMins: Int,
    remainingMins: Option[Int],
    siteUsage: List[SiteUsage],
    devices: List[DeviceUsageSummary],
) derives JsonCodec

case class ProfileDetail(
    profile: Profile,
    schedules: List[Schedule],
    timeLimit: Option[TimeLimit],
    siteTimeLimits: List[SiteTimeLimit],
) derives JsonCodec

// ── Dashboard "Now" ────────────────────────────────────────────────────────

case class DashboardNowHost(hostname: String, activeSeconds: Long) derives JsonCodec

case class DashboardNowCurrentSession(
    hostname: String,
    startedAt: String,
    durationSeconds: Long,
) derives JsonCodec

case class DashboardNowDevice(
    id: Long,
    name: String,
    mac: String,
    lastSeenSeconds: Long,
    topHosts: List[DashboardNowHost],
    currentSession: Option[DashboardNowCurrentSession],
) derives JsonCodec

case class DashboardNowProfile(
    id: Long,
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
    deviceProfiles: Map[String, CachedProfile],
    blocklists: Map[String, Set[String]],
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
    id: UUID,
    name: String,
    enrollmentTokenHash: Option[String],
    tokenHash: Option[String],
    lastSeenAt: Option[String],
    lastEtag: Option[String],
    createdAt: String,
    lastClockSkewSeconds: Option[Long] = None,
) derives JsonCodec

case class TrafficReport(
    id: Long,
    routerId: UUID,
    mac: String,
    ip: Option[String],
    hostname: String,
    date: String,
    periodStart: String,
    periodEnd: String,
    activeSeconds: Int,
    bytesIn: Long,
    bytesOut: Long,
) derives JsonCodec

case class Session(
    mac: String,
    deviceName: Option[String],
    profileId: Option[Long],
    profileName: Option[String],
    hostname: String,
    routerId: UUID,
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
    id: Long,
    mac: Option[String],
    hostname: String,
    reason: String,
    ts: String,
) derives JsonCodec

case class ConnectionEvent(
    id: Long,
    routerId: UUID,
    mac: Option[String],
    hostname: String,
    destIp: Option[String],
    allowed: Boolean,
    reason: String,
    ts: String,
) derives JsonCodec

case class UsageRecord(
    mac: String,
    ip: Option[String],
    hostname: String,
    activeSeconds: Long,
    bytesIn: Long,
    bytesOut: Long,
) derives JsonCodec

case class UsageReport(
    routerId: UUID,
    periodStart: String,
    periodEnd: String,
    records: List[UsageRecord],
    clockSkewSeconds: Option[Long] = None,
) derives JsonCodec

/**
 * Router event payload. `type` discriminates:
 *   - "connection_attempt": (mac, hostname, destIp, allowed, reason, ts)
 *   - "dhcp_lease": (mac, ip, hostname, ts)
 *   - "first_seen_mac": (mac, ip, hostname, ts)
 */
case class RouterEvent(
    `type`: String,
    mac: Option[String] = None,
    ip: Option[String] = None,
    hostname: Option[String] = None,
    destIp: Option[String] = None,
    allowed: Option[Boolean] = None,
    reason: Option[String] = None,
    ts: String,
) derives JsonCodec

case class RouterEventsRequest(
    routerId: UUID,
    events: List[RouterEvent],
) derives JsonCodec

// ── Router enrollment & policy snapshot ───────────────────────────────────

case class CreateRouterRequest(name: String) derives JsonCodec
case class CreateRouterResponse(
    routerId: UUID,
    name: String,
    enrollmentToken: String,
) derives JsonCodec

case class RouterSummary(
    id: UUID,
    name: String,
    enrolled: Boolean,
    lastSeenAt: Option[String],
    lastEtag: Option[String],
    createdAt: String,
    lastClockSkewSeconds: Option[Long] = None,
) derives JsonCodec

case class RegisterRouterRequest(
    enrollmentToken: String,
    platformVersion: Option[String] = None,
    agentVersion: Option[String] = None,
) derives JsonCodec

case class RegisterRouterResponse(
    routerId: UUID,
    routerToken: String,
) derives JsonCodec

case class RouterDecisionRequest(mac: String, hostname: String) derives JsonCodec
case class RouterDecisionResponse(
    decision: String,
    reason: String,
    expiresAt: Option[String],
) derives JsonCodec

// ── Policy snapshot (target shape per docs/architecture.md §0.2, #354) ────
//
// The snapshot is what the router agent consumes. It carries collapsed
// per-profile/per-device enforcement state — schedules, time limits, pause,
// site limits, and category membership have all been evaluated server-side
// in PolicyService and reflected here as `BlockRules`. The agent never
// re-evaluates them.
//
// Diverges from architecture.md §0.2 in one place: `failureMode` is still
// per-profile (carried in ProfilePolicy) rather than top-level. The DB
// has it as a per-profile column and we want to keep it that way until
// there's a reason to consolidate.
//
// `DevicePolicy.rules` exists for future per-device overrides; PolicyService
// currently always emits `None` there (no UI to set it).

opaque type BlocklistId = String
object BlocklistId {
  def apply(s: String): BlocklistId             = s
  extension (id: BlocklistId) def value: String = id
  given JsonEncoder[BlocklistId]                = JsonEncoder.string.contramap(_.value)
  given JsonDecoder[BlocklistId]                = JsonDecoder.string.map(apply)
  given JsonCodec[BlocklistId]                  =
    JsonCodec(summon[JsonEncoder[BlocklistId]], summon[JsonDecoder[BlocklistId]])
  given Ordering[BlocklistId] = Ordering.by[BlocklistId, String](_.value)(using Ordering.String)
  given JsonFieldEncoder[BlocklistId] = JsonFieldEncoder.string.contramap(_.value)
  given JsonFieldDecoder[BlocklistId] = JsonFieldDecoder.string.map(apply)
}

case class Blocklist(version: String, url: String) derives JsonCodec

case class BlockRules(
    blocked: Boolean,
    blockReason: Option[MacBlockReason],
    extraBlocked: List[String],
    extraAllowed: List[String],
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
    profileId: Option[Long],
    name: String,
    rules: Option[BlockRules],
) derives JsonCodec

case class ProfilePolicy(
    name: String,
    rules: BlockRules,
    failureMode: FailureMode,
) derives JsonCodec

case class PolicySnapshot(
    etag: String,
    generatedAt: String,
    devices: Map[String, DevicePolicy],
    profiles: Map[Long, ProfilePolicy],
    blocklists: Map[BlocklistId, Blocklist],
) derives JsonCodec

// ── Block reasons (snapshot + router-emitted) ─────────────────────────────
//
// MacBlockReason is the subset that can appear in a snapshot — the API
// pre-evaluates the policy and emits one of these in BlockRules.blockReason.
// The other BlockReason variants are emitted by the router agent at
// packet-drop time (for connection_events / the /blocked page) and never
// appear in the snapshot. The split is type-enforced: BlockRules.blockReason
// is typed Option[MacBlockReason], so a router-only reason cannot leak into
// the snapshot field.

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
  case class Host(host: String)                   extends BlockReason
  case class Category(host: String, list: String) extends BlockReason
  case class IpOnly(dstIp: String)                extends BlockReason
}
