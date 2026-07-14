import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'

vi.mock('@/api/client', () => ({
  api: { beta: { request: vi.fn() } },
}))

import { api } from '@/api/client'
import { BetaRequestPage } from './BetaRequestPage'

const requestMock = api.beta.request as unknown as ReturnType<typeof vi.fn>

beforeEach(() => {
  vi.resetAllMocks()
})

function renderPage() {
  return render(
    <MemoryRouter>
      <BetaRequestPage />
    </MemoryRouter>,
  )
}

describe('BetaRequestPage — /beta signup form', () => {
  it('submits email/name/note and shows the manual-approval success state', async () => {
    requestMock.mockResolvedValue({ status: 'received' })
    const user = userEvent.setup()
    renderPage()

    await user.type(screen.getByLabelText(/email/i), 'new@example.com')
    await user.type(screen.getByLabelText(/^name/i), 'The Smiths')
    await user.type(screen.getByLabelText(/anything else/i), 'excited to try it')
    await user.click(screen.getByRole('button', { name: /request access/i }))

    await screen.findByText(/request received/i)
    // The success copy must set the manual-approval + invite-link expectation.
    expect(screen.getByText(/approve beta requests manually/i)).toBeInTheDocument()
    expect(screen.getByText(/personal invite link/i)).toBeInTheDocument()

    expect(requestMock).toHaveBeenCalledWith({
      email: 'new@example.com',
      name: 'The Smiths',
      note: 'excited to try it',
    })
  })

  it('omits blank optional fields (email only)', async () => {
    requestMock.mockResolvedValue({ status: 'received' })
    const user = userEvent.setup()
    renderPage()

    await user.type(screen.getByLabelText(/email/i), 'solo@example.com')
    await user.click(screen.getByRole('button', { name: /request access/i }))

    await screen.findByText(/request received/i)
    expect(requestMock).toHaveBeenCalledWith({
      email: 'solo@example.com',
      name: undefined,
      note: undefined,
    })
  })

  it('shows a generic error on failure (no enumeration signal) and does not show success', async () => {
    requestMock.mockRejectedValue(new Error('HTTP 429'))
    const user = userEvent.setup()
    renderPage()

    await user.type(screen.getByLabelText(/email/i), 'dup@example.com')
    await user.click(screen.getByRole('button', { name: /request access/i }))

    await screen.findByText(/something went wrong/i)
    expect(screen.queryByText(/request received/i)).not.toBeInTheDocument()
    // The error message must not reveal whether the email already exists (no enumeration).
    expect(screen.getByText(/something went wrong/i).textContent).not.toMatch(/already|duplicate|exists/i)
  })
})
