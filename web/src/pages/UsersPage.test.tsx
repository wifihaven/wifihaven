import { describe, it, expect, beforeEach, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ProfileDetail, User } from '@/types/api'

vi.mock('@/api/client', () => ({
  api: {
    users: {
      list: vi.fn(),
      create: vi.fn(),
      patch: vi.fn(),
      setProfiles: vi.fn(),
      delete: vi.fn(),
    },
    profiles: {
      list: vi.fn(),
    },
  },
}))

import { api } from '@/api/client'
import { UsersPage } from './UsersPage'

const kidsProfile = {
  profile: { id: 1, name: 'Kids', blockedCategories: [], paused: false },
  schedules: [], timeLimit: null,
} as unknown as ProfileDetail

const adultsProfile = {
  profile: { id: 2, name: 'Adults', blockedCategories: [], paused: false },
  schedules: [], timeLimit: null,
} as unknown as ProfileDetail

const aliceUser: User = { id: 10, username: 'alice', role: 'admin', profileIds: [] }
const bobUser:   User = { id: 11, username: 'bob',   role: 'child', profileIds: [1] }

beforeEach(() => {
  vi.resetAllMocks()
  ;(api.users.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([aliceUser, bobUser])
  ;(api.profiles.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([kidsProfile, adultsProfile])
  ;(api.users.create as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 99 })
  ;(api.users.patch as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(undefined)
  ;(api.users.setProfiles as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(undefined)
  ;(api.users.delete as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(undefined)
})

describe('UsersPage — list', () => {
  it('renders username, role, and linked profile names from api', async () => {
    render(<UsersPage />)
    expect(await screen.findByText('alice')).toBeInTheDocument()
    expect(screen.getByText('bob')).toBeInTheDocument()
    expect(screen.getByText('admin')).toBeInTheDocument()
    expect(screen.getByText('child')).toBeInTheDocument()
    // bob has profile id 1 → resolves to "Kids"
    expect(screen.getByText('Kids')).toBeInTheDocument()
    // alice has no profiles
    expect(screen.getByText('No profiles')).toBeInTheDocument()
  })
})

describe('UsersPage — create flow', () => {
  it('opens modal, fills form including multi-select profiles, and calls api.users.create', async () => {
    const user = userEvent.setup()
    render(<UsersPage />)
    await screen.findByText('alice')

    await user.click(screen.getByRole('button', { name: /new user/i }))

    const modal = screen.getByText('New User').closest('div')!.parentElement!
    const inputs = modal.querySelectorAll('input')
    await user.type(inputs[0], 'carol')
    await user.type(inputs[1], 'pw1234')

    // Select adult role (it's the default but click anyway)
    await user.click(screen.getByRole('button', { name: 'adult' }))

    // Multi-select two profiles
    await user.click(screen.getByRole('button', { name: 'Kids' }))
    await user.click(screen.getByRole('button', { name: 'Adults' }))

    await user.click(screen.getByRole('button', { name: /^create$/i }))

    await waitFor(() => {
      expect(api.users.create).toHaveBeenCalledWith({
        username: 'carol',
        password: 'pw1234',
        role: 'adult',
        profileIds: [1, 2],
      })
    })
    // reload after success
    await waitFor(() => expect(api.users.list).toHaveBeenCalledTimes(2))
  })

  it('shows validation error if username is empty and does not call api', async () => {
    const user = userEvent.setup()
    render(<UsersPage />)
    await screen.findByText('alice')
    await user.click(screen.getByRole('button', { name: /new user/i }))
    await user.click(screen.getByRole('button', { name: /^create$/i }))
    expect(await screen.findByText(/username is required/i)).toBeInTheDocument()
    expect(api.users.create).not.toHaveBeenCalled()
  })
})

describe('UsersPage — inline autosave edit (#1001)', () => {
  it('expands an inline editor pre-filled with username, role, and profiles; no Save button', async () => {
    const user = userEvent.setup()
    render(<UsersPage />)
    await screen.findByText('bob')

    const bobRow = screen.getByTestId('user-row-11')
    await user.click(within(bobRow).getByRole('button', { name: /^edit$/i }))

    const editor = await screen.findByTestId('user-editor-11')
    expect(within(editor).getByDisplayValue('bob')).toBeInTheDocument()
    // bob is a child, with Kids assigned
    expect(within(editor).getByRole('button', { name: 'child' })).toBeInTheDocument()
    expect(within(editor).queryByRole('button', { name: /^save$/i })).not.toBeInTheDocument()
  })

  it('renaming fires a debounced PATCH {username}', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    try {
      render(<UsersPage />)
      await screen.findByText('bob')
      await user.click(within(screen.getByTestId('user-row-11')).getByRole('button', { name: /^edit$/i }))
      const editor = await screen.findByTestId('user-editor-11')
      const input = within(editor).getByTestId('user-name-input-11')

      // Set atomically: char-by-char typing under fake timers lets the 700ms
      // debounce fire on a transient partial value, producing extra PATCHes.
      fireEvent.change(input, { target: { value: 'bobby' } })
      expect(api.users.patch).not.toHaveBeenCalled()
      await vi.advanceTimersByTimeAsync(700)

      expect(api.users.patch).toHaveBeenCalledWith(11, { username: 'bobby' })
    } finally {
      vi.useRealTimers()
    }
  })

  it('changing role fires PATCH {role}', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    try {
      render(<UsersPage />)
      await screen.findByText('bob')
      await user.click(within(screen.getByTestId('user-row-11')).getByRole('button', { name: /^edit$/i }))
      const editor = await screen.findByTestId('user-editor-11')

      await user.click(within(editor).getByRole('button', { name: 'adult' }))
      await vi.advanceTimersByTimeAsync(700)

      expect(api.users.patch).toHaveBeenCalledWith(11, { role: 'adult' })
    } finally {
      vi.useRealTimers()
    }
  })

  it('toggling a profile fires PATCH {profileIds} with full-replace semantics', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    try {
      render(<UsersPage />)
      await screen.findByText('bob')
      await user.click(within(screen.getByTestId('user-row-11')).getByRole('button', { name: /^edit$/i }))
      const editor = await screen.findByTestId('user-editor-11')

      // bob currently has Kids (1); add Adults (2)
      await user.click(within(editor).getByRole('button', { name: 'Adults' }))
      await vi.advanceTimersByTimeAsync(700)

      expect(api.users.patch).toHaveBeenCalledWith(11, { profileIds: [1, 2] })
    } finally {
      vi.useRealTimers()
    }
  })

  it('PATCH failure surfaces inline error + Retry; dirty value retained; Retry re-sends', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    try {
      (api.users.patch as unknown as ReturnType<typeof vi.fn>).mockRejectedValueOnce(new Error('boom'))
      render(<UsersPage />)
      await screen.findByText('bob')
      await user.click(within(screen.getByTestId('user-row-11')).getByRole('button', { name: /^edit$/i }))
      const editor = await screen.findByTestId('user-editor-11')
      const input = within(editor).getByTestId('user-name-input-11')

      fireEvent.change(input, { target: { value: 'bobby' } })
      await vi.advanceTimersByTimeAsync(700)

      const status = within(editor).getByTestId('user-save-status-11')
      await waitFor(() => expect(status).toHaveAttribute('data-status', 'error'))
      expect(input).toHaveValue('bobby')

      await user.click(within(editor).getByTestId('user-save-status-11-retry'))
      await waitFor(() => expect(api.users.patch).toHaveBeenCalledTimes(2))
    } finally {
      vi.useRealTimers()
    }
  })
})

describe('UsersPage — delete', () => {
  it('confirms then calls api.users.delete', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const user = userEvent.setup()
    render(<UsersPage />)
    await screen.findByText('alice')

    const aliceRow = screen.getByTestId('user-row-10')
    await user.click(within(aliceRow).getByRole('button', { name: /delete/i }))

    expect(confirmSpy).toHaveBeenCalled()
    await waitFor(() => expect(api.users.delete).toHaveBeenCalledWith(10))
    confirmSpy.mockRestore()
  })

  it('does not call api when user cancels confirm', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false)
    const user = userEvent.setup()
    render(<UsersPage />)
    await screen.findByText('alice')

    const aliceRow = screen.getByTestId('user-row-10')
    await user.click(within(aliceRow).getByRole('button', { name: /delete/i }))

    expect(api.users.delete).not.toHaveBeenCalled()
    confirmSpy.mockRestore()
  })
})
