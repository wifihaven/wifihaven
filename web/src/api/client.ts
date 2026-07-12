import { apiHealth } from '@/api/apiHealth'
import type {
  Alert, AppDetail, ApproveAlertRequest, BlockedInfoResponse, BlocklistHosts, BlocklistSummary, CreateRouterRequest, CreateRouterResponse, CreateUserRequest,
  DashboardNow, DashboardStats, Device,
  CreateAccessRequest, DeviceTimeStatus, DeviceTimeStatusWeek, HouseholdSettings, LoginResponse, MeResponse, ProfileAppWeeklyUsage, ProfileDetail, ProfileTimeStatus, ProfileTimeStatusWeek, ProfileTimeSummary, ProfileTimeSummaryWeek, ProfileUsageByApp,
  ConnectionEventSeriesPage, QueryLogPage,
  PatchUserRequest,
  NamedSchedule, NamedScheduleRequest,
  RouterSummary, SetUserProfilesRequest, TimeExtension,
  TrafficUsageBucket, TrafficUsageGroupBy, TrafficUsageResponse, UsageConfig,
  PatchDeviceRequest, PatchProfileRequest,
  UpsertAppAssignmentRequest, UpsertDeviceRequest, UpsertProfileRequest, GrantExtensionRequest,
  UsageSeriesBatchResponse, UsageSeriesResponse, User,
} from '@/types/api'

// VITE_API_BASE_URL is empty by default (relative path — SPA served from the
// same origin as the API, which is the current JVM-bundled behaviour). Static
// Site builds override this to an absolute URL so the CDN-served SPA knows
// which API host to reach: https://api.wifihaven.net for prod,
// https://api-staging.wifihaven.net for staging. See render.yaml and #587.
const VITE_API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? ''
const BASE = `${VITE_API_BASE_URL}/api`

function getToken(): string | null {
  return localStorage.getItem('token')
}

// #1191: requests time out at REQUEST_TIMEOUT_MS so a hung backend surfaces
// the banner instead of spinning forever. Per-call AbortController so we
// don't leak signal across calls.
const REQUEST_TIMEOUT_MS = 10_000

// #2047 — a request the *caller* cancelled (e.g. the Traffic Usage page
// superseding its in-flight load on a bucket switch / filter change). This is
// categorically NOT an API-health signal: the API is fine, the client just
// stopped caring about the old answer. It must never trip the unreachable
// banner, and callers swallow it rather than rendering the cryptic
// "signal is aborted without reason". Distinct from the 10s timeout abort
// (a genuine "API is hung" signal), which keeps reporting unreachable.
export class RequestCanceledError extends Error {
  constructor() {
    super('request canceled')
    this.name = 'RequestCanceledError'
  }
}

export function isCanceledError(e: unknown): boolean {
  return e instanceof RequestCanceledError
}

// #2069: a 403 is a terminal authorization outcome, not a transient failure —
// retrying it just hot-loops the same denial (the prod child-account 403 storm).
// Throwing a typed error lets the React Query retry policy (queryClient.ts) skip
// it while still retrying genuine 5xx / network blips. `status` is carried so
// callers/tests can branch on it without string-matching the body.
export class ForbiddenError extends Error {
  readonly status = 403
  constructor(message: string) {
    super(message)
    this.name = 'ForbiddenError'
  }
}

export function isForbiddenError(e: unknown): boolean {
  return e instanceof ForbiddenError
}

