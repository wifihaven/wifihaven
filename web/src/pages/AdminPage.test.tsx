import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

vi.mock('@/api/client', () => ({
  api: {
    household: {
      get: vi.fn(),
      update: vi.fn(),
    },
  },
}))

import { api } from '@/api/client'
import type { HeartbeatFilter, HouseholdSettings } from '@/types/api'
import { AdminPage } from './AdminPage'

const DEFAULT_HF: HeartbeatFilter = {
  enabled: false,
  bytesThreshold: 2048,
  activeFractionPct: 20,
}

beforeEach(() => {
  vi.resetAllMocks()
  // Server-of-record: simulates the live API. `update` mutates this and
  // `get` reads from it, so tests exercise the real round-trip the UI does
  // (PUT, then re-GET to refresh the summary — #571).
  let stored: HouseholdSettings = {
    dailyResetTime: '00:00',
    dailyResetTz: 'America/Los_Angeles',
    heartbeatFilter: { ...DEFAULT_HF },
  }
  ;(api.household.get as unknown as ReturnType<typeof vi.fn>).mockImplementation(
    async () => ({ ...stored, heartbeatFilter: { ...stored.heartbeatFilter } }),
  )
  ;(api.household.update as unknown as ReturnType<typeof vi.fn>).mockImplementation(
    async (next: HouseholdSettings) => {
      stored = { ...next, heartbeatFilter: { ...next.heartbeatFilter } }
    },
  )
})

// Per-test override helper for the initial server state.
function seedServer(s: Partial<HouseholdSettings>) {
  (api.household.get as unknown as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
    dailyResetTime: '00:00',
    dailyResetTz: 'America/Los_Angeles',
    heartbeatFilter: { ...DEFAULT_HF },
    ...s,
  })
}

