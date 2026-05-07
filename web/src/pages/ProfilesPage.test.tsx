import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ProfileDetail } from '@/types/api'

vi.mock('@/api/client', () => ({
  api: {
    profiles: {
      list: vi.fn(),
      create: vi.fn(),
      update: vi.fn(),
      delete: vi.fn(),
      pause: vi.fn(),
    },
    blocklists: {
      counts: vi.fn(),
    },
  },
}))

vi.mock('@/hooks/useAuth', () => ({
  useAuth: () => mockAuth,
}))

import { api } from '@/api/client'
import { ProfilesPage } from './ProfilesPage'

let mockAuth = { isAdmin: true }

const kidsProfile: ProfileDetail = {
  profile: {
    id: 1,
    name: 'Kids',
    blockedCategories: ['adult', 'gambling'],
    extraBlocked: ['bad.com', 'evil.com'],
    extraAllowed: ['school.com'],
    paused: false,
  },
  schedules: [
    { id: 10, profileId: 1, name: 'Bedtime', days: ['mon', 'tue'], blockFrom: '21:00', blockUntil: '07:00' },
  ],
  timeLimit: { id: 5, profileId: 1, dailyMinutes: 120 },
  siteTimeLimits: [
    { id: 7, profileId: 1, domainPattern: 'youtube.com', dailyMinutes: 30, label: 'YouTube' },
  ],
}

const adultsProfile: ProfileDetail = {
  profile: {
    id: 2,
    name: 'Adults',
    blockedCategories: [],
    extraBlocked: [],
    extraAllowed: [],
    paused: true,
  },
  schedules: [],
  timeLimit: null,
  siteTimeLimits: [],
}