async function req<T>(
  method: string,
  path: string,
  body?: unknown,
  skipAuth = false,
  signal?: AbortSignal,
): Promise<T> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  const token = getToken()
  if (!skipAuth && token) headers['Authorization'] = `Bearer ${token}`

  // #2047: one fetch-scoped controller fed by two sources — our 10s timeout and
  // the caller's optional supersede signal. `timedOut` records which one fired
  // so the catch can tell a genuine timeout (report unreachable) from a caller
  // cancellation (silent). The caller's abort is forwarded to our controller so
  // the in-flight fetch is actually torn down.
  const controller = new AbortController()
  let timedOut = false
  const timeoutId = setTimeout(() => { timedOut = true; controller.abort() }, REQUEST_TIMEOUT_MS)
  const onCallerAbort = () => controller.abort()
  if (signal) {
    if (signal.aborted) controller.abort()
    else signal.addEventListener('abort', onCallerAbort)
  }
  const cleanup = () => {
    clearTimeout(timeoutId)
    if (signal) signal.removeEventListener('abort', onCallerAbort)
  }

  let res: Response
  try {
    res = await fetch(`${BASE}${path}`, {
      method,
      headers,
      body: body !== undefined ? JSON.stringify(body) : undefined,
      signal: controller.signal,
      // #1299: never let the browser HTTP cache answer an API read. Today-mode
      // time-status GETs are served with Cache-Control: max-age=30, so without
      // this a React Query refetch fired right after a mutation (e.g. the +Time
      // grant invalidating ['time','status']) could be served the stale cached
      // body — the used/cap bar then wouldn't update until a force-reload.
      // React Query remains the single source of client-side caching.
      cache: 'no-store',
    })
  } catch (e) {
    cleanup()
    // #2047: caller cancellation (supersede) — the API is fine, don't light the
    // banner. Surface a typed cancellation the caller swallows.
    if (signal?.aborted && !timedOut) throw new RequestCanceledError()
    // Network error or timeout abort. Either way the API isn't reachable
    // right now — light up the global banner via apiHealth.
    const aborted = e instanceof DOMException && e.name === 'AbortError'
    apiHealth.reportFailure(aborted ? 'timeout' : 'network')
    throw e
  }
  cleanup()

  if (res.status === 401) {
    // 401 is an auth-state outcome, not an API-down signal. The API answered.
    apiHealth.reportSuccess()
    localStorage.removeItem('token')
    window.location.href = '/login'
    throw new Error('Unauthorised')
  }

  // #586: server enforces must_change_password — redirect to /account so
  // the operator can set a new password before using any other route.
  if (res.status === 403) {
    apiHealth.reportSuccess()
    const text = await res.text().catch(() => '')
    if (text.includes('password_change_required')) {
      window.location.href = '/account'
      throw new Error('password_change_required')
    }
    // #2069: typed so React Query never hot-retries an authorization denial.
    throw new ForbiddenError(text || `HTTP 403`)
  }

  // #1191: 5xx is the canonical "API is broken" signal — surface the banner.
  // 4xx (other than the special cases above) is a client error; the API is
  // alive and answering, so don't trigger the banner on it.
  if (res.status >= 500) {
    apiHealth.reportFailure('5xx')
    const text = await res.text().catch(() => res.statusText)
    throw new Error(text || `HTTP ${res.status}`)
  }

  if (!res.ok) {
    apiHealth.reportSuccess()
    const text = await res.text().catch(() => res.statusText)
    throw new Error(text || `HTTP ${res.status}`)
  }

  apiHealth.reportSuccess()

  if (res.status === 204 || res.headers.get('content-length') === '0') {
    return undefined as T
  }

  return res.json() as Promise<T>
}

function weekQuery(to?: string, bucketOffsetMin?: number, profileId?: number): string {
  const parts: string[] = []
  if (to !== undefined) parts.push(`to=${to}`)
  if (bucketOffsetMin !== undefined) parts.push(`bucketOffsetMin=${bucketOffsetMin}`)
  if (profileId !== undefined) parts.push(`profileId=${profileId}`)
  return parts.length === 0 ? '' : `?${parts.join('&')}`
}

// ── Auth ───────────────────────────────────────────────────────────────────

