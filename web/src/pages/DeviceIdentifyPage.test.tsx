import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import type { DeviceIdentity } from '@/types/api'
import { HttpError } from '@/api/httpError'
import { withQuery } from '@/test/queryWrapper'

vi.mock('@/api/client', () => ({
  api: {
    devices: {
      identify: vi.fn(),
      upsert: vi.fn(),
      patch: vi.fn(),
      delete: vi.fn(),
    },
  },
}))

import { api } from '@/api/client'
import { DeviceIdentifyPage } from './DeviceIdentifyPage'

const MAC = '84:1b:5e:11:22:33'
const identify = vi.mocked(api.devices.identify)

function renderPage() {
  return render(
    withQuery(
      <MemoryRouter initialEntries={[`/devices/${MAC}/identify`]}>
        <Routes>
          <Route path="/devices/:mac/identify" element={<DeviceIdentifyPage />} />
        </Routes>
      </MemoryRouter>,
    ),
  )
}

function identity(over: Partial<DeviceIdentity> = {}): DeviceIdentity {
  const now = Date.now()
  return {
    mac: MAC,
    name: 'Roku-Living-Room',
    sentName: 'Roku-Living-Room',
    managed: false,
    vendor: { kind: 'vendor', name: 'NETGEAR' },
    firstSeenAt: new Date(now - 12 * 60_000).toISOString(),
    lastSeenAt: null,
    lastSeenIp: '192.168.1.50',
    windowDays: 7,
    totalAllowed: 1,
    totalBlocked: 2,
    hostCount: 1,
    truncated: false,
    destinations: [
      {
        host: { type: 'fqdn', value: 'api.roku.com' },
        app: { slug: 'roku', name: 'Roku' },
        allowed: 1,
        blocked: 2,
        firstSeen: new Date(now - 10 * 60_000).toISOString(),
        lastSeen: new Date(now - 60_000).toISOString(),
      },
    ],
    apps: [{ slug: 'roku', name: 'Roku', allowed: 1, blocked: 2, hosts: 1 }],
    guess: { appSlug: 'roku', appName: 'Roku', sharePct: 100 },
    ...over,
  }
}

describe('DeviceIdentifyPage (#2926)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('shows a loading state, never an empty "no traffic" state, while the query is pending', () => {
    identify.mockReturnValue(new Promise(() => {}))
    renderPage()
    expect(screen.queryByTestId('identify-no-traffic')).toBeNull()
    expect(screen.queryByTestId('identify-destinations')).toBeNull()
    // liveness: the request was actually made for this MAC
    expect(identify).toHaveBeenCalledWith(MAC)
  })

  it('renders the DHCP name, vendor, first seen, and blocked attempts', async () => {
    identify.mockResolvedValue(identity())
    renderPage()
    expect(await screen.findByTestId('identify-name')).toHaveTextContent('Roku-Living-Room')
    expect(screen.getByTestId('identify-vendor')).toHaveTextContent('NETGEAR')
    expect(screen.getByTestId('identify-first-seen')).toHaveTextContent('12 minutes ago')
    const table = screen.getByTestId('identify-destinations')
    expect(table).toHaveTextContent('api.roku.com')
    expect(table).toHaveTextContent('Roku')
    expect(screen.getByTestId('identify-summary')).toHaveTextContent('2 blocked')
  })

  it('labels the best guess as a guess', async () => {
    identify.mockResolvedValue(identity())
    renderPage()
    const guess = await screen.findByTestId('identify-guess')
    expect(guess).toHaveTextContent('Best guess')
    expect(guess).toHaveTextContent('only a guess')
  })

  it('shows "Private address" for a randomized MAC instead of a vendor', async () => {
    identify.mockResolvedValue(identity({ vendor: { kind: 'private', name: null } }))
    renderPage()
    const vendor = await screen.findByTestId('identify-vendor')
    expect(vendor).toHaveTextContent('Private address')
    expect(vendor).not.toHaveTextContent('NETGEAR')
  })

  it('#2927: shows the stored DHCP hostname, and the local name when someone renamed it', async () => {
    identify.mockResolvedValue(identity({ name: 'Den TV', sentName: 'Den TV', dhcpHostname: 'roku-ultra' }))
    renderPage()
    const name = await screen.findByTestId('identify-name')
    expect(name).toHaveTextContent('roku-ultra')
    expect(name).toHaveTextContent('called "Den TV" here')
  })

  it('a device that sent no DHCP name says so, even though it has a placeholder name', async () => {
    identify.mockResolvedValue(identity({ name: 'unknown', sentName: null }))
    renderPage()
    const name = await screen.findByTestId('identify-name')
    expect(name).toHaveTextContent("didn't send a name")
    expect(name).not.toHaveTextContent('unknown')
  })

  it('a loaded device with no traffic says so, distinct from loading', async () => {
    identify.mockResolvedValue(
      identity({ destinations: [], apps: [], guess: null, hostCount: 0, totalAllowed: 0, totalBlocked: 0 }),
    )
    renderPage()
    expect(await screen.findByTestId('identify-no-traffic')).toHaveTextContent('No connection attempts')
    // liveness: the identity card still rendered from the same payload
    expect(screen.getByTestId('identify-name')).toHaveTextContent('Roku-Living-Room')
  })

  it('an error renders an error, not an empty list', async () => {
    identify.mockRejectedValue(new HttpError(503, 'boom'))
    renderPage()
    expect(await screen.findByTestId('identify-error')).toHaveTextContent("Couldn't load")
    expect(screen.queryByTestId('identify-no-traffic')).toBeNull()
  })

  it('a 404 says the device is not in this household', async () => {
    identify.mockRejectedValue(new HttpError(404, 'Device not found'))
    renderPage()
    expect(await screen.findByTestId('identify-error')).toHaveTextContent('not found in your household')
  })

  it('offers no control that changes the device', async () => {
    identify.mockResolvedValue(identity())
    renderPage()
    await screen.findByTestId('identify-destinations')
    expect(screen.queryByRole('button', { name: /enroll|assign|allow|unblock|save/i })).toBeNull()
    expect(api.devices.upsert).not.toHaveBeenCalled()
    expect(api.devices.patch).not.toHaveBeenCalled()
  })
})
