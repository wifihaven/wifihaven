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
    // #1771: marks a household's "Global" sentinel profile. Exactly one row per
    // household may have `is_global = TRUE` (V73 `(household_id, is_global)`
    // partial unique index; V59 originally made it installation-wide, #2286
    // widened it per-household). The sentinel replaces the deleted `global_*`
    // tables (#1769): its
    // resolved `extraAllowed` / `extraBlocked` / `blocklistIds` are unioned into
    // every other profile's `BlockRules` by `PolicyService.snapshot`, while its
    // `paused` / schedules / time limits / pauseMode are meaningless household-
    // wide and write-rejected at the route layer. Devices cannot reference it.
    // Defaulted to false to keep existing positional constructions arity-stable.
    isGlobal: Boolean = false,
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
    // #1627: `None` means "no per-app limit configured" — distinct from a
    // 0-minute cap. The carve-out gate in `PolicyService.exemptUnderCapHosts`
    // treats `None` as "never exhausted" so an exempt-from-daily app with no
    // own cap always carves around whole-MAC blocks. Previously the repo
    // projected the nullable column through COALESCE(..., 0), collapsing the
    // two cases and making the carve unreachable for the no-limit shape.
    dailyMinutes: Option[Int],
    label: String,
    exemptFromDaily: Boolean = true,
    // #1564: typed FK to apps(id), carried straight from the listForProfile join. This is the
    // canonical app identity the cap/rollup surface keys on; `label` ("app:<slug>") stays as
    // SPA-facing display text only. Defaults to AppId(0L) so seed/test constructions that don't
    // care about the FK still compile during the rollout.
    appId: AppId = AppId(0L),
    // #1630: the assignment's mode and id, carried straight from the listForProfile join. Before
    // the #1630 collapse the repo filtered `WHERE mode='time_limited'` and these were implicit
    // (every row was TimeLimited, every assignment unique). Post-collapse the repo returns every
    // assignment's rows across every mode, so the projection needs both fields to identify the
    // owning assignment and decide which downstream bucket the row contributes to. Default
    // values keep legacy hand-built test constructions (single-app TimeLimited rows) compiling.
    mode: AppMode = AppMode.TimeLimited,
    assignmentId: AppPolicyAssignmentId = AppPolicyAssignmentId(0L),
    // #1679: when false, this app's hosts are suppressed from the snapshot's per-MAC extraAllowed
    // during a Schedule-reason whole-MAC block. Only applies to Schedule; Paused/TimeLimit/Manual
    // are not affected. Default true preserves current behavior (extraAllowed beats every block).
    allowedDuringScheduleBlock: Boolean = true,
    // #1897 (shared-hosts S2): this (app, host) row's `app_hosts.shared` flag. `true` marks a SHARED
    // backend (a multi-tenant vendor API / CDN listed on several apps); `false` (default) is a
    // DISTINCTIVE host that uniquely identifies the app's use. Carried per-(assignment × host) from
    // `AppTimeLimitRepo.listForProfile` so `ProfileAppDispositions` can partition each app's host-set
    // into distinctive vs shared. Shared hosts are excluded from the per-app engaged-minutes stitch
    // (already counted inside any overlapping distinctive span — see docs/design/shared-host-
    // allocation.md). Default false keeps every legacy hand-built construction distinctive.
    shared: Boolean = false,
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

// #1898 (shared-hosts S3): `shared` carries the per-(app, host) `app_hosts.shared` flag through the
// global host→app inventory (`listAllHostMappings`). The usage-by-app reporting path uses it to route
// SHARED hosts through the co-presence allocation instead of the unconditional `minBy(appId)` mapping
// that distinctive hosts keep. Default false keeps every legacy construction distinctive and is
// additive on the wire.
case class AppHost(appId: AppId, host: Hostname, shared: Boolean = false) derives JsonCodec

/**
 * #1896: an app's host paired with its `shared` flag (`false` == distinctive). Distinctive hosts
 * keep the unconditional `minBy(appId)` attribution; shared hosts (multi-tenant vendor backends)
 * are stored now and attributed only under the co-presence rule once S2-S5 land. See
 * `docs/design/shared-host-allocation.md`.
 */
case class AppHostEntry(host: Hostname, shared: Boolean) derives JsonCodec

case class AppPolicyAssignment(
    id: AppPolicyAssignmentId,
    appId: AppId,
    profileId: ProfileId,
    mode: AppMode,
    dailyMinutes: Option[Int],
    exemptFromDaily: Boolean = true,
    // #1679: toggle — false suppresses this app's extraAllowed carve-out during Schedule blocks.
    allowedDuringScheduleBlock: Boolean = true,
) derives JsonCodec

// #1798: the app *definition* mutator endpoints (create / update name+icon /
// replace hosts / PATCH) were retired — app definitions are authored only via
// the built-in `AppTemplates` in code — so their request shapes
// (CreateAppRequest / UpdateAppRequest / SetAppHostsRequest) were removed with
// them. Only reads + policy assignment remain on the apps surface.

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
    // #1679: omitted by existing clients decodes as None → server defaults to true.
    allowedDuringScheduleBlock: Option[Boolean] = None,
) derives JsonCodec