export const api = {
  auth: {
    // #2140: `household` is the optional household slug the user is signing in to. Omitted/blank →
    // the server resolves the default household (self-hosted single-household back-compat).
    login: (username: string, password: string, household?: string) =>
      req<LoginResponse>(
        'POST',
        '/auth/login',
        household ? { username, password, household } : { username, password },
        true,
      ),
    changePassword: (currentPassword: string, newPassword: string) =>
      req<void>('POST', '/auth/change-password', { currentPassword, newPassword }),
    me: () => req<MeResponse>('GET', '/me'),
  },

  // ── Users ──────────────────────────────────────────────────────────────
  users: {
    list: () => req<User[]>('GET', '/users'),
    create: (data: CreateUserRequest) =>
      req<{ id: number }>('POST', '/users', data),
    // #997 / #1001: field-scoped partial update (username / role / profileIds).
    patch: (id: number, data: PatchUserRequest) =>
      req<void>('PATCH', `/users/${id}`, data),
    setProfiles: (id: number, profileIds: number[]) =>
      req<void>('PUT', `/users/${id}/profiles`, { profileIds } as SetUserProfilesRequest),
    delete: (id: number) => req<void>('DELETE', `/users/${id}`),
  },

  // ── Profiles ───────────────────────────────────────────────────────────
  profiles: {
    list: () => req<ProfileDetail[]>('GET', '/profiles'),
    // #1773 — the single `is_global=TRUE` sentinel profile (#1771). Hidden from
    // /profiles; surfaced here so the SPA can render it through the same per-
    // profile editor and POST its app-policy assignments / categories / default-
    // deny via the existing `/profiles/<id>/...` routes.
    getGlobal: () => req<ProfileDetail>('GET', '/profiles/global'),
    get: (id: number) => req<ProfileDetail>('GET', `/profiles/${id}`),
    create: (data: UpsertProfileRequest) =>
      req<{ id: number }>('POST', '/profiles', data),
    update: (id: number, data: UpsertProfileRequest) =>
      req<void>('PUT', `/profiles/${id}`, data),
    // #423 / #995 — field-scoped partial update. Race-safe alternative to
    // `update`: only the supplied fields change. `timeLimit: null` clears the
    // daily limit; non-nullable fields cannot be null. PUT stays for callers
    // that want full-shape replace.
    patch: (id: number, data: PatchProfileRequest) =>
      req<void>('PATCH', `/profiles/${id}`, data),
    delete: (id: number) => req<void>('DELETE', `/profiles/${id}`),
    // #1494 / #1069 — replace the set of #1069 household named schedules
    // attached to this profile as BLOCK schedules (downtime while active).
    // Writes profile_schedule_rules; enforcement reads it (#1490).
    setSchedules: (id: number, scheduleIds: number[]) =>
      req<void>('PUT', `/profiles/${id}/schedules`, { scheduleIds }),
    getUsers: (id: number) => req<User[]>('GET', `/profiles/${id}/users`),
    setUsers: (id: number, userIds: number[]) =>
      req<void>('PUT', `/profiles/${id}/users`, { userIds }),
    // #1061 — per-app time-used breakdown over [from,to]. Defaults to today
    // when both query params are omitted; the SPA passes explicit dates so
    // Today and Week tabs hit distinct cache keys.
    usageByApp: (id: number, from?: string, to?: string) => {
      const qs = new URLSearchParams()
      if (from) qs.set('from', from)
      if (to)   qs.set('to', to)
      const tail = qs.toString()
      return req<ProfileUsageByApp>(
        'GET',
        `/profiles/${id}/usage-by-app${tail ? `?${tail}` : ''}`,
      )
    },
    // #1089 — per-app engaged-minutes summed over the trailing 7-day window
    // ending at `to`. `to` defaults to household-local today on the server.
    appWeekly: (id: number, to?: string) => {
      const tail = to ? `?to=${to}` : ''
      return req<ProfileAppWeeklyUsage>(
        'GET',
        `/profiles/${id}/usage/app/weekly${tail}`,
      )
    },
  },

  // ── Household settings (#334) ──────────────────────────────────────────
  household: {
    get: () => req<HouseholdSettings>('GET', '/household/settings'),
    patch: (data: Record<string, unknown>) =>
      req<void>('PATCH', '/household/settings', data),
  },

  // ── Devices ────────────────────────────────────────────────────────────
  devices: {
    list: () => req<Device[]>('GET', '/devices'),
    upsert: (data: UpsertDeviceRequest) => req<{ id: number }>('PUT', '/devices', data),
    // #973 / #996: field-scoped partial update; the route accepts raw MAC path
    // segments (zio-http does not decode percent-encoded colons in paths).
    patch: (mac: string, data: PatchDeviceRequest) =>
      req<void>('PATCH', `/devices/${mac}`, data),
    delete: (mac: string) => req<void>('DELETE', `/devices/${encodeURIComponent(mac)}`),
  },

  // ── Alerts (unifies #711 + #960) ───────────────────────────────────────
  alerts: {
    list: (includeAll = false) =>
      req<Alert[]>('GET', `/alerts${includeAll ? '?all=true' : ''}`),
    approve: (id: number, data: ApproveAlertRequest = {}) =>
      req<Alert>('POST', `/alerts/${id}/approve`, data),
    deny: (id: number) =>
      req<Alert>('POST', `/alerts/${id}/deny`),
    // POST /api/access-requests is the one public, unauthenticated endpoint
    // in this surface — the block page calls it with (mac, host, kind). It
    // creates an access_request-kinded alert server-side.
    createAccessRequest: (data: CreateAccessRequest) =>
      req<Alert>('POST', '/access-requests', data, true),
  },

  // ── Time ───────────────────────────────────────────────────────────────
  time: {
    statusAll: (date?: string, profileId?: number) => {
      const qs = new URLSearchParams()
      if (date) qs.set('date', date)
      if (profileId !== undefined) qs.set('profileId', String(profileId))
      const tail = qs.toString()
      return req<ProfileTimeStatus[]>('GET', `/time/status${tail ? `?${tail}` : ''}`)
    },
    // #777 lightweight per-profile summary used by the collapsed accordion.
    summaryAll: (date?: string) =>
      req<ProfileTimeSummary[]>('GET', `/time/status/summary${date ? `?date=${date}` : ''}`),
    summaryAllWeek: (to?: string) =>
      req<ProfileTimeSummaryWeek[]>('GET', `/time/status/summary/week${to ? `?to=${to}` : ''}`),
    statusAllWeek: (to?: string, bucketOffsetMin?: number, profileId?: number) =>
      req<ProfileTimeStatusWeek[]>(
        'GET',
        `/time/status/week${weekQuery(to, bucketOffsetMin, profileId)}`,
      ),
    // Colons in MAC addresses are valid URL path chars (sub-delims); zio-http
    // does NOT auto-decode percent-encoded colons in path segments, so
    // `encodeURIComponent` would turn the MAC into a 404. Send raw.
    statusDeviceWeek: (mac: string, to?: string, bucketOffsetMin?: number) =>
      req<DeviceTimeStatusWeek>('GET', `/time/status/${mac}/week${weekQuery(to, bucketOffsetMin)}`),
    statusDevice: (mac: string, date?: string) =>
      req<DeviceTimeStatus>('GET', `/time/status/${mac}${date ? `?date=${date}` : ''}`),
    grantExtension: (data: GrantExtensionRequest) =>
      req<{ id: number; grantedMinutes: number }>('POST', '/time/extend', data),
    listExtensions: (profileId: number) =>
      req<TimeExtension[]>('GET', `/time/extensions/${profileId}`),
  },

  // ── Usage (#716) ───────────────────────────────────────────────────────
  usage: {
    // #1743 + #1740: bucket → grain mapping and retention horizons. The SPA
    // reads both at boot so retentionGating.ts no longer hand-mirrors
    // `BucketPolicy.grainForBucket` or `RetentionSweepJob`'s day counts.
    config: () => req<UsageConfig>('GET', '/usage/config'),
    series: (params: {
      mac?: string
      profileId?: number
      date?: string
      tz?: string
      topN?: number
      groupBy?: 'app'
    }) => {
      const qs = new URLSearchParams()
      if (params.mac)       qs.set('mac', params.mac)
      if (params.profileId !== undefined) qs.set('profileId', String(params.profileId))
      if (params.date)      qs.set('date', params.date)
      if (params.tz)        qs.set('tz', params.tz)
      if (params.topN)      qs.set('topN', String(params.topN))
      if (params.groupBy)   qs.set('groupBy', params.groupBy)
      return req<UsageSeriesResponse>('GET', `/usage/series?${qs}`)
    },
    // #1099 — batched per-profile series. profileIds serialize comma-separated
    // (`profileId=1,2,3`), matching parseMultiProfileIdParam on the API. One
    // round-trip backs the whole visible profile set instead of N requests.
    seriesBatch: (params: {
      profileIds: number[]
      date?: string
      tz?: string
      topN?: number
      groupBy?: 'app'
    }) => {
      const qs = new URLSearchParams()
      if (params.profileIds.length) qs.set('profileId', params.profileIds.join(','))
      if (params.date)    qs.set('date', params.date)
      if (params.tz)      qs.set('tz', params.tz)
      if (params.topN)    qs.set('topN', String(params.topN))
      if (params.groupBy) qs.set('groupBy', params.groupBy)
      return req<UsageSeriesBatchResponse>('GET', `/usage/series/batch?${qs}`)
    },
    // #846 Traffic Usage page — multi-column groupBy.
    // #917: groupBy as repeated query params; empty = strictly aggregate
    // (one row per time bucket). The API still accepts the older comma form.
    // #865: mac / profileId are multi-value, serialized as comma-separated.
    // A single-element array round-trips to the legacy single-value form.
    // #862: cursor + nextCursor for infinite scroll; `until` re-anchors the
    // right edge of the window.
    traffic: (params: {
      macs?: string[]
      profileIds?: number[]
      from?: string
      to?: string
      bucket?: TrafficUsageBucket
      groupBy?: TrafficUsageGroupBy[]
      tz?: string
      limit?: number
      cursor?: string
      // #2047: the page passes its load-lifecycle signal so a superseded
      // request (bucket switch / filter change) is cancelled cleanly rather
      // than left in flight to resolve stale data — or to time out and trip
      // the unreachable banner.
      signal?: AbortSignal
    }) => {
      const qs = new URLSearchParams()
      if (params.macs?.length)             qs.set('mac', params.macs.join(','))
      if (params.profileIds?.length)       qs.set('profileId', params.profileIds.join(','))
      if (params.from)                     qs.set('from', params.from)
      if (params.to)                       qs.set('to', params.to)
      if (params.bucket)                   qs.set('bucket', params.bucket)
      if (params.groupBy) for (const g of params.groupBy) qs.append('groupBy', g)
      if (params.tz)                       qs.set('tz', params.tz)
      if (params.limit !== undefined)      qs.set('limit', String(params.limit))
      if (params.cursor)                   qs.set('cursor', params.cursor)
      return req<TrafficUsageResponse>('GET', `/usage/traffic?${qs}`, undefined, false, params.signal)
    },
  },

  // ── Logs ───────────────────────────────────────────────────────────────
  // #862: cursor + nextCursor on both endpoints. `until` anchors the right
  // edge of the window (defaults to NOW() server-side) — supports a Jump-to-Date
  // picker. `offset` is gone; keyset paging is stable under inserts.
  logs: {
    query: (params: {
      macs?: string[]
      deviceIds?: number[]
      profileIds?: number[]
      blocked?: boolean
      domain?: string
      location?: string
      hours?: number
      limit?: number
      until?: string
      cursor?: string
    }) => {
      const qs = new URLSearchParams()
      if (params.macs?.length)       qs.set('mac', params.macs.join(','))
      if (params.deviceIds?.length)  qs.set('deviceId', params.deviceIds.join(','))
      if (params.profileIds?.length) qs.set('profileId', params.profileIds.join(','))
      if (params.blocked !== undefined) qs.set('blocked', String(params.blocked))
      if (params.domain)   qs.set('domain', params.domain)
      if (params.location) qs.set('location', params.location)
      if (params.hours)    qs.set('hours', String(params.hours))
      if (params.limit)    qs.set('limit', String(params.limit))
      if (params.until)    qs.set('until', params.until)
      if (params.cursor)   qs.set('cursor', params.cursor)
      return req<QueryLogPage>('GET', `/logs?${qs}`)
    },
    // #847 + #917: aggregated connection-events series. groupBy is sent as
    // repeated query params; empty = one row per time bucket. apex deferred
    // to #856; app turned on by #769.
    series: (params: {
      bucket: '1m' | '10m' | '1h' | '12h' | '1d' | '1w'
      groupBy: Array<'domain' | 'device' | 'profile' | 'apex' | 'app'>
      macs?: string[]
      deviceIds?: number[]
      profileIds?: number[]
      blocked?: boolean
      domain?: string
      location?: string
      hours?: number
      limit?: number
      until?: string
      cursor?: string
    }) => {
      const qs = new URLSearchParams()
      qs.set('bucket', params.bucket)
      for (const g of params.groupBy) qs.append('groupBy', g)
      if (params.macs?.length)       qs.set('mac', params.macs.join(','))
      if (params.deviceIds?.length)  qs.set('deviceId', params.deviceIds.join(','))
      if (params.profileIds?.length) qs.set('profileId', params.profileIds.join(','))
      if (params.blocked !== undefined) qs.set('blocked', String(params.blocked))
      if (params.domain)   qs.set('domain', params.domain)
      if (params.location) qs.set('location', params.location)
      if (params.hours)    qs.set('hours', String(params.hours))
      if (params.limit)    qs.set('limit', String(params.limit))
      if (params.until)    qs.set('until', params.until)
      if (params.cursor)   qs.set('cursor', params.cursor)
      return req<ConnectionEventSeriesPage>('GET', `/connection-events/series?${qs}`)
    },
    stats: () => req<DashboardStats>('GET', '/stats'),
  },

  // ── Dashboard "now" ────────────────────────────────────────────────────
  dashboard: {
    now: () => req<DashboardNow>('GET', '/dashboard/now'),
  },

  // ── Named schedules (#1069) ────────────────────────────────────────────
  // Household-scoped reusable time-window primitive. Admin-only writes; the
  // PATCH carries the full schedule shape (replace semantics) so the edit form
  // can autosave (#423/#995). Referenced rows cascade-delete their references
  // server-side, so the SPA warns before deleting a referenced schedule.
  schedules: {
    list: () => req<NamedSchedule[]>('GET', '/schedules'),
    create: (data: NamedScheduleRequest) =>
      req<NamedSchedule>('POST', '/schedules', data),
    update: (id: number, data: NamedScheduleRequest) =>
      req<NamedSchedule>('PATCH', `/schedules/${id}`, data),
    delete: (id: number) => req<void>('DELETE', `/schedules/${id}`),
  },

  // ── Blocklists ─────────────────────────────────────────────────────────
  blocklists: {
    list: () => req<BlocklistSummary[]>('GET', '/blocklists'),
    hosts: (id: string) => req<BlocklistHosts>('GET', `/blocklists/${id}/hosts`),
    clearCategory: (cat: string) => req<void>('POST', `/blocklists/${cat}/clear`, {}),
  },

  // ── Apps (#762/#765) ───────────────────────────────────────────────────
  // #1798 — app *definitions* are authored only via the built-in `AppTemplates`
  // in code (seeded/reconciled server-side); the SPA-facing create/edit/delete
  // wrappers were removed. Only reads + policy assignment remain.
  apps: {
    list: () => req<AppDetail[]>('GET', '/apps'),
    get: (id: number) => req<AppDetail>('GET', `/apps/${id}`),
    setPolicy: (id: number, profileId: number, data: UpsertAppAssignmentRequest) =>
      req<void>('PUT', `/apps/${id}/policy/${profileId}`, data),
    deletePolicy: (id: number, profileId: number) =>
      req<void>('DELETE', `/apps/${id}/policy/${profileId}`),
  },

  // #959: kid-side block-page lookup. Unauthenticated — hit from a blocked
  // device after the router DNATs to the SPA's /blocked route.
  blocked: {
    info: (mac: string, host: string) => {
      const qs = new URLSearchParams({ mac, host })
      return req<BlockedInfoResponse>('GET', `/blocked?${qs}`, undefined, true)
    },
  },

  // ── Routers (admin) ────────────────────────────────────────────────────
  routers: {
    list: () => req<RouterSummary[]>('GET', '/admin/routers'),
    create: (data: CreateRouterRequest) =>
      req<CreateRouterResponse>('POST', '/admin/routers', data),
    delete: (id: string) => req<void>('DELETE', `/admin/routers/${id}`),
  },
}
