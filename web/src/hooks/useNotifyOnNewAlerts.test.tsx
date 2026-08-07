import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { Alert } from '@/types/api'

vi.mock('@/api/client', () => ({
  api: {
    alerts: {
      list: vi.fn(),
    },
  },
}))

import { api } from '@/api/client'
import { qk } from '@/api/queryKeys'
import { useNotifyOnNewAlerts, useNotificationPermission } from './useNotifyOnNewAlerts'

const newDevice: Alert = {
  id: 1,
  kind: 'new_device',
  status: 'pending',
  mac: 'aa:bb:cc:11:22:33',
  deviceName: 'device-112233',
  profileId: null,
  profileName: null,
  host: null,
  requestKind: null,
  note: null,
  grantedMinutes: null,
  createdAt: '2026-05-22T12:00:00Z',
  decidedAt: null,
  decidedBy: null,
  householdId: null,
}

const accessReq: Alert = {
  id: 9,
  kind: 'access_request',
  status: 'pending',
  mac: 'aa:bb:cc:44:55:66',
  deviceName: 'kid-laptop',
  profileId: 1,
  profileName: 'Kids',
  host: 'tiktok.com',
  requestKind: 'exemption',
  note: null,
  grantedMinutes: null,
  createdAt: '2026-05-26T10:00:00Z',
  decidedAt: null,
  decidedBy: null,
  householdId: null,
}

class FakeNotification {
  static permission: NotificationPermission = 'granted'
  static instances: FakeNotification[] = []
  static requestPermission = vi.fn(async (): Promise<NotificationPermission> => 'granted')
  constructor(public title: string, public options?: NotificationOptions) {
    FakeNotification.instances.push(this)
  }
}

function installNotification(permission: NotificationPermission = 'granted') {
  FakeNotification.permission = permission
  FakeNotification.instances = []
  // @ts-expect-error inject into jsdom
  globalThis.Notification = FakeNotification
  // @ts-expect-error jsdom's window holds its own typed-global reference
  window.Notification = FakeNotification
}

function uninstallNotification() {
  // @ts-expect-error cleanup
  delete globalThis.Notification
  // @ts-expect-error cleanup
  delete window.Notification
}

const mockList = () => api.alerts.list as unknown as ReturnType<typeof vi.fn>

let testClient: QueryClient

function makeClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, refetchOnWindowFocus: false, refetchOnReconnect: false, staleTime: 0, gcTime: Infinity },
      mutations: { retry: false },
    },
  })
}

function wrap({ children }: { children: React.ReactNode }) {
  return <QueryClientProvider client={testClient}>{children}</QueryClientProvider>
}

beforeEach(() => {
  vi.resetAllMocks()
  installNotification('granted')
  testClient = makeClient()
  Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true })
  Object.defineProperty(document, 'hasFocus', { value: () => false, configurable: true })
})

afterEach(() => {
  uninstallNotification()
})

async function awaitFirstFetch() {
  await waitFor(() =>
    expect(testClient.getQueryData(qk.alerts(false))).toBeDefined(),
  )
}

async function refetchWith(alerts: Alert[]) {
  mockList().mockResolvedValueOnce(alerts)
  await act(async () => {
    await testClient.invalidateQueries({ queryKey: qk.alertsAll() })
  })
}

describe('useNotifyOnNewAlerts — unified engine (#960 unifies #711)', () => {
  it('does NOT notify on the initial fetch (avoid flood on page load)', async () => {
    mockList().mockResolvedValue([newDevice])
    renderHook(() => useNotifyOnNewAlerts(), { wrapper: wrap })
    await waitFor(() => expect(mockList()).toHaveBeenCalled())
    expect(FakeNotification.instances).toHaveLength(0)
  })

  it('fires a Notification with the new-device title for a new_device kind', async () => {
    mockList().mockResolvedValueOnce([])
    renderHook(() => useNotifyOnNewAlerts(), { wrapper: wrap })
    await waitFor(() => expect(mockList()).toHaveBeenCalled())
    await awaitFirstFetch()

    await refetchWith([newDevice])
    await waitFor(() => expect(FakeNotification.instances).toHaveLength(1))
    expect(FakeNotification.instances[0].title).toBe('New device on the network')
    expect(FakeNotification.instances[0].options?.body).toContain('aa:bb:cc:11:22:33')
  })

  it('fires an access-request-flavoured Notification for kind=access_request (#960)', async () => {
    mockList().mockResolvedValueOnce([])
    renderHook(() => useNotifyOnNewAlerts(), { wrapper: wrap })
    await waitFor(() => expect(mockList()).toHaveBeenCalled())
    await awaitFirstFetch()

    await refetchWith([accessReq])
    await waitFor(() => expect(FakeNotification.instances).toHaveLength(1))
    expect(FakeNotification.instances[0].title).toMatch(/access request/i)
    expect(FakeNotification.instances[0].options?.body).toContain('tiktok.com')
  })

  it('does NOT fire when permission is not granted', async () => {
    installNotification('default')
    mockList().mockResolvedValueOnce([])
    renderHook(() => useNotifyOnNewAlerts(), { wrapper: wrap })
    await waitFor(() => expect(mockList()).toHaveBeenCalled())
    await awaitFirstFetch()

    await refetchWith([newDevice])
    expect(FakeNotification.instances).toHaveLength(0)
  })

  it('does NOT fire when the tab is focused (banner is already onscreen)', async () => {
    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true })
    Object.defineProperty(document, 'hasFocus', { value: () => true, configurable: true })

    mockList().mockResolvedValueOnce([])
    renderHook(() => useNotifyOnNewAlerts(), { wrapper: wrap })
    await waitFor(() => expect(mockList()).toHaveBeenCalled())
    await awaitFirstFetch()

    await refetchWith([newDevice])
    expect(FakeNotification.instances).toHaveLength(0)
  })

  it('is a no-op when the browser does not support Notification', async () => {
    uninstallNotification()
    mockList().mockResolvedValue([newDevice])
    renderHook(() => useNotifyOnNewAlerts(), { wrapper: wrap })
    await waitFor(() => expect(mockList()).toHaveBeenCalled())
    // No throw is the assertion.
  })
})

describe('useNotificationPermission', () => {
  it('returns current permission state', () => {
    installNotification('granted')
    const { result } = renderHook(() => useNotificationPermission())
    expect(result.current.state).toBe('granted')
  })

  it('returns "unsupported" when Notification is undefined', () => {
    uninstallNotification()
    const { result } = renderHook(() => useNotificationPermission())
    expect(result.current.state).toBe('unsupported')
  })
})
