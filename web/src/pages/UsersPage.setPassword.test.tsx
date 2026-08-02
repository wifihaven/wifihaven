import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ProfileDetail, User } from '@/types/api'

vi.mock('@/api/client', () => ({
  api: {
    users: {
      list: vi.fn(),
      create: vi.fn(),
      patch: vi.fn(),
      setProfiles: vi.fn(),
      setPassword: vi.fn(),
      delete: vi.fn(),
    },
    profiles: {
      list: vi.fn(),
    },
  },
}))

import { api } from '@/api/client'
import { UsersPage } from './UsersPage'

const noProfiles: ProfileDetail[] = []

const adminUser: User = { id: 10, username: 'alice', role: 'admin', profileIds: [] }
const adultUser: User = { id: 11, username: 'bob',   role: 'adult', profileIds: [] }
const childUser: User = { id: 12, username: 'kiddo', role: 'child', profileIds: [] }

const mocked = (f: unknown) => f as unknown as ReturnType<typeof vi.fn>

beforeEach(() => {
  vi.resetAllMocks()
  mocked(api.users.list).mockResolvedValue([adminUser, adultUser, childUser])
  mocked(api.profiles.list).mockResolvedValue(noProfiles)
  mocked(api.users.setPassword).mockResolvedValue(undefined)
})

/**
 * #2576 — the admin's in-band password handoff. The whole point is a parent helping a locked-out
 * child in the moment, so the affordance has to be on the user row, not behind an email round-trip.
 *
 * Deliberately NOT autosaved (the house default): a credential write is destructive and
 * irreversible from the target's point of view, so it is an explicit confirmed submit — a debounced
 * autosave would fire a real password change on every keystroke pause.
 */
describe('UsersPage — admin sets a member password (#2576)', () => {
  it('offers the affordance for an adult and a child, but not for the admin (#2512)', async () => {
    render(<UsersPage />)
    await screen.findByText('alice')

    expect(screen.getByTestId('set-password-11')).toBeInTheDocument()
    expect(screen.getByTestId('set-password-12')).toBeInTheDocument()
    // Admin-to-admin is out of scope: the admin rotates their own password on /account.
    expect(screen.queryByTestId('set-password-10')).not.toBeInTheDocument()
  })

  it('submits the new password explicitly and reports the forced-change handoff', async () => {
    const user = userEvent.setup()
    render(<UsersPage />)
    await screen.findByText('alice')

    await user.click(screen.getByTestId('set-password-12'))
    const input = await screen.findByTestId('set-password-input')
    await user.type(input, 'handoff-password-456')

    // Typing alone must NOT write — no autosave on a credential.
    expect(api.users.setPassword).not.toHaveBeenCalled()

    await user.click(screen.getByTestId('set-password-submit'))

    await waitFor(() =>
      expect(api.users.setPassword).toHaveBeenCalledWith(12, 'handoff-password-456'),
    )
    // The admin is told the credential is a handoff, not a permanent shared secret.
    expect(await screen.findByText(/change it .*next .*log/i)).toBeInTheDocument()
  })

  it('refuses to submit a password below the server minimum without calling the API', async () => {
    const user = userEvent.setup()
    render(<UsersPage />)
    await screen.findByText('alice')

    await user.click(screen.getByTestId('set-password-12'))
    await user.type(await screen.findByTestId('set-password-input'), 'short')
    await user.click(screen.getByTestId('set-password-submit'))

    expect(await screen.findByText(/at least 12 characters/i)).toBeInTheDocument()
    expect(api.users.setPassword).not.toHaveBeenCalled()
  })

  it('surfaces a server refusal instead of silently claiming success', async () => {
    const user = userEvent.setup()
    mocked(api.users.setPassword).mockRejectedValue(new Error('User not found'))
    render(<UsersPage />)
    await screen.findByText('alice')

    await user.click(screen.getByTestId('set-password-12'))
    await user.type(await screen.findByTestId('set-password-input'), 'handoff-password-456')
    await user.click(screen.getByTestId('set-password-submit'))

    expect(await screen.findByText(/User not found/)).toBeInTheDocument()
  })
})
