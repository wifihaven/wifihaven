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
import { AdminPage } from './AdminPage'

beforeEach(() => {
  vi.resetAllMocks()
  // Server-of-record: simulates the live API. `update` mutates this and
  // `get` reads from it, so tests exercise the real round-trip the UI does
  // (PUT, then re-GET to refresh the summary — #571).
  let stored: { dailyResetTime: string; dailyResetTz: string } = {
    dailyResetTime: '00:00',
    dailyResetTz: 'America/Los_Angeles',
  }
  ;(api.household.get as unknown as ReturnType<typeof vi.fn>).mockImplementation(
    async () => ({ ...stored }),
  )
  ;(api.household.update as unknown as ReturnType<typeof vi.fn>).mockImplementation(
    async (next: { dailyResetTime: string; dailyResetTz: string }) => {
      stored = { ...next }
    },
  )
})

// Per-test override helper for the initial server state.
function seedServer(s: { dailyResetTime: string; dailyResetTz: string }) {
  ;(api.household.get as unknown as ReturnType<typeof vi.fn>).mockResolvedValueOnce({ ...s })
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
      }),
    )
  })

  it('(#571) summary after save reflects what the server returns, not the local form', async () => {
    // Simulate a server that silently drops dailyResetTz (the suspected
    // shape of bug #571). The UI must surface the server-of-record value,
    // not the user's typed-in value, so the operator sees the persistence
    // failure rather than a false confirmation.
    ;(api.household.update as unknown as ReturnType<typeof vi.fn>).mockImplementationOnce(
      async (next: { dailyResetTime: string }) => {
        // Pretend server only persisted the time, ignoring the tz.
        ;(api.household.get as unknown as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
          dailyResetTime: next.dailyResetTime,
          dailyResetTz: 'America/Los_Angeles',
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
