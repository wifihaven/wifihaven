// Mirrors wifihaven.shared Models.scala

// #385: three failover modes. Wire form is lower-kebab to match
// shared/src/Models.scala FailureMode.asString.
export type FailureMode = 'block-all' | 'allow-all' | 'last-known-good'

// #751: per-profile knob — `sum` adds per-device totals (siblings on the same
// profile double-count when both are active in the same bucket); `dedup`
// unions the per-device active-bucket sets so overlap counts once. Default
// is `sum` (preserves pre-#751 semantics).
export type CrossDeviceOverlapMode = 'sum' | 'dedup'

export interface Profile {
  id: number
  name: string
  blockedCategories: string[]
  paused: boolean
  failureMode: FailureMode
  crossDeviceOverlapMode: CrossDeviceOverlapMode
}

export interface Schedule {
  id: number
  profileId: number
  name: string
  days: string[]
  startLocal: string  // "HH:mm" wall-clock time in `tz`
  endLocal: string    // "HH:mm" wall-clock time in `tz`
  tz: string          // IANA timezone, e.g. "America/Los_Angeles"
}

// #714 — server-side heartbeat filter for device/profile screen-time totals.
// Rows classified as heartbeats are excluded from rollups; the filter is
// household-wide.
export interface HeartbeatFilter {
  enabled: boolean
  bytesThreshold: number          // bytes/min floor (rows below are heartbeats)
  heartbeatHostPatterns: string[] // #788 FQDN allowlist; *.foo.com / foo.com semantics
}

// #961 — how the household treats MACs that have appeared on the network
// but are not yet enrolled into any profile. Router-side enforcement of
// `block` is deferred behind Gate 2 (#654); for v1 the field is persisted
// + surfaced in the SPA only.
export interface UnmanagedMacPolicy {
  policy: 'block' | 'allow'
  blockPage: boolean
}

export interface HouseholdSettings {
  dailyResetTime: string  // "HH:mm" wall-clock time in `dailyResetTz`
  dailyResetTz: string    // IANA timezone
  heartbeatFilter: HeartbeatFilter
  unmanagedMacPolicy: UnmanagedMacPolicy
}

export interface UpdateHouseholdSettingsRequest {
  dailyResetTime: string
  dailyResetTz: string
  heartbeatFilter: HeartbeatFilter
  unmanagedMacPolicy: UnmanagedMacPolicy
}

export interface TimeLimit {
  id: number
  profileId: number
  dailyMinutes: number
}

export interface SiteTimeLimit {
  id: number
  profileId: number
  domainPattern: string
  dailyMinutes: number
  label: string
  exemptFromDaily: boolean
}

export interface ProfileDetail {
  profile: Profile
  schedules: Schedule[]
  timeLimit: TimeLimit | null
}

export interface Device {
  id: number
  mac: string
  name: string
  profileId: number | null
  profileName: string | null
  lastSeenIp: string | null
  lastSeenAt: string | null
}

// Generic admin-action feed (formerly DeviceAlert, #711). The schema (V34)
// supports two kinds — `new_device` and `access_request`; #960 is the writer
// for access_request, so today every row in the feed has kind='new_device'.
export type AlertKind = 'new_device' | 'access_request'
export type AlertStatus = 'pending' | 'approved' | 'denied'
export type AccessRequestKind = 'extension' | 'exemption' | 'unpause'

export interface Alert {
  id: number
  kind: AlertKind
  status: AlertStatus
  mac: string
  deviceName: string | null
  profileId: number | null
  profileName: string | null
  // access_request-only payload — null on new_device rows.
  host: string | null
  requestKind: AccessRequestKind | null
  note: string | null
  grantedMinutes: number | null
  createdAt: string
  decidedAt: string | null
  decidedBy: string | null
}

/** Admin POST body for /approve. Empty today — new_device approval is just a
 *  status transition. #960 extends this with grant-specific fields. */
/** Block-page POST shape (no auth) — kid asks for access. */
export interface CreateAccessRequest {
  mac: string
  host: string
  kind: AccessRequestKind
  note?: string
}

/** Admin POST body for /approve. `minutes` is consumed by extension grants;
 *  ignored for the other kinds. */
