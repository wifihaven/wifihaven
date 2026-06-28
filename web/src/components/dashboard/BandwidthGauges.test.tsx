// #2040/#2041: the LIVE BANDWIDTH gauge renders three distinct states off `useWsTrafficUsage` —
// a loading skeleton (never "0 B/s"), the live rates, and a genuinely-idle "No live traffic".
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import type { WsTrafficUsage } from '@/hooks/useWs'
import type { BandwidthRate } from '@/api/wsCache'

const hookState = vi.fn<() => WsTrafficUsage>()
vi.mock('@/hooks/useWs', () => ({ useWsTrafficUsage: () => hookState() }))

import { BandwidthGauges } from './BandwidthGauges'

const ZERO: BandwidthRate = { bytesInPerSec: 0, bytesOutPerSec: 0, bytesPerSec: 0 }
const base: WsTrafficUsage = {
  live: true,
  loading: false,
  bucket: '1m',
  setBucket: () => {},
  rows: [],
  overall: ZERO,
  rate: () => ZERO,
}

beforeEach(() => hookState.mockReset())

describe('BandwidthGauges (#2040/#2041)', () => {
  it('shows a loading skeleton — NOT "0 B/s" / "No live traffic" — while data is loading', () => {
    hookState.mockReturnValue({ ...base, loading: true })
    render(<BandwidthGauges />)
    expect(screen.getByTestId('bandwidth-loading')).toBeInTheDocument()
    expect(screen.getByTestId('bandwidth-overall-loading')).toBeInTheDocument()
    expect(screen.queryByText('No live traffic right now')).not.toBeInTheDocument()
    expect(screen.queryByText(/0 B\/s/)).not.toBeInTheDocument()
  })

  it('renders per-profile rates once data is present', () => {
    const rate: BandwidthRate = { bytesInPerSec: 1024, bytesOutPerSec: 0, bytesPerSec: 1024 }
    hookState.mockReturnValue({
      ...base,
      rows: [{
        groups: { profile: 'Kids' }, windowStart: 'w', windowEnd: 'w',
        totalBytesIn: 0, totalBytesOut: 0, totalSeconds: 60,
      }],
      overall: rate,
      rate: () => rate,
    })
    render(<BandwidthGauges />)
    expect(screen.queryByTestId('bandwidth-loading')).not.toBeInTheDocument()
    expect(screen.getByTestId('bandwidth-profile-Kids')).toBeInTheDocument()
  })

  it('shows "No live traffic right now" only when data is present and the rate is idle', () => {
    hookState.mockReturnValue({ ...base, loading: false, rows: [] })
    render(<BandwidthGauges />)
    expect(screen.queryByTestId('bandwidth-loading')).not.toBeInTheDocument()
    expect(screen.getByText('No live traffic right now')).toBeInTheDocument()
  })

  it('shows the connection-paused message when the socket is down (not loading, not live)', () => {
    hookState.mockReturnValue({ ...base, live: false, loading: false, rows: [] })
    render(<BandwidthGauges />)
    expect(screen.getByTestId('bandwidth-overall-idle')).toBeInTheDocument()
    expect(screen.getByText(/Live throughput pauses/)).toBeInTheDocument()
  })
})
