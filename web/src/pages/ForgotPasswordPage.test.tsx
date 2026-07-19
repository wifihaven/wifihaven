import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'

vi.mock('@/api/client', () => ({
  api: { auth: { forgotPassword: vi.fn() } },
}))

import { api } from '@/api/client'
import { ForgotPasswordPage } from './ForgotPasswordPage'

const forgotMock = api.auth.forgotPassword as unknown as ReturnType<typeof vi.fn>

beforeEach(() => {
  vi.resetAllMocks()
})

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/forgot-password']}>
      <ForgotPasswordPage />
    </MemoryRouter>,
  )
}

describe('ForgotPasswordPage — /forgot-password', () => {
  it('submits the email and shows the generic check-your-email state', async () => {
    forgotMock.mockResolvedValue({ status: 'sent' })
    const user = userEvent.setup()
    renderPage()

    await user.type(screen.getByLabelText(/email/i), 'parent@example.com')
    await user.click(screen.getByRole('button', { name: /send reset link/i }))

    await waitFor(() => expect(forgotMock).toHaveBeenCalledWith('parent@example.com'))
    expect(await screen.findByText(/check your email/i)).toBeInTheDocument()
    expect(screen.getByText(/if that email is registered/i)).toBeInTheDocument()
  })

  it('shows the SAME success state even when the request errors (no enumeration)', async () => {
    // A rate-limited / transient failure must not reveal whether the address exists.
    forgotMock.mockRejectedValue(new Error('rate limited'))
    const user = userEvent.setup()
    renderPage()

    await user.type(screen.getByLabelText(/email/i), 'nobody@example.com')
    await user.click(screen.getByRole('button', { name: /send reset link/i }))

    await waitFor(() => expect(forgotMock).toHaveBeenCalledWith('nobody@example.com'))
    expect(await screen.findByText(/check your email/i)).toBeInTheDocument()
  })
})
