import type {
  CreateRouterRequest, CreateRouterResponse, CreateUserRequest, DashboardNow, DashboardStats, Device,
  DeviceTimeStatus, HouseholdSettings, LoginResponse, MeResponse, ProfileDetail, ProfileTimeStatus,
  QueryLog, RouterSummary, SessionPage, SetUserProfilesRequest, TimeExtension,
  UpdateHouseholdSettingsRequest, UpsertDeviceRequest, UpsertProfileRequest, GrantExtensionRequest,
  User,
} from '@/types/api'

// VITE_API_BASE_URL is empty by default (relative path — SPA served from the
// same origin as the API, which is the current JVM-bundled behaviour). Static
// Site builds override this to an absolute URL so the CDN-served SPA knows
// which API host to reach: https://api.wifihaven.app for prod,
// https://api-staging.wifihaven.app for staging. See render.yaml and #587.
const VITE_API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? ''
const BASE = `${VITE_API_BASE_URL}/api`

function getToken(): string | null {
  return localStorage.getItem('token')
}

async function req<T>(
  method: string,
  path: string,
  body?: unknown,
  skipAuth = false,
): Promise<T> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  const token = getToken()
  if (!skipAuth && token) headers['Authorization'] = `Bearer ${token}`

  const res = await fetch(`${BASE}${path}`, {
    method,
    headers,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })

  if (res.status === 401) {
    localStorage.removeItem('token')
    window.location.href = '/login'
    throw new Error('Unauthorised')
  }

  // #586: server enforces must_change_password — redirect to /account so
  // the operator can set a new password before using any other route.
  if (res.status === 403) {
    const text = await res.text().catch(() => '')
    if (text.includes('password_change_required')) {
      window.location.href = '/account'
      throw new Error('password_change_required')
    }
    throw new Error(text || `HTTP 403`)
  }

  if (!res.ok) {
    const text = await res.text().catch(() => res.statusText)
    throw new Error(text || `HTTP ${res.status}`)
  }

  if (res.status === 204 || res.headers.get('content-length') === '0') {
    return undefined as T
  }

  return res.json() as Promise<T>
}

// ── Auth ───────────────────────────────────────────────────────────────────

