import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'

const navigateMock = vi.fn()

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom')
  return { ...actual, useNavigate: () => navigateMock }
})

vi.mock('@/api/client', () => ({
  api: { auth: { resetPassword: vi.fn() } },
}))

import { api } from '@/api/client'
import { ResetPasswordPage } from './ResetPasswordPage'
import { MIN_PASSWORD_LENGTH } from './WelcomePage'

const resetMock = api.auth.resetPassword as unknown as ReturnType<typeof vi.fn>

beforeEach(() => {
  vi.resetAllMocks()
})

function renderPage(search = '?token=reset-abc') {
  return render(
    <MemoryRouter initialEntries={[`/reset-password${search}`]}>
      <ResetPasswordPage />
    </MemoryRouter>,
  )
}

const strongPw = 'a'.repeat(MIN_PASSWORD_LENGTH)

describe('ResetPasswordPage — /reset-password', () => {
  it('resets the password with the token and shows the success state', async () => {
    resetMock.mockResolvedValue({ ok: true })
    const user = userEvent.setup()
    renderPage()

    await user.type(screen.getByLabelText(/^new password/i), strongPw)
    await user.type(screen.getByLabelText(/confirm new password/i), strongPw)
    await user.click(screen.getByRole('button', { name: /^reset password/i }))

    await waitFor(() => expect(resetMock).toHaveBeenCalledWith('reset-abc', strongPw))
    expect(await screen.findByText(/password updated/i)).toBeInTheDocument()
  })

  it('rejects a password shorter than the policy minimum without calling the API', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.type(screen.getByLabelText(/^new password/i), 'short')
    await user.type(screen.getByLabelText(/confirm new password/i), 'short')
    await user.click(screen.getByRole('button', { name: /^reset password/i }))

    expect(await screen.findByText(/password must be at least \d+ characters/i)).toBeInTheDocument()
    expect(resetMock).not.toHaveBeenCalled()
  })

  it('flags mismatched passwords without calling the API', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.type(screen.getByLabelText(/^new password/i), strongPw)
    await user.type(screen.getByLabelText(/confirm new password/i), strongPw + 'x')
    await user.click(screen.getByRole('button', { name: /^reset password/i }))

    expect(await screen.findByText(/do not match/i)).toBeInTheDocument()
    expect(resetMock).not.toHaveBeenCalled()
  })

  it('shows an invalid/expired message when the reset fails and does not redirect', async () => {
    resetMock.mockRejectedValue(new Error('invalid or expired reset link'))
    const user = userEvent.setup()
    renderPage()

    await user.type(screen.getByLabelText(/^new password/i), strongPw)
    await user.type(screen.getByLabelText(/confirm new password/i), strongPw)
    await user.click(screen.getByRole('button', { name: /^reset password/i }))

    expect(await screen.findByText(/invalid or has expired/i)).toBeInTheDocument()
    expect(navigateMock).not.toHaveBeenCalled()
  })

  it('prompts for a valid reset link when the token is missing', () => {
    renderPage('')
    expect(screen.getByText(/reset link required/i)).toBeInTheDocument()
  })
})
