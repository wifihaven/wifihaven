import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'

class ForbiddenError extends Error {}
vi.mock('@/api/client', () => ({
  api: { support: { consent: vi.fn() } },
  isForbiddenError: (e: unknown) => e instanceof ForbiddenError,
}))

const authState = { isAuthenticated: true }
vi.mock('@/hooks/useAuth', () => ({
  useAuth: () => authState,
}))

import { api } from '@/api/client'
import { SupportConsentPage } from './SupportConsentPage'

const consentMock = api.support.consent as unknown as ReturnType<typeof vi.fn>

beforeEach(() => {
  vi.resetAllMocks()
  authState.isAuthenticated = true
})

function renderPage(search = '?g=g1.abc.def') {
  return render(
    <MemoryRouter initialEntries={[`/support/consent${search}`]}>
      <SupportConsentPage />
    </MemoryRouter>,
  )
}

describe('SupportConsentPage — /support/consent (#2419)', () => {
  it('grants consent with the link token from the query string', async () => {
    consentMock.mockResolvedValue({ status: 'granted' })
    const user = userEvent.setup()
    renderPage()

    await user.click(screen.getByRole('button', { name: /^allow$/i }))

    await waitFor(() => expect(consentMock).toHaveBeenCalledWith('g1.abc.def', true))
    expect(await screen.findByText(/can now see your account summary/i)).toBeInTheDocument()
  })

  it('withdraws consent from the same page', async () => {
    consentMock.mockResolvedValue({ status: 'granted' })
    const user = userEvent.setup()
    renderPage()

    await user.click(screen.getByRole('button', { name: /^allow$/i }))
    await screen.findByRole('button', { name: /withdraw this permission/i })

    consentMock.mockResolvedValue({ status: 'revoked' })
    await user.click(screen.getByRole('button', { name: /withdraw this permission/i }))

    await waitFor(() => expect(consentMock).toHaveBeenCalledWith('g1.abc.def', false))
    expect(await screen.findByText(/permission withdrawn/i)).toBeInTheDocument()
  })

  it('surfaces a stale or wrong-account link without claiming success', async () => {
    consentMock.mockRejectedValue(new Error('400'))
    const user = userEvent.setup()
    renderPage()

    await user.click(screen.getByRole('button', { name: /^allow$/i }))

    expect(await screen.findByText(/no longer valid/i)).toBeInTheDocument()
    expect(screen.queryByText(/can now see your account summary/i)).not.toBeInTheDocument()
  })

  it('tells a wrong-account / non-admin visitor what to do instead of blaming the link', async () => {
    consentMock.mockRejectedValue(new ForbiddenError('forbidden'))
    const user = userEvent.setup()
    renderPage()

    await user.click(screen.getByRole('button', { name: /^allow$/i }))

    expect(await screen.findByText(/not for this account/i)).toBeInTheDocument()
    expect(screen.queryByText(/no longer valid/i)).not.toBeInTheDocument()
  })

  it('asks a signed-out visitor to sign in instead of posting', async () => {
    authState.isAuthenticated = false
    renderPage()

    expect(screen.getByRole('link', { name: /sign in/i })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^allow$/i })).not.toBeInTheDocument()
    expect(consentMock).not.toHaveBeenCalled()
  })

  it('explains what is missing when the link carries no token', () => {
    renderPage('')
    expect(screen.getByText(/needs the permission link/i)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^allow$/i })).not.toBeInTheDocument()
  })
})