export interface ApproveAlertRequest {
  minutes?: number
}

// Tagged-union host identifier (#391). Wire shape carried by every endpoint
// that surfaces a "what host did the device contact" field. FQDN is a
// resolved hostname; ipv4/ipv6 are raw IP literals emitted when DNS
// attribution missed (DoH, Apple Private Relay, direct-IP).
export type HostId =
  | { type: 'fqdn'; value: string }
  | { type: 'ipv4'; value: string }
  | { type: 'ipv6'; value: string }

export function hostDisplay(h: HostId): string {
  return h.value
}

export function hostIsFqdn(h: HostId): boolean {
  return h.type === 'fqdn'
}

// #962: typed BlockReason mirroring shared.BlockReason. Kind-tagged JSON; the
// API persists this shape in JSONB on block_events/connection_events and
// returns it verbatim on /api/logs. Render via blockReasonText (lib/blockReason).
export type BlockReason =
  | { kind: 'allow' }
  | { kind: 'blocked' }
  | { kind: 'extraAllowed' }
  | { kind: 'extraBlocked' }
  | { kind: 'noProfile' }
  | { kind: 'unmanaged' }
  | { kind: 'paused' }
  | { kind: 'schedule' }
  | { kind: 'timeLimit' }
  | { kind: 'manual' }
  | { kind: 'category'; slug: string }
  | { kind: 'siteTimeLimit'; label: string }
  | { kind: 'appBlocked'; appId: string }
  | { kind: 'unknown'; raw: string }

export interface QueryLog {
  id: number
  mac: string | null
  deviceName: string | null
  profileId: number | null
  profileName: string | null
  host: HostId
  qtype: number
  blocked: boolean
  reason: BlockReason
  location: string | null
  ts: string
}

// #847 + #846: aggregated connection-events row, with multi-column groupBy.
// `groups` keyed by "domain" | "device" | "profile" | "app" depending on the
// request. When `app` is grouped the slug lives in `groups.app` and the
// display name + icon are surfaced as `appName` / `appIcon` (#769).
export interface ConnectionEventAggRow {
  groups: Record<string, string>
  windowStart: string
  countSucceeded: number
  countBlocked: number
  lastSeen: string
  topDevice: string | null
  distinctDevices?: number
  distinctProfiles?: number
  distinctDomains?: number
  distinctApps?: number
  soleDevice?: string | null
  soleProfile?: string | null
  soleDomain?: string | null
  soleApp?: string | null
  appId?: number | null
  appName?: string | null
  appIcon?: string | null
}


export interface DashboardStats {
  totalToday: number
  blockedToday: number
  totalHour: number
  blockedHour: number
  topBlocked: DomainCount[]
  perDevice: DeviceStats[]
}

export interface DashboardNowHost {
  host: HostId
  activeSeconds: number
}

export interface DashboardNowActivity {
  topHost: HostId
  // zio-json omits None — field is absent on the wire (treat absent as no run).
  minutes?: number | null
}

export interface DashboardNowDevice {
  id: number
  name: string
  mac: string
  lastSeenSeconds: number
  topHosts: DashboardNowHost[]
  // zio-json omits None — field is absent on the wire (treat absent as idle).
  nowActivity?: DashboardNowActivity | null
}

export interface DashboardNowProfile {
  id: number
  name: string
  paused: boolean
  activeDevices: DashboardNowDevice[]
}

export interface DashboardNow {
  asOf: string
  profiles: DashboardNowProfile[]
}

export interface DomainCount {
  host: HostId
  count: number
}

export interface DeviceStats {
  mac: string
  deviceName: string
  total: number
  blocked: number
}

export interface SiteUsage {
  label: string
  domainPattern: string
  limitMins: number
  usedMins: number
  remainingMins: number
}

export interface DeviceTimeStatus {
  deviceMac: string
  deviceName: string
  date: string
  profileName: string
  profileId?: number | null
  dailyLimitMins?: number | null
  usedMins: number
  extensionMins: number
  remainingMins?: number | null
  siteUsage: SiteUsage[]
}

export interface DeviceUsageSummary {
  deviceMac: string
  deviceName: string
  usedMins: number
}