describe('AdminPage — daily reset card', () => {
  it('defaults to collapsed summary view with current values', async () => {
    render(<AdminPage />)
    const summary = await screen.findByTestId('household-summary')
    expect(summary).toHaveTextContent(/Resets daily at 12:00 AM/i)
    expect(summary).toHaveTextContent('America/Los_Angeles')
    // Form inputs are hidden in viewing state
    expect(screen.queryByTestId('household-reset-time')).not.toBeInTheDocument()
    expect(screen.queryByTestId('household-save')).not.toBeInTheDocument()
  })

  it('formats 13:30 as "1:30 PM" in the summary', async () => {
    seedServer({ dailyResetTime: '13:30', dailyResetTz: 'America/New_York' })
    render(<AdminPage />)
    const summary = await screen.findByTestId('household-summary')
    expect(summary).toHaveTextContent(/Resets daily at 1:30 PM/i)
    expect(summary).toHaveTextContent('America/New_York')
  })

  it('clicking Edit opens the form pre-filled with current values', async () => {
    const user = userEvent.setup()
    render(<AdminPage />)
    await screen.findByTestId('household-summary')
    await user.click(screen.getByTestId('household-edit'))

    const time = screen.getByTestId('household-reset-time') as HTMLInputElement
    expect(time.value).toBe('00:00')
    expect(screen.getByTestId('household-save')).toBeInTheDocument()
    expect(screen.getByTestId('household-cancel')).toBeInTheDocument()
    expect(screen.queryByTestId('household-summary')).not.toBeInTheDocument()
  })

  it('saving calls api.household.update and collapses back to the summary with new values', async () => {
    const user = userEvent.setup()
    render(<AdminPage />)
    await screen.findByTestId('household-summary')
    await user.click(screen.getByTestId('household-edit'))

    const time = screen.getByTestId('household-reset-time') as HTMLInputElement
    await user.clear(time)
    await user.type(time, '06:00')

    await user.click(screen.getByTestId('household-save'))

    await waitFor(() =>
      expect(api.household.update).toHaveBeenCalledWith({
        dailyResetTime: '06:00',
        dailyResetTz: 'America/Los_Angeles',
        heartbeatFilter: DEFAULT_HF,
      }),
    )
    const summary = await screen.findByTestId('household-summary')
    expect(summary).toHaveTextContent(/Resets daily at 6:00 AM/i)
    expect(screen.queryByTestId('household-reset-time')).not.toBeInTheDocument()
  })

  it('cancel returns to the summary without persisting changes', async () => {
    const user = userEvent.setup()
    render(<AdminPage />)
    await screen.findByTestId('household-summary')
    await user.click(screen.getByTestId('household-edit'))

    const time = screen.getByTestId('household-reset-time') as HTMLInputElement
    await user.clear(time)
    await user.type(time, '06:00')

    await user.click(screen.getByTestId('household-cancel'))

    expect(api.household.update).not.toHaveBeenCalled()
    const summary = await screen.findByTestId('household-summary')
    // Summary still shows original value
    expect(summary).toHaveTextContent(/Resets daily at 12:00 AM/i)

    // Re-opening edit shows original (not dirty) value
    await user.click(screen.getByTestId('household-edit'))
    expect((screen.getByTestId('household-reset-time') as HTMLInputElement).value).toBe('00:00')
  })

  it('(#571) changing the TZ in the dropdown persists it via update', async () => {
    const user = userEvent.setup()
    render(<AdminPage />)
    await screen.findByTestId('household-summary')
    await user.click(screen.getByTestId('household-edit'))

    const tzSelect = screen.getByTestId('household-reset-tz-select') as HTMLSelectElement
    await user.selectOptions(tzSelect, 'America/New_York')

    await user.click(screen.getByTestId('household-save'))

    await waitFor(() =>
      expect(api.household.update).toHaveBeenCalledWith({
        dailyResetTime: '00:00',
        dailyResetTz: 'America/New_York',
        heartbeatFilter: DEFAULT_HF,
      }),
    )
  })

  it('(#571) filter that narrows to one option then click persists it', async () => {
    // Repro of the on-device bug: operator picks "More…", types a filter
    // that narrows to a single match, clicks it, hits Save. Pre-fix the
    // controlled <select>'s value never updated because the lone <option>
    // was already the browser's de-facto selection, so no `change` fired.
    const user = userEvent.setup()
    render(<AdminPage />)
    await screen.findByTestId('household-summary')
    await user.click(screen.getByTestId('household-edit'))

    const tzSelect = screen.getByTestId('household-reset-tz-select') as HTMLSelectElement
    await user.selectOptions(tzSelect, '__more__')

    const filterInput = screen.getByTestId('household-reset-tz-filter')
    await user.type(filterInput, 'Denver')

    const expanded = screen.getByTestId('household-reset-tz-select') as HTMLSelectElement
    await user.selectOptions(expanded, 'America/Denver')

    await user.click(screen.getByTestId('household-save'))

    await waitFor(() =>
      expect(api.household.update).toHaveBeenCalledWith({
        dailyResetTime: '00:00',
        dailyResetTz: 'America/Denver',
        heartbeatFilter: DEFAULT_HF,
      }),
    )
  })

  it('(#571) picking a non-common TZ via "More…" expander persists it', async () => {
    const user = userEvent.setup()
    render(<AdminPage />)
    await screen.findByTestId('household-summary')
    await user.click(screen.getByTestId('household-edit'))

    const tzSelect = screen.getByTestId('household-reset-tz-select') as HTMLSelectElement
    await user.selectOptions(tzSelect, '__more__')

    const expanded = screen.getByTestId('household-reset-tz-select') as HTMLSelectElement
    await user.selectOptions(expanded, 'Europe/London')

    await user.click(screen.getByTestId('household-save'))

    await waitFor(() =>
      expect(api.household.update).toHaveBeenCalledWith({
        dailyResetTime: '00:00',
        dailyResetTz: 'Europe/London',
        heartbeatFilter: DEFAULT_HF,
      }),
    )
  })

  it('(#571) summary after save reflects what the server returns, not the local form', async () => {
    // Simulate a server that silently drops dailyResetTz (the suspected
    // shape of bug #571). The UI must surface the server-of-record value,
    // not the user's typed-in value, so the operator sees the persistence
    // failure rather than a false confirmation.
    (api.household.update as unknown as ReturnType<typeof vi.fn>).mockImplementationOnce(
      async (next: { dailyResetTime: string }) => {
        // Pretend server only persisted the time, ignoring the tz.
        (api.household.get as unknown as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
          dailyResetTime: next.dailyResetTime,
          dailyResetTz: 'America/Los_Angeles',
          heartbeatFilter: { ...DEFAULT_HF },
        })
      },
    )
    const user = userEvent.setup()
    render(<AdminPage />)
    await screen.findByTestId('household-summary')
    await user.click(screen.getByTestId('household-edit'))

    const tzSelect = screen.getByTestId('household-reset-tz-select') as HTMLSelectElement
    await user.selectOptions(tzSelect, 'America/New_York')
    await user.click(screen.getByTestId('household-save'))

    const summary = await screen.findByTestId('household-summary')
    // Surfaces the dropped value: America/Los_Angeles, NOT America/New_York.
    expect(summary).toHaveTextContent('America/Los_Angeles')
  })

  it('keeps the form open and shows an error when the API rejects', async () => {
    (api.household.update as unknown as ReturnType<typeof vi.fn>).mockRejectedValue(
      new Error('boom from server'),
    )
    const user = userEvent.setup()
    render(<AdminPage />)
    await screen.findByTestId('household-summary')
    await user.click(screen.getByTestId('household-edit'))
    await user.click(screen.getByTestId('household-save'))

    expect(await screen.findByText(/boom from server/i)).toBeInTheDocument()
    // Still in editing state
    expect(screen.getByTestId('household-reset-time')).toBeInTheDocument()
    expect(screen.queryByTestId('household-summary')).not.toBeInTheDocument()
  })
})