// #1983: one app host that is a member of one or more shipped category
// blocklists, plus the blocklist(s) it appears on. Surfaced so the SPA can warn
// — on the Apps page and the app selector — that allowing this app overlaps a
// category that would otherwise drop the host (see #1980 for the policy-layer
// precedence). Membership is GLOBAL: a host is flagged if it (or an apex parent)
// is in ANY blocklist we ship, not scoped to the blocklists enabled for a given
// profile — the simplest correct shape per #1983 (the warning is about the app
// definition, which is profile-independent).
case class AppBlocklistedHost(host: Hostname, blocklists: List[BlocklistId]) derives JsonCodec

case class AppDetail(
    app: App,
    hosts: List[Hostname],
    assignments: List[AppPolicyAssignment],
    // #1983: additive — hosts of this app that overlap a category blocklist.
    // Existing clients omit it; it decodes to `Nil`, so the wire stays
    // back-compatible.
    blocklisted: List[AppBlocklistedHost] = Nil,
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
 *
 * `householdId` is resolved from the alert's device via the join `AlertRepo` already does (alerts
 * rows are MAC-keyed with no household_id column of their own). It carries the tenancy key to the
 * out-of-band notifier (#578) so it can look up this household's `notify_email`; it is `None` when
 * the alert's MAC has no device row (e.g. a stale/unknown MAC).
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
    householdId: Option[HouseholdId] = None,
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
    // #2566/#2322: the block-page token the router put on the redirect URL, relayed by the SPA.
    // It is the ONLY thing on this unauthenticated request that says which household is asking —
    // `mac` cannot, since the same MAC may exist in several households (V74/V75). Optional so an
    // agent that predates the token keeps working (the API↔agent wire is additive); absent, the
    // intake falls back to the default household exactly as it did before.
    bpt: Option[String] = None,
) derives JsonCodec

/**
 * #2566: what `POST /api/access-requests` returns to its UNAUTHENTICATED caller, on both the create
 * (201) and the debounce-hit (200) paths.
 *
 * Deliberately narrower than [[Alert]]: the block page renders the reason and the minutes, and
 * needs nothing here but an acknowledgement, whereas the full row carries `deviceName`,
 * `profileName` and the child's free-text `note` — household data with no business crossing back
 * over an unauthenticated response. Both paths return the SAME shape so the debounce hit cannot
 * disclose more than the create it stood in for.
 */
case class AccessRequestReceipt(
    id: AlertId,
    status: AlertStatus,
    kind: AccessRequestKind,
    host: Hostname,
) derives JsonCodec

/**
 * #2566/#2569/#2322: response of `GET /api/router/block-page-token` — the opaque, router-bound
 * token the agent stamps onto its block-page redirect so the unauthenticated block-page endpoints
 * can resolve the household. Router-authenticated, like every other `/api/router/` endpoint.
 */
case class BlockPageTokenResponse(token: String) derives JsonCodec

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

// ── Beta access requests (#2132, multi-tenant P5-2, epic #622) ──────────────
// The request-gated beta signup queue (design docs/design/multi-tenant-launch.md
// §3). DISTINCT from the block-page `AccessRequest`/`AccessRequestKind` concept
// above — a `BetaRequest` is a prospective NEW HOUSEHOLD asking to join, resolved
// by manual operator approval, not a kid asking to unblock a host.

/** Lifecycle of a beta_requests row (design §3.1): pending → approved | rejected. */
enum BetaRequestStatus derives JsonCodec {
  case Pending, Approved, Rejected
}
object BetaRequestStatus                 {
  def asString(s: BetaRequestStatus): String      = s match {
    case Pending  => "pending"
    case Approved => "approved"
    case Rejected => "rejected"
  }
  def parse(s: String): Option[BetaRequestStatus] = s match {
    case "pending"  => Some(Pending)
    case "approved" => Some(Approved)
    case "rejected" => Some(Rejected)
    case _          => None
  }
}

/**
 * Public intake body for `POST /api/beta/request` — unauthenticated. `email` and `name` are both
 * required (#2217): `name` is the household name that seeds `households.name` + the derived login
 * slug at provisioning, so the route rejects a missing/blank name with a 400. `note` is optional
 * free text for the operator. The field stays `Option` on the wire (older/nullable rows,
 * back-compat) — presence is enforced in the route, not the codec. Idempotent on duplicate email
 * (the route returns a generic 200 either way, so a duplicate leaks no enumeration signal).
 */
case class CreateBetaRequest(
    email: String,
    name: Option[String] = None,
    note: Option[String] = None,
) derives JsonCodec

/**
 * Generic acknowledgement returned to the public intake — deliberately content-free (no enumeration
 * leak; a brand-new and a duplicate request are indistinguishable to the caller).
 */
case class BetaRequestAck(status: String = "received") derives JsonCodec

/**
 * Operator-facing view of a beta_requests row (`GET /api/operator/beta-requests`). Only the
 * operator (admin of household 1) ever sees these — the invite token hash is NEVER included.
 */
case class BetaRequestSummary(
    id: BetaRequestId,
    email: String,
    name: Option[String],
    note: Option[String],
    status: BetaRequestStatus,
    requestedAt: String,
    decidedAt: Option[String],
    householdId: Option[HouseholdId],
) derives JsonCodec