// #715: per-host time-on-site has two parallel numbers.
//   - `usedMins` is bucket-presence: every host the device touched in a 5-min
//     bucket is credited with that bucket's full duration. Sums across hosts
//     can wildly exceed wall-clock time when a device polls many endpoints.
//   - `proportionalMins` is byte-share-weighted within each 5-min bucket — a
//     fair "wall-clock attention" number. Summing across hosts within a mac ≈
//     the device's wall-clock minutes. UI defaults to displaying this.
//
// The daily-cap math collapses each bucket once per device and reads neither
// field; both are additive surface area.
export interface HostUsage {
  host: HostId
  usedMins: number
  proportionalMins: number
}

// #777 — collapsed-accordion payload: just the headline numbers, no per-device /
// per-host / per-bucket arrays. The server computes the whole list in a single
// batched presence query, so page load is `1 summary + N on-demand` instead of
// `N rollups`.
export interface ProfileTimeSummary {
  profileId: number
  profileName: string
  date: string
  dailyLimitMins?: number | null
  usedMins: number
  extensionMins: number
  remainingMins?: number | null
}

export interface ProfileTimeSummaryWeek {
  profileId: number
  profileName: string
  from: string
  to: string
  dailyLimitMins?: number | null
  totalMins: number
}

export interface ProfileTimeStatus {
  profileId: number
  profileName: string
  date: string
  dailyLimitMins?: number | null
  usedMins: number
  extensionMins: number
  remainingMins?: number | null
  siteUsage: SiteUsage[]
  devices: DeviceUsageSummary[]
  hostUsage: HostUsage[]
}

// #1061 — per-app time-used breakdown for one profile over a date window.
// `appId = null` is the synthetic "Other" bucket: hosts not in any app_hosts
// membership. `proportionalSeconds` is the wall-clock-attention number (#715),
// `presenceSeconds` is bucket-deduped at the app level. The drill-down `hosts`
// list reuses HostUsage so the SPA renders rows with the same Attention/Seen
// formatter.
export interface ProfileAppUsage {
  appId: number | null
  appName: string
  appIcon: string | null
  appIconType?: IconType | null
  proportionalSeconds: number
  presenceSeconds: number
  hosts: HostUsage[]
}

export interface ProfileUsageByApp {
  profileId: number
  profileName: string
  from: string
  to: string
  apps: ProfileAppUsage[]
}

// #716 / #721 — per-device hourly usage timeline. `totalMins` is the device's
// bucket-deduplicated wall-clock minutes for the hour (matches the daily cap).
// `perHost.mins + otherMins == totalMins` — per-host minutes are proportionally
// allocated within each 5-min bucket (sketch of #715 proposal 2).
export interface UsageHostTotal {
  host: HostId
  dayMins: number
}

export interface UsageBucketHost {
  host: HostId
  mins: number
}

export interface UsageBucket {
  hour: number
  totalMins: number
  perHost: UsageBucketHost[]
  otherMins: number
}

// #722 — profile-mode adds parallel per-device aggregates so the SPA can
// toggle stack-by-device vs stack-by-host on the same payload.
export interface UsageDeviceTotal {
  deviceMac: string
  deviceName: string
  dayMins: number
}

export interface UsageBucketDevice {
  deviceMac: string
  deviceName: string
  mins: number
}

export interface UsageDeviceBucket {
  hour: number
  totalMins: number
  perDevice: UsageBucketDevice[]
  otherMins: number
}

export interface UsageSeriesResponse {
  // device-mode fields (mac=)
  deviceMac?: string
  deviceName?: string
  // profile-mode fields (profileId=)
  profileId?: number
  profileName?: string
  date: string
  tz: string
  topHosts: UsageHostTotal[]
  buckets: UsageBucket[]
  topDevices?: UsageDeviceTotal[]
  bucketsByDevice?: UsageDeviceBucket[]
}

// #846 — Traffic Usage page. Wire-distinct from UsageSeriesResponse: that one
// drives the screen-time minutes chart; this one drives raw + aggregated bytes
// inspection. 1m bucket and apex/app groupBy are reserved (router cadence /
// PSL / apps track) — server returns 400 with a typed `error` code.
export type TrafficUsageBucket = 'raw' | '1m' | '10m' | '1h' | '12h' | '1d' | '1w'
// #846: groupBy is composable. Apex is deferred to #856 (needs PSL). #769
// turned `app` on — it now resolves to a server-side join through
// `app_hosts`. The empty/synthetic bucket is keyed `__other__`.
export type TrafficUsageGroupBy = 'domain' | 'device' | 'profile' | 'apex' | 'app'

