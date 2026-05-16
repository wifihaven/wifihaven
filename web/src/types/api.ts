// Mirrors familydns.shared Models.scala

// #385: three failover modes. Wire form is lower-kebab to match
// shared/src/Models.scala FailureMode.asString.
export type FailureMode = 'block-all' | 'allow-all' | 'last-known-good'

export interface Profile {
  id: number
  name: string
  blockedCategories: string[]
  extraBlocked: string[]
  extraAllowed: string[]
  paused: boolean
  failureMode: FailureMode
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

export interface HouseholdSettings {
  dailyResetTime: string  // "HH:mm" wall-clock time in `dailyResetTz`
  dailyResetTz: string    // IANA timezone
}

export interface UpdateHouseholdSettingsRequest {
  dailyResetTime: string
  dailyResetTz: string
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

export interface Session {
  mac: string
  deviceName: string | null
  profileId: number | null
  profileName: string | null
  host: HostId
  routerId: string
  date: string
  startedAt: string
  endedAt: string
  durationSeconds: number
  bytesIn: number
  bytesOut: number
  periodCount: number
}

export interface SessionPage {
  sessions: Session[]
  nextCursor: string | null
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

export interface DashboardNowCurrentSession {
  host: HostId
  startedAt: string
  durationSeconds: number
}

export interface DashboardNowDevice {
  id: number
  name: string
  mac: string
  lastSeenSeconds: number
  topHosts: DashboardNowHost[]
  currentSession: DashboardNowCurrentSession | null
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

export interface HostUsage {
  host: HostId
  usedMins: number
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