/**
 * Operator's response to `POST /api/operator/beta-requests/{id}/approve`. Carries the single-use
 * invite URL (the operator sends it to the requester manually — no email transport is invented,
 * design §3.3) and the provisioned household's slug (the SPA accept page uses it to set the
 * long-lived `wh_household` cookie, design §3.4 / #2140).
 */
case class ApproveBetaResponse(
    householdId: HouseholdId,
    slug: String,
    inviteUrl: String,
    inviteExpiresAt: String,
) derives JsonCodec

/**
 * Invite acceptance body for `POST /api/beta/accept` — unauthenticated. Validates the single-use,
 * unexpired token, then creates the new household's FIRST admin user (design §3.4). This is the
 * only second-household admin-bootstrap path (before it, the V1 seed was the sole admin creator).
 *
 * Revised 2026-07-10 (design §3.4/§4): the payload carries **no username or email**. The admin's
 * `email` is bound server-side from the originating `beta_requests.email` (the address the operator
 * approved; V67 `users.email`), and `username` defaults to `admin` (per-household unique, V65). The
 * admin logs in by email thereafter (§4).
 */
case class AcceptInviteRequest(
    token: String,
    password: String,
) derives JsonCodec

/**
 * Response to a successful invite accept — the new household's slug (so the SPA sets the
 * `wh_household` cookie before auto-login) and the created admin's `username` (always `admin`),
 * design §3.4 / #2140.
 */
case class AcceptInviteResponse(
    householdId: HouseholdId,
    slug: String,
    username: String,
) derives JsonCodec

// ── Billing (#2135, multi-tenant P5-5) ──────────────────────────────────────
// The SPA billing page reads current plan/status; the checkout/portal routes return a redirect URL
// into a Stripe-hosted surface. `status` is the household_billing.status value (beta|active|lapsed).
// `founding` surfaces the founding-price framing. No Stripe secrets ever cross this wire.

/**
 * `GET /api/billing` — current billing state for the minimal SPA billing page.
 *
 * #2137 (P5-6): the two `flip*` fields carry the cohort flip-window state so the SPA can gate the
 * Subscribe CTA and the conversion banner (design §5.4, A1). Both are additive with defaults so the
 * wire stays back-compatible.
 *   - `flipWindowOpen` — the flip clock has started and has not yet flipped (the window is open, so
 *     the Subscribe CTA + banner show for unconverted households). During pure beta (clock not yet
 *     started) this is false and the SPA shows NO payment CTA (A1).
 *   - `flipDate` — the window-end instant (ISO-8601), when unconverted households flip to `lapsed`.
 *     Printed at signup surfaces ("free through <date>"). None until the clock starts.
 */
case class BillingStatusResponse(
    status: String,
    founding: Boolean,
    priceId: Option[String],
    currentPeriodEnd: Option[String],
    lapsedAt: Option[String],
    flipWindowOpen: Boolean = false,
    flipDate: Option[String] = None,
) derives JsonCodec

/**
 * `POST /api/billing/checkout` / `GET /api/billing/portal` — the hosted-Stripe URL to redirect to.
 */
case class BillingRedirect(url: String) derives JsonCodec

// #2164 (multi-tenant P5-8, single-identifier login — supersedes #2140's visible household field):
// login carries ONE identifier string, resolved server-side to a household by its syntax
// (docs/design/multi-tenant-launch.md §4):
//   - contains '@'   → `users.email` (global-unique, V67/#2159); household = matched row's
//   - contains '/'   → `slug/username` composite (split at first '/'), resolve households.slug
//   - neither        → bare username → DEFAULT household (self-hosted / existing API clients)
// The three forms are syntactically disjoint (V67 forbids '@' and '/' in usernames; V66 slugs are
// [a-z0-9-]) so there is no parsing precedence.
//
// `identifier` is the new field. `username` is retained as a back-compat ALIAS: pre-#2164 clients
// (and self-hosted scripts) posted the bare login name as `username`, and must keep working
// unchanged (bare name → default household). Both are Option so at least one must be present; when
// both are set `identifier` wins.
case class LoginRequest(
    identifier: Option[String] = None,
    password: String,
    username: Option[String] = None,
) derives JsonCodec {

  /** The effective login identifier: prefer `identifier`, fall back to the legacy `username`. */
  def loginIdentifier: String = identifier.orElse(username).getOrElse("")
}
// mustChangePassword: true when the server-side flag is set (e.g. freshly-seeded admin).
// The web uses this to redirect directly to the change-password page after login
// before the user can reach any other route.
//
// #2164: `householdSlug` is the resolved household's slug (`households.slug`, V66). The SPA writes it
// to the long-lived `wh_household` cookie so a later BARE-username login on this browser can be
// client-composed into `slug/username` (design §4 form 3). It is post-authentication output only —
// returning the authenticated user's own household slug is not an enumeration signal. Defaulted to
// None so pre-#2164 clients decode unchanged.
case class LoginResponse(
    token: JwtToken,
    role: UserRole,
    username: String,
    mustChangePassword: Boolean = false,
    householdSlug: Option[String] = None,
) derives JsonCodec
case class ChangePasswordRequest(currentPassword: String, newPassword: String) derives JsonCodec