export interface TrafficUsageRawRow {
  mac: string
  deviceName?: string
  profileId?: number
  profileName?: string
  host: HostId
  bytesIn: number
  bytesOut: number
  activeSeconds: number
  periodStart: string
  periodEnd: string
}

export interface TrafficUsageAggregateRow {
  // Keyed by the column codes in the request's groupBy set ("domain" |
  // "device" | "profile" | "app"). For columns NOT in the set, the SPA shows
  // the distinct-count from `distinct*` below (drill-down deferred to #859).
  groups: Record<string, string>
  windowStart: string
  windowEnd: string
  totalBytesIn: number
  totalBytesOut: number
  totalSeconds: number
  distinctDevices?: number
  distinctProfiles?: number
  distinctDomains?: number
  distinctApps?: number
  // Populated only when the corresponding `distinct*` is 1 AND the column is
  // not in `groupBy` — lets the SPA render the value in place of "1".
  soleDevice?: string | null
  soleProfile?: string | null
  soleDomain?: string | null
  soleApp?: string | null
  // #769: present when `app` is in groupBy. The slug lives in `groups.app`;
  // these carry the display metadata. `__other__` rows emit appName="Other".
  appId?: number | null
  appName?: string | null
  appIcon?: string | null
}

export interface TrafficUsageResponse {
  bucket: TrafficUsageBucket
  groupBy?: TrafficUsageGroupBy[]
  from: string
  to: string
  tz: string
  rawRows: TrafficUsageRawRow[]
  aggregateRows: TrafficUsageAggregateRow[]
  rawRowLimit?: number
  rawRowsTruncated?: boolean
  // #862: opaque cursor for the next (older) page. null/undefined = end of stream.
  nextCursor?: string | null
}

// #862: paged envelopes for /api/logs and /api/connection-events/series.
export interface QueryLogPage {
  rows: QueryLog[]
  nextCursor?: string | null
}

export interface ConnectionEventSeriesPage {
  rows: ConnectionEventAggRow[]
  nextCursor?: string | null
}

// #794: server returns hourly UTC buckets aligned to a caller-specified `bucketOffsetMin`
// (one of 0/15/30/45 — minute past the UTC hour where the grid starts). The SPA picks the
// offset so each bucket falls fully within one local-tz day, then groups by local day for the
// chart. `bucketStart` is an ISO-8601 instant. Empty buckets are omitted — chart code fills gaps.
export interface ProfileTimeBucket {
  bucketStart: string
  usedMins: number
}

export interface ProfileTimeStatusWeek {
  profileId: number
  profileName: string
  from: string
  to: string
  dailyLimitMins?: number | null
  totalMins: number
  perBucket: ProfileTimeBucket[]
  devices: DeviceUsageSummary[]
  hostUsage: HostUsage[]
}

export interface DeviceTimeStatusWeek {
  deviceMac: string
  deviceName: string
  from: string
  to: string
  profileName: string
  profileId?: number | null
  dailyLimitMins?: number | null
  totalMins: number
  perBucket: ProfileTimeBucket[]
  hostUsage: HostUsage[]
}

export interface TimeExtension {
  id: number
  profileId: number | null
  deviceMac: string | null
  date: string
  extraMinutes: number
  grantedBy: string
  note: string | null
  createdAt: string
}

export type UserRole = 'admin' | 'adult' | 'child'

export interface User {
  id: number
  username: string
  role: UserRole
  profileIds: number[]
}

export interface LoginResponse {
  token: string
  role: UserRole
  username: string
  // #586: true when the server has the must_change_password flag set.
  // The web redirects to /account immediately after login when true.
  mustChangePassword?: boolean
}

export interface MeResponse {
  username: string
  role: UserRole
  profileIds: number[]
}

export interface CreateUserRequest {
  username: string
  password: string
  role: UserRole
  profileIds: number[]
}

