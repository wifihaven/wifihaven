import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { withQuery } from '@/test/queryWrapper'
import type { PressMessage } from '@/types/api'

vi.mock('@/api/client', () => ({
  api: {
    press: {
      messages: vi.fn(),
    },
  },
}))

import { api } from '@/api/client'
import { PressPage } from './PressPage'

const messagesMock = api.press.messages as unknown as ReturnType<typeof vi.fn>

const inbound: PressMessage = {
  id: 1,
  direction: 'inbound',
  peerEmail: 'reporter@techdaily.example',
  subject: 'Comment request',
  body: 'Can you comment on how WifiHaven blocks sites?',
  messageId: '<abc@mail>',
  inReplyTo: null,
  outcome: null,
  createdAt: '2026-07-18T10:00:00Z',
  references: '',
}

const outbound: PressMessage = {
  id: 2,
  direction: 'outbound',
  peerEmail: 'reporter@techdaily.example',
  subject: 'Re: Comment request',
  body: 'Happy to share our public overview of how blocking works.',
  messageId: '',
  inReplyTo: 1,
  outcome: 'sent',
  createdAt: '2026-07-18T10:05:00Z',
  references: '',
}

beforeEach(() => {
  vi.resetAllMocks()
})

function renderPage() {
  return render(withQuery(<PressPage />))
}

describe('PressPage — operator press correspondence log', () => {
  it('shows a loading state before the list resolves', () => {
    // A never-resolving promise keeps the query pending.
    messagesMock.mockReturnValue(new Promise<PressMessage[]>(() => {}))
    renderPage()
    expect(screen.getByRole('status')).toHaveTextContent(/loading/i)
  })

  it('shows an error affordance (not a fake-empty) when the query fails', async () => {
    messagesMock.mockRejectedValue(new Error('boom'))
    renderPage()
    expect(await screen.findByText(/couldn't load the press log/i)).toBeInTheDocument()
    // The empty-state copy must NOT appear on error — an error is not "no correspondence".
    expect(screen.queryByText(/no press correspondence yet/i)).not.toBeInTheDocument()
  })

  it('renders a genuine empty state only once loaded', async () => {
    messagesMock.mockResolvedValue([])
    renderPage()
    expect(await screen.findByText(/no press correspondence yet/i)).toBeInTheDocument()
  })

  it('pairs an inbound inquiry with its AI reply', async () => {
    // Newest-first as the API returns it.
    messagesMock.mockResolvedValue([outbound, inbound])
    renderPage()
    expect(await screen.findByText('Comment request')).toBeInTheDocument()
    expect(screen.getByText(/from reporter@techdaily.example/i)).toBeInTheDocument()
    expect(screen.getByText(/how WifiHaven blocks sites/i)).toBeInTheDocument()
    // The reply is rendered as the AI reply, in the same thread.
    expect(screen.getByText(/AI reply/i)).toBeInTheDocument()
    expect(screen.getByText(/public overview of how blocking works/i)).toBeInTheDocument()
  })

  it('flags a failed reply send', async () => {
    messagesMock.mockResolvedValue([{ ...outbound, outcome: 'failed' }, inbound])
    renderPage()
    expect(await screen.findByText(/send failed/i)).toBeInTheDocument()
  })
})