// ── Admin-initiated password set (#2576, epic #622) ─────────────────────────
// The recovery path for the users #2308's emailed reset structurally cannot serve: a child (who
// generally has no email address) and an admin-created adult with none on file. The household admin
// — physically present and the account owner — sets the credential in band via
// `POST /api/users/{id}/password`.

/**
 * `POST /api/users/{id}/password` body. The target is the PATH id, validated server-side against
 * the caller's own household (`claims.hh`) — deliberately NOT a field here: a household or username
 * in the body would be a client-supplied tenancy key, which is the #2533/#2386 defect class and, on
 * a credential write, a cross-household account-takeover primitive. `newPassword` is subject to the
 * same #2084 policy as every other password path.
 */
case class SetUserPasswordRequest(newPassword: String) derives JsonCodec

/**
 * Response to a successful admin-initiated set. `mustChangePassword` is always `true` and says what
 * the admin needs to hear: the credential they just chose is a HANDOFF — the target is forced to
 * replace it at next login (#2492/#586), so it does not persist as a secret the admin knows. The
 * plaintext is never echoed back, here or anywhere else.
 */
case class SetUserPasswordResponse(mustChangePassword: Boolean = true) derives JsonCodec

// ── Forgot / reset password (#2308, epic #622) ──────────────────────────────
// The self-service recovery path for a household admin who forgot their password (beta admins log in
// by email; a lockout otherwise has no recovery). Two unauthenticated, rate-limited endpoints:
//   - POST /api/auth/forgot-password — request a reset link by email.
//   - POST /api/auth/reset-password  — consume the single-use token + set a new password.

/**
 * `POST /api/auth/forgot-password` body. `email` is the global login identifier (`users.email`,
 * V67); the server resolves it to a user (and thus household) with no household in hand. A user is
 * only emailed if the address is registered — but the response is ALWAYS the same generic
 * [[ForgotPasswordAck]] (no account enumeration).
 */
case class ForgotPasswordRequest(email: String) derives JsonCodec

/**
 * Deliberately content-free acknowledgement for `POST /api/auth/forgot-password` — a registered and
 * an unregistered email are byte-identical to the caller (no enumeration leak; mirrors
 * [[BetaRequestAck]]).
 */
case class ForgotPasswordAck(status: String = "sent") derives JsonCodec

/**
 * `POST /api/auth/reset-password` body. `token` is the single-use, short-TTL raw token from the
 * emailed `/reset-password?token=…` link (only its hash is stored server-side); `newPassword` is
 * subject to the #2084 password policy. A successful reset bumps the user's `token_version`
 * (#2080), invalidating every previously-issued JWT.
 */
case class ResetPasswordRequest(token: String, newPassword: String) derives JsonCodec

/** Response to a successful `POST /api/auth/reset-password`. */
case class ResetPasswordResponse(ok: Boolean = true) derives JsonCodec
// #623: change-password used to return an empty 200 body, which broke clients
// that assume "200 ⇒ parseable JSON" (every other route returns JSON). Echo
// back mustChangePassword=false so callers can refresh session state inline.
case class ChangePasswordResponse(mustChangePassword: Boolean) derives JsonCodec
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
// #2199 (support intake B, epic #2197) — the SERVER-SIGNED identity the authenticated admin SPA
// feeds Plain's identified chat widget (design/umbrella #2206 §2). The API computes the HMAC-SHA256
// email hash server-side (never trusting a client-supplied email/household) so household A can never
// obtain household B's widget identity — the household-gating boundary. Everything is `Option` so
// the DARK case (Plain widget unconfigured, OR the caller has no email to identify) returns
// `{configured:false}` with every other field absent and the SPA renders nothing.
case class SupportIdentityResponse(
    // Whether the identified widget should render: the Plain widget config is present AND the caller
    // is identifiable (has an email). When false, the SPA renders no widget (feature dark).
    configured: Boolean,
    // Plain chat/widget app id (public) — the SPA needs it to boot the widget SDK.
    appId: Option[String] = None,
    // The authenticated caller's own email (resolved server-side from their JWT, not client-supplied).
    email: Option[String] = None,
    // HMAC-SHA256(identitySecret, lowercased-email) hex — Plain "chat authentication". Signed
    // server-side; this is what prevents impersonation of another user/household.
    emailHash: Option[String] = None,
    // Plain's native tenant field = the caller's household id (household-gating). String on the wire
    // because Plain's tenantIdentifier is a string.
    tenantIdentifier: Option[String] = None,
    // Bounded account context surfaced to Plain as customer attributes for the operator / responder.
    fullName: Option[String] = None,
    // Subscription state (beta / active / lapsed, from #2135 household_billing) — intake context,
    // never a gate on reading replies.
    plan: Option[String] = None,
    founding: Option[Boolean] = None,
    // #2429: the environment-correct support inbox (`support.emailAddress` —
    // support@wifihaven.net on prod, support@staging.wifihaven.net on staging; both are live
    // Cloudflare Email Routing rules into the same Plain workspace). The API is the SINGLE SOURCE
    // OF TRUTH for it so the SPA never duplicates environment logic. Unlike every other field it is
    // present on the DARK response TOO: when the chat widget is off (or the caller is not
    // identifiable) email is still a working support path, so the SPA degrades its affordance to
    // email-only rather than hiding it. `None` = no hosted support desk (the self-hosted posture)
    // ⇒ the SPA shows no support line at all.
    supportEmail: Option[String] = None,
) derives JsonCodec

