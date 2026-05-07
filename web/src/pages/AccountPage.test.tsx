import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

vi.mock('@/api/client', () => ({
  api: {
    auth: {
      changePassword: vi.fn(),
    },
  },
}))

vi.mock('@/hooks/useAuth', () => ({
  useAuth: () => mockAuth,
}))

import { api } from '@/api/client'
import { AccountPage } from './AccountPage'

let mockAuth: { username: string; isAdmin: boolean } = { username: 'alice', isAdmin: true }

beforeEach(() => {
  vi.resetAllMocks()
  mockAuth = { username: 'alice', isAdmin: true }
  ;(api.auth.changePassword as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(undefined)
})

async function fillFields(user: ReturnType<typeof userEvent.setup>, current: string, next: string, confirm: string) {
  const inputs = document.querySelectorAll('input[type="password"]')
  await user.type(inputs[0] as HTMLInputElement, current)
  await user.type(inputs[1] as HTMLInputElement, next)
  await user.type(inputs[2] as HTMLInputElement, confirm)
}

describe('AccountPage — role display', () => {
  it('shows admin role for admin user', () => {
    render(<AccountPage />)
    expect(screen.getByText('alice')).toBeInTheDocument()
    expect(screen.getByText('admin')).toBeInTheDocument()
  })

  it('shows readonly role for non-admin user', () => {
    mockAuth = { username: 'bob', isAdmin: false }
    render(<AccountPage />)
    expect(screen.getByText('readonly')).toBeInTheDocument()
  })
})

describe('AccountPage — password change', () => {
  it('calls api.auth.changePassword and clears fields on success', async () => {
    const user = userEvent.setup()
    render(<AccountPage />)
    await fillFields(user, 'oldpass12', 'newpass34', 'newpass34')
    await user.click(screen.getByRole('button', { name: /Update password/ }))

    await waitFor(() => expect(api.auth.changePassword).toHaveBeenCalledWith('oldpass12', 'newpass34'))
    expect(await screen.findByText(/Password updated/)).toBeInTheDocument()
    const inputs = document.querySelectorAll('input[type="password"]')
    expect((inputs[0] as HTMLInputElement).value).toBe('')
    expect((inputs[1] as HTMLInputElement).value).toBe('')
    expect((inputs[2] as HTMLInputElement).value).toBe('')
  })

  it('rejects when new and confirm do not match', async () => {
    const user = userEvent.setup()
    render(<AccountPage />)
    await fillFields(user, 'oldpass12', 'newpass34', 'different')
    await user.click(screen.getByRole('button', { name: /Update password/ }))
    expect(await screen.findByText(/do not match/i)).toBeInTheDocument()
    expect(api.auth.changePassword).not.toHaveBeenCalled()
  })

  it('rejects when new password is too short', async () => {
    const user = userEvent.setup()
    render(<AccountPage />)
    await fillFields(user, 'oldpass12', 'short', 'short')
    await user.click(screen.getByRole('button', { name: /Update password/ }))
    expect(await screen.findByText(/at least 8 characters/i)).toBeInTheDocument()
    expect(api.auth.changePassword).not.toHaveBeenCalled()
  })

  it('rejects when new password equals current', async () => {
    const user = userEvent.setup()
    render(<AccountPage />)
    await fillFields(user, 'samesame12', 'samesame12', 'samesame12')
    await user.click(screen.getByRole('button', { name: /Update password/ }))
    expect(await screen.findByText(/differ from the current/i)).toBeInTheDocument()
    expect(api.auth.changePassword).not.toHaveBeenCalled()
  })

  it('maps 401 error to "Current password is incorrect"', async () => {
    (api.auth.changePassword as unknown as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('HTTP 401 Unauthorised'))
    const user = userEvent.setup()
    render(<AccountPage />)
    await fillFields(user, 'oldpass12', 'newpass34', 'newpass34')
    await user.click(screen.getByRole('button', { name: /Update password/ }))
    expect(await screen.findByText(/Current password is incorrect/)).toBeInTheDocument()
  })

  it('shows raw error for other failures', async () => {
    (api.auth.changePassword as unknown as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('Server exploded'))
    const user = userEvent.setup()
    render(<AccountPage />)
    await fillFields(user, 'oldpass12', 'newpass34', 'newpass34')
    await user.click(screen.getByRole('button', { name: /Update password/ }))
    expect(await screen.findByText('Server exploded')).toBeInTheDocument()
  })
})
