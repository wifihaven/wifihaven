import type {
  CreateRouterRequest, CreateRouterResponse, CreateUserRequest, DashboardStats, Device,
  DeviceTimeStatus, LoginResponse, MeResponse, ProfileDetail, ProfileTimeStatus, QueryLog,
  RouterSummary, SessionPage, SetUserProfilesRequest, TimeExtension, UpsertDeviceRequest,
  UpsertProfileRequest, GrantExtensionRequest, User,
} from '@/types/api'

const BASE = '/api'

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
    pause: (id: number) => req<{ paused: boolean }>('POST', `/profiles/${id}/pause`, {}),
    getUsers: (id: number) => req<User[]>('GET', `/profiles/${id}/users`),
    setUsers: (id: number, userIds: number[]) =>
      req<void>('PUT', `/profiles/${id}/users`, { userIds }),
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
      blocked?: boolean
      domain?: string
      location?: string
      hours?: number
      limit?: number
      offset?: number
    }) => {
      const qs = new URLSearchParams()
      if (params.mac)      qs.set('mac', params.mac)
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