object SupportIdentityResponse {
  // The dark response: widget unconfigured, or the caller is not identifiable. The SPA renders no
  // widget on `configured=false` — but still renders the email-only support line when
  // `supportEmail` is set (#2429).
  def disabled(supportEmail: Option[String] = None): SupportIdentityResponse =
    SupportIdentityResponse(configured = false, supportEmail = supportEmail)
}

case class MeResponse(
    username: String,
    role: UserRole,
    profileIds: List[ProfileId],
    // #2133 (multi-tenant P5-3): whether this caller passes the operator gate
    // (admin AND a member of household 1 — `requireOperator`, design §3.2). The SPA
    // drives beta-request queue nav/route visibility off THIS signal rather than
    // hardcoding "household 1 admin" client-side. Defaulted to false so pre-#2133
    // clients decode unchanged; a non-operator omits it and reads false.
    isOperator: Boolean = false,
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
    // #1912 / #1909: network-wide "block encrypted DNS & relays" toggle. When
    // true, PolicyService emits the additive top-level `PolicySnapshot.blockEncryptedDns`
    // and the agent (#1911) forces devices onto the LAN resolver — NXDOMAIN for
    // the curated relay/DoH hostnames (iCloud Private Relay, public DoH) plus
    // nftables drops for hardcoded resolver IPs and DoT/853. Household-level
    // (NOT per-profile): the clean disable signal is NXDOMAIN, which stock
    // dnsmasq answers network-wide only. The curated host/IP lists are baked in
    // the agent, never shipped on the wire.
    //
    // #2643: the value a NEW household starts with is
    // [[HouseholdSettings.DefaultBlockEncryptedDns]] (true) — written explicitly
    // by every creation path, NOT taken from V61's column default. This field
    // default stays `false` because it is a JSON-DECODING default: it only
    // applies when a peer sends a `HouseholdSettings` JSON object without the
    // key, and a decode fallback that turned enforcement on would be the wrong
    // failure direction. It is not, and never was, the product default — see
    // the scaladoc on `DefaultBlockEncryptedDns`.
    blockEncryptedDns: Boolean = false,
    // #2077: the engagement-anchor gate over the isolation-learned ambient-host
    // baseline (docs/design/idle-traffic-discrimination.md). `ambientGateEnabled`
    // is the master switch. The three thresholds mirror migration V63: a device
    // span with <= `ambientIsolationMaxHosts` distinct hosts and no app-attributed
    // row is "isolated" for learning; a host isolated on >= `ambientMinIsolatedDays`
    // distinct days within the trailing `ambientLearningWindowDays` becomes ambient.
    // Default off — the learner runs regardless, so the operator can inspect the
    // would-be ambient set via GET /api/presence/ambient-hosts before enabling.
    //
    // #2643 proposed defaulting this ON for new households alongside
    // `blockEncryptedDns` and did NOT ship it, for a reason worth keeping here:
    // `ambient_host_days` has no `household_id` (V63) and `AmbientHostsRepo
    // .ambientHosts` reads it unscoped, so a new household on a multi-tenant
    // install would inherit the FULL baseline learned from other households'
    // devices on its first request — not the empty one the safety argument
    // assumed. That inverts the risk from under-filtering to cross-tenant
    // over-suppression of a household's screen time. Scoping the table (#2583)
    // is the prerequisite; the flip is tracked separately.
    ambientGateEnabled: Boolean = false,
    ambientIsolationMaxHosts: Int = HouseholdSettings.DefaultAmbientIsolationMaxHosts,
    ambientMinIsolatedDays: Int = HouseholdSettings.DefaultAmbientMinIsolatedDays,
    ambientLearningWindowDays: Int = HouseholdSettings.DefaultAmbientLearningWindowDays,
    // #578: per-household address the API notifies out-of-band (email) when an
    // access-request alert is raised. `None` = no recipient configured, in which
    // case the Notifier falls back to its structured-log line. A notification
    // *preference*, deliberately not coupled to any admin's `users.email` login
    // identity. Column V69 (household_settings.notify_email); nullable.
    notifyEmail: Option[String] = None,
) derives JsonCodec

object HouseholdSettings {

