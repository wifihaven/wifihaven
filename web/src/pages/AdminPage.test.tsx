import { describe, it, expect, beforeEach, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

vi.mock('@/api/client', () => ({
  api: {
    household: {
      get: vi.fn(),
      update: vi.fn(),
      patch: vi.fn(),
    },
  },
}))

import { api } from '@/api/client'
import type { HeartbeatFilter, HouseholdSettings, UnmanagedMacPolicy } from '@/types/api'
import { AdminPage } from './AdminPage'

const DEFAULT_HF: HeartbeatFilter = {
  enabled: false,
  bytesThreshold: 2048,
  heartbeatHostPatterns: [],
}

const DEFAULT_UMM: UnmanagedMacPolicy = { policy: 'allow', blockPage: true }

beforeEach(() => {
  vi.resetAllMocks()
  // Server-of-record: simulates the live API. `patch` deep-merges into this
  // and `get` reads from it, so tests exercise the real round-trip the
  // autosave UI does (PATCH a single field, then re-GET to reconcile).
  let stored: HouseholdSettings = {
    dailyResetTime: '00:00',
    dailyResetTz: 'America/Los_Angeles',
    heartbeatFilter: { ...DEFAULT_HF },
    unmanagedMacPolicy: { ...DEFAULT_UMM },
  }
  ;(api.household.get as unknown as ReturnType<typeof vi.fn>).mockImplementation(
    async () => ({
      ...stored,
      heartbeatFilter: { ...stored.heartbeatFilter },
      unmanagedMacPolicy: { ...stored.unmanagedMacPolicy },
    }),
  )
  ;(api.household.patch as unknown as ReturnType<typeof vi.fn>).mockImplementation(
    async (patch: Partial<HouseholdSettings>) => {
      stored = {
        ...stored,
        ...patch,
        heartbeatFilter: { ...stored.heartbeatFilter, ...(patch.heartbeatFilter ?? {}) },
        unmanagedMacPolicy: {
          ...stored.unmanagedMacPolicy,
          ...(patch.unmanagedMacPolicy ?? {}),
        },
      }
    },
  )
})

describe('AdminPage — daily reset autosave (#1002)', () => {
  it('renders live time + tz inputs, no Edit/Save/Cancel buttons', async () => {
    render(<AdminPage />)
    const time = await screen.findByTestId('household-reset-time') as HTMLInputElement
    expect(time.value).toBe('00:00')
    expect(screen.getByTestId('household-reset-tz-select')).toBeInTheDocument()
    expect(screen.queryByTestId('household-edit')).not.toBeInTheDocument()
    expect(screen.queryByTestId('household-save')).not.toBeInTheDocument()
    expect(screen.queryByTestId('household-cancel')).not.toBeInTheDocument()
  })

  it('editing the reset time fires a debounced PATCH {dailyResetTime}', async () => {
    // Let the initial async `api.household.get()` load fully settle under real
    // timers BEFORE switching to fake timers. Enabling fake timers up front
    // makes `findByTestId`'s internal waitFor resolve while a load-driven React
    // update is still pending, and the next `fireEvent.change` is clobbered by
    // that unflushed resync — so the controlled input never reaches '06:00' and
    // the debounce never fires (#1354). Fake timers are only needed for the
    // deterministic debounce window, so we scope them to it.
    render(<AdminPage />)
    const time = await screen.findByTestId('household-reset-time') as HTMLInputElement
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      // Set the value atomically: typing a `<input type="time">` char-by-char
      // under fake timers lets the 700ms debounce fire on a transient partial
      // value (e.g. '00:59') before the full '06:00' lands. fireEvent.change
      // gives the debounce a single, final value to latch.
      fireEvent.change(time, { target: { value: '06:00' } })
      expect(api.household.patch).not.toHaveBeenCalled()
      await vi.advanceTimersByTimeAsync(700)

      expect(api.household.patch).toHaveBeenCalledWith({ dailyResetTime: '06:00' })
    } finally {
      vi.useRealTimers()
    }
  })

  it('changing the timezone fires PATCH {dailyResetTz}', async () => {
    const user = userEvent.setup()
    render(<AdminPage />)
    await screen.findByTestId('household-reset-time')

    const tzSelect = screen.getByTestId('household-reset-tz-select') as HTMLSelectElement
    await user.selectOptions(tzSelect, 'America/New_York')

    await waitFor(() =>
      expect(api.household.patch).toHaveBeenCalledWith({ dailyResetTz: 'America/New_York' }),
    )
  })

  it('PATCH failure surfaces error + Retry; dirty value retained; Retry re-sends', async () => {
    (api.household.patch as unknown as ReturnType<typeof vi.fn>).mockRejectedValueOnce(
      new Error('boom from server'),
    )
    // Settle the initial load under real timers before enabling fake timers —
    // see the reset-time test for why (#1354).
    render(<AdminPage />)
    const time = await screen.findByTestId('household-reset-time') as HTMLInputElement
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    try {
      fireEvent.change(time, { target: { value: '06:00' } })
      await vi.advanceTimersByTimeAsync(700)

      const status = screen.getByTestId('household-save-status')
      await waitFor(() => expect(status).toHaveAttribute('data-status', 'error'))
      expect((screen.getByTestId('household-reset-time') as HTMLInputElement).value).toBe('06:00')

      await user.click(screen.getByTestId('household-save-status-retry'))
      await waitFor(() => expect(api.household.patch).toHaveBeenCalledTimes(2))
    } finally {
      vi.useRealTimers()
    }
  })
})