export const api = {
  auth: {
    login: (username: string, password: string) =>
      req<LoginResponse>('POST', '/auth/login', { username, password }, true),
    changePassword: (currentPassword: string, newPassword: string) =>
      req<void>('POST', '/auth/change-password', { currentPassword, newPassword }),
    me: () => req<MeResponse>('GET', '/me'),
  },

  // ── Users ──────────────────────────────────────────────────────────────
  users: {
    list: () => req<User[]>('GET', '/users'),
    create: (data: CreateUserRequest) =>
      req<{ id: number }>('POST', '/users', data),
    setProfiles: (id: number, profileIds: number[]) =>
      req<void>('PUT', `/users/${id}/profiles`, { profileIds } as SetUserProfilesRequest),
    delete: (id: number) => req<void>('DELETE', `/users/${id}`),
  },

  // ── Profiles ───────────────────────────────────────────────────────────
  profiles: {
    list: () => req<ProfileDetail[]>('GET', '/profiles'),
    get: (id: number) => req<ProfileDetail>('GET', `/profiles/${id}`),
    create: (data: UpsertProfileRequest) =>
      req<{ id: number }>('POST', '/profiles', data),
    update: (id: number, data: UpsertProfileRequest) =>
      req<void>('PUT', `/profiles/${id}`, data),
    delete: (id: number) => req<void>('DELETE', `/profiles/${id}`),
    getUsers: (id: number) => req<User[]>('GET', `/profiles/${id}/users`),
    setUsers: (id: number, userIds: number[]) =>
      req<void>('PUT', `/profiles/${id}/users`, { userIds }),
  },

  // ── Household settings (#334) ──────────────────────────────────────────
  household: {
    get: () => req<HouseholdSettings>('GET', '/household/settings'),
    update: (data: UpdateHouseholdSettingsRequest) =>
      req<void>('PUT', '/household/settings', data),
  },

  // ── Devices ────────────────────────────────────────────────────────────
  devices: {
    list: () => req<Device[]>('GET', '/devices'),
    upsert: (data: UpsertDeviceRequest) => req<{ id: number }>('PUT', '/devices', data),
    delete: (mac: string) => req<void>('DELETE', `/devices/${encodeURIComponent(mac)}`),
  },

  // ── Time ───────────────────────────────────────────────────────────────
  time: {
    statusAll: (date?: string) =>
      req<ProfileTimeStatus[]>('GET', `/time/status${date ? `?date=${date}` : ''}`),
    statusDevice: (mac: string, date?: string) =>
      req<DeviceTimeStatus>('GET', `/time/status/${encodeURIComponent(mac)}${date ? `?date=${date}` : ''}`),
    grantExtension: (data: GrantExtensionRequest) =>
      req<{ id: number; grantedMinutes: number }>('POST', '/time/extend', data),
    listExtensions: (profileId: number) =>
      req<TimeExtension[]>('GET', `/time/extensions/${profileId}`),
  },

  // ── Logs ───────────────────────────────────────────────────────────────
  logs: {
    query: (params: {
      mac?: string
      deviceId?: number
      profileId?: number
      blocked?: boolean
      domain?: string
      location?: string
      hours?: number
      limit?: number
      offset?: number
    }) => {
      const qs = new URLSearchParams()
      if (params.mac)      qs.set('mac', params.mac)
      if (params.deviceId !== undefined)  qs.set('deviceId', String(params.deviceId))
      if (params.profileId !== undefined) qs.set('profileId', String(params.profileId))
      if (params.blocked !== undefined) qs.set('blocked', String(params.blocked))
      if (params.domain)   qs.set('domain', params.domain)
      if (params.location) qs.set('location', params.location)
      if (params.hours)    qs.set('hours', String(params.hours))
      if (params.limit)    qs.set('limit', String(params.limit))
      if (params.offset)   qs.set('offset', String(params.offset))
      return req<QueryLog[]>('GET', `/logs?${qs}`)
    },
    stats: () => req<DashboardStats>('GET', '/stats'),
  },

  // ── Dashboard "now" ────────────────────────────────────────────────────
  dashboard: {
    now: () => req<DashboardNow>('GET', '/dashboard/now'),
  },

  // ── Sessions ───────────────────────────────────────────────────────────
  sessions: {
    list: (params: {
      mac?: string
      deviceId?: number
      profileId?: number
      host?: string
      since?: string
      until?: string
      hours?: number
      limit?: number
      cursor?: string
    }) => {
      const qs = new URLSearchParams()
      if (params.mac)       qs.set('mac', params.mac)
      if (params.deviceId !== undefined)  qs.set('deviceId', String(params.deviceId))
      if (params.profileId !== undefined) qs.set('profileId', String(params.profileId))
      if (params.host)      qs.set('host', params.host)
      if (params.since)     qs.set('since', params.since)
      if (params.until)     qs.set('until', params.until)
      if (params.hours)     qs.set('hours', String(params.hours))
      if (params.limit)     qs.set('limit', String(params.limit))
      if (params.cursor)    qs.set('cursor', params.cursor)
      return req<SessionPage>('GET', `/sessions?${qs}`)
    },
  },

  // ── Blocklists ─────────────────────────────────────────────────────────
  blocklists: {
    counts: () => req<{ category: string; count: number }[]>('GET', '/blocklists'),
    clearCategory: (cat: string) => req<void>('POST', `/blocklists/${cat}/clear`, {}),
  },

  // ── Routers (admin) ────────────────────────────────────────────────────
  routers: {
    list: () => req<RouterSummary[]>('GET', '/admin/routers'),
    create: (data: CreateRouterRequest) =>
      req<CreateRouterResponse>('POST', '/admin/routers', data),
    delete: (id: string) => req<void>('DELETE', `/admin/routers/${id}`),
  },
}