describe('AdminPage — heartbeat filter card', () => {
  it('summary shows "Disabled" when filter is off', async () => {
    render(<AdminPage />)
    const summary = await screen.findByTestId('heartbeat-filter-summary')
    expect(summary).toHaveTextContent(/Disabled/i)
  })

  it('summary shows thresholds when filter is enabled', async () => {
    seedServer({
      heartbeatFilter: { enabled: true, bytesThreshold: 4096, activeFractionPct: 30 },
    })
    render(<AdminPage />)
    const summary = await screen.findByTestId('heartbeat-filter-summary')
    expect(summary).toHaveTextContent(/Enabled/i)
    expect(summary).toHaveTextContent('4096')
    expect(summary).toHaveTextContent('30%')
  })

  it('clicking Edit opens the form pre-filled with current values', async () => {
    const user = userEvent.setup()
    render(<AdminPage />)
    await screen.findByTestId('heartbeat-filter-summary')
    await user.click(screen.getByTestId('heartbeat-filter-edit'))

    const enabled = screen.getByTestId('heartbeat-filter-enabled') as HTMLInputElement
    const bytes = screen.getByTestId('heartbeat-filter-bytes') as HTMLInputElement
    const fraction = screen.getByTestId('heartbeat-filter-fraction') as HTMLInputElement
    expect(enabled.checked).toBe(false)
    expect(bytes.value).toBe('2048')
    expect(fraction.value).toBe('20')
  })

  it('toggle + save sends the right body and the summary reflects the GET reconcile', async () => {
    const user = userEvent.setup()
    render(<AdminPage />)
    await screen.findByTestId('heartbeat-filter-summary')
    await user.click(screen.getByTestId('heartbeat-filter-edit'))

    await user.click(screen.getByTestId('heartbeat-filter-enabled'))
    await user.click(screen.getByTestId('heartbeat-filter-save'))

    await waitFor(() =>
      expect(api.household.update).toHaveBeenCalledWith({
        dailyResetTime: '00:00',
        dailyResetTz: 'America/Los_Angeles',
        heartbeatFilter: { enabled: true, bytesThreshold: 2048, activeFractionPct: 20 },
      }),
    )
    const summary = await screen.findByTestId('heartbeat-filter-summary')
    expect(summary).toHaveTextContent(/Enabled/i)
    expect(summary).toHaveTextContent('2048')
  })

  it('threshold edits round-trip via update + re-GET', async () => {
    const user = userEvent.setup()
    render(<AdminPage />)
    await screen.findByTestId('heartbeat-filter-summary')
    await user.click(screen.getByTestId('heartbeat-filter-edit'))

    await user.click(screen.getByTestId('heartbeat-filter-enabled'))
    const bytes = screen.getByTestId('heartbeat-filter-bytes') as HTMLInputElement
    await user.clear(bytes)
    await user.type(bytes, '8192')
    const fraction = screen.getByTestId('heartbeat-filter-fraction') as HTMLInputElement
    await user.clear(fraction)
    await user.type(fraction, '40')

    await user.click(screen.getByTestId('heartbeat-filter-save'))

    await waitFor(() =>
      expect(api.household.update).toHaveBeenCalledWith({
        dailyResetTime: '00:00',
        dailyResetTz: 'America/Los_Angeles',
        heartbeatFilter: { enabled: true, bytesThreshold: 8192, activeFractionPct: 40 },
      }),
    )
    const summary = await screen.findByTestId('heartbeat-filter-summary')
    expect(summary).toHaveTextContent('8192')
    expect(summary).toHaveTextContent('40%')
  })

  it('cancel discards local changes', async () => {
    const user = userEvent.setup()
    render(<AdminPage />)
    await screen.findByTestId('heartbeat-filter-summary')
    await user.click(screen.getByTestId('heartbeat-filter-edit'))

    const bytes = screen.getByTestId('heartbeat-filter-bytes') as HTMLInputElement
    await user.clear(bytes)
    await user.type(bytes, '9999')
    await user.click(screen.getByTestId('heartbeat-filter-cancel'))

    expect(api.household.update).not.toHaveBeenCalled()
    const summary = await screen.findByTestId('heartbeat-filter-summary')
    expect(summary).toHaveTextContent(/Disabled/i)

    // Re-opening edit shows server value, not the dirty form.
    await user.click(screen.getByTestId('heartbeat-filter-edit'))
    expect((screen.getByTestId('heartbeat-filter-bytes') as HTMLInputElement).value).toBe('2048')
  })

  it('disables Save and shows a validation error when the active fraction is out of range', async () => {
    const user = userEvent.setup()
    render(<AdminPage />)
    await screen.findByTestId('heartbeat-filter-summary')
    await user.click(screen.getByTestId('heartbeat-filter-edit'))

    const fraction = screen.getByTestId('heartbeat-filter-fraction') as HTMLInputElement
    await user.clear(fraction)
    await user.type(fraction, '150')

    expect(screen.getByTestId('heartbeat-filter-validation')).toBeInTheDocument()
    expect(screen.getByTestId('heartbeat-filter-save')).toBeDisabled()
  })
})
