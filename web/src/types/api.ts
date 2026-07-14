// Mirrors wifihaven.shared Models.scala

// #385: three failover modes. Wire form is lower-kebab to match
// shared/src/Models.scala FailureMode.asString.
export type FailureMode = 'block-all' | 'allow-all' | 'last-known-good'

// #751: per-profile knob — `sum` adds per-device totals (siblings on the same
// profile double-count when both are active in the same bucket); `dedup`
// unions the per-device active-bucket sets so overlap counts once. Default
// is `sum` (preserves pre-#751 semantics).
export type CrossDeviceOverlapMode = 'sum' | 'dedup'

// #1418: pause has two modes. `soft` (default) is today's behavior — a paused
// profile still spares its allowlisted hosts (an allowed app + the global infra
// allowlist stay reachable). `hard` is a true off-switch: even those go dark,
// keeping only the block-page / admin-UI hosts. Wire form matches
// shared/src/Models.scala PauseMode.asString.
export type PauseMode = 'soft' | 'hard'

export interface Profile {
  id: number
  name: string
  blockedCategories: string[]
  paused: boolean
  failureMode: FailureMode
  crossDeviceOverlapMode: CrossDeviceOverlapMode
  pauseMode: PauseMode
  // #1320 / #1308: default-deny baseline. When true the profile blocks all
  // traffic except its extraAllowed hosts/apps + the household global allowlist
  // (the inverse of the allow-by-default + blocklists model). Resolved
  // server-side into the per-MAC `blocked = true`; the router never sees it.
  defaultDeny: boolean
  // #1771/#1773: marks the single household-wide sentinel profile. Hidden from
  // GET /api/profiles; fetched separately via GET /api/profiles/global. Edited
  // through the same per-profile editor; subsections that can't apply
  // household-wide (devices, schedules, time limits, pause, users) are hidden.
  // Optional + defaulted false so older API responses decode unchanged.
  isGlobal?: boolean
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

// #1069 — household-scoped reusable named schedule. One named schedule owns
// one or more windows; anything time-bound (profiles today, per-app rules
// #1380, blocklists #1067 next) references it by id. This is the SPA mirror of
// the API's `NamedSchedule` — it never reaches the router wire; PolicyService
// folds the active windows into the per-MAC BlockRules at snapshot time.
export interface ScheduleWindow {
  days: string[]      // lowercase 3-letter tokens: "mon".."sun"
  startLocal: string  // "HH:mm" wall-clock time in `tz`
  endLocal: string    // "HH:mm" wall-clock time in `tz`
  tz: string          // IANA timezone, e.g. "America/Los_Angeles"
}

export interface NamedSchedule {
  id: number
  name: string
  description: string | null
  windows: ScheduleWindow[]
}

// Create/update bodies for the /api/schedules CRUD. `windows` is the full
// desired set (replace semantics) — matches the API's
// Create/UpdateNamedScheduleRequest.
export interface NamedScheduleRequest {
  name: string
  description?: string | null
  windows: ScheduleWindow[]
}

// #714 — server-side heartbeat filter for device/profile screen-time totals.
// Rows classified as heartbeats are excluded from rollups; the filter is
// household-wide.
export interface HeartbeatFilter {
  enabled: boolean
  bytesThreshold: number          // bytes/min floor (rows below are heartbeats)
  // DEPRECATED (#1525): no longer read for enforcement or edited in the UI. Host-identity
  // suppression lives in the server-side canonical InfraHosts list. Kept on the type because the
  // API still returns it; removed when the wire field + DB column are dropped.
  heartbeatHostPatterns: string[]
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
  // #1912 / #1909 — network-wide "block encrypted DNS & relays" toggle. When on,
  // the API ships `blockEncryptedDns:true` on the policy snapshot and the router
  // forces devices onto the LAN resolver (NXDOMAIN for iCloud Private Relay +
  // public DoH/DoT hostnames, connection-layer drops for hardcoded resolver IPs)
  // so filtering and hostname attribution work. Household-wide, not per-profile.
  blockEncryptedDns: boolean
  // #2077 — the engagement-anchor gate over the isolation-learned ambient-host
  // baseline: idle background traffic (OS sync/telemetry bursts that habitually
  // appear alone) stops crediting as screen time. The three thresholds are
  // API-side tuning knobs with no SPA editor (like presenceContinuationSeconds).
  ambientGateEnabled: boolean
  ambientIsolationMaxHosts: number
  ambientMinIsolatedDays: number
  ambientLearningWindowDays: number
  // #578 — per-household address the API emails when a kid raises an access
  // request from the block page (extension / exemption / unpause). null = no
  // recipient configured (the API falls back to a log line, and also sends
  // nothing when the Resend transport itself is unconfigured). A notification
  // preference, not a login identity.
  notifyEmail: string | null
}

export interface TimeLimit {
  id: number
  profileId: number
  dailyMinutes: number
}

export interface AppTimeLimit {
  id: number
  profileId: number
  domainPattern: string
  // #1627: null means "no per-app limit configured" (distinct from a 0-minute cap).
  dailyMinutes: number | null
  label: string
  exemptFromDaily: boolean
}

export interface ProfileDetail {
  profile: Profile
  timeLimit: TimeLimit | null
  // #1069 — ids of the household named schedules attached to this profile as
  // block schedules (downtime while active). Empty until the operator attaches
  // one. Optional for back-compat with older API responses that omit it.
  scheduleIds?: number[]
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
  // #578 — the alert's household, resolved server-side from the device join.
  // null when the MAC has no device row. Not rendered; carried for completeness.
  householdId: number | null
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
// attribution missed (DoH, Apple Private Relay, direct-IP). `label` (#1708)
// is a synthetic name attached because the destination IP fell inside a
// hardcoded operator-curated range (e.g. apple-push / google-dns /
// cloudflare-dns) — NOT a hostname the agent observed at the resolver.
export type HostId =
  | { type: 'fqdn'; value: string }
  | { type: 'ipv4'; value: string }
  | { type: 'ipv6'; value: string }
  // `source` is open-ended (forward-compat for a future ASN map etc.); today
  // the only emitted value is 'static-ip-range'. Kept as plain string per
  // eslint @typescript-eslint/ban-types (rules out the literal+string trick).
  | { type: 'label'; value: string; source?: string }

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
  | { kind: 'extraBlockedBy'; host: string } // #1645: names the matched eb_<host> rule
  | { kind: 'noProfile' }
  | { kind: 'unmanaged' }
  | { kind: 'paused' }
  | { kind: 'schedule' }
  | { kind: 'timeLimit' }
  | { kind: 'manual' }
  | { kind: 'category'; slug: string }
  | { kind: 'appTimeLimit'; label: string } // #1518 rename from `siteTimeLimit`. The API
  // JsonEncoder canonicalizes legacy V40-migrated DB rows to this kind on read,
  // so the SPA never sees the old `siteTimeLimit` over the wire.
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
  // #2056 / §9.3 — IoT/appliance display classification. ADDITIVE + backend-gated:
  // the server does NOT populate these today, so they are absent on the wire and the
  // dashboard renders every active device exactly as it does now (the §9.4 "render all
  // until the classifier lands" gate). When the classifier sub-task ships, `kind`
  // marks appliances (Sonos/printer/NAS/…) so NOW can filter them out, and
  // `anomalous` surfaces one ANYWAY — flagged — when its traffic is a likely-compromise
  // signal (the narrow §9.3 exception to "per-device throughput stays off the dashboard").
  // Absent ⇒ personal device, always shown. The server-side signal is #2061.
  kind?: DeviceKind | null
  anomalous?: boolean | null
}

// #2056 / §9.3 — display classification only, NOT policy (the two-tier global+profile
// rule in AGENTS.md stands; this never authors per-device policy). `appliance` = IoT /
// appliance (filtered out of NOW unless anomalous); `personal` (or absent) = a device a
// human uses (always shown).
export type DeviceKind = 'personal' | 'appliance'

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

export interface AppUsage {
  label: string
  domainPattern: string
  // #1627: null means "no per-app limit configured". `remainingMins` is null
  // when `limitMins` is null — there is no remaining quantity without a cap.
  limitMins: number | null
  usedMins: number
  remainingMins: number | null
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
  appUsage: AppUsage[]
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
  appUsage: AppUsage[]
  devices: DeviceUsageSummary[]
  hostUsage: HostUsage[]
}

// #1061 — per-app time-used breakdown for one profile over a date window.
// `proportionalSeconds` is the wall-clock-attention number (#715),
// `presenceSeconds` is bucket-deduped at the app level. The drill-down `hosts`
// list reuses HostUsage so the SPA renders rows with the same Attention/Seen
// formatter. #1519: `apps` carries only real (mapped) apps; non-app hosts
// surface in `orphanHosts`, never in a synthetic "Other" entry here.
export interface ProfileAppUsage {
  appId: number | null
  appName: string
  appIcon: string | null
  appIconType?: IconType | null
  proportionalSeconds: number
  presenceSeconds: number
  hosts: HostUsage[]
}

// #1519 — a host not in any configured app's host-set. Per the App-Centric
// Model it IS its own single-host app (there is no semantic "Other"). The SPA
// renders these side-by-side with real app rows; "Other" only ever exists as a
// presentation-level top-N + "+N more sites" affordance.
export interface OrphanHostUsage {
  host: HostId
  proportionalSeconds: number
  presenceSeconds: number
}

// #1089 — per-app engaged-minutes summed over a 7-day trailing window. Mirrors
// the API's `ProfileAppWeeklyUsageRow` / `ProfileAppWeeklyUsage`. The weekly
// view aggregates FROM the daily rollup (`app_used_daily`), so the heartbeat
// filter the daily view sees flows through unchanged.
export interface ProfileAppWeeklyUsageRow {
  appId: number
  appName: string
  appIcon?: string | null
  appIconType?: IconType | null
  engagedMinutes: number
}

export interface ProfileAppWeeklyUsage {
  profileId: number
  profileName: string
  from: string
  to: string
  apps: ProfileAppWeeklyUsageRow[]
}

export interface ProfileUsageByApp {
  profileId: number
  profileName: string
  from: string
  to: string
  apps: ProfileAppUsage[]
  // #1519: additive — older API responses omit it; SPA treats undefined as [].
  orphanHosts?: OrphanHostUsage[]
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

// #1079 — unified by-app axis. Apps roll up their member hosts; non-app hosts
// surface individually as first-class entries; 'Other' is strictly the long
// tail past top-N (never a catch-all for unmapped hosts).
export interface UsageEntityRef {
  kind: 'app' | 'host'
  id: string
  name: string
  appId?: number
  appIcon?: string
  host?: HostId
}

export interface UsageEntityTotal {
  entity: UsageEntityRef
  dayMins: number
}

export interface UsageBucketEntity {
  entity: UsageEntityRef
  mins: number
}

export interface UsageEntityBucket {
  hour: number
  totalMins: number
  perEntity: UsageBucketEntity[]
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
  // #1079 — populated when the request asked groupBy=app.
  topEntries?: UsageEntityTotal[]
  bucketsByEntry?: UsageEntityBucket[]
  // #1492 — the day-level session-stitch presence total (floored once). This is
  // the same number the profile card shows as time-used; display it as the graph
  // headline rather than summing the per-hour bars, which lose sub-minute
  // fractions and read a few minutes low. Optional: older API responses omit it.
  presenceTotalMins?: number
  // #1507 — hosts that traffic was seen for but that contributed 0 engaged-
  // minutes because they were classified as device-level background/infra (Apple
  // OCSP, Google connectivity probes, …) or fell under the keepalive byte-floor.
  // Surfaced on the per-device drill-in so the operator can see what the engaged-
  // time calculation excluded and why. Optional: older API responses omit it.
  suppressedHosts?: SuppressedHostUsage[]
}

// #1507 — one device-level-infra / keepalive host with the bytes observed.
// `reason`: 'infra' = matched the InfraHosts background list; 'bytes-below-
// threshold' = filter-enabled keepalive floor.
export interface SuppressedHostUsage {
  host: HostId
  bytes: number
  buckets: number
  reason: 'infra' | 'bytes-below-threshold' | string
}

// #1099 — batched per-profile series: one round-trip resolves the whole
// visible profile set instead of N parallel /usage/series calls. Each entry is
// the same shape the single-profile endpoint returns.
export interface UsageSeriesBatchResponse {
  series: UsageSeriesResponse[]
}

// #846 — Traffic Usage page. Wire-distinct from UsageSeriesResponse: that one
// drives the screen-time minutes chart; this one drives raw + aggregated bytes
// inspection. 1m bucket and apex/app groupBy are reserved (router cadence /
// PSL / apps track) — server returns 400 with a typed `error` code.
export type TrafficUsageBucket = 'raw' | '1m' | '10m' | '1h' | '12h' | '1d' | '1w'

// #1743: which storage grain backs each display bucket. Sourced from the API
// (`BucketPolicy.bucketTiers`), not hand-mirrored — see `useUsageConfig` /
// `retentionGating.ts`.
export type BucketGrain = 'raw' | 'hourly' | 'daily'

export interface UsageConfig {
  bucketTiers: Record<string, BucketGrain>
  // #1740: retention horizons (raw / hourly / daily) sourced from
  // `RetentionSweepJob` so the bucket gating in `retentionGating.ts` no longer
  // hand-mirrors the sweep job's day counts.
  horizons: RetentionHorizons
}
// #846: groupBy is composable. Apex is deferred to #856 (needs PSL). #769
// turned `app` on — it now resolves to a server-side join through
// `app_hosts`. #1526: a host with no registered app is its own single-host
// app, keyed by the host itself (no shared "Other" bucket).
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
  // these carry the display metadata. #1526: host-keyed single-host apps
  // (unmatched hosts) emit appName=<host> and appId=null.
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
  // #2164: the resolved household's slug. The SPA writes it to the long-lived `wh_household`
  // cookie so a later BARE-username login on this browser can be client-composed into
  // `slug/username` (design §4 form 3). Absent for a household with no slug yet.
  householdSlug?: string
}

export interface MeResponse {
  username: string
  role: UserRole
  profileIds: number[]
  // #2133 (multi-tenant P5-3): true when the caller passes the operator gate
  // (admin AND household 1 — design §3.2). The SPA gates the beta-request queue
  // nav/route on this instead of hardcoding the household-1-admin rule. Absent
  // (→ false) for every non-operator and for pre-#2133 API builds.
  isOperator?: boolean
}

// NOTE: the beta-pipeline wire types (BetaRequestStatus, CreateBetaRequest,
// BetaRequestAck, BetaRequestSummary, ApproveBetaResponse, AcceptInviteRequest,
// AcceptInviteResponse) already ship below with #2132 — see "Beta access
// requests". The #2133 SPA consumes those; do NOT redeclare them here.

export interface CreateUserRequest {
  username: string
  password: string
  role: UserRole
  profileIds: number[]
}

// #997 — field-scoped partial update; the server applies only the keys
// present. profileIds is replace-set ([] unassigns all). Password changes go
// through the dedicated change-password endpoint, never here.
export interface PatchUserRequest {
  username?: string
  role?: UserRole
  profileIds?: number[]
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
  // #1494: schedules are NOT carried here. A profile's block schedules are
  // #1069 household named schedules attached via PUT /api/profiles/{id}/schedules
  // (SetProfileSchedulesRequest -> profile_schedule_rules), which enforcement
  // reads (#1482/#1490). The old inline array wrote the dead V1 schedules table.
  timeLimit: number | null
  failureMode: FailureMode
  // #751: omit to preserve existing value on update; defaults to 'sum' on create.
  crossDeviceOverlapMode?: CrossDeviceOverlapMode
  // #1418: omit to preserve existing value on update; defaults to 'soft' on create.
  pauseMode?: PauseMode
  // #1320: omit to preserve existing value on update; defaults to false on create.
  defaultDeny?: boolean
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

// #423: PATCH /api/profiles/:id — field-scoped partial update. Omitted fields
// preserve their current value; `timeLimit` is the only nullable field and
// accepts an explicit `null` to clear. Non-nullable fields cannot be `null`.
export interface PatchProfileRequest {
  name?: string
  blockedCategories?: string[]
  paused?: boolean
  failureMode?: FailureMode
  blockIpOnly?: boolean
  crossDeviceOverlapMode?: CrossDeviceOverlapMode
  pauseMode?: PauseMode
  defaultDeny?: boolean
  timeLimit?: number | null
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

// #1380 / #1376 — per-app schedule rules. Each rule attaches a #1069 named
// schedule (by id) to an app's profile assignment with a mode:
//   allowed_during — the app stays reachable while the schedule's window is
//     active, even during profile downtime (a carve-out that beats whole-MAC
//     blocks per #421). Subject to the daily time limit unless exemptFromDaily.
//   blocked_during — the app is dropped while the window is active, even when
//     the profile is otherwise unrestricted.
// API-internal: PolicyService collapses active rules into the existing per-MAC
// extraAllowed / extraBlocked snapshot fields — no wire/router change (design
// doc docs/design/per-app-schedules.md §2–§5). Mirrors the API's ScheduleMode
// wire strings.
export type AppScheduleMode = 'allowed_during' | 'blocked_during'

export interface AppScheduleRule {
  scheduleId: number
  mode: AppScheduleMode
}

export interface AppPolicyAssignment {
  id: number
  appId: number
  profileId: number
  mode: AppMode
  dailyMinutes: number | null
  exemptFromDaily: boolean
  // #1380 — attached per-app schedule rules. Optional/back-compat: omitted by
  // an API that predates #1379 (defaults to no rules).
  scheduleRules?: AppScheduleRule[]
  // #1679 — when false, this app's extraAllowed carve-out is suppressed during
  // Schedule-reason blocks. Optional/back-compat: omitted by older APIs → treat as true.
  allowedDuringScheduleBlock?: boolean
}

// #1983 — one app host that is a member of one or more shipped category
// blocklists, with the blocklist id(s) it appears on. Drives the "this app
// contains blocklisted hosts" warning on the Apps page + app selector.
export interface AppBlocklistedHost {
  host: string
  blocklists: string[]
}

export interface AppDetail {
  app: App
  hosts: string[]
  assignments: AppPolicyAssignment[]
  // #1983 — additive; omitted by older APIs → treat as empty.
  blocklisted?: AppBlocklistedHost[]
}

// #1798 — app *definitions* (name/slug/icon/host-set/create) are authored only
// via the built-in `AppTemplates` in code; the SPA create/edit/delete surface
// and its request shapes (CreateAppRequest, PatchAppRequest) and the recent-apex
// host picker (RecentApex/RecentApexesResponse) were removed.

export interface UpsertAppAssignmentRequest {
  mode: AppMode
  dailyMinutes?: number | null
  exemptFromDaily?: boolean
  // #1380 — additive (default Nil server-side). The full desired rule set for
  // this assignment (replace semantics, like SetProfileSchedulesRequest's ids).
  scheduleRules?: AppScheduleRule[]
  // #1679 — omit to keep current behavior (server defaults to true).
  allowedDuringScheduleBlock?: boolean
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
// "app_time_limit" | "category" | "extra_blocked". `blocked: false`
// means the device is not blocked for this host (or is unenrolled).
export interface BlockedInfoResponse {
  blocked: boolean
  reasonClass?: string | null
  categoryName?: string | null
  profileName?: string | null
  // #335: today's usage for the device's profile so a restricted kid can see
  // why their time is gone. Populated for any enrolled MAC; null/undefined when
  // the MAC has no profile or there's no daily limit configured.
  usedMinutes?: number | null
  dailyLimitMinutes?: number | null
  extensionMinutes?: number | null
  remainingMinutes?: number | null
}

// #1740 — usage retention horizons (days). Carried on `UsageConfig.horizons`
// via GET /api/usage/config. Authoritative values live in
// api/src/usage/RetentionSweepJob.scala; the SPA reads this at boot so the
// bucket gating in `web/src/components/usage/retentionGating.ts` can't
// silently drift from the sweep job's actual behaviour.
export interface RetentionHorizons {
  rawDays: number
  hourlyDays: number
  dailyDays: number
}

// ── Beta access requests (#2132, multi-tenant P5-2, epic #622) ──────────────
// Wire shapes for the request → operator approval → provisioning → invite
// accept pipeline (design docs/design/multi-tenant-launch.md §3). The SPA
// surfaces that consume these (the `/beta` form, operator queue, `/welcome`
// accept page) land in #2133; these types ship with the API so that work can
// build on them. DISTINCT from the block-page `AccessRequest` concept above.
export type BetaRequestStatus = 'Pending' | 'Approved' | 'Rejected'

export interface CreateBetaRequest {
  email: string
  name?: string | null
  note?: string | null
}

export interface BetaRequestAck {
  status: string
}

export interface BetaRequestSummary {
  id: number
  email: string
  name: string | null
  note: string | null
  status: BetaRequestStatus
  requestedAt: string
  decidedAt: string | null
  householdId: number | null
}

export interface ApproveBetaResponse {
  householdId: number
  slug: string
  inviteUrl: string
  inviteExpiresAt: string
}

// Revised 2026-07-10 (design §3.4): no username/email — the admin's email is bound server-side
// from beta_requests.email and username defaults to `admin`.
export interface AcceptInviteRequest {
  token: string
  password: string
}

export interface AcceptInviteResponse {
  householdId: number
  slug: string
  username: string
}

// #2135 (multi-tenant P5-5): billing status + Checkout/Portal redirect. status ∈ beta|active|lapsed
// (design §5.1); on lapse enforcement stops but there is NO read-only gating.
export type BillingStatus = 'beta' | 'active' | 'lapsed'

export interface BillingStatusResponse {
  status: BillingStatus
  founding: boolean
  priceId: string | null
  currentPeriodEnd: string | null
  lapsedAt: string | null
}

export interface BillingRedirect {
  url: string
}
