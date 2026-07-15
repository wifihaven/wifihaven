import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { withQuery } from '@/test/queryWrapper'
import type { BetaRequestSummary, ApproveBetaResponse } from '@/types/api'

vi.mock('@/api/client', () => ({
  api: {
    beta: {
      operatorList: vi.fn(),
      approve: vi.fn(),
      reject: vi.fn(),
    },
  },
}))

import { api } from '@/api/client'
import { BetaRequestsPage } from './BetaRequestsPage'

const listMock    = api.beta.operatorList as unknown as ReturnType<typeof vi.fn>
const approveMock = api.beta.approve as unknown as ReturnType<typeof vi.fn>
const rejectMock  = api.beta.reject as unknown as ReturnType<typeof vi.fn>

const pending: BetaRequestSummary = {
  id: 42,
  email: 'hopeful@example.com',
  name: 'The Hopefuls',
  note: 'please let us in',
  status: 'Pending',
  requestedAt: '2026-07-10T12:00:00Z',
  decidedAt: null,
  householdId: null,
}

const approveResp: ApproveBetaResponse = {
  householdId: 9,
  slug: 'the-hopefuls',
  inviteUrl: 'https://app.wifihaven.net/welcome?token=abc123',
  inviteExpiresAt: '2026-07-17T12:00:00Z',
}

beforeEach(() => {
  vi.resetAllMocks()
  listMock.mockResolvedValue([pending])
})

function renderPage() {
  return render(withQuery(
    <MemoryRouter>
      <BetaRequestsPage />
    </MemoryRouter>,
  ))
}

describe('BetaRequestsPage — operator queue', () => {
  it('lists pending requests', async () => {
    renderPage()
    expect(await screen.findByText('hopeful@example.com')).toBeInTheDocument()
    expect(screen.getByText('The Hopefuls')).toBeInTheDocument()
    expect(listMock).toHaveBeenCalledWith('Pending')
  })

  it('approve surfaces the invite URL with a copy button', async () => {
    approveMock.mockResolvedValue(approveResp)
    // Force the execCommand fallback (jsdom has no real secure-context Clipboard); it mirrors the
    // plain-http LAN self-host path and lets us assert the user-visible "Copied" outcome.
    Object.defineProperty(globalThis, 'isSecureContext', { get: () => false, configurable: true })
    const execCommand = vi.fn().mockReturnValue(true)
    ;(document as unknown as { execCommand: unknown }).execCommand = execCommand

    const user = userEvent.setup()
    renderPage()
    await screen.findByText('hopeful@example.com')

    // Exact name — the "Approved" status tab also matches /approve/i.
    await user.click(screen.getByRole('button', { name: /^approve$/i }))

    expect(await screen.findByText(approveResp.inviteUrl)).toBeInTheDocument()
    expect(approveMock).toHaveBeenCalledWith(42)

    await user.click(screen.getByRole('button', { name: /^copy$/i }))
    await waitFor(() => expect(execCommand).toHaveBeenCalledWith('copy'))
    expect(await screen.findByText(/copied/i)).toBeInTheDocument()
  })

  it('keeps the invite URL visible after the approved row leaves the Pending list (no flash)', async () => {
    approveMock.mockResolvedValue(approveResp)
    // First Pending load has the row; after approval it moves to Approved, so the Pending refetch
    // (triggered by invalidateAll) returns []. The invite URL must survive the row leaving the list.
    listMock.mockResolvedValueOnce([pending]).mockResolvedValue([])
    const user = userEvent.setup()
    renderPage()
    await screen.findByText('hopeful@example.com')

    await user.click(screen.getByRole('button', { name: /^approve$/i }))
    expect(await screen.findByText(approveResp.inviteUrl)).toBeInTheDocument()

    // The Pending list is now empty (the approved row moved to Approved) — yet the invite persists
    // in its own standalone surface rather than flashing away with the row.
    expect(await screen.findByText(/no pending requests/i)).toBeInTheDocument()
    expect(screen.getByText(approveResp.inviteUrl)).toBeInTheDocument()
  })

  it('reject calls the API', async () => {
    rejectMock.mockResolvedValue(undefined)
    const user = userEvent.setup()
    renderPage()
    await screen.findByText('hopeful@example.com')

    // Exact name — the "Rejected" status tab also matches /reject/i.
    await user.click(screen.getByRole('button', { name: /^reject$/i }))
    await waitFor(() => expect(rejectMock).toHaveBeenCalledWith(42))
  })

  it('shows a loading state before the list resolves', async () => {
    let resolve!: (v: BetaRequestSummary[]) => void
    listMock.mockReturnValue(new Promise<BetaRequestSummary[]>(r => { resolve = r }))
    renderPage()
    expect(screen.getByRole('status')).toHaveTextContent(/loading/i)
    resolve([])
    await screen.findByText(/no pending requests/i)
  })
})
