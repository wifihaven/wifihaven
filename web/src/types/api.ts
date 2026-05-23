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
  extraBlocked: string[]
  extraAllowed: string[]
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

export interface HouseholdSettings {
  dailyResetTime: string  // "HH:mm" wall-clock time in `dailyResetTz`
  dailyResetTz: string    // IANA timezone
  heartbeatFilter: HeartbeatFilter
}

export interface UpdateHouseholdSettingsRequest {
  dailyResetTime: string
  dailyResetTz: string
  heartbeatFilter: HeartbeatFilter
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
  siteTimeLimits: SiteTimeLimit[]
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

// #711: notification raised when the agent auto-creates a Device row for a
// previously-unseen MAC. `dismissedAt` is null while pending.
export interface DeviceAlert {
  id: number
  mac: string
  deviceName: string
  profileId: number | null
  profileName: string | null
  firstSeenAt: string
  dismissedAt: string | null
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

export interface QueryLog {
  id: number
  mac: string | null
  deviceName: string | null
  profileId: number | null
  profileName: string | null
  host: HostId
  qtype: number
  blocked: boolean
  reason: string
  location: string | null
  ts: string
}

// #847 + #846: aggregated connection-events row, with multi-column groupBy.
// `groups` keyed by "domain" | "device" | "profile" depending on the request.
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
  soleDevice?: string | null
  soleProfile?: string | null
  soleDomain?: string | null
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
  minutes: number | null
}

export interface DashboardNowDevice {
  id: number
  name: string
  mac: string
  lastSeenSeconds: number
  topHosts: DashboardNowHost[]
  nowActivity: DashboardNowActivity | null
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
// #846: groupBy is composable. Apex is deferred to #856 (needs PSL), App to
// #857 (needs apps track) — both still rejected server-side with typed errors.
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
  // "device" | "profile"). For columns NOT in the set, the SPA shows the
  // distinct-count from `distinct*` below (drill-down deferred to #859).
  groups: Record<string, string>
  windowStart: string
  windowEnd: string
  totalBytesIn: number
  totalBytesOut: number
  totalSeconds: number
  distinctDevices?: number
  distinctProfiles?: number
  distinctDomains?: number
  // Populated only when the corresponding `distinct*` is 1 AND the column is
  // not in `groupBy` — lets the SPA render the value in place of "1".
  soleDevice?: string | null
  soleProfile?: string | null
  soleDomain?: string | null
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

export interface SiteTimeLimitRequest {
  domainPattern: string
  dailyMinutes: number
  label: string
  exemptFromDaily: boolean
}

export interface UpsertProfileRequest {
  name: string
  blockedCategories: string[]
  extraBlocked: string[]
  extraAllowed: string[]
  paused: boolean
  schedules: ScheduleRequest[]
  timeLimit: number | null
  siteTimeLimits: SiteTimeLimitRequest[]
  failureMode: FailureMode
  // #751: omit to preserve existing value on update; defaults to 'sum' on create.
  crossDeviceOverlapMode?: CrossDeviceOverlapMode
}

export interface UpsertDeviceRequest {
  mac: string
  name: string
  profileId: number
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
}

export interface CreateRouterRequest {
  name: string
}

export interface CreateRouterResponse {
  routerId: string
  name: string
  enrollmentToken: string
}
