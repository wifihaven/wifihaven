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
  ;(api.household.get as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({
    dailyResetTime: '00:00',
    dailyResetTz: 'America/Los_Angeles',
  })
  ;(api.household.update as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(undefined)
})

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
    (api.household.get as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({
      dailyResetTime: '13:30',
      dailyResetTz: 'America/New_York',
    })
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
