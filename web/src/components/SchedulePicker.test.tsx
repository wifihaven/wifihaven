import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

vi.mock('@/api/client', () => ({
  api: {
    schedules: {
      list: vi.fn(),
      create: vi.fn(),
    },
  },
}))

import { api } from '@/api/client'
import type { NamedSchedule } from '@/types/api'
import { SchedulePicker, ALWAYS_VALUE } from './SchedulePicker'
import { withQuery } from '@/test/queryWrapper'

const bedtime: NamedSchedule = {
  id: 1, name: 'Bedtime', description: null,
  windows: [{ days: ['mon', 'tue'], startLocal: '20:00', endLocal: '07:00', tz: 'UTC' }],
}
const school: NamedSchedule = {
  id: 2, name: 'School hours', description: null,
  windows: [{ days: ['mon'], startLocal: '08:00', endLocal: '15:00', tz: 'UTC' }],
}

beforeEach(() => {
  vi.resetAllMocks()
  ;(api.schedules.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([bedtime, school])
  ;(api.schedules.create as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({
    id: 99, name: 'Naptime', description: null, windows: [],
  } satisfies NamedSchedule)
})

describe('SchedulePicker (#1069)', () => {
  it('renders household schedules + Always + Custom options', async () => {
    render(withQuery(<SchedulePicker value={null} onChange={vi.fn()} />))
    // options appear once the catalog loads
    expect(await screen.findByRole('option', { name: 'Bedtime' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'School hours' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'Always' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: /custom \(define inline\)/i })).toBeInTheDocument()
  })

  it('selecting Always yields null', async () => {
    const onChange = vi.fn()
    render(withQuery(<SchedulePicker value={1} onChange={onChange} />))
    await screen.findByRole('option', { name: 'Bedtime' })
    await userEvent.selectOptions(screen.getByTestId('schedule-picker-select'), ALWAYS_VALUE)
    expect(onChange).toHaveBeenCalledWith(null)
  })

  it('selecting a named schedule yields its id', async () => {
    const onChange = vi.fn()
    render(withQuery(<SchedulePicker value={null} onChange={onChange} />))
    await screen.findByRole('option', { name: 'School hours' })
    await userEvent.selectOptions(screen.getByTestId('schedule-picker-select'), '2')
    expect(onChange).toHaveBeenCalledWith(2)
  })

  it('hides Custom when allowCustom is false', async () => {
    render(withQuery(<SchedulePicker value={null} onChange={vi.fn()} allowCustom={false} />))
    await screen.findByRole('option', { name: 'Bedtime' })
    expect(screen.queryByRole('option', { name: /custom \(define inline\)/i })).not.toBeInTheDocument()
  })

  it('Custom transparently creates a household schedule and emits its id', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(withQuery(<SchedulePicker value={null} onChange={onChange} />))
    await screen.findByRole('option', { name: 'Bedtime' })

    await user.selectOptions(screen.getByTestId('schedule-picker-select'), '__custom__')
    expect(screen.getByTestId('schedule-picker-custom-form')).toBeInTheDocument()

    await user.type(screen.getByTestId('schedule-picker-custom-name'), 'Naptime')
    await user.click(screen.getByTestId('schedule-picker-custom-create'))

    await waitFor(() => expect(api.schedules.create).toHaveBeenCalledTimes(1))
    const arg = (api.schedules.create as unknown as ReturnType<typeof vi.fn>).mock.calls[0][0]
    expect(arg.name).toBe('Naptime')
    expect(Array.isArray(arg.windows)).toBe(true)
    expect(onChange).toHaveBeenCalledWith(99)
    // form closes after create
    await waitFor(() =>
      expect(screen.queryByTestId('schedule-picker-custom-form')).not.toBeInTheDocument(),
    )
  })

  it('blocks custom create with an empty name', async () => {
    const user = userEvent.setup()
    render(withQuery(<SchedulePicker value={null} onChange={vi.fn()} />))
    await screen.findByRole('option', { name: 'Bedtime' })
    await user.selectOptions(screen.getByTestId('schedule-picker-select'), '__custom__')
    await user.click(screen.getByTestId('schedule-picker-custom-create'))
    expect(api.schedules.create).not.toHaveBeenCalled()
    expect(screen.getByTestId('schedule-picker-custom-error')).toBeInTheDocument()
  })
})
