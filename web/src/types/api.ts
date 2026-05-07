// Mirrors familydns.shared Models.scala

export interface Profile {
  id: number
  name: string
  blockedCategories: string[]
  extraBlocked: string[]
  extraAllowed: string[]
  paused: boolean
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
  profileId: number
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

export interface DashboardStats {
  totalToday: number
  blockedToday: number
  totalHour: number
  blockedHour: number
  topBlocked: DomainCount[]
  perDevice: DeviceStats[]
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
  dailyLimitMins?: number | null
  usedMins: number
  extensionMins: number
  remainingMins?: number | null
  siteUsage: SiteUsage[]
}

export interface TimeExtension {
  id: number
  deviceMac: string
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
}

export interface UpsertDeviceRequest {
  mac: string
  name: string
  profileId: number
}

export interface GrantExtensionRequest {
  deviceMac: string
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