  /**
   * #2643: the value "Block encrypted DNS & relays" takes for a NEWLY created household — the ONE
   * place the new-household default lives (AGENTS.md §single-source-of-truth). Every creation path
   * names `household_settings.block_encrypted_dns` explicitly from this constant:
   * `HouseholdSeed.insertHousehold` (provisioned households) and
   * `HouseholdSettingsRepoLive.ensureDefault` (the singleton household of a fresh self-hosted
   * install).
   *
   * ON, because a device that tunnels around the LAN resolver — iCloud Private Relay, public
   * DoH/DoT — bypasses ALL hostname attribution and everything that depends on it: site and
   * category blocking, and per-app limits. (NOT daily time limits, which resolve to a whole-MAC
   * `blocked = true` forward-drop that never consults DNS and still bite.) And it does so silently:
   * the dashboard still renders, it just shows raw IPs, and configured blocks quietly stop biting.
   * A parental-control product whose out-of-the-box posture permits that is inert until an operator
   * happens to find the toggle. Hit live on a fresh prod household during #2527.
   *
   * Deliberately NOT the same knob as three other `false`s nearby, which are different concerns:
   *   - V61's `block_encrypted_dns BOOLEAN NOT NULL DEFAULT FALSE` column default — the value a row
   *     gets when written by code that does not name the column — since #2643 that is the
   *     back-compat contract for image-(N-1) and nothing else.
   *     `HouseholdSeed.backfillMissingSettings`, which repairs PRE-EXISTING households (#2386),
   *     used to rely on it and now stamps FALSE explicitly. Those are live networks whose DNS
   *     behaviour is the operator's call to change, per household, so they must keep OFF — which is
   *     exactly why the new-household default moved into code instead of into the column default,
   *     and why the backfill no longer depends on the column default's value at all.
   *   - `HouseholdSettings.blockEncryptedDns` / `UpdateHouseholdSettingsRequest.blockEncryptedDns`
   *     field defaults — JSON-decoding fallbacks for a peer that omits the key.
   *   - `PolicySnapshot.blockEncryptedDns` — a wire-decoding default for an agent that never
   *     received the additive field (AGENTS.md back-compat); changing it would be a wire-contract
   *     violation.
   */
  val DefaultBlockEncryptedDns: Boolean = true

  val DefaultPresenceContinuationSeconds: Int = 120
  // #2077: defaults mirror migration V63 (causally validated on prod — see
  // docs/design/idle-traffic-discrimination.md).
  val DefaultAmbientIsolationMaxHosts: Int    = 2
  val DefaultAmbientMinIsolatedDays: Int      = 3
  val DefaultAmbientLearningWindowDays: Int   = 14
}

case class UpdateHouseholdSettingsRequest(
    dailyResetTime: LocalTime,
    dailyResetTz: ZoneId,
    heartbeatFilter: HeartbeatFilter,
    unmanagedMacPolicy: UnmanagedMacPolicy = UnmanagedMacPolicy.Default,
    presenceContinuationSeconds: Int = HouseholdSettings.DefaultPresenceContinuationSeconds,
    // #1912: additive — an older SPA build that PUTs without this field decodes
    // to the default (false), so a full-replace PUT never silently clears it for
    // a peer that doesn't know about it yet.
    // #2643 left this `false` on purpose. It is a decoding default for an
    // inbound full-replace PUT, not the new-household default (that is
    // `HouseholdSettings.DefaultBlockEncryptedDns`). Flipping it here would mean
    // an older SPA's PUT silently TURNS ON relay/DoH blocking for an existing
    // household that never asked for it — the "never flip a live network behind
    // the operator's back" rule #2643 is scoped by.
    // The hazard does cut both ways, and the other direction is now live: a
    // full-replace PUT that omits the key silently turns a NEW household's ON
    // default back OFF. Nothing exercises it today — the SPA client exposes only
    // GET and PATCH for this resource (`web/src/api/client.ts`), and PATCH
    // preserves absent fields — so `false` remains the right decode default.
    // `Option[Boolean]` with preserve-on-absence is what removes the asymmetry
    // for good, and it is the shape to reach for if a PUT caller ever appears.
    blockEncryptedDns: Boolean = false,
    // #2077: additive for the same reason. NOTE the ambient THRESHOLDS default to
    // the V63 values (not "preserve stored") on a full-replace PUT, matching the
    // presenceContinuationSeconds precedent above; the SPA autosave path uses
    // PATCH, which preserves absent fields.
    ambientGateEnabled: Boolean = false,
    ambientIsolationMaxHosts: Int = HouseholdSettings.DefaultAmbientIsolationMaxHosts,
    ambientMinIsolatedDays: Int = HouseholdSettings.DefaultAmbientMinIsolatedDays,
    ambientLearningWindowDays: Int = HouseholdSettings.DefaultAmbientLearningWindowDays,
    // #578: additive — an older SPA build that PUTs without this field decodes to
    // the default (None). NOTE a full-replace PUT that omits notifyEmail therefore
    // CLEARS it; the SPA autosave path uses PATCH, which preserves absent fields
    // (and supports explicit null to clear). Mirrors the blockEncryptedDns precedent.
    notifyEmail: Option[String] = None,
) derives JsonCodec

/**
 * #2382: the server-level per-household "disable enforcement" escape hatch, as seen by the SPA.
 * When `enforcementDisabled` is true the household's router snapshot goes fully permissive (all
 * blocking OFF) — the easy dashboard equivalent of the router-level offline hatch (#2381). Shared
 * by `GET /api/household/enforcement` (read) and `PUT /api/household/enforcement` (admin write).
 */
case class EnforcementStatusResponse(enforcementDisabled: Boolean) derives JsonCodec
case class SetEnforcementRequest(enforcementDisabled: Boolean) derives JsonCodec

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
 * #2077: response body for `GET /api/presence/ambient-hosts`. The isolation-learned ambient-host
 * baseline with per-host day counts — the tune-before-enable explain surface (the operator inspects
 * the would-be ambient set, then flips `ambientGateEnabled`). `hosts` includes below-threshold
 * candidates (`ambient=false`) so threshold changes can be previewed.
 */
