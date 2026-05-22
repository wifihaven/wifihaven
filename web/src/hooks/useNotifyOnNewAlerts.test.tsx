import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { DeviceAlert } from '@/types/api'

vi.mock('@/api/client', () => ({
  api: {
    deviceAlerts: {
      list: vi.fn(),
    },
  },
}))

import { api } from '@/api/client'
import { qk } from '@/api/queries'
import { useNotifyOnNewAlerts, useNotificationPermission } from './useNotifyOnNewAlerts'

const baseAlert: DeviceAlert = {
  id: 1,
  mac: 'aa:bb:cc:11:22:33',
  deviceName: 'device-112233',
  profileId: null,
  profileName: null,
  firstSeenAt: '2026-05-22T12:00:00Z',
  dismissedAt: null,
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
  // @ts-expect-error - injecting into jsdom
  globalThis.Notification = FakeNotification
  // @ts-expect-error - inject into window separately; jsdom's window is its own
  // object distinct from globalThis for typed globals like Notification.
  window.Notification = FakeNotification
}

function uninstallNotification() {
  // @ts-expect-error - cleanup
  delete globalThis.Notification
  // @ts-expect-error - cleanup
  delete window.Notification
}

const mockList = () => api.deviceAlerts.list as unknown as ReturnType<typeof vi.fn>

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
  // Default: page is hidden / unfocused so notifications are allowed to fire.
  Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true })
  Object.defineProperty(document, 'hasFocus', { value: () => false, configurable: true })
})

afterEach(() => {
  uninstallNotification()
})

describe('useNotifyOnNewAlerts', () => {
  it('does NOT notify on the initial fetch (avoid flood on page load)', async () => {
    mockList().mockResolvedValue([baseAlert])
    renderHook(() => useNotifyOnNewAlerts(), { wrapper: wrap })
    await waitFor(() => expect(mockList()).toHaveBeenCalled())
    expect(FakeNotification.instances).toHaveLength(0)
  })

  async function awaitFirstFetch() {
    // Wait for the seeded data to actually settle into the cache so the hook's
    // useEffect runs once with the initial array before we invalidate. Without
    // this, the React Query observer collapses both updates into a single
    // render and the "first batch is treated as seen" branch swallows the new
    // alert too.
    await waitFor(() =>
      expect(testClient.getQueryData(qk.deviceAlerts())).toBeDefined(),
    )
  }

  async function refetchWith(alerts: DeviceAlert[]) {
    mockList().mockResolvedValueOnce(alerts)
    await act(async () => {
      await testClient.invalidateQueries({ queryKey: qk.deviceAlerts() })
    })
  }

  it('fires a Notification when a new alert ID appears in a later fetch', async () => {
    mockList().mockResolvedValueOnce([baseAlert])
    renderHook(() => useNotifyOnNewAlerts(), { wrapper: wrap })
    await waitFor(() => expect(mockList()).toHaveBeenCalledTimes(1))
    await awaitFirstFetch()

    const second: DeviceAlert = { ...baseAlert, id: 2, mac: 'aa:bb:cc:44:55:66', deviceName: 'phone' }
    await refetchWith([baseAlert, second])

    await waitFor(() => expect(FakeNotification.instances).toHaveLength(1))
    expect(FakeNotification.instances[0].title).toBe('New device on the network')
    expect(FakeNotification.instances[0].options?.body).toContain('aa:bb:cc:44:55:66')
  })

  it('does NOT fire when permission is not granted', async () => {
    installNotification('default')
    mockList().mockResolvedValueOnce([])
    renderHook(() => useNotifyOnNewAlerts(), { wrapper: wrap })
    await waitFor(() => expect(mockList()).toHaveBeenCalledTimes(1))
    await awaitFirstFetch()

    await refetchWith([baseAlert])
    expect(FakeNotification.instances).toHaveLength(0)
  })

  it('does NOT fire when the tab is focused (banner is already visible)', async () => {
    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true })
    Object.defineProperty(document, 'hasFocus', { value: () => true, configurable: true })

    mockList().mockResolvedValueOnce([])
    renderHook(() => useNotifyOnNewAlerts(), { wrapper: wrap })
    await waitFor(() => expect(mockList()).toHaveBeenCalledTimes(1))
    await awaitFirstFetch()

    await refetchWith([baseAlert])
    expect(FakeNotification.instances).toHaveLength(0)
  })

  it('is a no-op when the browser does not support Notification', async () => {
    uninstallNotification()
    mockList().mockResolvedValue([baseAlert])
    renderHook(() => useNotifyOnNewAlerts(), { wrapper: wrap })
    await waitFor(() => expect(mockList()).toHaveBeenCalled())
    // No throw; nothing to assert beyond "did not crash"
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

  it('request() prompts the browser and updates state', async () => {
    installNotification('default')
    FakeNotification.requestPermission.mockResolvedValueOnce('granted')
    const { result } = renderHook(() => useNotificationPermission())
    expect(result.current.state).toBe('default')
    await act(async () => {
      await result.current.request()
    })
    expect(FakeNotification.requestPermission).toHaveBeenCalled()
    expect(result.current.state).toBe('granted')
  })
})
