import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'

import { BlockedPage } from './BlockedPage'

function renderBlocked(params: Record<string, string>) {
  const search = '?' + new URLSearchParams(params).toString()
  return render(
    <MemoryRouter initialEntries={[`/blocked${search}`]}>
      <BlockedPage />
    </MemoryRouter>,
  )
}

describe('BlockedPage — reason display', () => {
  // Reason strings here mirror MacBlockReason wire format
  // (shared/src/Models.scala: Paused / Schedule / TimeLimit / Manual).
  it('shows paused-profile copy for reason=Paused (#437)', () => {
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'example.com', reason: 'Paused' })
    expect(screen.getByText(/profile is paused/i)).toBeInTheDocument()
  })

  it('shows schedule end time for reason=Schedule with until param', () => {
    renderBlocked({
      mac: 'aa:bb:cc:11:22:33',
      host: 'example.com',
      reason: 'Schedule',
      until: '07:00',
    })
    expect(screen.getByText(/07:00/)).toBeInTheDocument()
  })

  it('shows daily limit message for reason=TimeLimit', () => {
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'youtube.com', reason: 'TimeLimit' })
    expect(screen.getByText(/daily/i)).toBeInTheDocument()
    expect(screen.getByText(/screen time/i)).toBeInTheDocument()
  })

  it('shows manual-block copy for reason=Manual', () => {
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'example.com', reason: 'Manual' })
    expect(screen.getByText(/blocked by a parent/i)).toBeInTheDocument()
  })

  it('shows extra-blocked copy for reason=ExtraBlocked (#576)', () => {
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'example.com', reason: 'ExtraBlocked' })
    expect(screen.getByText(/blocked by the household/i)).toBeInTheDocument()
  })

  it('shows category message for reason=category:ads', () => {
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'ads.example.com', reason: 'category:ads' })
    expect(screen.getByText(/blocked category/i)).toBeInTheDocument()
  })

  it('shows a non-empty fallback for unknown reason instead of leaving it blank (#437)', () => {
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'example.com', reason: 'whatever' })
    expect(screen.getByText(/access blocked/i)).toBeInTheDocument()
  })

  it('shows the blocked hostname prominently', () => {
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'youtube.com', reason: 'TimeLimit' })
    expect(screen.getByText('youtube.com')).toBeInTheDocument()
  })
})

describe('BlockedPage — no request-extension UI (#577)', () => {
  it('does not show a "Request extension" button', () => {
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'youtube.com', reason: 'TimeLimit' })
    expect(screen.queryByRole('button', { name: /request extension/i })).not.toBeInTheDocument()
  })

  it('does not show a parent-login dialog', () => {
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'youtube.com', reason: 'TimeLimit' })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('shows a static "ask a parent" instruction instead', () => {
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'youtube.com', reason: 'TimeLimit' })
    expect(screen.getByText(/ask a parent/i)).toBeInTheDocument()
  })
})