case class AmbientHostEntry(
    host: String,
    isolatedDays: Int,
    lastIsolatedDay: String,
    isolatedSpanCount: Int,
    ambient: Boolean,
) derives JsonCodec

case class AmbientHostsResponse(
    gateEnabled: Boolean,
    isolationMaxHosts: Int,
    minIsolatedDays: Int,
    learningWindowDays: Int,
    hosts: List[AmbientHostEntry],
) derives JsonCodec

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

// #1837: `perDevice` (per-device 24h volume leaderboard) was dropped from the
// dashboard in #1836 — that data belongs on /devices + /profiles, not a security
// panel. The field and its now-dead raw 24h scan are removed; the 24h aggregations
// below ride the connection_events rollup (see ConnectionEventRepoLive.stats).
case class DashboardStats(
    totalToday: Int,
    blockedToday: Int,
    totalHour: Int,
    blockedHour: Int,
    topBlocked: List[DomainCount],
) derives JsonCodec

case class DomainCount(host: HostId, count: Int) derives JsonCodec

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
    // #1627: `None` means "no per-app limit configured" — distinct from a
    // 0-minute cap and from "limit set but not yet hit". When the limit is
    // None there is no "remaining" quantity, so `remainingMins` follows.
    limitMins: Option[Int],
    usedMins: Int,
    remainingMins: Option[Int],
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

