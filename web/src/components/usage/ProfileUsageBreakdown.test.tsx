import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { BreakdownBody } from './ProfileUsageBreakdown'
import type { ProfileUsageByApp } from '@/types/api'

function fixture(): ProfileUsageByApp {
  return {
    profileId: 1,
    profileName: 'Kids',
    from: '2026-06-07',
    to: '2026-06-07',
    apps: [
      {
        appId: 10,
        appName: 'YouTube',
        appIcon: '📺',
        appIconType: null,
        proportionalSeconds: 900,
        presenceSeconds: 900,
        hosts: [
          { host: { type: 'fqdn', value: 'youtube.com' }, usedMins: 5, proportionalMins: 5 },
          { host: { type: 'fqdn', value: 'ytimg.com' }, usedMins: 5, proportionalMins: 5 },
          { host: { type: 'fqdn', value: 'googlevideo.com' }, usedMins: 5, proportionalMins: 5 },
        ],
      },
    ],
    orphanHosts: [
      { host: { type: 'fqdn', value: 'wikipedia.org' }, proportionalSeconds: 300, presenceSeconds: 300 },
      { host: { type: 'fqdn', value: 'example.com' }, proportionalSeconds: 180, presenceSeconds: 180 },
    ],
  }
}

describe('ProfileUsageBreakdown — #1519/#726', () => {
  it('renders one row per configured app and one row per orphan host, NO "Other" bucket', () => {
    render(<BreakdownBody profileId={1} data={fixture()} />)
    // App row present
    expect(screen.getByTestId('profile-usage-breakdown-1-app-10')).toBeInTheDocument()
    // Per-orphan rows present (single-host pseudo-apps)
    expect(screen.getByTestId('profile-usage-breakdown-1-orphan-wikipedia.org')).toBeInTheDocument()
    expect(screen.getByTestId('profile-usage-breakdown-1-orphan-example.com')).toBeInTheDocument()
    // Nothing labelled "Other" anywhere
    expect(screen.queryByText(/^Other$/)).not.toBeInTheDocument()
  })

  it('drills into per-FQDN breakdown when the app row is expanded (#726)', async () => {
    render(<BreakdownBody profileId={1} data={fixture()} />)
    // Host rows hidden by default
    expect(
      screen.queryByTestId('profile-usage-breakdown-1-app-10-hosts'),
    ).not.toBeInTheDocument()
    await userEvent.click(screen.getByTestId('profile-usage-breakdown-1-app-10-toggle'))
    const hostList = screen.getByTestId('profile-usage-breakdown-1-app-10-hosts')
    expect(hostList).toHaveTextContent('youtube.com')
    expect(hostList).toHaveTextContent('ytimg.com')
    expect(hostList).toHaveTextContent('googlevideo.com')
  })

  it('orphan-list "+N more sites" expander is labelled explicitly as a UI rollup, not an "Other" bucket', async () => {
    const data = fixture()
    // 12 orphans → top-10 visible + "+2 more sites"
    data.orphanHosts = Array.from({ length: 12 }).map((_, i) => ({
      host: { type: 'fqdn', value: `host${i}.example.com` },
      proportionalSeconds: 300 - i,
      presenceSeconds: 300 - i,
    }))
    render(<BreakdownBody profileId={1} data={data} />)
    expect(screen.queryByTestId('profile-usage-breakdown-1-orphan-host10.example.com'))
      .not.toBeInTheDocument()
    const expand = screen.getByTestId('profile-usage-breakdown-1-orphan-expand')
    expect(expand).toHaveTextContent(/\+2 more sites/)
    // The copy MUST clarify these are individual sites, not an "Other" bucket
    expect(expand).toHaveTextContent(/not an "Other" bucket/i)
    await userEvent.click(expand)
    expect(screen.getByTestId('profile-usage-breakdown-1-orphan-host10.example.com'))
      .toBeInTheDocument()
  })

  it('renders an empty-state when there is no usage at all', () => {
    render(
      <BreakdownBody
        profileId={7}
        data={{
          profileId: 7,
          profileName: 'X',
          from: '2026-06-07',
          to: '2026-06-07',
          apps: [],
          orphanHosts: [],
        }}
      />,
    )
    expect(screen.getByTestId('profile-usage-breakdown-7-empty')).toBeInTheDocument()
  })
})