describe('AdminPage — heartbeat filter autosave (#1002)', () => {
  it('renders live enabled + bytes controls, no Save button', async () => {
    render(<AdminPage />)
    const enabled = await screen.findByTestId('heartbeat-filter-enabled') as HTMLInputElement
    const bytes = screen.getByTestId('heartbeat-filter-bytes') as HTMLInputElement
    expect(enabled.checked).toBe(false)
    expect(bytes.value).toBe('2048')
    expect(screen.queryByTestId('heartbeat-filter-save')).not.toBeInTheDocument()
  })

  it('toggling enabled fires PATCH {heartbeatFilter:{enabled}}', async () => {
    const user = userEvent.setup()
    render(<AdminPage />)
    await screen.findByTestId('heartbeat-filter-enabled')

    await user.click(screen.getByTestId('heartbeat-filter-enabled'))

    await waitFor(() =>
      expect(api.household.patch).toHaveBeenCalledWith({ heartbeatFilter: { enabled: true } }),
    )
  })

  it('editing the bytes threshold fires PATCH {heartbeatFilter:{bytesThreshold}}', async () => {
    // Settle the initial load under real timers before enabling fake timers —
    // see the reset-time test for why (#1354).
    render(<AdminPage />)
    const bytes = await screen.findByTestId('heartbeat-filter-bytes') as HTMLInputElement
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      fireEvent.change(bytes, { target: { value: '8192' } })
      await vi.advanceTimersByTimeAsync(700)

      expect(api.household.patch).toHaveBeenCalledWith({
        heartbeatFilter: { bytesThreshold: 8192 },
      })
    } finally {
      vi.useRealTimers()
    }
  })
})

describe('AdminPage — unmanaged-MAC policy autosave (#1002)', () => {
  it('selecting block fires PATCH {unmanagedMacPolicy:{policy}}', async () => {
    const user = userEvent.setup()
    render(<AdminPage />)
    await screen.findByTestId('unmanaged-mac-policy-block')

    await user.click(screen.getByTestId('unmanaged-mac-policy-block'))

    await waitFor(() =>
      expect(api.household.patch).toHaveBeenCalledWith({
        unmanagedMacPolicy: { policy: 'block' },
      }),
    )
  })

  it('block-page checkbox is disabled while policy is allow', async () => {
    render(<AdminPage />)
    const card = await screen.findByTestId('unmanaged-mac-policy-card')
    const blockPage = within(card).getByTestId('unmanaged-mac-policy-block-page') as HTMLInputElement
    expect(blockPage.disabled).toBe(true)
  })
})
