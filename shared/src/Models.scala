package wifihaven.shared

import wifihaven.shared.types.*
import zio.json.*
import zio.json.ast.Json

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

// #1418: pause has two modes. `Soft` is today's behavior — a paused profile
// drops all forwarded traffic except its `extraAllowed` hosts (an allowed app +
// the #1307 infra allowlist survive, per #421/#1413). `Hard` is a true
// off-switch: when paused, even those allowlisted/global hosts go dark. Carried
// per-profile; collapsed server-side into the functional snapshot (empty
// `extraAllowed`) — never a wire field of its own.
enum PauseMode {
  case Soft, Hard
}

object PauseMode {
  def asString(m: PauseMode): String      = m match {
    case Soft => "soft"
    case Hard => "hard"
  }
  def parse(s: String): Option[PauseMode] = s.toLowerCase match {
    case "soft" => Some(Soft)
    case "hard" => Some(Hard)
    case _      => None
  }
  given JsonCodec[PauseMode]              = JsonCodec[String].transformOrFail(
    s => parse(s).toRight(s"unknown pauseMode: $s"),
    asString,
  )
}

case class Profile(
    id: ProfileId,
    name: String,
    blockedCategories: List[BlocklistId],
    paused: Boolean,
    failureMode: FailureMode = FailureMode.LastKnownGood,
    blockIpOnly: Boolean = false,
    crossDeviceOverlapMode: CrossDeviceOverlapMode = CrossDeviceOverlapMode.Sum,
    // #1418: soft (default) honors extraAllowed through a pause; hard cuts even
    // allowlisted/global hosts. Only consulted when the profile is paused.
    pauseMode: PauseMode = PauseMode.Soft,
    // #1318 / #1308: default-deny baseline. When true the profile's effective
    // BlockRules collapse to block-all (`blocked = true` + `DefaultDeny` reason)
    // with only `extraAllowed` (plus the fleet-wide `global.extraAllowed`)
    // reachable — the inverse of the allow-by-default + blocklists model.
    // Resolved entirely server-side in PolicyService; the router never sees the
    // flag, only the collapsed `blocked = true`. Default false. Additive field
    // with a default, so an older client that omits it decodes unchanged.
    defaultDeny: Boolean = false,
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

// #1069: household-scoped reusable named schedule. A `NamedSchedule` owns one
// or more `ScheduleWindow`s; anything time-bound (profiles today, per-app rules
// #1378 and schedule-driven blocklists #1067 next) references it by id. A
// window is the exact typed shape of the legacy per-profile `Schedule` (days /
// startLocal / endLocal / tz) so the existing DST-correct
// `PolicyService.scheduleActiveAt` evaluates it unchanged. This is an
// API-internal model — it never reaches the router wire (`PolicySnapshot`):
// PolicyService folds the active windows into the existing per-MAC BlockRules.
case class ScheduleWindow(
    days: List[String],
    startLocal: LocalTime,
    endLocal: LocalTime,
    tz: ZoneId,
) derives JsonCodec

case class NamedSchedule(
    id: NamedScheduleId,
    name: String,
    description: Option[String],
    windows: List[ScheduleWindow],
) derives JsonCodec

// Create/replace bodies for the /api/schedules CRUD. `windows` is the full
// desired set (replace semantics, mirroring how AppRepo.setHosts replaces).
case class CreateNamedScheduleRequest(
    name: String,
    description: Option[String] = None,
    windows: List[ScheduleWindow] = Nil,
) derives JsonCodec

case class UpdateNamedScheduleRequest(
    name: String,
    description: Option[String] = None,
    windows: List[ScheduleWindow] = Nil,
) derives JsonCodec

// #1069: the (named schedule, mode) attachments on a profile. `mode` decides
// what an active window does. Profiles are block-only for now (allow-mode is
// deferred — see the #1069 thread), so the API below sets block schedules; the
// mode lives here so the model already matches the per-app schedule shape when
// allow lands.
enum ScheduleMode { case BlockedDuring, AllowedDuring }

object ScheduleMode {
  def asString(m: ScheduleMode): String      = m match {
    case BlockedDuring => "blocked_during"
    case AllowedDuring => "allowed_during"
  }
  def parse(s: String): Option[ScheduleMode] = s match {
    case "blocked_during" => Some(BlockedDuring)
    case "allowed_during" => Some(AllowedDuring)
    case _                => None
  }
  given JsonCodec[ScheduleMode]              = JsonCodec[String].transformOrFail(
    s => parse(s).toRight(s"unknown schedule mode: $s"),
    asString,
  )
}

// Body for PUT /api/profiles/{id}/schedules — replace the profile's block
// schedules with this set (a profile can reference many). Kept off
// UpsertProfileRequest so an ordinary profile save can't clobber it. Allow-mode
// profile schedules are deferred, so this carries ids only (block implied).
case class SetProfileSchedulesRequest(
    scheduleIds: List[NamedScheduleId] = Nil,
) derives JsonCodec

case class TimeLimit(
    id: TimeLimitId,
    profileId: ProfileId,
    dailyMinutes: Int,
) derives JsonCodec

case class AppTimeLimit(
    id: AppTimeLimitId,
    profileId: ProfileId,
    domainPattern: String,
    dailyMinutes: Int,
    label: String,
    exemptFromDaily: Boolean = true,
    // #1564: typed FK to apps(id), carried straight from the listForProfile join. This is the
    // canonical app identity the cap/rollup surface keys on; `label` ("app:<slug>") stays as
    // SPA-facing display text only. Defaults to AppId(0L) so seed/test constructions that don't
    // care about the FK still compile during the rollout.
    appId: AppId = AppId(0L),
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

// #1379: per-app schedule rules. Each rule attaches a #1069 named schedule
// (NamedScheduleId, V50 `named_schedules`) to an app's (app, profile) assignment
// with a mode:
//   - AllowedDuring — while any window of the schedule is active, the app's hosts
//     are carved into `extraAllowed`, beating the profile's whole-MAC block (#421)
//     — the headline "reachable during bedtime" case.
//   - BlockedDuring — while active, the app's hosts go to `extraBlocked`, even when
//     the profile is otherwise unrestricted.
// API-internal only: this is NEVER a `PolicySnapshot` field. PolicyService folds
// the active windows into the existing per-MAC `extraAllowed` / `extraBlocked`
// (docs/design/per-app-schedules.md §2, §4) — no wire/router change.
enum AppScheduleMode { case AllowedDuring, BlockedDuring }

object AppScheduleMode {
  def asString(m: AppScheduleMode): String      = m match {
    case AllowedDuring => "allowed_during"
    case BlockedDuring => "blocked_during"
  }
  def parse(s: String): Option[AppScheduleMode] = s match {
    case "allowed_during" => Some(AllowedDuring)
    case "blocked_during" => Some(BlockedDuring)
    case _                => None
  }
  given JsonCodec[AppScheduleMode]              = JsonCodec[String].transformOrFail(
    s => parse(s).toRight(s"unknown app schedule mode: $s"),
    asString,
  )
}

// A single (assignment, named-schedule, mode) rule row (`app_policy_schedule_rules`,
// V51). `id` / `assignmentId` are server-assigned and default to placeholders so the
// request shape below only needs `{scheduleId, mode}` on input.
case class AppScheduleRule(
    scheduleId: NamedScheduleId,
    mode: AppScheduleMode,
    id: AppScheduleRuleId = AppScheduleRuleId(0L),
    assignmentId: AppPolicyAssignmentId = AppPolicyAssignmentId(0L),
) derives JsonCodec

case class UpsertAppAssignmentRequest(
    mode: AppMode,
    dailyMinutes: Option[Int] = None,
    exemptFromDaily: Option[Boolean] = None,
    // #1379: additive — the full desired set of per-app schedule rules (replace
    // semantics, like SetAppHostsRequest.hosts). Existing clients omit it (`Nil`).
    scheduleRules: List[AppScheduleRule] = Nil,
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

// Generic admin-action feed, formerly DeviceAlert (#711, V29). The schema
// (V33) supports a second `access_request` kind, but #960 is the writer for
// that path; this PR only exercises new_device.
enum AlertKind   {
  case NewDevice, AccessRequest
}
object AlertKind {
  def asString(k: AlertKind): String      = k match {
    case NewDevice     => "new_device"
    case AccessRequest => "access_request"
  }
  def parse(s: String): Option[AlertKind] = s match {
    case "new_device"     => Some(NewDevice)
    case "access_request" => Some(AccessRequest)
    case _                => None
  }
  given JsonCodec[AlertKind]              = JsonCodec[String].transformOrFail(
    s => parse(s).toRight(s"unknown alert kind: $s"),
    asString,
  )
}

enum AlertStatus   {
  case Pending, Approved, Denied
}
object AlertStatus {
  def asString(s: AlertStatus): String      = s match {
    case Pending  => "pending"
    case Approved => "approved"
    case Denied   => "denied"
  }
  def parse(s: String): Option[AlertStatus] = s match {
    case "pending"  => Some(Pending)
    case "approved" => Some(Approved)
    case "denied"   => Some(Denied)
    case _          => None
  }
  given JsonCodec[AlertStatus]              = JsonCodec[String].transformOrFail(
    s => parse(s).toRight(s"unknown alert status: $s"),
    asString,
  )
}

// The three kinds of access-request alerts the schema supports. Lives here
// (rather than in #960's code) because the `request_kind` column is in V33
// — the type just doesn't have any writer until #960 lands.
enum AccessRequestKind   {
  case Extension, Exemption, Unpause
}
object AccessRequestKind {
  def asString(k: AccessRequestKind): String      = k match {
    case Extension => "extension"
    case Exemption => "exemption"
    case Unpause   => "unpause"
  }
  def parse(s: String): Option[AccessRequestKind] = s match {
    case "extension" => Some(Extension)
    case "exemption" => Some(Exemption)
    case "unpause"   => Some(Unpause)
    case _           => None
  }
  given JsonCodec[AccessRequestKind]              = JsonCodec[String].transformOrFail(
    s => parse(s).toRight(s"unknown access-request kind: $s"),
    asString,
  )
}

/**
 * Admin read shape — JOINed with device + profile so the banner / review UI doesn't have to chase
 * names per row. `host` / `requestKind` / `note` / `grantedMinutes` are populated only for
 * `kind=AccessRequest` rows (none of which exist until #960 ships).
 */
case class Alert(
    id: AlertId,
    kind: AlertKind,
    status: AlertStatus,
    mac: MacAddress,
    deviceName: Option[String],
    profileId: Option[ProfileId],
    profileName: Option[String],
    host: Option[Hostname],
    requestKind: Option[AccessRequestKind],
    note: Option[String],
    grantedMinutes: Option[Int],
    createdAt: String,
    decidedAt: Option[String],
    decidedBy: Option[String],
) derives JsonCodec

/**
 * Public POST shape — no auth, posted from the block page CTA. Creates an
 * `Alert(kind=AccessRequest, …)` row server-side. The (mac, host) pair is all we need to identify
 * the kid; the block page already has them on the URL.
 */
case class CreateAccessRequest(
    mac: MacAddress,
    host: Hostname,
    kind: AccessRequestKind,
    note: Option[String] = None,
) derives JsonCodec

/**
 * Admin POST body for /api/alerts/{id}/approve. `minutes` is read by extension grants; the field is
 * ignored for other kinds (a new-device approval has no side-effect to parameterise).
 */
case class ApproveAlertRequest(
    minutes: Option[Int] = None,
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
    reason: BlockReason,
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
    paused: Boolean,
    // #1494: schedules are NO LONGER carried here. Enforcement reads from
    // named_schedules / profile_schedule_rules (#1482/#1490); a profile's block
    // schedules are attached via PUT /api/profiles/{id}/schedules
    // (SetProfileSchedulesRequest). The legacy inline array wrote the dead V1
    // `schedules` table, so editing it was a silent no-op — the field is gone.
    // (An older client that still sends `schedules` is tolerated: ZIO JSON
    // ignores the unknown field, and nothing acts on it.)
    timeLimit: Option[Int],
    failureMode: Option[FailureMode] = None,
    blockIpOnly: Option[Boolean] = None,
    crossDeviceOverlapMode: Option[CrossDeviceOverlapMode] = None,
    // #1418: omitted → preserve existing value (default 'soft' on create).
    pauseMode: Option[PauseMode] = None,
    // #1320 / #1308: per-profile default-deny baseline (block-all; only
    // extraAllowed + global.extraAllowed reachable). Omitted → preserve
    // existing value (default false on create). Additive optional field, so an
    // older client that never sends it leaves the profile's default-deny
    // setting untouched.
    defaultDeny: Option[Boolean] = None,
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
    unmanagedMacPolicy: UnmanagedMacPolicy = UnmanagedMacPolicy.Default,
    // #1464: idle gap `N` (seconds) for the presence session-stitch model. Per
    // (device, app), activity merges into one session as long as the wall-clock
    // gap to the next activity is ≤ N; a larger gap ends the session and its
    // presence is the [first, last]-activity span. Default 120 (migration V52);
    // the rollup raises it to the 2×R collapse guard at compute time.
    presenceContinuationSeconds: Int = HouseholdSettings.DefaultPresenceContinuationSeconds,
) derives JsonCodec

object HouseholdSettings {
  val DefaultPresenceContinuationSeconds: Int = 120
}

case class UpdateHouseholdSettingsRequest(
    dailyResetTime: LocalTime,
    dailyResetTz: ZoneId,
    heartbeatFilter: HeartbeatFilter,
    unmanagedMacPolicy: UnmanagedMacPolicy = UnmanagedMacPolicy.Default,
    presenceContinuationSeconds: Int = HouseholdSettings.DefaultPresenceContinuationSeconds,
) derives JsonCodec

/**
 * #961: how the household treats MACs that have appeared on the network but are not yet enrolled
 * into any profile.
 *
 *   - policy = "block": deny egress; HTTP/80 DNATs to the block page when `blockPage` is true
 *     (router-side enforcement deferred to follow-up blocked-on-#654).
 *   - policy = "allow": unmanaged MACs flow freely; admin still gets a #711 alert.
 */
case class UnmanagedMacPolicy(
    policy: String,
    blockPage: Boolean,
) derives JsonCodec

object UnmanagedMacPolicy {
  val Default: UnmanagedMacPolicy = UnmanagedMacPolicy(policy = "allow", blockPage = true)
  val ValidPolicies: Set[String]  = Set("block", "allow")
}

/**
 * #714: knobs for the server-side heartbeat filter applied at the Presence aggregation stage. The
 * filter drops a `traffic_reports` row from per-device/per-profile screen-time totals (NOT from
 * `time_usage`-derived per-site totals) when total bytes fall below `bytesThreshold`.
 */
case class HeartbeatFilter(
    enabled: Boolean,
    bytesThreshold: Int,
    // DEPRECATED (#1525): no longer read for enforcement. Host-identity suppression now lives in the
    // canonical `shared.types.InfraHosts` code constant (allow+suppress) plus its suppress-only
    // tier, so this hand-curated per-install list is vestigial — it caused the #1499 drift it was
    // meant to prevent. Retained on the wire and in `household_settings` for back-compat (older
    // peers still send it; we accept and ignore). Slated for removal + column drop in a later,
    // migration-isolated PR once the fleet has rolled forward. Do NOT add new readers.
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

case class AppUsage(
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
    appUsage: List[AppUsage],
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
    appUsage: List[AppUsage],
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
    timeLimit: Option[TimeLimit],
    // #1069: ids of the named schedules attached to this profile as block
    // schedules (downtime while active). Empty until the operator attaches one.
    scheduleIds: List[NamedScheduleId] = Nil,
) derives JsonCodec

/**
 * #1061 — per-app time-used breakdown for one profile over a date window.
 *
 * `proportionalSeconds` is the wall-clock-attention number (#715): each 5-min bucket's duration is
 * split across hosts by byte share, then summed across the hosts that belong to this app. Summing
 * across apps within a profile ≈ the profile's wall-clock seconds — the right number to drive a
 * per-app screen-time UI.
 *
 * `presenceSeconds` is bucket-dedupes at the app level: a bucket where any host belongs to this app
 * contributes its full duration once. Surfaces "did the profile interact with this app at all"
 * volume; not additive across apps.
 *
 * `appId = None` is the synthetic "Other" bucket: rows whose host isn't in any `app_hosts`
 * membership.
 *
 * The drill-down `hosts` list reuses [[HostUsage]] (#262) so the SPA can render the per-host rows
 * with the same Attention/Seen formatter as the existing per-profile breakdown.
 */
case class ProfileAppUsage(
    appId: Option[AppId],
    appName: String,
    appIcon: Option[String],
    appIconType: Option[IconType],
    proportionalSeconds: Long,
    presenceSeconds: Long,
    hosts: List[HostUsage],
) derives JsonCodec

/**
 * #1519 — a non-app host: a host that doesn't belong to any configured app's host-set. Per the
 * App-Centric Model (CLAUDE.md "App"), such a host **is its own single-host app** — there is no
 * semantic "Other" app. "Other" only ever appears as a display rollup (top-N + "+N more sites") at
 * the SPA layer, never as a wire entity.
 *
 * Units match the parallel app row ([[ProfileAppUsage]]) so the SPA can render orphans and apps
 * side-by-side with one formatter.
 */
case class OrphanHostUsage(
    host: HostId,
    proportionalSeconds: Long,
    presenceSeconds: Long,
) derives JsonCodec

/**
 * #1089 — per-app engaged minutes summed over a 7-day window, aggregated FROM the `app_used_daily`
 * rollup. The weekly view is by construction consistent with the daily one (same primitive, summed)
 * and the heartbeat filter applied at rollup-write time flows through unchanged. Apps with zero
 * minutes in the window are absent. Distinct from [[ProfileUsageByApp]], which is a presence-rolled
 * per-host breakdown over a [from,to] window — this row is the *engaged*-time counterpart for one
 * app, the same quantity the per-app cap reads.
 */
case class ProfileAppWeeklyUsageRow(
    appId: AppId,
    appName: String,
    appIcon: Option[String],
    appIconType: Option[IconType],
    engagedMinutes: Int,
) derives JsonCodec

case class ProfileAppWeeklyUsage(
    profileId: ProfileId,
    profileName: String,
    from: String,
    to: String,
    apps: List[ProfileAppWeeklyUsageRow],
) derives JsonCodec

case class ProfileUsageByApp(
    profileId: ProfileId,
    profileName: String,
    from: String,
    to: String,
    // #1519: only "real" apps (rows whose host is mapped through `app_hosts`). The synthetic
    // `appId=null` "Other" row that earlier shipped here is gone; non-app hosts surface
    // individually in `orphanHosts` below.
    apps: List[ProfileAppUsage],
    // #1519/#726: per non-app host, one entry. Additive (defaults to Nil) so older clients keep
    // working; new clients render these as single-host pseudo-app rows.
    orphanHosts: List[OrphanHostUsage] = Nil,
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

// #1079 — unified by-app axis. Each entry is either an app (apps roll up
// their member hosts) or a single non-app host. `Other` is the long tail
// past `topN`, NOT a catch-all for unmapped hosts. Populated only when the
// request asks `?groupBy=app`; otherwise both fields stay empty.
case class UsageEntityRef(
    kind: String, // "app" | "host"
    id: String,   // app slug or hostname
    name: String,
    appId: Option[AppId] = None,
    appIcon: Option[String] = None,
    host: Option[HostId] = None,
) derives JsonCodec
case class UsageEntityTotal(entity: UsageEntityRef, dayMins: Int) derives JsonCodec
case class UsageBucketEntity(entity: UsageEntityRef, mins: Int) derives JsonCodec
case class UsageEntityBucket(
    hour: Int,
    totalMins: Int,
    perEntity: List[UsageBucketEntity],
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
    topEntries: List[UsageEntityTotal] = Nil,
    bucketsByEntry: List[UsageEntityBucket] = Nil,
    // #1492: the day-level session-stitch presence total (floored once), so the graph's headline
    // reconciles exactly with the time-used number on the profile card. Summing the per-hour
    // `totalMins` would drop sub-minute fractions and read a few minutes low; clients should show
    // this as the "total". Additive; older clients ignore it. Defaults to 0 for the empty case.
    presenceTotalMins: Int = 0,
    // #1507: hosts whose traffic was reached during the window but contributed 0 engaged-minutes
    // because they were classified as device-level background/infra (Apple OCSP, Google
    // connectivity probes, …) or fell under the keepalive byte-floor. Surfaced so the operator can
    // see what the engaged-time calculation excluded and why — the bytes did happen, they just
    // don't drive engagement. Additive; older clients ignore it. Derived from the same
    // [[wifihaven.api.presence.Presence.isHeartbeat]] predicate the rollup builder uses, so the
    // "excluded" view can't drift from the "counted" view (single-source-of-truth, AGENTS.md
    // §1532). #1560 will collapse the per-device span and suppression-list call sites to one entry
    // point so this stays canonical.
    suppressedHosts: List[SuppressedHostUsage] = Nil,
) derives JsonCodec

// #1507: one device-level-infra / keepalive host that traffic was seen for, with the bytes the
// router observed and the reason the heartbeat predicate fired. `reason` is a small enum the SPA
// reads to group rows ("infra" = matched the InfraHosts background list; "bytes-below-threshold"
// = filter-enabled keepalive floor). `buckets` is the number of 5-min traffic-report rows the
// host appeared in over the window, useful for "how chatty was this thing."
case class SuppressedHostUsage(
    host: HostId,
    bytes: Long,
    buckets: Int,
    reason: String,
) derives JsonCodec

// #1099: batched per-profile series for the /profiles page. One request
// resolves the whole visible profile set in a single partition-pruned scan
// instead of N parallel `/api/usage/series?profileId=` round-trips. Each
// element is identical to what the single-profile endpoint returns for that
// profile, so callers can treat the entries interchangeably.
case class UsageSeriesBatchResponse(
    series: List[UsageSeriesResponse],
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

case class DnsCache(
    deviceProfiles: Map[MacAddress, Profile],
    blocklists: Map[BlocklistId, Set[Hostname]],
    defaultProfile: Option[Profile],
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
    // #771: agent package version posted on each policy fetch via the
    // `X-WifiHaven-Agent-Version` header. NULL until the router upgrades to
    // an agent that reports it.
    agentVersion: Option[String] = None,
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
    reason: BlockReason,
    ts: String,
) derives JsonCodec

case class ConnectionEvent(
    id: ConnectionEventId,
    routerId: RouterId,
    mac: Option[MacAddress],
    host: HostId,
    destIp: Option[IpAddress],
    allowed: Boolean,
    reason: BlockReason,
    ts: String,
) derives JsonCodec

case class UsageRecord(
    mac: MacAddress,
    ip: Option[IpAddress],
    host: HostId,
    activeSeconds: Long,
    bytesIn: Long,
    bytesOut: Long,
    // #730: the destination IP these bytes/seconds were attributed to. The
    // OpenWRT agent's accumulator is already keyed (mac, dst_ip), so this is a
    // pass-through of that dst_ip onto the wire record. Optional for
    // backward-compat with pre-#730 agents that do not emit the field; the
    // API stores NULL on traffic_reports.dest_ip when absent.
    destIp: Option[IpAddress] = None,
) derives JsonCodec

case class UsageReport(
    routerId: RouterId,
    periodStart: String,
    periodEnd: String,
    records: List[UsageRecord],
) derives JsonCodec

/**
 * #1205 / design §3.2 — the router → API metrics push batch. The OpenWRT agent posts one of these
 * to `POST /api/router/metrics` every ~60 s. Counters are **cumulative running totals** since agent
 * start; the server re-bases when `agentStartedAt` changes (agent restart) instead of recording a
 * negative delta, so duplicate/retried batches never double-count (see RouterMetricsService).
 *
 * Deliberately a standalone JSON document with no HTTP-framing dependence: when #1023 (websocket
 * transport) lands it rehomes verbatim as `{"op":"metrics","payload":<this body>}` and dispatches
 * into the same carrier-agnostic RouterMetricsService.
 *
 * The agent sends only the *extra* labels per series (e.g. `reason`/`result`/`status`/`version`);
 * the server attaches the bounded `router_id` (and `installation_id` once that concept lands)
 * dimension itself.
 */
case class MetricCounter(name: String, labels: Map[String, String] = Map.empty, value: Double)
    derives JsonCodec

case class MetricGauge(name: String, labels: Map[String, String] = Map.empty, value: Double)
    derives JsonCodec

/**
 * A single cumulative bucket: observations with value ≤ `le` (`"+Inf"` for the overflow bucket).
 */
case class MetricHistogramBucket(le: String, count: Double) derives JsonCodec

case class MetricHistogram(
    name: String,
    labels: Map[String, String] = Map.empty,
    buckets: List[MetricHistogramBucket],
    sum: Double,
    count: Double,
) derives JsonCodec

case class RouterMetricsBatch(
    routerId: RouterId,
    agentVersion: String,
    // Counter-reset sentinel: a changed value between batches means the agent restarted and its
    // cumulative counters reset to zero, so the server re-bases rather than seeing a negative delta.
    agentStartedAt: String,
    sampledAt: String,
    counters: List[MetricCounter] = Nil,
    gauges: List[MetricGauge] = Nil,
    histograms: List[MetricHistogram] = Nil,
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
    // #771: agent package version reported on the most recent policy fetch
    // (or NULL for routers that haven't polled with a version-aware agent).
    agentVersion: Option[String] = None,
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

// #959: SPA-facing payload for the kid-side block page.
//
// `reasonClass` is one of a small enumerated set the SPA can switch on for
// kid-friendly copy: "paused", "schedule", "time_limit", "site_time_limit",
// "category", "extra_blocked". Internal granular reasons (e.g. the specific
// site label, the schedule end time) are intentionally omitted per the #952
// design doc Q4 decision — kids don't see "until 9:05pm" or "AdServerList".
//
// `blocked` is false when the (mac, host) pair resolves to Allow or the
// device is unenrolled; the SPA renders a generic "not blocked" page in
// that case rather than leaking household state.
case class BlockedInfoResponse(
    blocked: Boolean,
    reasonClass: Option[String],
    categoryName: Option[String],
    profileName: Option[String],
) derives JsonCodec

// ── Policy snapshot (target shape per docs/architecture.md §0.2, #354) ────
//
// Diverges from architecture.md §0.2 in one place: `failureMode` is per-profile
// (carried in ProfilePolicy) rather than top-level. The DB has it as a
// per-profile column and we keep it that way until there's a reason to consolidate.

case class Blocklist(version: BlocklistVersion, url: BlocklistUrl) derives JsonCodec

// #958: SPA-facing metadata row for the blocklist management page.
// `bundled` distinguishes API-shipped lists (host content overwritten on
// each API startup) from operator/test-created categories whose hosts
// only ever change via the API. `hostCount` is denormalized from
// blocklist_domains; `lastBuiltAt` reflects the last startup seed of a
// bundled list, NULL for non-bundled categories.
case class BlocklistSummary(
    id: BlocklistId,
    name: String,
    description: Option[String],
    bundled: Boolean,
    source: Option[String],
    hostCount: Int,
    lastBuiltAt: Option[java.time.Instant],
) derives JsonCodec

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

// ── Global policy management (#1320 / #1308) ──────────────────────────────
// Admin-facing read/write surface for the household-global allow/block sets
// that PolicyService (#1318) collapses into `PolicySnapshot.global`. The wire
// snapshot carries only the flat hostname / category lists the router needs;
// the *why* and *who* — the security-sensitive audit trail for the
// always-reachable bypass list (design §7) — live in the DB and are surfaced
// ONLY through these management types, never to the router.

// One row of the `global_allow` / `global_blocks` audit history. `removedAt ==
// None` ⇒ the entry is active and feeds `PolicySnapshot.global`; `Some` ⇒ it
// was soft-deleted and is retained for audit only. `addedBy`/`removedBy` are
// resolved to usernames server-side (the DB stores user ids).
case class GlobalPolicyAuditEntry(
    host: Hostname,
    reason: Option[String],
    addedBy: Option[String],
    addedAt: String,
    removedBy: Option[String],
    removedAt: Option[String],
) derives JsonCodec

// Full management view of the global section. `allow`/`blocks` include
// soft-deleted history so the audit trail is visible; the active set feeding
// `PolicySnapshot.global` is exactly the rows with `removedAt == None`.
case class GlobalPolicyView(
    allow: List[GlobalPolicyAuditEntry],
    blocks: List[GlobalPolicyAuditEntry],
    blocklistIds: List[BlocklistId],
    blocked: Boolean,
    blockReason: Option[MacBlockReason],
    blockIpOnly: Boolean,
) derives JsonCodec

// Append a host to the global allow or block set, with an optional audit
// `reason`. Re-adding a previously-removed host is fine (the soft-deleted row
// keeps its `removedAt`; a fresh active row is inserted).
case class AddGlobalHostRequest(host: Hostname, reason: Option[String] = None) derives JsonCodec

// Replace the household-global category set (`global.blocklistIds`) wholesale.
case class SetGlobalBlocklistsRequest(blocklistIds: List[BlocklistId]) derives JsonCodec

// Set the flat global flags: the network-lockdown kill switch (`blocked` +
// block-page `blockReason`) and the network-wide strict-IP floor.
case class SetGlobalFlagsRequest(
    blocked: Boolean,
    blockReason: Option[MacBlockReason] = None,
    blockIpOnly: Boolean,
) derives JsonCodec

case class ProfilePolicy(
    name: String,
    rules: BlockRules,
    failureMode: FailureMode,
) derives JsonCodec

// `global` carries fleet-wide policy ONCE per snapshot (#1308 design,
// docs/design/global-policy-layer.md §3.1). It reuses `BlockRules` verbatim —
// no new struct, no "why" metadata — so each field has the same router meaning
// it has on a profile, just applied to every MAC: `extraAllowed` is the
// always-reachable list that carves out every drop (the relocated infra /
// UI / block-page hosts), `extraBlocked` / `blocklistIds` / `blocked` are
// mandatory network-wide blocks a profile may NOT un-block, and `blockIpOnly`
// is a network-wide strict-IP floor. The global section is NOT a third merge
// tier: composition precedence (`global.extraAllowed` outranks everything;
// global blocks outrank per-MAC allow) resolves server-side and the router
// applies it as one more `BlockRules` (§5.2). Defaults to `allowAll` (inert)
// so a snapshot with no global policy — and an older snapshot JSON predating
// this field — decodes to a no-op. PolicyService assembly lands in #1318.
case class PolicySnapshot(
    etag: ETag,
    generatedAt: String,
    global: BlockRules = BlockRules.allowAll,
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

// #1605: `jsonKind` and `wireKind` are the single source of truth for the
// kind-tag strings each case carries on the wire. The exhaustive-match
// encoders read these directly (`asWire`, `JsonEncoder`), and the
// string-match decoders (`fromWire`, `JsonDecoder`) derive their nullary
// lookup table from `NullaryCases`, so a new sealed-trait case wires through
// every encode/decode path without separate hand edits.
//
// Parameterised cases (`Category`, `AppTimeLimit`, `AppBlocked`, `Unknown`)
// still need per-case payload handling in the encoders and per-prefix
// handling in the decoders — `wireKind` is the prefix-before-colon for
// these and remains the canonical string.
sealed trait BlockReason {
  def jsonKind: String
  def wireKind: String
}

sealed trait MacBlockReason extends BlockReason
object MacBlockReason {
  case object Paused      extends MacBlockReason {
    val jsonKind = "paused"; val wireKind = "paused"
  }
  case object Schedule    extends MacBlockReason {
    val jsonKind = "schedule"; val wireKind = "schedule"
  }
  case object TimeLimit   extends MacBlockReason {
    val jsonKind = "timeLimit"; val wireKind = "time_limit"
  }
  case object Manual      extends MacBlockReason {
    val jsonKind = "manual"; val wireKind = "manual"
  }
  // #1122: default-block path for devices with no profile assignment when
  // settings.unmanagedMacPolicy.policy=="block". Distinct from Manual (which
  // is an admin block on a known device) so the Logs page and block-page can
  // surface a more specific reason.
  case object Unmanaged   extends MacBlockReason {
    val jsonKind = "unmanaged"; val wireKind = "unmanaged_mac"
  }
  // #1316 / #1308: a profile in default-deny mode collapses to `blocked = true`
  // with this reason (block-page text only). Lowest-precedence reason — it is
  // the steady-state baseline, so a concurrent Paused/Schedule/TimeLimit
  // reports the stronger reason instead (see docs/design/global-policy-layer.md §3.3).
  case object DefaultDeny extends MacBlockReason {
    val jsonKind = "defaultDeny"; val wireKind = "default_deny"
  }

  def asString(r: MacBlockReason): String      = r match {
    case Paused      => "Paused"
    case Schedule    => "Schedule"
    case TimeLimit   => "TimeLimit"
    case Manual      => "Manual"
    case Unmanaged   => "Unmanaged"
    case DefaultDeny => "DefaultDeny"
  }
  def parse(s: String): Option[MacBlockReason] = s match {
    case "Paused"      => Some(Paused)
    case "Schedule"    => Some(Schedule)
    case "TimeLimit"   => Some(TimeLimit)
    case "Manual"      => Some(Manual)
    case "Unmanaged"   => Some(Unmanaged)
    case "DefaultDeny" => Some(DefaultDeny)
    case _             => None
  }

  given JsonCodec[MacBlockReason] = JsonCodec[String].transformOrFail(
    s => parse(s).toRight(s"unknown blockReason: $s"),
    asString,
  )
}

// #962: typed reason for block_events / connection_events. Stored as JSONB
// in Postgres tagged on `kind`. The router/PolicyService emit a free-form
// wire string today; the API converts on ingest via `BlockReason.fromWire`.
// MacBlockReason cases are subtypes so a snapshot reason can be lifted into
// the event-table form without re-encoding (their wire form is unchanged in
// the snapshot JSON — only the event-DB encoding becomes kind-tagged).
object BlockReason {
  case object Allow                      extends BlockReason {
    val jsonKind = "allow"; val wireKind = "allow"
  }
  case object Blocked                    extends BlockReason {
    val jsonKind = "blocked"; val wireKind = "blocked"
  }
  case object ExtraAllowed               extends BlockReason {
    val jsonKind = "extraAllowed"; val wireKind = "extra_allowed"
  }
  case object ExtraBlocked               extends BlockReason {
    val jsonKind = "extraBlocked"; val wireKind = "extra_blocked"
  }
  case object NoProfile                  extends BlockReason {
    val jsonKind = "noProfile"; val wireKind = "no_profile"
  }
  case class Category(slug: BlocklistId) extends BlockReason {
    val jsonKind = "category"; val wireKind = "category"
  }
  case class AppTimeLimit(label: String) extends BlockReason {
    // #1518 rename; the legacy `siteTimeLimit` jsonKind is still ACCEPTED by
    // the decoder below for V40-migrated DB rows, but never emitted.
    val jsonKind = "appTimeLimit"; val wireKind = "app_time_limit"
  }
  case class AppBlocked(appId: String)   extends BlockReason {
    val jsonKind = "appBlocked"; val wireKind = "app"
  }
  case class Unknown(raw: String)        extends BlockReason {
    // `wireKind` here is informational only; `asWire(Unknown(raw))` emits
    // `raw` verbatim, not via this field, so a non-categorizable wire string
    // round-trips through `fromWire`.
    val jsonKind = "unknown"; val wireKind = "unknown"
  }

  // #1605: single source of truth for the nullary cases that the
  // string-match decoders look up. Adding a new nullary case to the sealed
  // trait and to this list automatically wires both `fromWire` and the
  // `JsonDecoder` to accept it, mirroring what the exhaustive-match
  // encoders are already forced to handle.
  private val NullaryCases: List[BlockReason] = List(
    Allow,
    Blocked,
    ExtraAllowed,
    ExtraBlocked,
    NoProfile,
    MacBlockReason.Paused,
    MacBlockReason.Schedule,
    MacBlockReason.TimeLimit,
    MacBlockReason.Manual,
    MacBlockReason.Unmanaged,
    MacBlockReason.DefaultDeny,
  )

  private val byWireKind: Map[String, BlockReason] =
    NullaryCases.map(r => r.wireKind -> r).toMap
  private val byJsonKind: Map[String, BlockReason] =
    NullaryCases.map(r => r.jsonKind -> r).toMap

  // Back-compat legacy wire aliases preserved verbatim from pre-#1605. These
  // accept-only paths support snapshot-form (PascalCase) reasons, the legacy
  // "host" / "allowed" / "device_not_enrolled" strings, and any other
  // alternate spelling a deployed router may still be posting. They never
  // appear in any encoder.
  private val LegacyWireAliases: Map[String, BlockReason] = Map(
    "allowed"             -> Allow,
    "host"                -> ExtraBlocked,
    "ExtraBlocked"        -> ExtraBlocked,
    "Paused"              -> MacBlockReason.Paused,
    "Schedule"            -> MacBlockReason.Schedule,
    "TimeLimit"           -> MacBlockReason.TimeLimit,
    "Manual"              -> MacBlockReason.Manual,
    "Unmanaged"           -> MacBlockReason.Unmanaged,
    "device_not_enrolled" -> MacBlockReason.Unmanaged,
    "DefaultDeny"         -> MacBlockReason.DefaultDeny,
  )

  /**
   * Parse a router / PolicyService wire-format reason string. Unknown values fall through to
   * `Unknown(raw)` so we don't drop event rows on a wire-shape mismatch between API and router
   * versions.
   */
  def fromWire(s: String): BlockReason =
    // Nullary cases derive from NullaryCases (#1605). Legacy spellings carry
    // their own lookup table (PascalCase snapshot form, "host", "allowed",
    // "device_not_enrolled"). Parameterised cases keep prefix matching.
    LegacyWireAliases.get(s).orElse(byWireKind.get(s)).getOrElse {
      if (s.startsWith("category:"))
        BlocklistId
          .parse(s.stripPrefix("category:"))
          .map(Category(_))
          .getOrElse(Unknown(s))
      else if (s.startsWith("app_time_limit:"))
        // #1518: routers treat decision-response reasons as opaque pass-through
        // (echo verbatim on /api/router/events), and the dual-written
        // `reason_text` column is consumed by older-image rollback only —
        // never re-parsed in this codebase — so dropping the legacy
        // `site_time_limit:` parse arm doesn't strand any live caller. The
        // JSONB `siteTimeLimit` decoder arm (JsonDecoder below) is the one
        // that still needs the legacy alias because V40-migrated DB rows
        // persist that kind.
        AppTimeLimit(s.stripPrefix("app_time_limit:"))
      else if (s.startsWith("app:"))
        AppBlocked(s.stripPrefix("app:"))
      else
        Unknown(s)
    }

  /**
   * Inverse of [[fromWire]]. Returns the canonical pre-V40 TEXT-format wire string for a
   * `BlockReason` — what a router agent prior to #1147 would have written to
   * `connection_events.reason` (TEXT) or `block_events.reason` (TEXT).
   *
   * Used as the source of truth for the back-compat dual-write introduced in V44 (#1176 / #1179):
   * every write to either table populates `reason_text` from `asWire(reason)` so a rollback to an
   * image that binds the column as TEXT can still read meaningful values.
   *
   * Each non-`Unknown` case round-trips through `fromWire(asWire(r)) == r`. The `Unknown(raw)` case
   * emits `raw` verbatim — anything `fromWire` couldn't categorize is preserved unmodified, so a
   * second `fromWire` reparse still produces the same `Unknown(raw)`.
   */
  // #1605: nullary cases delegate to `wireKind`; parameterised cases still
  // need per-case payload assembly. The compiler still requires exhaustive
  // coverage of every BlockReason variant.
  def asWire(r: BlockReason): String = r match {
    case Category(slug)      => s"category:${slug.value}"
    case AppTimeLimit(label) =>
      s"app_time_limit:$label" // #1518 rename; routers echo back what they receive, no legacy parse needed
    case AppBlocked(appId)   => s"app:$appId"
    case Unknown(raw)        => raw
    case r                   => r.wireKind
  }

  // Kind-tagged JSON. The encoder is exhaustive — its nullary cases delegate
  // to `jsonKind` (#1605), parameterised cases assemble per-case payloads.
  // The decoder is string-match; its nullary lookup derives from
  // `NullaryCases`, parameterised arms handle their payloads.
  given JsonEncoder[BlockReason] = JsonEncoder[Json].contramap {
    case Category(slug)      =>
      Json.Obj("kind" -> Json.Str("category"), "slug" -> Json.Str(slug.value))
    case AppTimeLimit(label) =>
      Json.Obj(
        "kind"  -> Json.Str("appTimeLimit"),
        "label" -> Json.Str(label),
      ) // #1518 rename; decoder still accepts the legacy `siteTimeLimit` kind
    // for V40-migrated DB rows and any older SPA build that pattern-matches on it.
    case AppBlocked(appId)   =>
      Json.Obj("kind" -> Json.Str("appBlocked"), "appId" -> Json.Str(appId))
    case Unknown(raw)        =>
      Json.Obj("kind" -> Json.Str("unknown"), "raw" -> Json.Str(raw))
    case r                   =>
      Json.Obj("kind" -> Json.Str(r.jsonKind))
  }

  given JsonDecoder[BlockReason] = JsonDecoder[Json].mapOrFail {
    case obj: Json.Obj =>
      def field(k: String): Either[String, String] =
        obj.fields.find(_._1 == k).map(_._2) match {
          case Some(Json.Str(s)) => Right(s)
          case Some(_)           => Left(s"BlockReason field '$k' is not a string")
          case None              => Left(s"BlockReason missing field '$k'")
        }
      field("kind").flatMap { k =>
        // Nullary cases derive from NullaryCases (#1605); parameterised cases
        // and the legacy `siteTimeLimit` JSONB alias keep their own arms.
        byJsonKind.get(k) match {
          case Some(r) => Right(r)
          case None    =>
            k match {
              case "category"                       =>
                field("slug").flatMap(s =>
                  BlocklistId
                    .parse(s)
                    .map(Category(_))
                    .left
                    .map(_ => s"invalid category slug: $s"),
                )
              case "appTimeLimit" | "siteTimeLimit" =>
                // #1518: encoder emits `appTimeLimit`; the legacy
                // `siteTimeLimit` kind is accepted so V40-migrated
                // `block_events.reason`/`connection_events.reason` JSONB rows
                // (written before that PR) still decode.
                field("label").map(AppTimeLimit(_))
              case "appBlocked"                     => field("appId").map(AppBlocked(_))
              case "unknown"                        => field("raw").map(Unknown(_))
              case other                            => Left(s"BlockReason: unknown kind '$other'")
            }
        }
      }
    case _             => Left("BlockReason: expected JSON object")
  }

  given JsonCodec[BlockReason] =
    JsonCodec(summon[JsonEncoder[BlockReason]], summon[JsonDecoder[BlockReason]])
}
