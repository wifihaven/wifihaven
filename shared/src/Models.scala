package familydns.shared

import zio.json.*

import java.util.UUID

given JsonCodec[UUID] =
  JsonCodec[String].transformOrFail(
    s => scala.util.Try(UUID.fromString(s)).toEither.left.map(_.getMessage),
    _.toString,
  )

enum UserRole derives JsonCodec:
  case Admin, Adult, Child

object UserRole:
  def parse(s: String): Option[UserRole] = s.toLowerCase match
    case "admin" => Some(Admin)
    case "adult" => Some(Adult)
    case "child" => Some(Child)
    case _       => None
  def asString(r: UserRole): String      = r match
    case Admin => "admin"
    case Adult => "adult"
    case Child => "child"

case class Profile(
    id: Long,
    name: String,
    blockedCategories: List[String],
    extraBlocked: List[String],
    extraAllowed: List[String],
    paused: Boolean,
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
    deviceMac: String,
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
    profileId: Long,
    profileName: Option[String],
    lastSeenIp: Option[String],
    lastSeenAt: Option[String],
    location: Option[String],
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

case class UpsertProfileRequest(
    name: String,
    blockedCategories: List[String],
    extraBlocked: List[String],
    extraAllowed: List[String],
    paused: Boolean,
    schedules: List[ScheduleRequest],
    timeLimit: Option[Int],
    siteTimeLimits: List[SiteTimeLimitRequest],
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
) derives JsonCodec

case class UpsertDeviceRequest(
    mac: String,
    name: String,
    profileId: Long,
    location: Option[String],
) derives JsonCodec

case class GrantExtensionRequest(
    deviceMac: String,
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
    dailyLimitMins: Option[Int],
    usedMins: Int,
    extensionMins: Int,
    remainingMins: Option[Int],
    siteUsage: List[SiteUsage],
) derives JsonCodec

case class ProfileDetail(
    profile: Profile,
    schedules: List[Schedule],
    timeLimit: Option[TimeLimit],
    siteTimeLimits: List[SiteTimeLimit],
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

object DnsCache:
  val empty: DnsCache = DnsCache(Map.empty, Map.empty, None)

case class TimeUsageSnapshot(
    domainUsage: Map[(String, String, String), Int],
    totalUsage: Map[(String, String), Int],
    extensions: Map[(String, String), Int],
)

object TimeUsageSnapshot:
  val empty: TimeUsageSnapshot = TimeUsageSnapshot(Map.empty, Map.empty, Map.empty)

case class Router(
    id: UUID,
    name: String,
    enrollmentTokenHash: Option[String],
    tokenHash: Option[String],
    lastSeenAt: Option[String],
    lastEtag: Option[String],
    createdAt: String,
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

case class BlockEvent(
    id: Long,
    mac: Option[String],
    hostname: String,
    reason: String,
    ts: String,
) derives JsonCodec
