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
  // #2947: the server echoes the toggle it applied, and the confirmation copy is derived from that
  // echo — so the mock answers with what it was asked for, like the real route.
  mocked(api.users.setPassword).mockImplementation(
    async (_id: number, _pw: string, mustChangePassword: boolean) => ({ mustChangePassword }),
  )
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

    await user.click(screen.getByTestId('set-password-11'))
    const input = await screen.findByTestId('set-password-input')
    await user.type(input, 'handoff-password-456')

    // Typing alone must NOT write — no autosave on a credential.
    expect(api.users.setPassword).not.toHaveBeenCalled()

    await user.click(screen.getByTestId('set-password-submit'))

    await waitFor(() =>
      expect(api.users.setPassword).toHaveBeenCalledWith(11, 'handoff-password-456', true),
    )
    // The admin is told the credential is a handoff, not a permanent shared secret.
    expect(await screen.findByTestId('set-password-done')).toHaveTextContent(
      /change it at the next login/i,
    )
  })

  it('refuses to submit a password below the server minimum without calling the API', async () => {
    const user = userEvent.setup()
    render(<UsersPage />)
    await screen.findByText('alice')

    await user.click(screen.getByTestId('set-password-12'))
    await user.type(await screen.findByTestId('set-password-input'), 'short')
    await user.click(screen.getByTestId('set-password-submit'))

    // Asserted on the error slot specifically — the modal also carries a standing "At least 12
    // characters." policy hint, so matching on the copy alone would pass without any refusal.
    expect(await screen.findByTestId('set-password-error')).toHaveTextContent(
      /at least 12 characters/i,
    )
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

/**
 * #2947 — the admin chooses whether the password they type is a one-time handoff or the member's
 * real password. Defaults differ by role (operator call): a parent usually manages a CHILD's
 * password, so a child's set keeps it by default; an ADULT's set still forces a change.
 */
describe('UsersPage — require-change toggle (#2947)', () => {
  async function openFor(id: number) {
    const user = userEvent.setup()
    render(<UsersPage />)
    await screen.findByText('alice')
    await user.click(screen.getByTestId(`set-password-${id}`))
    await user.type(await screen.findByTestId('set-password-input'), 'handoff-password-456')
    return user
  }

  it('defaults ON for an adult and sends mustChangePassword=true', async () => {
    const user = await openFor(11)
    expect(screen.getByTestId('set-password-require-change')).toBeChecked()

    await user.click(screen.getByTestId('set-password-submit'))
    await waitFor(() =>
      expect(api.users.setPassword).toHaveBeenCalledWith(11, 'handoff-password-456', true),
    )
    expect(await screen.findByTestId('set-password-done')).toHaveTextContent(
      /change it at the next login/i,
    )
  })

  it('defaults OFF for a child, sends mustChangePassword=false, and says the password stays', async () => {
    const user = await openFor(12)
    const toggle = screen.getByTestId('set-password-require-change')
    expect(toggle).not.toBeChecked()
    // The off state spells out the consequence before the admin submits.
    expect(screen.getByTestId('set-password-require-change-hint')).toHaveTextContent(
      /stays their password/i,
    )

    await user.click(screen.getByTestId('set-password-submit'))
    await waitFor(() =>
      expect(api.users.setPassword).toHaveBeenCalledWith(12, 'handoff-password-456', false),
    )
    const done = await screen.findByTestId('set-password-done')
    expect(done).toHaveTextContent(/stays their password/i)
    expect(done).not.toHaveTextContent(/change it at the next login/i)
  })

  it('lets the admin turn it ON for a child', async () => {
    const user = await openFor(12)
    await user.click(screen.getByTestId('set-password-require-change'))
    expect(screen.getByTestId('set-password-require-change')).toBeChecked()

    await user.click(screen.getByTestId('set-password-submit'))
    await waitFor(() =>
      expect(api.users.setPassword).toHaveBeenCalledWith(12, 'handoff-password-456', true),
    )
    expect(await screen.findByTestId('set-password-done')).toHaveTextContent(
      /change it at the next login/i,
    )
  })

  it('lets the admin turn it OFF for an adult', async () => {
    const user = await openFor(11)
    await user.click(screen.getByTestId('set-password-require-change'))

    await user.click(screen.getByTestId('set-password-submit'))
    await waitFor(() =>
      expect(api.users.setPassword).toHaveBeenCalledWith(11, 'handoff-password-456', false),
    )
    expect(await screen.findByTestId('set-password-done')).toHaveTextContent(
      /stays their password/i,
    )
  })
})
