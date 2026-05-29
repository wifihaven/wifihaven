import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { BucketSelector } from './BucketSelector'
import { bucketAvailability } from './retentionGating'

const NOW = new Date('2026-05-29T12:00:00.000Z')
const daysAgo = (n: number) => new Date(NOW.getTime() - n * 86400000)

describe('BucketSelector — retention gating', () => {
  it('greys out swept buckets and surfaces the retention reason as a tooltip', () => {
    // 45d back: raw (30d) swept, hourly/daily retained.
    const gates = bucketAvailability(daysAgo(45), NOW)
    render(<BucketSelector value="1h" onChange={() => {}} gates={gates} />)

    const raw = screen.getByTestId('bucket-raw')
    expect(raw).toBeDisabled()
    expect(raw).toHaveAttribute('title', expect.stringMatching(/30 days/))

    expect(screen.getByTestId('bucket-1h')).toBeEnabled()
    expect(screen.getByTestId('bucket-1d')).toBeEnabled()
  })

  it('does not fire onChange when a disabled bucket is clicked', async () => {
    const onChange = vi.fn()
    const gates = bucketAvailability(daysAgo(45), NOW)
    render(<BucketSelector value="1h" onChange={onChange} gates={gates} />)

    await userEvent.click(screen.getByTestId('bucket-raw'))
    expect(onChange).not.toHaveBeenCalled()
  })

  it('without gates, all buckets are enabled (backwards-compatible)', () => {
    render(<BucketSelector value="raw" onChange={() => {}} />)
    expect(screen.getByTestId('bucket-raw')).toBeEnabled()
    expect(screen.getByTestId('bucket-1w')).toBeEnabled()
  })
})