// #1740 — usage retention horizons (days). Authoritative values live in
// api/src/usage/RetentionSweepJob.scala; this shape rides on
// `UsageConfig.horizons` (GET /api/usage/config), which the SPA reads at
// boot so the bucket granularity gate (`web/src/components/usage/retentionGating.ts`)
// can't silently drift from the sweep job. Defaults in the SPA are only
// used as an offline fallback when the fetch fails.
case class RetentionHorizons(
    rawDays: Int,
    hourlyDays: Int,
    dailyDays: Int,
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
    // #2083: enrollment tokens are single-use (enrollmentTokenHash cleared on
    // completion) but previously never expired. NULL once enrollment
    // completes (the hash is cleared too, so it can no longer be looked up
    // anyway) or for pre-#2083 rows that had no pending enrollment.
    enrollmentExpiresAt: Option[String] = None,
    // #2106 (multi-tenant, epic #622): the household this router belongs to,
    // stamped at enrollment from the creating admin's household (V65). This is
    // a SERVER-SIDE data-scoping key resolved from the router token — it never
    // appears on the router wire (design invariant 3). Defaults to the
    // single-install backfill household so pre-multi-tenant rows and non-DB
    // constructions stay tenant-safe.
    householdId: HouseholdId = HouseholdId.Default,
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
    // #2572: the recording router, and through `routers.household_id` the only tenancy key this
    // table has. Option because V87 added the column nullable with no backfill — pre-existing rows
    // have no derivable router (`mac` stopped identifying a household when V74 dropped
    // `devices_mac_key`, and inventing one would be a confabulation). So None means exactly
    // "written before V87", never "unknown scope": `BlockEventInsert.routerId` is REQUIRED, so
    // every row written from this image carries it. Note this is deliberately NOT the shape of
    // `ConnectionEvent.routerId` below, which is a bare `RouterId` because `connection_events`
    // (V4) declared the column NOT NULL from the start and so has no unattributable rows.
    routerId: Option[RouterId],
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
    // #2025: the REAL activity envelope of this bucket — the wall-clock (RFC3339,
    // UTC, same format as periodStart/periodEnd) of the first and last sample
    // tick the agent observed byte growth on for this (mac, dst_ip), distinct
    // from the [periodStart, periodEnd] FLUSH window the envelope sits inside.
    // Additive + optional: pre-#2025 agents omit them and the API stores NULL on
    // traffic_reports.active_start / active_end, with the server falling back to
    // the flush window in Presence.spanOf. Both must be present to be used.
    activeStart: Option[String] = None,
    activeEnd: Option[String] = None,
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
    // What this promises (#2619): the newest policy version the server has SENT
    // to this router, on whichever transport sent it — the REST poll stamps it
    // when it SERVES the snapshot, the websocket push stamps it when the frame
    // has been handed to the channel. It does NOT mean the router has applied
    // that policy, and never did on either transport. The applied etag is the
    // one in the router's own on-disk snapshot (what `scripts/e2e/lib/wait.py`
    // reads for the stronger barrier). NULL until the router first receives
    // policy.
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
// #335: usage fields are populated for any enrolled MAC (blocked or not) so a
// restricted kid can see *why* their time is gone, not just that it is. None on
// an unknown MAC (no enrollment leak) and on an enrolled MAC with no profile
// assigned. `usedMinutes` is the active screen-minutes today; `dailyLimitMinutes`
// is the configured cap before extensions; `extensionMinutes` is today's granted
// extension; `remainingMinutes` is `cap + extensions - used`, clamped at 0.
// Sourced from the canonical TimeStatusService.todaysState so the block page
// cannot drift from the snapshot's "blocked because TimeLimit" decision.
case class BlockedInfoResponse(
    blocked: Boolean,
    reasonClass: Option[String],
    categoryName: Option[String],
    profileName: Option[String],
    usedMinutes: Option[Int] = None,
    dailyLimitMinutes: Option[Int] = None,
    extensionMinutes: Option[Int] = None,
    remainingMinutes: Option[Int] = None,
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
    // #1912 / #1909: network-wide "block encrypted DNS & relays" flag, resolved
    // from `household_settings.block_encrypted_dns`. Additive top-level boolean,
    // defaults false (so an older snapshot JSON predating it decodes to off, and
    // an un-updated agent ignoring it is also off). It carries ONLY the boolean:
    // the curated relay/DoH host + resolver-IP lists are baked in the agent
    // (#1911), not shipped here — the router enforces NXDOMAIN/nftables drops
    // off its own lists when this is true. This is the snapshot's one deliberate,
    // narrowly-scoped exception to "DNS always resolves" (Architectural Truth #1),
    // and it is bypass-disablement signaling, which is itself enforcement-enabling.
    blockEncryptedDns: Boolean = false,
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

// #1743 + #1740: client-facing usage config the SPA reads at boot. Carries
// the bucket → grain mapping (so `retentionGating.ts` no longer hand-mirrors
// `BucketPolicy.grainForBucket`) and the retention horizons (so it no longer
// hand-mirrors `RetentionSweepJob`'s day counts either). One endpoint covers
// both SSOT folds.
case class UsageConfig(
    bucketTiers: Map[String, String],
    horizons: RetentionHorizons,
) derives JsonCodec

// #962: typed reason for block_events / connection_events. Stored as JSONB
// in Postgres tagged on `kind`. The router/PolicyService emit a free-form
// wire string today; the API converts on ingest via `BlockReason.fromWire`.
// MacBlockReason cases are subtypes so a snapshot reason can be lifted into
// the event-table form without re-encoding (their wire form is unchanged in
// the snapshot JSON — only the event-DB encoding becomes kind-tagged).
object BlockReason {
  case object Allow                         extends BlockReason {
    val jsonKind = "allow"; val wireKind = "allow"
  }
  case object Blocked                       extends BlockReason {
    val jsonKind = "blocked"; val wireKind = "blocked"
  }
  case object ExtraAllowed                  extends BlockReason {
    val jsonKind = "extraAllowed"; val wireKind = "extra_allowed"
  }
  case object ExtraBlocked                  extends BlockReason {
    val jsonKind = "extraBlocked"; val wireKind = "extra_blocked"
  }
  case object NoProfile                     extends BlockReason {
    val jsonKind = "noProfile"; val wireKind = "no_profile"
  }
  case class Category(slug: BlocklistId)    extends BlockReason {
    val jsonKind = "category"; val wireKind = "category"
  }
  case class AppTimeLimit(label: String)    extends BlockReason {
    // #1518 rename; the legacy `siteTimeLimit` jsonKind is still ACCEPTED by
    // the decoder below for V40-migrated DB rows, but never emitted.
    val jsonKind = "appTimeLimit"; val wireKind = "app_time_limit"
  }
  case class AppBlocked(appId: String)      extends BlockReason {
    val jsonKind = "appBlocked"; val wireKind = "app"
  }
  // #1645: labels a per-host extraBlocked drop with the matched eb_<host>
  // rule so triage can see *which* extraBlocked entry fired. Wire form is
  // `host:<host>`; the legacy bare `host` alias below still parses as the
  // anonymous `ExtraBlocked` for pre-rollout routers.
  case class ExtraBlockedBy(host: Hostname) extends BlockReason {
    // `wireKind` here is the prefix-form sentinel (trailing `:` implied) —
    // same convention as `Category("category")`, `AppTimeLimit("app_time_limit")`,
    // `AppBlocked("app")`. The actual wire string is assembled in `asWire`
    // (`"host:" + h.value`) and parsed via the `startsWith("host:")` arm in
    // `fromWire`. The bare `"host"` string still maps to anonymous
    // `ExtraBlocked` via `LegacyWireAliases`; the overlap is harmless because
    // `byWireKind` is built only from `NullaryCases`, which does NOT include
    // this parameterised case.
    val jsonKind = "extraBlockedBy"; val wireKind = "host"
  }
  case class Unknown(raw: String)           extends BlockReason {
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
      else if (s.startsWith("host:"))
        // #1645: `host:<host>` names the matched eb_<host> rule. Bare `host`
        // (no colon) is still handled by the LegacyWireAliases lookup above
        // and resolves to the anonymous ExtraBlocked.
        Hostname
          .parse(s.stripPrefix("host:"))
          .map(ExtraBlockedBy(_))
          .getOrElse(Unknown(s))
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
    case ExtraBlockedBy(h)   => s"host:${h.value}" // #1645
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
    case ExtraBlockedBy(h)   =>
      Json.Obj("kind" -> Json.Str("extraBlockedBy"), "host" -> Json.Str(h.value))
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
              case "extraBlockedBy"                 =>
                // #1645
                field("host").flatMap(s =>
                  Hostname.parse(s).left.map(e => s"invalid host: $e").map(ExtraBlockedBy(_)),
                )
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
