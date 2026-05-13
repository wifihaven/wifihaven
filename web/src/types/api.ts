// Mirrors familydns.shared Models.scala

export type FailureMode = 'open' | 'closed'

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
  blockFrom: string
  blockUntil: string
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

export interface QueryLog {
  id: number
  mac: string | null
  deviceName: string | null
  profileId: number | null
  profileName: string | null
  domain: string
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
  hostname: string
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
  hostname: string
  activeSeconds: number
}

export interface DashboardNowCurrentSession {
  hostname: string
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
  domain: string
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
  blockFrom: string
  blockUntil: string
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
  // Signed seconds; positive = router clock ahead of API. null when the
  // agent has never reported a measurement (issue #312).
  lastClockSkewSeconds: number | null
}

export interface CreateRouterRequest {
  name: string
}

export interface CreateRouterResponse {
  routerId: string
  name: string
  enrollmentToken: string
}
