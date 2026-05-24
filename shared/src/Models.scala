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

// #751: per-profile knob controlling how the profile's screen-time total
// handles two devices on the same profile being active in the same 5-min
// bucket.
//   Sum   — current behavior: per-device bucket-deduped minutes are added.
//           Two siblings on the same profile both active for a bucket count
//           as two buckets.
//   Dedup — the per-device active-bucket sets are unioned before counting,
//           so overlap counts once at the profile level. Right for "one
//           profile = one human with multiple devices".
enum CrossDeviceOverlapMode {
  case Sum, Dedup
}

object CrossDeviceOverlapMode {
  def asString(m: CrossDeviceOverlapMode): String      = m match {
    case Sum   => "sum"
    case Dedup => "dedup"
  }
  def parse(s: String): Option[CrossDeviceOverlapMode] = s.toLowerCase match {
    case "sum"   => Some(Sum)
    case "dedup" => Some(Dedup)
    case _       => None
  }
  given JsonCodec[CrossDeviceOverlapMode]              = JsonCodec[String].transformOrFail(
    s => parse(s).toRight(s"unknown crossDeviceOverlapMode: $s"),
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
    crossDeviceOverlapMode: CrossDeviceOverlapMode = CrossDeviceOverlapMode.Sum,
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

// #761: app concept. An App is a household-scoped named bundle of host
// patterns (apex form — subdomain match is inherent to the wire). See #105
// design comment §2. Wire stays unchanged — apps are an API-side bundling
// concept that #763 will expand into the existing per-MAC BlockRules buckets.
enum AppMode {
  case Blocked, Allowed, TimeLimited
}

object AppMode {
  def asString(m: AppMode): String      = m match {
    case Blocked     => "blocked"
    case Allowed     => "allowed"
    case TimeLimited => "time_limited"
  }
  def parse(s: String): Option[AppMode] = s match {
    case "blocked"      => Some(Blocked)
    case "allowed"      => Some(Allowed)
    case "time_limited" => Some(TimeLimited)
    case _              => None
  }
  given JsonCodec[AppMode]              = JsonCodec[String].transformOrFail(
    s => parse(s).toRight(s"unknown app mode: $s"),
    asString,
  )
}

/**
 * How the `icon` string on an [[App]] should be interpreted by the SPA. The DB stores `icon` as
 * free-form TEXT so we can ship emojis today, swap to a URL or inline a base64 PNG tomorrow without
 * a schema change. `icon_type` tells the renderer which it is.
 */
enum IconType {
  case Emoji, Url, PngBase64
}

object IconType {
  def asString(t: IconType): String      = t match {
    case Emoji     => "emoji"
    case Url       => "url"
    case PngBase64 => "png_base64"
  }
  def parse(s: String): Option[IconType] = s match {
    case "emoji"      => Some(Emoji)
    case "url"        => Some(Url)
    case "png_base64" => Some(PngBase64)
    case _            => None
  }
  given JsonCodec[IconType]              = JsonCodec[String].transformOrFail(
    s => parse(s).toRight(s"unknown icon type: $s"),
    asString,
  )
}

case class App(
    id: AppId,
    name: String,
    slug: String,
    templateId: Option[AppTemplateId],
    icon: Option[String],
    iconType: IconType,
    createdAt: java.time.Instant,
) derives JsonCodec

case class AppHost(appId: AppId, host: Hostname) derives JsonCodec

case class AppPolicyAssignment(
    id: AppPolicyAssignmentId,
    appId: AppId,
    profileId: ProfileId,
    mode: AppMode,
    dailyMinutes: Option[Int],
    exemptFromDaily: Boolean = true,
) derives JsonCodec

// #762: HTTP request/response shapes for the apps CRUD endpoints. Hosts are
// accepted as strings on input (the server strips a leading `*.` then runs
// Hostname.parse — both `foo.com` and `*.foo.com` canonicalize to apex).
case class CreateAppRequest(
    name: String,
    slug: Option[String] = None,
    icon: Option[String] = None,
    iconType: Option[IconType] = None,
    templateId: Option[AppTemplateId] = None,
    hosts: List[String] = Nil,
) derives JsonCodec

case class UpdateAppRequest(
    name: String,
    icon: Option[String] = None,
    iconType: Option[IconType] = None,
    templateId: Option[AppTemplateId] = None,
) derives JsonCodec

case class SetAppHostsRequest(hosts: List[String]) derives JsonCodec

case class UpsertAppAssignmentRequest(
    mode: AppMode,
    dailyMinutes: Option[Int] = None,
    exemptFromDaily: Option[Boolean] = None,
) derives JsonCodec

case class AppDetail(
    app: App,
    hosts: List[Hostname],
    assignments: List[AppPolicyAssignment],
) derives JsonCodec

// #766: recently-visited-hosts picker for the apps create/edit flow. The
// endpoint returns FQDN traffic for a single device over a windowDays-day
// window, collapsed to the PSL registered domain ("apex"). Bare-IP rows are
// excluded — the picker only surfaces hosts the operator can express as a
// host pattern. `subdomains` is the set of FQDNs observed beneath the apex.
case class RecentApex(
    apex: Hostname,
    bytes: Long,
    hits: Long,
    subdomains: List[Hostname],
) derives JsonCodec

case class RecentApexesResponse(
    deviceMac: MacAddress,
    deviceName: String,
    windowDays: Int,
    items: List[RecentApex],
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

/**
 * #711: a notification raised when the agent ingest path auto-creates a row for a previously-unseen
 * MAC. The admin reviews pending alerts in the SPA and dismisses each one once they have named the
 * device / assigned a profile / decided it isn't worth a name. `dismissedAt` is `None` while
 * pending, `Some(ts)` once cleared.
 */
case class DeviceAlert(
    id: DeviceAlertId,
    mac: MacAddress,
    deviceName: String,
    profileId: Option[ProfileId],
    profileName: Option[String],
    firstSeenAt: String,
    dismissedAt: Option[String],
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
    crossDeviceOverlapMode: Option[CrossDeviceOverlapMode] = None,
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
    profileId: Option[ProfileId],
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

// ── Connection-events aggregation (#847) ───────────────────────────────────
// Bucket widths supported by /api/connection-events/series. "off" = caller
// wants raw rows from /api/logs (the series endpoint rejects it as 400).
enum ConnectionEventBucket(val wire: String, val seconds: Int) {
  case Off extends ConnectionEventBucket("off", 0)
  case M1  extends ConnectionEventBucket("1m", 60)
  case M10 extends ConnectionEventBucket("10m", 600)
  case H1  extends ConnectionEventBucket("1h", 3600)
  case H12 extends ConnectionEventBucket("12h", 43200)
  case D1  extends ConnectionEventBucket("1d", 86400)
  case W1  extends ConnectionEventBucket("1w", 604800)
}

object ConnectionEventBucket {
  def fromWire(s: String): Option[ConnectionEventBucket] =
    ConnectionEventBucket.values.find(_.wire == s)
}

// #846: groupBy is now a comma-separated set. Apex is deferred to #856
// (needs PSL), App to #857 (needs apps track). Device/Profile/Domain are
// composable — e.g. groupBy=device,domain returns one row per
// (window, device, domain).
enum ConnectionEventGroupBy(val wire: String) {
  case Domain  extends ConnectionEventGroupBy("domain")
  case Device  extends ConnectionEventGroupBy("device")
  case Profile extends ConnectionEventGroupBy("profile")
  case App     extends ConnectionEventGroupBy("app")
}

object ConnectionEventGroupBy {
  def fromWire(s: String): Option[ConnectionEventGroupBy] =
    ConnectionEventGroupBy.values.find(_.wire == s)
}

// `groups` maps each column in the request's groupBy set to its value for
// this row — e.g. {"device": "Prima iPad", "domain": "youtube.com"}. For
// columns NOT in the groupBy set, the SPA shows the distinct-count from
// the matching `distinct*` field (per #846 audit decision: just show the
// number until drill-down lands in #859/#860).
case class ConnectionEventAggRow(
    groups: Map[String, String],
    windowStart: String,
    countSucceeded: Int,
    countBlocked: Int,
    lastSeen: String,
    topDevice: Option[String],
    distinctDevices: Int = 0,
    distinctProfiles: Int = 0,
    distinctDomains: Int = 0,
    distinctApps: Int = 0,
    // #846 audit follow-up: see TrafficUsageAggregateRow.
    soleDevice: Option[String] = None,
    soleProfile: Option[String] = None,
    soleDomain: Option[String] = None,
    soleApp: Option[String] = None,
    // #769: populated when groupBy=app so the SPA can render the display
    // name + icon instead of just the slug. `__other__` (hosts not in any
    // app) emits appName="Other", appIcon=None, appId=None.
    appId: Option[AppId] = None,
    appName: Option[String] = None,
    appIcon: Option[String] = None,
) derives JsonCodec

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

/**
 * #715: per-host time-on-site has two parallel numbers.
 *   - `usedMins` is bucket-presence: every host the device touched in a 5-min bucket is credited
 *     with that bucket's full duration. Sums across hosts can wildly exceed wall-clock time when a
 *     device polls many endpoints; useful only for "did this host show up at all today".
 *   - `proportionalMins` is the same bucket duration weighted by this host's byte share of the
 *     bucket (bytes_in + bytes_out). Summing across hosts within a mac ≈ the device's wall-clock
 *     minutes, so this is the right number to drive per-app screen-time UI.
 *
 * The daily-cap math (which already collapses each bucket once per device) reads neither field —
 * adding `proportionalMins` is additive and does not affect cap arithmetic.
 */
case class HostUsage(host: HostId, usedMins: Int, proportionalMins: Int) derives JsonCodec

/**
 * #777 lightweight per-profile rollup for the collapsed accordion on the screen-time page. Just the
 * headline numbers; no per-host / per-device / per-bucket arrays. The endpoint computes the whole
 * list in a single batched presence query instead of fanning out per-profile, so the page load is
 * `1 summary + N on-demand` rather than `N rollups`.
 */
case class ProfileTimeSummary(
    profileId: ProfileId,
    profileName: String,
    date: String,
    dailyLimitMins: Option[Int],
    usedMins: Int,
    extensionMins: Int,
    remainingMins: Option[Int],
) derives JsonCodec

/**
 * #777 weekly sibling of [[ProfileTimeSummary]]. Just `totalMins` over the trailing 7 days; the
 * per-bucket chart and per-host breakdown still come from the heavyweight endpoint on expand.
 */
case class ProfileTimeSummaryWeek(
    profileId: ProfileId,
    profileName: String,
    from: String,
    to: String,
    dailyLimitMins: Option[Int],
    totalMins: Int,
) derives JsonCodec

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

/**
 * One UTC hourly bucket of presence minutes (#794). `bucketStart` is an ISO-8601 instant; buckets
 * are exactly 1 hour wide. The grid alignment is set by the caller via the `bucketOffsetMin` query
 * param (one of 0/15/30/45 — the minute past the UTC hour where the grid starts), so the SPA can
 * shift the grid so each bucket falls fully within one local-tz day:
 *
 *   - whole-hour-offset zones (UTC, US, EU): `bucketOffsetMin=0` → buckets at 00:00Z, 01:00Z, …
 *   - half-hour-offset zones (India +5:30, Newfoundland -3:30): `bucketOffsetMin=30` → 00:30Z,
 *     01:30Z, …
 *   - quarter-hour-offset zones (Nepal +5:45, Chatham +12:45): `bucketOffsetMin=15` or `45`.
 *
 * The server stays tz-agnostic — it doesn't know the household's tz, just emits the grid the caller
 * asked for.
 */
case class ProfileTimeBucket(bucketStart: java.time.Instant, usedMins: Int) derives JsonCodec

/**
 * Weekly screen-time roll-up (#723) — sibling shape to [[ProfileTimeStatus]]. `totalMins`,
 * `devices` and `hostUsage` are bucket-deduped across the full `from`..`to` range, so totals can be
 * lower than naively summing `perBucket.usedMins` (a device touching the same 5-min bucket on two
 * hosts still only counts once for the range). `dailyLimitMins` is informational — the daily cap
 * does not weekly-aggregate. `perBucket` is hourly UTC buckets aligned to `bucketOffsetMin` (#794);
 * the SPA groups by local day.
 */
case class ProfileTimeStatusWeek(
    profileId: ProfileId,
    profileName: String,
    from: String,
    to: String,
    dailyLimitMins: Option[Int],
    totalMins: Int,
    perBucket: List[ProfileTimeBucket],
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
    perBucket: List[ProfileTimeBucket],
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

// ── Traffic Usage page (#846) ─────────────────────────────────────────────
//
// New page-backing endpoint for raw-row inspection and group-by-domain
// aggregation. Wire-distinct from UsageSeriesResponse (which powers the
// screen-time minutes chart and is shape-locked to per-hour minute buckets);
// see PR for #846 for why this is a sibling rather than an extension.

case class TrafficUsageRawRow(
    mac: MacAddress,
    deviceName: Option[String],
    profileId: Option[ProfileId],
    profileName: Option[String],
    host: HostId,
    bytesIn: Long,
    bytesOut: Long,
    activeSeconds: Int,
    periodStart: String,
    periodEnd: String,
) derives JsonCodec

// #846: multi-column aggregation. `groups` is the per-row mapping for
// columns in the request's groupBy set (e.g. {"device": "Prima iPad"}).
// For columns NOT in the set, the corresponding `distinct*` field carries
// the count of distinct values that contributed to this row — the SPA
// renders the count in that column header until drill-down lands (#859).
case class TrafficUsageAggregateRow(
    groups: Map[String, String],
    windowStart: String,
    windowEnd: String,
    totalBytesIn: Long,
    totalBytesOut: Long,
    totalSeconds: Long,
    distinctDevices: Int = 0,
    distinctProfiles: Int = 0,
    distinctDomains: Int = 0,
    distinctApps: Int = 0,
    // #846 audit follow-up: when a non-grouped column has only one distinct
    // value contributing to the row, surface it so the SPA can render the
    // value instead of just "1". `None` when the column is in `groups`
    // (already covered) OR when distinct > 1.
    soleDevice: Option[String] = None,
    soleProfile: Option[String] = None,
    soleDomain: Option[String] = None,
    soleApp: Option[String] = None,
    // #769: populated when groupBy=app so SPA can render display name + icon.
    // `__other__` (hosts not in any app) emits appName="Other".
    appId: Option[AppId] = None,
    appName: Option[String] = None,
    appIcon: Option[String] = None,
) derives JsonCodec

case class TrafficUsageResponse(
    bucket: String,
    groupBy: List[String] = Nil,
    from: String,
    to: String,
    tz: String,
    rawRows: List[TrafficUsageRawRow] = Nil,
    aggregateRows: List[TrafficUsageAggregateRow] = Nil,
    rawRowLimit: Option[Int] = None,
    rawRowsTruncated: Boolean = false,
    // #862: opaque cursor for the next (older) page. None = end of stream.
    // Wire-distinct from rawRowsTruncated which signals "this single response
    // hit the row cap" — nextCursor signals "more rows exist beyond this".
    nextCursor: Option[String] = None,
) derives JsonCodec

// #862: page envelopes for /api/logs and /api/connection-events/series. Wraps
// the per-row payload with a nextCursor field (None = no more older rows).
case class QueryLogPage(
    rows: List[QueryLog],
    nextCursor: Option[String] = None,
) derives JsonCodec

case class ConnectionEventSeriesPage(
    rows: List[ConnectionEventAggRow],
    nextCursor: Option[String] = None,
) derives JsonCodec

// ── Dashboard "Now" ────────────────────────────────────────────────────────

case class DashboardNowHost(host: HostId, activeSeconds: Long) derives JsonCodec

/**
 * "Watching right now" replacement for the removed `currentSession` line. Derived per-request from
 * `traffic_reports` — `topHost` is the host with the most active_seconds in the latest populated
 * 5-min bucket; `minutes` is the run of consecutive earlier buckets in which that same host was
 * also top, capped at 60. None when we can't make a confident call. See #852.
 */
case class DashboardNowActivity(topHost: HostId, minutes: Option[Int]) derives JsonCodec

case class DashboardNowDevice(
    id: DeviceId,
    name: String,
    mac: MacAddress,
    lastSeenSeconds: Long,
    topHosts: List[DashboardNowHost],
    nowActivity: Option[DashboardNowActivity],
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
