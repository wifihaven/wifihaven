import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

vi.mock('@/api/client', () => ({
  api: {
    schedules: {
      list: vi.fn(),
      create: vi.fn(),
      update: vi.fn(),
      delete: vi.fn(),
    },
    profiles: {
      list: vi.fn(),
    },
  },
}))

import { api } from '@/api/client'
import type { NamedSchedule, ProfileDetail } from '@/types/api'
import { SchedulesPage } from './SchedulesPage'
import { withQuery } from '@/test/queryWrapper'

const bedtime: NamedSchedule = {
  id: 1, name: 'Bedtime', description: 'overnight',
  windows: [{ days: ['mon', 'tue'], startLocal: '20:00', endLocal: '07:00', tz: 'UTC' }],
}
const school: NamedSchedule = {
  id: 2, name: 'School hours', description: null,
  windows: [{ days: ['mon', 'tue', 'wed', 'thu', 'fri'], startLocal: '08:00', endLocal: '15:00', tz: 'UTC' }],
}

// Kids references Bedtime (id 1); Adults references nothing.
const kids = {
  profile: { id: 10, name: 'Kids', blockedCategories: [], paused: false },
  timeLimit: null, scheduleIds: [1],
} as unknown as ProfileDetail
const adults = {
  profile: { id: 11, name: 'Adults', blockedCategories: [], paused: false },
  timeLimit: null, scheduleIds: [],
} as unknown as ProfileDetail

const fn = (f: unknown) => f as unknown as ReturnType<typeof vi.fn>

beforeEach(() => {
  vi.resetAllMocks()
  fn(api.schedules.list).mockResolvedValue([bedtime, school])
  fn(api.profiles.list).mockResolvedValue([kids, adults])
  fn(api.schedules.create).mockResolvedValue({ id: 3, name: 'Afternoon', description: null, windows: [] })
  fn(api.schedules.update).mockResolvedValue({ ...bedtime })
  fn(api.schedules.delete).mockResolvedValue(undefined)
})

describe('SchedulesPage (#1069) — list', () => {
  it('renders household schedules with their names', async () => {
    render(withQuery(<SchedulesPage />))
    expect(await screen.findByDisplayValue('Bedtime')).toBeInTheDocument()
    expect(screen.getByDisplayValue('School hours')).toBeInTheDocument()
  })

  it('shows which profiles reference a schedule', async () => {
    render(withQuery(<SchedulesPage />))
    await screen.findByDisplayValue('Bedtime')
    expect(screen.getByTestId('schedule-refs-1')).toHaveTextContent(/Kids/)
    // School hours is referenced by nobody → no refs line
    expect(screen.queryByTestId('schedule-refs-2')).not.toBeInTheDocument()
  })
})

describe('SchedulesPage (#1069) — create', () => {
  it('creates a named schedule via the API', async () => {
    const user = userEvent.setup()
    render(withQuery(<SchedulesPage />))
    await screen.findByDisplayValue('Bedtime')

    await user.click(screen.getByTestId('schedules-new'))
    await user.type(screen.getByTestId('schedule-create-name'), 'Afternoon')
    await user.click(screen.getByTestId('schedule-create-submit'))

    await waitFor(() => expect(api.schedules.create).toHaveBeenCalledTimes(1))
    const arg = fn(api.schedules.create).mock.calls[0][0]
    expect(arg.name).toBe('Afternoon')
    expect(Array.isArray(arg.windows)).toBe(true)
  })

  it('blocks create with an empty name', async () => {
    const user = userEvent.setup()
    render(withQuery(<SchedulesPage />))
    await screen.findByDisplayValue('Bedtime')
    await user.click(screen.getByTestId('schedules-new'))
    await user.click(screen.getByTestId('schedule-create-submit'))
    expect(api.schedules.create).not.toHaveBeenCalled()
    expect(screen.getByTestId('schedule-create-error')).toBeInTheDocument()
  })
})

describe('SchedulesPage (#1069) — edit (autosave)', () => {
  it('autosaves a renamed schedule via PATCH with the full shape', async () => {
    const user = userEvent.setup()
    render(withQuery(<SchedulesPage />))
    await screen.findByDisplayValue('Bedtime')

    const input = screen.getByTestId('schedule-name-1')
    await user.clear(input)
    await user.type(input, 'Lights out')

    await waitFor(() => expect(api.schedules.update).toHaveBeenCalled(), { timeout: 2000 })
    const calls = fn(api.schedules.update).mock.calls
    const [id, body] = calls[calls.length - 1]
    expect(id).toBe(1)
    expect(body.name).toBe('Lights out')
    expect(Array.isArray(body.windows)).toBe(true)
  })
})

describe('SchedulesPage (#1069) — delete', () => {
  it('deletes an unreferenced schedule after confirm', async () => {
    const user = userEvent.setup()
    render(withQuery(<SchedulesPage />))
    await screen.findByDisplayValue('School hours')

    await user.click(screen.getByTestId('schedule-delete-2'))
    // confirm box appears (no reference warning for School hours)
    expect(screen.getByTestId('schedule-delete-confirm-2')).toBeInTheDocument()
    await user.click(screen.getByTestId('schedule-delete-confirm-yes-2'))

    await waitFor(() => expect(api.schedules.delete).toHaveBeenCalledWith(2))
  })

  it('guards deletion of a referenced schedule with a warning', async () => {
    const user = userEvent.setup()
    render(withQuery(<SchedulesPage />))
    await screen.findByDisplayValue('Bedtime')

    await user.click(screen.getByTestId('schedule-delete-1'))
    const confirm = screen.getByTestId('schedule-delete-confirm-1')
    // The warning names the referencing profile so deletion isn't silent.
    expect(confirm).toHaveTextContent(/Kids/)
    // Not deleted until the operator confirms past the warning.
    expect(api.schedules.delete).not.toHaveBeenCalled()
    await user.click(screen.getByTestId('schedule-delete-confirm-yes-1'))
    await waitFor(() => expect(api.schedules.delete).toHaveBeenCalledWith(1))
  })
})