export interface SetUserProfilesRequest {
  profileIds: number[]
}

// ── Request types ──────────────────────────────────────────────────────────

export interface ScheduleRequest {
  name: string
  days: string[]
  startLocal: string
  endLocal: string
  tz: string
}

export interface UpsertProfileRequest {
  name: string
  blockedCategories: string[]
  paused: boolean
  schedules: ScheduleRequest[]
  timeLimit: number | null
  failureMode: FailureMode
  // #751: omit to preserve existing value on update; defaults to 'sum' on create.
  crossDeviceOverlapMode?: CrossDeviceOverlapMode
}

export interface UpsertDeviceRequest {
  mac: string
  name: string
  profileId: number
}

// #996: field-scoped partial update for devices. `name` may be set (not cleared);
// `profileId` may be set or cleared (null detaches the device from any profile).
export interface PatchDeviceRequest {
  name?: string
  profileId?: number | null
}

export interface GrantExtensionRequest {
  profileId: number
  extraMinutes: number
  note: string | null
}

// ── Routers ────────────────────────────────────────────────────────────────

export interface RouterSummary {
  id: string
  name: string
  enrolled: boolean
  lastSeenAt: string | null
  lastEtag: string | null
  createdAt: string
  // #771: package version reported by the agent on its most recent
  // policy fetch. NULL for pre-#771 routers and routers that have not
  // polled yet.
  agentVersion: string | null
}

export interface CreateRouterRequest {
  name: string
}

export interface CreateRouterResponse {
  routerId: string
  name: string
  enrollmentToken: string
}

// ── Apps (#761/#762/#765) ──────────────────────────────────────────────────

export type AppMode = 'blocked' | 'allowed' | 'time_limited'

// #1004 — how to render the `icon` string. `emoji` = literal text; `url` =
// remote image (HTTPS); `png_base64` = inline data URL (sans the prefix).
// Optional on the wire for older fixtures; renderers default to 'emoji'.
export type IconType = 'emoji' | 'url' | 'png_base64'

export interface App {
  id: number
  name: string
  slug: string
  templateId: number | null
  icon: string | null
  iconType?: IconType
  createdAt: string
}

export interface AppPolicyAssignment {
  id: number
  appId: number
  profileId: number
  mode: AppMode
  dailyMinutes: number | null
  exemptFromDaily: boolean
}

export interface AppDetail {
  app: App
  hosts: string[]
  assignments: AppPolicyAssignment[]
}

export interface CreateAppRequest {
  name: string
  slug?: string
  icon?: string | null
  iconType?: IconType
  templateId?: number | null
  hosts: string[]
}

export interface UpdateAppRequest {
  name: string
  icon?: string | null
  iconType?: IconType
  templateId?: number | null
}

export interface SetAppHostsRequest {
  hosts: string[]
}

// #766 — recently-visited apex picker payload. One row per PSL-collapsed
// registered domain for the device, sorted by total bytes desc. `subdomains`
// is the set of observed FQDNs underneath the apex (empty when the apex
// itself was the only hit). Bare-IP rows are excluded server-side.
export interface RecentApex {
  apex: string
  bytes: number
  hits: number
  subdomains: string[]
}

export interface RecentApexesResponse {
  deviceMac: string
  deviceName: string
  windowDays: number
  items: RecentApex[]
}

export interface UpsertAppAssignmentRequest {
  mode: AppMode
  dailyMinutes?: number | null
  exemptFromDaily?: boolean
}

// #958: BlocklistSummary as returned by GET /api/blocklists.
export interface BlocklistSummary {
  id: string
  name: string
  description?: string | null
  bundled: boolean
  source?: string | null
  hostCount: number
  lastBuiltAt?: string | null
}

export interface BlocklistHosts {
  id: string
  hosts: string[]
}

// #959: kid-side block-page payload from GET /api/blocked?mac=&host=.
// `reasonClass` is one of: "paused" | "schedule" | "time_limit" |
// "site_time_limit" | "category" | "extra_blocked". `blocked: false`
// means the device is not blocked for this host (or is unenrolled).
export interface BlockedInfoResponse {
  blocked: boolean
  reasonClass?: string | null
  categoryName?: string | null
  profileName?: string | null
}
