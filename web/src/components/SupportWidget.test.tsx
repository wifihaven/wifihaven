import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render } from '@testing-library/react'
import type { SupportIdentityResponse } from '@/types/api'

// The widget gates on admin role (useAuth) and the server-signed identity (useSupportIdentity).
let mockAuth: { isAdmin: boolean } = { isAdmin: true }
vi.mock('@/hooks/useAuth', () => ({ useAuth: () => mockAuth }))

let mockIdentity: { data?: SupportIdentityResponse } = {}
const useSupportIdentityMock = vi.fn((_opts?: unknown) => mockIdentity)
vi.mock('@/api/queries', () => ({
  useSupportIdentity: (opts?: unknown) => useSupportIdentityMock(opts),
}))

import { SupportWidget } from './SupportWidget'

const configured: SupportIdentityResponse = {
  configured: true,
  appId: 'plainApp_123',
  email: 'admin@example.com',
  emailHash: 'deadbeefhash',
  tenantIdentifier: '7',
  fullName: 'The Test Family',
  plan: 'beta',
  founding: true,
  supportEmail: 'support@staging.wifihaven.net',
}

const dark: SupportIdentityResponse = {
  configured: false,
  appId: null,
  email: null,
  emailHash: null,
  tenantIdentifier: null,
  fullName: null,
  plan: null,
  founding: null,
  // #2429: the dark response still carries the address — email works with the chat widget off.
  supportEmail: 'support@staging.wifihaven.net',
}

beforeEach(() => {
  mockAuth = { isAdmin: true }
  mockIdentity = {}
  useSupportIdentityMock.mockClear()
  // Reset any injected script + global between tests.
  document.getElementById('plain-chat-sdk')?.remove()
  delete (window as unknown as { Plain?: unknown }).Plain
})

describe('SupportWidget (#2199)', () => {
  it('renders nothing and boots Plain when the server says configured (forwarding server-signed identity)', () => {
    const init = vi.fn()
    ;(window as unknown as { Plain: { init: typeof init } }).Plain = { init }
    mockIdentity = { data: configured }

    const { container } = render(<SupportWidget />)

    // No DOM of its own — the Plain SDK owns the UI.
    expect(container).toBeEmptyDOMElement()
    // Booted with the SERVER-signed identity, verbatim (household-gating: never client-derived).
    expect(init).toHaveBeenCalledTimes(1)
    const cfg = init.mock.calls[0][0] as Record<string, unknown>
    expect(cfg.appId).toBe('plainApp_123')
    expect(cfg.tenantIdentifier).toBe('7')
    const details = cfg.customerDetails as Record<string, unknown>
    expect(details.email).toBe('admin@example.com')
    expect(details.emailHash).toBe('deadbeefhash')
  })

  it('renders nothing and never boots Plain when the widget is dark (configured=false)', () => {
    const init = vi.fn()
    ;(window as unknown as { Plain: { init: typeof init } }).Plain = { init }
    mockIdentity = { data: dark }

    const { container } = render(<SupportWidget />)

    expect(container).toBeEmptyDOMElement()
    expect(init).not.toHaveBeenCalled()
    expect(document.getElementById('plain-chat-sdk')).toBeNull()
  })

  it('does not fetch identity or boot Plain for a non-admin', () => {
    const init = vi.fn()
    ;(window as unknown as { Plain: { init: typeof init } }).Plain = { init }
    mockAuth = { isAdmin: false }
    mockIdentity = { data: configured }

    render(<SupportWidget />)

    // The query is disabled for non-admins.
    expect(useSupportIdentityMock).toHaveBeenCalledWith({ enabled: false })
    expect(init).not.toHaveBeenCalled()
    expect(document.getElementById('plain-chat-sdk')).toBeNull()
  })

  it('injects the Plain SDK script when the global is not yet present', () => {
    // No window.Plain — the widget must inject the script and defer boot to its load event.
    mockIdentity = { data: configured }

    render(<SupportWidget />)

    const script = document.getElementById('plain-chat-sdk') as HTMLScriptElement | null
    expect(script).not.toBeNull()
    expect(script?.src).toContain('chat.cdn-plain.com')
  })
})