beforeEach(() => {
  vi.resetAllMocks()
  mockAuth = { isAdmin: true }
  ;(api.profiles.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([kidsProfile, adultsProfile])
  ;(api.blocklists.counts as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([
    { category: 'adult', count: 1000 },
    { category: 'gambling', count: 500 },
    { category: 'social', count: 200 },
  ])
  ;(api.profiles.create as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 99 })
  ;(api.profiles.update as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(undefined)
  ;(api.profiles.delete as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(undefined)
  ;(api.profiles.pause as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({ paused: true })
})

describe('ProfilesPage — list', () => {
  it('renders profile names, paused badge, blocked categories, schedules, site limits, and daily limit', async () => {
    render(<ProfilesPage />)
    expect(await screen.findByText('Kids')).toBeInTheDocument()
    expect(screen.getByText('Adults')).toBeInTheDocument()
    expect(screen.getByText('Paused')).toBeInTheDocument()
    expect(screen.getByText('adult')).toBeInTheDocument()
    expect(screen.getByText('gambling')).toBeInTheDocument()
    expect(screen.getByText('Bedtime')).toBeInTheDocument()
    expect(screen.getByText('21:00 → 07:00')).toBeInTheDocument()
    expect(screen.getByText('YouTube')).toBeInTheDocument()
    expect(screen.getByText('30m · youtube.com')).toBeInTheDocument()
    expect(screen.getByText('120 min')).toBeInTheDocument()
  })
})

describe('ProfilesPage — pause / delete', () => {
  it('clicking Pause/Resume calls api.profiles.pause and reloads', async () => {
    const user = userEvent.setup()
    render(<ProfilesPage />)
    const kidsCard = (await screen.findByText('Kids')).closest('div.bg-gray-900')!
    await user.click(within(kidsCard as HTMLElement).getByRole('button', { name: /Pause/ }))
    await waitFor(() => expect(api.profiles.pause).toHaveBeenCalledWith(1))
    await waitFor(() => expect(api.profiles.list).toHaveBeenCalledTimes(2))
  })

  it('confirms then calls api.profiles.delete', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const user = userEvent.setup()
    render(<ProfilesPage />)
    const kidsCard = (await screen.findByText('Kids')).closest('div.bg-gray-900')!
    await user.click(within(kidsCard as HTMLElement).getByRole('button', { name: /^Delete$/ }))
    expect(confirmSpy).toHaveBeenCalled()
    await waitFor(() => expect(api.profiles.delete).toHaveBeenCalledWith(1))
    confirmSpy.mockRestore()
  })

  it('does not delete when confirm is cancelled', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false)
    const user = userEvent.setup()
    render(<ProfilesPage />)
    const kidsCard = (await screen.findByText('Kids')).closest('div.bg-gray-900')!
    await user.click(within(kidsCard as HTMLElement).getByRole('button', { name: /^Delete$/ }))
    expect(api.profiles.delete).not.toHaveBeenCalled()
    confirmSpy.mockRestore()
  })
})

describe('ProfilesPage — create', () => {
  it('shows validation error when name is empty', async () => {
    const user = userEvent.setup()
    render(<ProfilesPage />)
    await screen.findByText('Kids')
    await user.click(screen.getByRole('button', { name: /\+ New Profile/ }))
    await user.click(screen.getByRole('button', { name: /^Save$/ }))
    expect(await screen.findByText(/Name is required/i)).toBeInTheDocument()
    expect(api.profiles.create).not.toHaveBeenCalled()
  })

  it('fills the editor and calls api.profiles.create with the expected body', async () => {
    const user = userEvent.setup()
    render(<ProfilesPage />)
    await screen.findByText('Kids')
    await user.click(screen.getByRole('button', { name: /\+ New Profile/ }))

    await user.type(screen.getByPlaceholderText('Kids'), 'Teens')

    // toggle category "social"
    await user.click(screen.getByRole('button', { name: 'social' }))

    // extra blocked / allowed (split lines, trim)
    const blockedTa = screen.getAllByPlaceholderText('One domain per line')[0]
    const allowedTa = screen.getAllByPlaceholderText('One domain per line')[1]
    await user.type(blockedTa, '  tiktok.com  \n  insta.com  ')
    await user.type(allowedTa, 'khan.org')

    // daily limit
    await user.type(screen.getByPlaceholderText('Leave blank for unlimited'), '90')

    // add schedule (default Bedtime, all 7 days, 21:00 → 07:00); toggle "sun" off
    await user.click(screen.getByRole('button', { name: /\+ Add schedule/ }))
    await user.click(screen.getByRole('button', { name: 'sun' }))

    // add site limit
    await user.click(screen.getByRole('button', { name: /\+ Add site limit/ }))
    await user.type(screen.getByPlaceholderText('YouTube'), 'YouTube')
    await user.type(screen.getByPlaceholderText('youtube.com'), 'youtube.com')

    await user.click(screen.getByRole('button', { name: /^Save$/ }))

    await waitFor(() => expect(api.profiles.create).toHaveBeenCalledTimes(1))
    expect(api.profiles.create).toHaveBeenCalledWith({
      name: 'Teens',
      blockedCategories: ['social'],
      extraBlocked: ['tiktok.com', 'insta.com'],
      extraAllowed: ['khan.org'],
      paused: false,
      timeLimit: 90,
      schedules: [
        { name: 'Bedtime', days: ['mon', 'tue', 'wed', 'thu', 'fri', 'sat'], blockFrom: '21:00', blockUntil: '07:00' },
      ],
      siteTimeLimits: [
        { label: 'YouTube', domainPattern: 'youtube.com', dailyMinutes: 30 },
      ],
    })
    await waitFor(() => expect(api.profiles.list).toHaveBeenCalledTimes(2))
  })
})

describe('ProfilesPage — edit', () => {
  it('pre-fills editor from selected profile and calls api.profiles.update', async () => {
    const user = userEvent.setup()
    render(<ProfilesPage />)
    const kidsCard = (await screen.findByText('Kids')).closest('div.bg-gray-900')!
    await user.click(within(kidsCard as HTMLElement).getByRole('button', { name: /^Edit$/ }))

    expect(screen.getByDisplayValue('Kids')).toBeInTheDocument()
    expect(screen.getByDisplayValue('120')).toBeInTheDocument()
    expect(screen.getByDisplayValue('school.com')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /^Save$/ }))

    await waitFor(() => expect(api.profiles.update).toHaveBeenCalledTimes(1))
    expect(api.profiles.update).toHaveBeenCalledWith(1, {
      name: 'Kids',
      blockedCategories: ['adult', 'gambling'],
      extraBlocked: ['bad.com', 'evil.com'],
      extraAllowed: ['school.com'],
      paused: false,
      timeLimit: 120,
      schedules: [
        { name: 'Bedtime', days: ['mon', 'tue'], blockFrom: '21:00', blockUntil: '07:00' },
      ],
      siteTimeLimits: [
        { domainPattern: 'youtube.com', dailyMinutes: 30, label: 'YouTube' },
      ],
    })
  })
})

describe('ProfilesPage — role gating', () => {
  it('hides admin-only buttons for non-admins', async () => {
    mockAuth = { isAdmin: false }
    render(<ProfilesPage />)
    await screen.findByText('Kids')
    expect(screen.queryByRole('button', { name: /\+ New Profile/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Pause/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Edit$/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Delete$/ })).not.toBeInTheDocument()
  })
})
