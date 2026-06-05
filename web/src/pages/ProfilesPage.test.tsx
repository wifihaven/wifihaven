import { describe, it, expect, beforeEach, vi } from 'vitest'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import type { BlocklistSummary, Device, ProfileDetail, ProfileTimeSummary, User } from '@/types/api'
import { withQuery } from '@/test/queryWrapper'

vi.mock('@/api/client', () => ({
  api: {
    profiles: {
      list: vi.fn(),
      create: vi.fn(),
      update: vi.fn(),
      delete: vi.fn(),
      setUsers: vi.fn(),
      setSchedules: vi.fn(),
      usageByApp: vi.fn(),
    },
    // #1473 — the inline blocked-categories editor on the profile card
    // fetches the blocklist catalog from /blocklists (same fetch the
    // Blocklists matrix page uses) and PATCHes blockedCategories.
    blocklists: {
      list: vi.fn(),
    },
    devices: {
      list: vi.fn(),
      patch: vi.fn(),
    },
    users: {
      list: vi.fn(),
    },
    household: {
      get: vi.fn(),
    },
    apps: {
      list: vi.fn(),
      setPolicy: vi.fn(),
      deletePolicy: vi.fn(),
    },
    // #1380 — the per-app schedule-rule editor embeds the #1069 SchedulePicker,
    // which reads/writes the household named-schedule catalog.
    schedules: {
      list: vi.fn(),
      create: vi.fn(),
    },
    time: {
      summaryAll: vi.fn(),
      grantExtension: vi.fn(),
      statusAllWeek: vi.fn(),
    },
    usage: {
      series: vi.fn(),
    },
  },
}))

// recharts pulls in canvas + ResizeObserver under jsdom; the expanded card
// always mounts ProfileTimelineChart, so stub the chart primitives globally.
vi.mock('recharts', () => {
  const Pass = ({ children }: { children?: React.ReactNode }) => <div>{children}</div>
  return {
    Bar: () => null,
    BarChart: Pass,
    CartesianGrid: () => null,
    Legend: () => null,
    ResponsiveContainer: Pass,
    Tooltip: () => null,
    XAxis: () => null,
    YAxis: () => null,
  }
})

vi.mock('@/hooks/useAuth', () => ({
  useAuth: () => mockAuth,
}))

import { api } from '@/api/client'
import { ProfilesPage } from './ProfilesPage'

function renderPage(initialEntries: string[] = ['/profiles']) {
  return render(withQuery(
    <MemoryRouter initialEntries={initialEntries}>
      <ProfilesPage />
    </MemoryRouter>,
  ))
}

let mockAuth = { isAdmin: true }

const kidsProfile: ProfileDetail = {
  profile: {
    id: 1,
    name: 'Kids',
    blockedCategories: ['adult', 'gambling'],
    paused: false,
    failureMode: 'block-all',
    crossDeviceOverlapMode: 'sum',
    pauseMode: 'soft',
    defaultDeny: false,
  },
  // #1494: the legacy inline `schedules` read field is now always empty (the
  // upsert no longer writes the V1 table). A profile's block schedules are the
  // attached #1069 named-schedule ids.
  schedules: [],
  scheduleIds: [10],
  timeLimit: { id: 5, profileId: 1, dailyMinutes: 120 },
}

const adultsProfile: ProfileDetail = {
  profile: {
    id: 2,
    name: 'Adults',
    blockedCategories: [],
    paused: true,
    failureMode: 'last-known-good',
    crossDeviceOverlapMode: 'sum',
    pauseMode: 'soft',
    defaultDeny: false,
  },
  schedules: [],
  scheduleIds: [],
  timeLimit: null,
}

const phoneDevice: Device = {
  id: 100, mac: 'aa:bb:cc:dd:ee:01', name: 'Kid Phone', profileId: 1,
  profileName: 'Kids', lastSeenIp: null, lastSeenAt: null,
}
const tabletDevice: Device = {
  id: 101, mac: 'aa:bb:cc:dd:ee:02', name: 'Adult Tablet', profileId: 2,
  profileName: 'Adults', lastSeenIp: null, lastSeenAt: null,
}

const kidsSummary: ProfileTimeSummary = {
  profileId: 1, profileName: 'Kids', date: '2026-05-24',
  dailyLimitMins: 120, usedMins: 45, extensionMins: 0, remainingMins: 75,
}
const adultsSummary: ProfileTimeSummary = {
  profileId: 2, profileName: 'Adults', date: '2026-05-24',
  dailyLimitMins: null, usedMins: 0, extensionMins: 0, remainingMins: null,
}

// #1473 — blocklist catalog returned by GET /api/blocklists, consumed by the
// inline blocked-categories editor on the profile card.
const blocklistCatalog: BlocklistSummary[] = [
  { id: 'adult', name: 'Adult content', description: 'Adult sites', bundled: true, source: null, hostCount: 100, lastBuiltAt: null },
  { id: 'gambling', name: 'Gambling', description: null, bundled: true, source: null, hostCount: 50, lastBuiltAt: null },
  { id: 'social', name: 'Social media', description: null, bundled: true, source: null, hostCount: 30, lastBuiltAt: null },
  { id: 'malware', name: 'Malware', description: null, bundled: false, source: 'operator', hostCount: 10, lastBuiltAt: null },
]

const aliceUser: User = { id: 10, username: 'alice', role: 'child', profileIds: [1] }
const bobUser:   User = { id: 11, username: 'bob',   role: 'adult', profileIds: [2] }
const carolUser: User = { id: 12, username: 'carol', role: 'admin', profileIds: [1, 2] }

beforeEach(() => {
  vi.resetAllMocks()
  mockAuth = { isAdmin: true }
  ;(api.profiles.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([kidsProfile, adultsProfile])
  ;(api.profiles.create as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 99 })
  ;(api.profiles.update as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(undefined)
  ;(api.profiles.delete as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(undefined)
  ;(api.profiles.setUsers as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(undefined)
  ;(api.profiles.setSchedules as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(undefined)
  ;(api.devices.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([phoneDevice, tabletDevice])
  ;(api.devices.patch as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(undefined)
  ;(api.users.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([aliceUser, bobUser, carolUser])
  ;(api.household.get as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({
    dailyResetTime: '00:00',
    dailyResetTz: 'America/Los_Angeles',
  })
  ;(api.blocklists.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(blocklistCatalog)
  ;(api.apps.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([])
  ;(api.apps.setPolicy as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(undefined)
  ;(api.apps.deletePolicy as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(undefined)
  ;(api.schedules.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([])
  ;(api.schedules.create as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(undefined)
  ;(api.profiles.usageByApp as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({
    profileId: 1, profileName: 'Kids', from: '2026-05-26', to: '2026-05-26', apps: [],
  })
  ;(api.time.summaryAll as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([kidsSummary, adultsSummary])
  ;(api.time.grantExtension as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 1, grantedMinutes: 30 })
  ;(api.time.statusAllWeek as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([])
  // #1036 — ProfileTimelineChart fires /api/usage/series whenever a card is
  // expanded (Today is the default tab). Default to an empty payload; specific
  // tests can override to populate top hosts/devices.
  ;(api.usage.series as unknown as ReturnType<typeof vi.fn>).mockImplementation(
    ({ profileId, date }: { profileId: number; date: string }) =>
      Promise.resolve({
        profileId,
        profileName: profileId === 1 ? 'Kids' : 'Adults',
        date,
        tz: 'UTC',
        topHosts: [],
        buckets: Array.from({ length: 24 }, (_, h) => ({
          hour: h, totalMins: 0, perHost: [], otherMins: 0,
        })),
        topDevices: [],
        bucketsByDevice: Array.from({ length: 24 }, (_, h) => ({
          hour: h, totalMins: 0, perDevice: [], otherMins: 0,
        })),
      }),
  )
})

// #972 — cards are collapse-by-default; tests that need the expanded body
// (Edit / Delete / Pause buttons, devices, linked users) must expand first.
async function expand(pid: number, user = userEvent.setup()) {
  await user.click(screen.getByTestId(`profile-row-toggle-${pid}`))
}

describe('ProfilesPage — list (collapse-by-default shell, #972)', () => {
  it('renders one collapsed card per profile with name and pause chip', async () => {
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    const adultsCard = screen.getByTestId('profile-card-2')
    expect(within(kidsCard).getByText('Kids')).toBeInTheDocument()
    expect(within(adultsCard).getByText('Adults')).toBeInTheDocument()
    // collapsed body is hidden — categories / schedules / limit live inside it
    expect(within(kidsCard).queryByText('Bedtime')).not.toBeInTheDocument()
    expect(within(kidsCard).queryByText('120 min')).not.toBeInTheDocument()
  })

  it('summary row carries used/cap, pause chip, and +Time for limited profiles', async () => {
    // override the Kids profile to drop the schedule so the "active" chip
    // assertion is independent of when the test happens to run.
    (api.profiles.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([
      { ...kidsProfile, schedules: [] },
      adultsProfile,
    ])
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    // "Kids" has dailyLimitMins=120, usedMins=45 → "45m / 2:00"
    const time = within(kidsCard).getByTestId('profile-summary-time-1')
    expect(time).toHaveTextContent('45m')
    expect(time).toHaveTextContent('2:00')
    const chip = within(kidsCard).getByTestId('profile-pause-chip-1')
    expect(chip).toHaveAttribute('data-chip', 'active')
    expect(within(kidsCard).getByTestId('profile-row-grant-1')).toBeInTheDocument()
  })

  it('paused profile renders the Paused chip', async () => {
    renderPage()
    const adultsCard = await screen.findByTestId('profile-card-2')
    const chip = within(adultsCard).getByTestId('profile-pause-chip-2')
    expect(chip).toHaveAttribute('data-chip', 'paused-manual')
    expect(chip).toHaveTextContent(/Paused/)
  })

  it('summary row reflects granted +Time extension in the cap text', async () => {
    // #975 follow-up: pre-fix the row read "45m / 2:00" even after a +30m
    // grant — the bar denominator grew but the text ignored extensionMins,
    // making fresh grants look like no-ops. Post-fix the denominator is
    // base+extension and an "(+Xm)" suffix calls the grant out explicitly.
    (api.time.summaryAll as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([
      { ...kidsSummary, extensionMins: 30, remainingMins: 105 },
      adultsSummary,
    ])
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    const time = within(kidsCard).getByTestId('profile-summary-time-1')
    expect(time).toHaveTextContent('45m')
    // 120 base + 30 extension = 150 = "2:30"
    expect(time).toHaveTextContent('2:30')
    expect(time).toHaveTextContent('(+30m)')
  })

  it('time-exceeded summary flips the chip and hides the bar fill at 100%', async () => {
    (api.time.summaryAll as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([
      { ...kidsSummary, usedMins: 130, remainingMins: 0 },
      adultsSummary,
    ])
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    const chip = within(kidsCard).getByTestId('profile-pause-chip-1')
    expect(chip).toHaveAttribute('data-chip', 'time-exceeded')
  })

  // #1109 — follow-up to #1104. Locks in that a blocked-today time-status
  // payload (usedMins == dailyLimitMins, remainingMins == 0) reaches the
  // operator-visible summary row: chip copy reads "Time exceeded", the
  // burn renders as "30m / 30m", and the date comes from the mock — no
  // hard-coded UTC date that would silently rot when run in non-UTC tz.
  //
  // Scope note: the issue mentions /api/time/status/{mac} (DeviceTimeStatus),
  // but no SPA surface consumes statusDevice today. The blocked-today UI
  // lives on the profile summary chip, fed by /api/time/status/summary
  // (ProfileTimeSummary). Same set of burn fields, same #1104 fix path.
  it('blocked-today profile surfaces "Time exceeded" copy with used/limit burn (#1109)', async () => {
    const blockedDate = '2026-05-26'
    const blockedSummary: ProfileTimeSummary = {
      profileId: 1, profileName: 'Kids', date: blockedDate,
      dailyLimitMins: 30, usedMins: 30, extensionMins: 0, remainingMins: 0,
    }
    ;(api.time.summaryAll as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([
      blockedSummary, adultsSummary,
    ])
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    const chip = within(kidsCard).getByTestId('profile-pause-chip-1')
    expect(chip).toHaveAttribute('data-chip', 'time-exceeded')
    expect(chip).toHaveTextContent(/Time exceeded/i)
    const time = within(kidsCard).getByTestId('profile-summary-time-1')
    expect(time).toHaveTextContent('30m')
    expect(time).toHaveTextContent(/30m\s*\/\s*30m/)
    expect(time).not.toHaveTextContent('(+')
  })

  it('no +Time button for profiles without a daily limit', async () => {
    renderPage()
    const adultsCard = await screen.findByTestId('profile-card-2')
    expect(within(adultsCard).queryByTestId('profile-row-grant-2')).not.toBeInTheDocument()
  })

  it('clicking the row toggles the expanded body', async () => {
    // #1494: the schedule summary now lists the attached named schedule's name
    // (kidsProfile.scheduleIds = [10]) from the household catalog.
    (api.schedules.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(
      [{ id: 10, name: 'Bedtime', description: null, windows: [] }],
    )
    const user = userEvent.setup()
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    expect(within(kidsCard).queryByText('Bedtime')).not.toBeInTheDocument()
    await user.click(within(kidsCard).getByTestId('profile-row-toggle-1'))
    expect(within(kidsCard).getByText('Bedtime')).toBeInTheDocument()
    expect(within(kidsCard).getByText('120 min')).toBeInTheDocument()
    await user.click(within(kidsCard).getByTestId('profile-row-toggle-1'))
    expect(within(kidsCard).queryByText('Bedtime')).not.toBeInTheDocument()
  })
})

describe('ProfilesPage — +Time mutation (#972 / #946)', () => {
  it('opens the modal then submits grantExtension with the chosen minutes', async () => {
    const user = userEvent.setup()
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    await user.click(within(kidsCard).getByTestId('profile-row-grant-1'))
    // 30m is the default; pick 60m to make the assertion specific
    await user.click(screen.getByRole('button', { name: /^60m$/ }))
    await user.click(screen.getByRole('button', { name: /^Grant 60m$/ }))
    await waitFor(() =>
      expect(api.time.grantExtension).toHaveBeenCalledWith({
        profileId: 1, extraMinutes: 60, note: null,
      }),
    )
  })
})

// #1299 — granting +Time must refresh the collapsed summary's used/cap text +
// bar (and the #975 "(+Xm)" extension surfacing) WITHOUT a reload. The summary
// is served by `api.time.summaryAll` via the ['time','status','summary','today']
// query; the grant mutation's onSuccess invalidates the ['time','status']
// subtree (the #946 pattern, shared by the #1086 app-policy edits below), which
// matches that key as a prefix and forces a refetch. This test is the
// regression guard for that invalidation: drop the `invalidators.timeStatus()`
// call in ProfilesPage's grantMutation.onSuccess and it goes red (summaryAll is
// called once, the "(+30m)" suffix never appears).
describe('ProfilesPage — +Time grant refreshes the summary used/cap (#1299)', () => {
  it('grant refetches the time-status summary and surfaces the new extension without a reload', async () => {
    const summaryFn = api.time.summaryAll as unknown as ReturnType<typeof vi.fn>
    // Mount sees no extension; the post-grant refetch sees +30m on Kids.
    summaryFn.mockResolvedValueOnce([kidsSummary, adultsSummary])
    summaryFn.mockResolvedValue([
      { ...kidsSummary, extensionMins: 30, remainingMins: 105 },
      adultsSummary,
    ])
    const user = userEvent.setup()
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')

    // Pre-grant: base cap only, no "(+…)" suffix.
    const time = within(kidsCard).getByTestId('profile-summary-time-1')
    expect(time).toHaveTextContent('45m')
    expect(time).toHaveTextContent('2:00')
    expect(time).not.toHaveTextContent('(+')
    expect(summaryFn).toHaveBeenCalledTimes(1)

    await user.click(within(kidsCard).getByTestId('profile-row-grant-1'))
    await user.click(screen.getByRole('button', { name: /^Grant 30m$/ }))

    await waitFor(() =>
      expect(api.time.grantExtension).toHaveBeenCalledWith({
        profileId: 1, extraMinutes: 30, note: null,
      }),
    )
    // The summary query is invalidated → refetched (no reload).
    await waitFor(() => expect(summaryFn).toHaveBeenCalledTimes(2))
    // 120 base + 30 extension = 150 = "2:30", with the #975 "(+30m)" call-out.
    await waitFor(() => {
      const t = within(kidsCard).getByTestId('profile-summary-time-1')
      expect(t).toHaveTextContent('2:30')
      expect(t).toHaveTextContent('(+30m)')
    })
  })
})

describe('ProfilesPage — pause / delete in collapsed row (#1063)', () => {
  // #1063 — Pause/Resume + Delete were moved from the expanded body into the
  // collapsed summary row (alongside the +Time button). The card no longer
  // needs to be expanded to reach either action.
  // #406: pause is still an explicit PUT with the full profile + paused=!current.
  // #1471: clicking Pause on an active profile no longer fires immediately —
  // it surfaces a soft/hard choice; the chosen mode rides the same PUT.
  it('Pause button is rendered in the collapsed row and fires update without expanding', async () => {
    const user = userEvent.setup()
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    // collapsed body is hidden — no schedule subsection visible
    expect(within(kidsCard).queryByText('Bedtime')).not.toBeInTheDocument()
    const pauseBtn = within(kidsCard).getByTestId('profile-row-pause-1')
    await user.click(pauseBtn)
    // the click opens the soft/hard picker; nothing is saved yet
    expect(api.profiles.update).not.toHaveBeenCalled()
    await user.click(within(kidsCard).getByTestId('profile-row-pause-soft-1'))
    await waitFor(() =>
      expect(api.profiles.update).toHaveBeenCalledWith(
        1,
        expect.objectContaining({ paused: true, pauseMode: 'soft', name: 'Kids' }),
      ),
    )
    await waitFor(() => expect(api.profiles.list).toHaveBeenCalledTimes(2))
  })

  it('Resume button is rendered in the collapsed row for a paused profile', async () => {
    const user = userEvent.setup()
    renderPage()
    const adultsCard = await screen.findByTestId('profile-card-2')
    expect(within(adultsCard).queryByText('Bedtime')).not.toBeInTheDocument()
    await user.click(within(adultsCard).getByTestId('profile-row-pause-2'))
    await waitFor(() =>
      expect(api.profiles.update).toHaveBeenCalledWith(
        2,
        expect.objectContaining({ paused: false, name: 'Adults' }),
      ),
    )
  })

  it('Delete button is rendered in the collapsed row and confirms before deleting', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const user = userEvent.setup()
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    expect(within(kidsCard).queryByText('Bedtime')).not.toBeInTheDocument()
    await user.click(within(kidsCard).getByTestId('profile-row-delete-1'))
    expect(confirmSpy).toHaveBeenCalled()
    await waitFor(() => expect(api.profiles.delete).toHaveBeenCalledWith(1))
    confirmSpy.mockRestore()
  })

  it('does not delete when confirm is cancelled', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false)
    const user = userEvent.setup()
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    await user.click(within(kidsCard).getByTestId('profile-row-delete-1'))
    expect(api.profiles.delete).not.toHaveBeenCalled()
    confirmSpy.mockRestore()
  })

  it('expanded body no longer renders the Pause/Delete affordances', async () => {
    const user = userEvent.setup()
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    await expand(1, user)
    // collapsed-row controls are the only Pause/Delete: exactly one of each,
    // and both carry the row test ids.
    const pauseButtons = within(kidsCard).getAllByRole('button', { name: /Pause|Resume/ })
    expect(pauseButtons).toHaveLength(1)
    expect(pauseButtons[0]).toBe(within(kidsCard).getByTestId('profile-row-pause-1'))
    const deleteButtons = within(kidsCard).getAllByRole('button', { name: /^Delete$/ })
    expect(deleteButtons).toHaveLength(1)
    expect(deleteButtons[0]).toBe(within(kidsCard).getByTestId('profile-row-delete-1'))
  })
})

// #1471 — soft vs hard pause is chosen at the moment of pausing, via a small
// picker on the row Pause action, NOT as a persistent radio buried in the
// Time-limits subsection. The chosen mode rides the same PUT that sets paused.
describe('ProfilesPage — pause-mode chosen at pause-time (#1471)', () => {
  it('clicking Pause surfaces a soft/hard choice rather than saving immediately', async () => {
    const user = userEvent.setup()
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    await user.click(within(kidsCard).getByTestId('profile-row-pause-1'))
    expect(within(kidsCard).getByTestId('profile-row-pause-soft-1')).toBeInTheDocument()
    expect(within(kidsCard).getByTestId('profile-row-pause-hard-1')).toBeInTheDocument()
    expect(api.profiles.update).not.toHaveBeenCalled()
  })

  it('choosing Hard pause PUTs paused=true with pauseMode=hard', async () => {
    const user = userEvent.setup()
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    await user.click(within(kidsCard).getByTestId('profile-row-pause-1'))
    await user.click(within(kidsCard).getByTestId('profile-row-pause-hard-1'))
    await waitFor(() =>
      expect(api.profiles.update).toHaveBeenCalledWith(
        1,
        expect.objectContaining({ paused: true, pauseMode: 'hard', name: 'Kids' }),
      ),
    )
  })

  it('choosing Soft pause PUTs paused=true with pauseMode=soft', async () => {
    const user = userEvent.setup()
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    await user.click(within(kidsCard).getByTestId('profile-row-pause-1'))
    await user.click(within(kidsCard).getByTestId('profile-row-pause-soft-1'))
    await waitFor(() =>
      expect(api.profiles.update).toHaveBeenCalledWith(
        1,
        expect.objectContaining({ paused: true, pauseMode: 'soft', name: 'Kids' }),
      ),
    )
  })

  it('Resume stays a single click (no picker) for a paused profile', async () => {
    const user = userEvent.setup()
    renderPage()
    const adultsCard = await screen.findByTestId('profile-card-2')
    await user.click(within(adultsCard).getByTestId('profile-row-pause-2'))
    // no picker for resume
    expect(within(adultsCard).queryByTestId('profile-row-pause-soft-2')).not.toBeInTheDocument()
    expect(within(adultsCard).queryByTestId('profile-row-pause-hard-2')).not.toBeInTheDocument()
    await waitFor(() =>
      expect(api.profiles.update).toHaveBeenCalledWith(
        2,
        expect.objectContaining({ paused: false, name: 'Adults' }),
      ),
    )
  })

  it('the standalone persistent Pause-mode radios are gone from the Time-limits subsection', async () => {
    const user = userEvent.setup()
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    await expand(1, user)
    await user.click(within(kidsCard).getByTestId('profile-time-toggle-1'))
    expect(within(kidsCard).queryByTestId('profile-pause-mode-soft-1')).not.toBeInTheDocument()
    expect(within(kidsCard).queryByTestId('profile-pause-mode-hard-1')).not.toBeInTheDocument()
  })
})

// #978 — the old ProfileEditor modal is gone; "+ New Profile" now opens a
// tiny inline name-only form at the top of the page. Everything else (time
// limit, schedules, blocked categories, app policies, users, devices) is
// filled in via the inline subsections on the new card's expanded body.
describe('ProfilesPage — create (inline name-only, #978)', () => {
  it('shows validation error when name is empty', async () => {
    const user = userEvent.setup()
    renderPage()
    await screen.findByText('Kids')
    await user.click(screen.getByRole('button', { name: /\+ New Profile/ }))
    await user.click(screen.getByTestId('profile-create-save'))
    expect(await screen.findByTestId('profile-create-error')).toHaveTextContent(/Name is required/i)
    expect(api.profiles.create).not.toHaveBeenCalled()
  })

  it('calls api.profiles.create with the typed name + safe-by-default body', async () => {
    const user = userEvent.setup()
    renderPage()
    await screen.findByText('Kids')
    await user.click(screen.getByRole('button', { name: /\+ New Profile/ }))

    await user.type(screen.getByTestId('profile-create-name'), 'Teens')
    await user.click(screen.getByTestId('profile-create-save'))

    await waitFor(() => expect(api.profiles.create).toHaveBeenCalledTimes(1))
    expect(api.profiles.create).toHaveBeenCalledWith({
      name: 'Teens',
      blockedCategories: [],
      paused: false,
      timeLimit: null,
      // #1494: the create payload no longer carries schedules — they attach via
      // PUT /profiles/{id}/schedules after the profile exists.
      // #385: column-default for a brand-new profile is LastKnownGood.
      failureMode: 'last-known-good',
      // #751: column-default for a brand-new profile is Sum.
      crossDeviceOverlapMode: 'sum',
    })
    await waitFor(() => expect(api.profiles.list).toHaveBeenCalledTimes(2))
  })

  it('Cancel closes the form without calling create', async () => {
    const user = userEvent.setup()
    renderPage()
    await screen.findByText('Kids')
    await user.click(screen.getByRole('button', { name: /\+ New Profile/ }))
    await user.type(screen.getByTestId('profile-create-name'), 'Teens')
    await user.click(screen.getByTestId('profile-create-cancel'))
    expect(screen.queryByTestId('profile-create-form')).not.toBeInTheDocument()
    expect(api.profiles.create).not.toHaveBeenCalled()
  })

  it('Enter submits, Escape cancels', async () => {
    const user = userEvent.setup()
    renderPage()
    await screen.findByText('Kids')
    await user.click(screen.getByRole('button', { name: /\+ New Profile/ }))
    const input = screen.getByTestId('profile-create-name')
    await user.type(input, 'Teens{Enter}')
    await waitFor(() => expect(api.profiles.create).toHaveBeenCalledTimes(1))
    // Form re-closed after success.
    await waitFor(() =>
      expect(screen.queryByTestId('profile-create-form')).not.toBeInTheDocument(),
    )
  })
})

// #973/#978: the modal-based edit + create paths are both gone. Coverage of
// the per-field write paths now lives in the per-subsection describes:
//   - name           → "ProfilesPage — #973 inline name subsection"
//   - timeLimit /
//     schedules /
//     crossDeviceOverlapMode → "ProfilesPage — inline time-limit subsection (#975)"
//   - blockedCategories → "ProfilesPage — apps subsection (#976)"
//   - paused          → "ProfilesPage — pause / delete" + inline subsection sync
//   - app policies   → "ProfilesPage — apps section (#767)" exercises the
//                      inline subsection's AppsSection (post-#976).
//   - failureMode    → tracked as an orphan until the inline failureMode
//                      subsection lands; the column default
//                      (`last-known-good`) is pinned by the create body
//                      assertion above.
// Post-#764 the legacy extraBlocked/extraAllowed/siteTimeLimits fields are
// gone — per-host policy lives in apps, exercised by the apps subsection.

describe('ProfilesPage — inline time-limit subsection (#975)', () => {
  it('collapsed-by-default subsection shows daily-limit + overlap, no schedules (#1474)', async () => {
    const user = userEvent.setup()
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    await expand(1, user)

    const sub = within(kidsCard).getByTestId('profile-time-subsection-1')
    // Collapsed: editor body is hidden. The summary readout shows the daily cap
    // and the cross-device overlap mode — schedules moved to their own expander
    // (#1474), so no "Bedtime …" line here.
    expect(within(sub).getByText(/Daily limit:/)).toBeInTheDocument()
    expect(within(sub).getByText('120 min')).toBeInTheDocument()
    expect(within(sub).getByText(/Cross-device overlap:/)).toBeInTheDocument()
    expect(within(sub).queryByText('Bedtime')).not.toBeInTheDocument()
    expect(within(sub).queryByText(/21:00 → 07:00/)).not.toBeInTheDocument()
    // Editable inputs only appear after expand.
    expect(within(sub).queryByTestId('profile-time-limit-1')).not.toBeInTheDocument()

    await user.click(within(sub).getByTestId('profile-time-toggle-1'))
    expect(within(sub).getByTestId('profile-time-limit-1')).toHaveValue(120)
    expect(within(sub).getByTestId('profile-time-overlap-sum-1')).toBeChecked()
    // The schedule editor is no longer inside the time subsection (#1474).
    expect(within(sub).queryByTestId('profile-schedule-add-1')).not.toBeInTheDocument()
    expect(within(sub).queryByTestId('profile-schedule-row-1-0')).not.toBeInTheDocument()
  })

  // Real timers + waitFor throughout (no fake-timer juggling). These debounced
  // autosave tests previously used `vi.useFakeTimers({ shouldAdvanceTime })`,
  // which flaked (#1439): the time-limit input's mount value-resync
  // `useEffect(() => setX(value.x))` can run AFTER our change (its passive
  // effect hadn't flushed when the element was found under CI load), reverting
  // the input so the debounce never commits and the PUT is never sent.
  // `await act` drains those pending mount effects before we interact; `waitFor`
  // then polls for the real-clock debounce.
  it('autosaves the daily cap after debounce — single PUT, no Save button', async () => {
    const user = userEvent.setup()
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    await expand(1, user)
    await user.click(within(kidsCard).getByTestId('profile-time-toggle-1'))

    const input = within(kidsCard).getByTestId('profile-time-limit-1')
    // Drain the input's pending mount effects (value-resync) before interacting.
    await act(async () => {})

    // Set atomically: char-by-char typing lets the debounce fire on a transient
    // partial value, producing extra PUTs. fireEvent.change gives one value.
    fireEvent.change(input, { target: { value: '90' } })

    // Pre-debounce: no save yet.
    expect(api.profiles.update).not.toHaveBeenCalled()

    await waitFor(() => {
      expect(api.profiles.update).toHaveBeenCalledTimes(1)
      expect(api.profiles.update).toHaveBeenLastCalledWith(
        1,
        expect.objectContaining({ timeLimit: 90, name: 'Kids' }),
      )
    })

    // "Saved" indicator surfaces after the PUT resolves.
    await waitFor(() => {
      const status = within(kidsCard).getByTestId('profile-time-status-1')
      expect(status).toHaveAttribute('data-status', 'saved')
      expect(status).toHaveTextContent('Saved')
    })
  })

  it('autosaves the cross-device overlap toggle', async () => {
    const user = userEvent.setup()
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    await expand(1, user)
    await user.click(within(kidsCard).getByTestId('profile-time-toggle-1'))
    await act(async () => {})

    await user.click(within(kidsCard).getByTestId('profile-time-overlap-dedup-1'))

    // Poll for the debounced PUT — see the daily-cap test (#1439).
    await waitFor(() =>
      expect(api.profiles.update).toHaveBeenLastCalledWith(
        1,
        expect.objectContaining({ crossDeviceOverlapMode: 'dedup' }),
      ),
    )
  })

  it('clearing the daily cap sends timeLimit:null', async () => {
    const user = userEvent.setup()
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    await expand(1, user)
    await user.click(within(kidsCard).getByTestId('profile-time-toggle-1'))
    await act(async () => {})

    await user.clear(within(kidsCard).getByTestId('profile-time-limit-1'))

    // Poll for the debounced PUT — see the daily-cap test (#1439).
    await waitFor(() =>
      expect(api.profiles.update).toHaveBeenLastCalledWith(
        1,
        expect.objectContaining({ timeLimit: null }),
      ),
    )
  })

  it('non-admins see read-only subsection — no editable inputs', async () => {
    mockAuth = { isAdmin: false }
    const user = userEvent.setup()
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    await expand(1, user)
    await user.click(within(kidsCard).getByTestId('profile-time-toggle-1'))
    const input = within(kidsCard).getByTestId('profile-time-limit-1')
    expect(input).toBeDisabled()
    expect(within(kidsCard).getByTestId('profile-time-overlap-sum-1')).toBeDisabled()
  })
})

// #1494 — the profile editor's schedule section now attaches #1069 household
// named schedules (via the shared SchedulePicker) and persists them through
// PUT /api/profiles/{id}/schedules (api.profiles.setSchedules ->
// profile_schedule_rules), which enforcement reads (#1490). The old inline
// per-window editor wrote the dead V1 `schedules` table — a silent no-op — and
// is gone. These tests pin the picker round-trip and that the legacy upsert
// write path is never used for schedules.
describe('ProfilesPage — inline schedules subsection (#1494 named-schedule picker)', () => {
  const bedtime = { id: 10, name: 'Bedtime', description: null, windows: [] }
  const schoolHours = { id: 11, name: 'School hours', description: null, windows: [] }

  it('collapsed-by-default subsection summarizes attached named schedules', async () => {
    (api.schedules.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([bedtime, schoolHours])
    const user = userEvent.setup()
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    await expand(1, user)

    const sub = within(kidsCard).getByTestId('profile-schedule-subsection-1')
    // kidsProfile carries scheduleIds: [10] → the catalog name 'Bedtime' shows.
    expect(within(sub).getByText('Bedtime')).toBeInTheDocument()
    // The old inline per-window editor is gone — no window rows.
    expect(within(sub).queryByTestId('profile-schedule-row-1-0')).not.toBeInTheDocument()
  })

  it('empty schedules summary reads "No schedules"', async () => {
    const user = userEvent.setup()
    renderPage()
    const adultsCard = await screen.findByTestId('profile-card-2')
    await expand(2, user)
    const sub = within(adultsCard).getByTestId('profile-schedule-subsection-2')
    expect(within(sub).getByText(/No schedules/i)).toBeInTheDocument()
  })

  it('attaching a named schedule round-trips through setSchedules, not the legacy upsert', async () => {
    (api.schedules.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([bedtime, schoolHours])
    const user = userEvent.setup()
    renderPage()
    const adultsCard = await screen.findByTestId('profile-card-2') // scheduleIds: []
    await expand(2, user)
    const sub = within(adultsCard).getByTestId('profile-schedule-subsection-2')
    await user.click(within(sub).getByTestId('profile-schedule-toggle-2'))
    await act(async () => {})

    await user.selectOptions(within(sub).getByTestId('profile-schedule-picker-2-select'), '11')
    await user.click(within(sub).getByTestId('profile-schedule-add-2'))

    await waitFor(() =>
      expect(api.profiles.setSchedules).toHaveBeenCalledWith(2, [11]),
    )
    // The legacy full-profile PUT is NOT used to carry schedules.
    expect(api.profiles.update).not.toHaveBeenCalled()
  })

  it('removing an attached schedule persists the reduced id set via setSchedules', async () => {
    (api.schedules.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([bedtime, schoolHours])
    const user = userEvent.setup()
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1') // scheduleIds: [10]
    await expand(1, user)
    const sub = within(kidsCard).getByTestId('profile-schedule-subsection-1')
    await user.click(within(sub).getByTestId('profile-schedule-toggle-1'))

    await user.click(within(sub).getByTestId('profile-schedule-attached-1-10-remove'))

    await waitFor(() =>
      expect(api.profiles.setSchedules).toHaveBeenCalledWith(1, []),
    )
    expect(api.profiles.update).not.toHaveBeenCalled()
  })

  it('non-admins see read-only schedules — no picker, no attach/remove', async () => {
    mockAuth = { isAdmin: false }
    ;(api.schedules.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([bedtime])
    const user = userEvent.setup()
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    await expand(1, user)
    const sub = within(kidsCard).getByTestId('profile-schedule-subsection-1')
    await user.click(within(sub).getByTestId('profile-schedule-toggle-1'))
    // Read-only: no picker, no attach button, no remove control.
    expect(within(sub).queryByTestId('profile-schedule-add-1')).not.toBeInTheDocument()
    expect(within(sub).queryByTestId('profile-schedule-picker-1')).not.toBeInTheDocument()
    expect(within(sub).queryByTestId('profile-schedule-attached-1-10-remove')).not.toBeInTheDocument()
    // The attached schedule is still listed for reference.
    expect(within(sub).getByText('Bedtime')).toBeInTheDocument()
  })
})

describe('ProfilesPage — role gating', () => {
  it('hides admin-only buttons for non-admins', async () => {
    mockAuth = { isAdmin: false }
    renderPage()
    await screen.findByText('Kids')
    expect(screen.queryByRole('button', { name: /\+ New Profile/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Pause/ })).not.toBeInTheDocument()
    // #973: the standalone "Edit" escape-hatch button is gone — every
    // editable field has an inline subsection now, and admin-only ones
    // are role-gated at the subsection level (see other tests below).
    expect(screen.queryByRole('button', { name: /^Delete$/ })).not.toBeInTheDocument()
    // #977 — inline users subsection is admin-only; hidden along with the
    // other admin affordances when isAdmin=false.
    expect(screen.queryByTestId('profile-users-1')).not.toBeInTheDocument()
  })
})

describe('ProfilesPage — devices section', () => {
  it('renders devices grouped under their profile', async () => {
    const user = userEvent.setup()
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    const adultsCard = screen.getByTestId('profile-card-2')
    await expand(1, user)
    await expand(2, user)

    expect(within(kidsCard).getByTestId('profile-device-100')).toHaveTextContent('Kid Phone')
    expect(within(kidsCard).getByTestId('profile-device-100')).toHaveTextContent('aa:bb:cc:dd:ee:01')
    expect(within(kidsCard).queryByTestId('profile-device-101')).not.toBeInTheDocument()

    expect(within(adultsCard).getByTestId('profile-device-101')).toHaveTextContent('Adult Tablet')
  })
})

describe('ProfilesPage — linked users section', () => {
  it('renders linked users for each profile (admin view)', async () => {
    const user = userEvent.setup()
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    const adultsCard = screen.getByTestId('profile-card-2')
    await expand(1, user)
    await expand(2, user)

    expect(within(kidsCard).getByTestId('profile-user-10')).toHaveTextContent('alice')
    expect(within(kidsCard).getByTestId('profile-user-12')).toHaveTextContent('carol')
    expect(within(kidsCard).queryByTestId('profile-user-11')).not.toBeInTheDocument()

    expect(within(adultsCard).getByTestId('profile-user-11')).toHaveTextContent('bob')
    expect(within(adultsCard).getByTestId('profile-user-12')).toHaveTextContent('carol')
  })

  it('hides the linked-users section entirely for non-admins', async () => {
    mockAuth = { isAdmin: false }
    renderPage()
    await screen.findByText('Kids')
    expect(screen.queryByTestId('profile-users-1')).not.toBeInTheDocument()
    expect(screen.queryByTestId('profile-users-2')).not.toBeInTheDocument()
    expect(api.users.list).not.toHaveBeenCalled()
  })

  // #977 — inline users subsection autosaves on each toggle.
  it('admin toggles a user chip in the expanded card → autosaves via api.profiles.setUsers', async () => {
    const user = userEvent.setup()
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    await expand(1, user)

    // Currently linked to Kids: alice (10), carol (12). bob (11) is not.
    expect(within(kidsCard).getByTestId('profile-user-10')).toHaveAttribute('data-on', 'true')
    expect(within(kidsCard).getByTestId('profile-user-12')).toHaveAttribute('data-on', 'true')
    expect(within(kidsCard).getByTestId('profile-user-toggle-1-11')).toHaveAttribute('data-on', 'false')

    // Toggle bob on — autosaves immediately with the new full set.
    await user.click(within(kidsCard).getByTestId('profile-user-toggle-1-11'))

    await waitFor(() => expect(api.profiles.setUsers).toHaveBeenCalledTimes(1))
    const addCall = (api.profiles.setUsers as unknown as ReturnType<typeof vi.fn>).mock.calls[0]
    expect(addCall[0]).toBe(1)
    expect([...addCall[1]].sort((a, b) => a - b)).toEqual([10, 11, 12])

    // Toggle alice off — autosaves again with alice removed.
    await user.click(within(kidsCard).getByTestId('profile-user-10'))

    await waitFor(() => expect(api.profiles.setUsers).toHaveBeenCalledTimes(2))
    const removeCall = (api.profiles.setUsers as unknown as ReturnType<typeof vi.fn>).mock.calls[1]
    expect(removeCall[0]).toBe(1)
    // The second toggle's "current" comes from the re-fetched users list. Both
    // remove-alice variants are acceptable: with or without the optimistic bob,
    // depending on whether the refetch lands between clicks. Assert alice is
    // absent and the rest of the on-set is preserved.
    expect(removeCall[1]).not.toContain(10)
  })
})

// #978 — the failureMode (#385) radios used to be exposed via the
// "+ New Profile" modal; after the modal was deleted there is no UI surface
// for choosing failureMode at create time. The column default
// (`last-known-good`) is pinned by the create-body assertion in
// "ProfilesPage — create (inline name-only, #978)" above. A dedicated inline
// failureMode subsection is tracked as the orphan follow-up.

describe('ProfilesPage — highlight from ?id= (#298)', () => {
  it('rings the matching profile card when ?id=... is set', async () => {
    HTMLElement.prototype.scrollIntoView = vi.fn()
    renderPage(['/profiles?id=1'])
    const card = await screen.findByTestId('profile-card-1')
    await waitFor(() => expect(card.className).toContain('ring-brand-accent'))
  })

  it('does not ring any card when ?id= is not set', async () => {
    renderPage()
    const card = await screen.findByTestId('profile-card-1')
    expect(card.className).not.toContain('ring-brand-accent')
  })
})

describe('ProfilesPage — apps section (#767)', () => {
  // YouTube starts with an existing 'allowed' assignment so its row is
  // visible under the #1007 filter (only assigned apps show by default).
  const youtube = {
    app: { id: 50, name: 'YouTube', slug: 'youtube', templateId: null, icon: '📺', createdAt: '2026-01-01' },
    hosts: ['youtube.com'],
    assignments: [
      { id: 2, appId: 50, profileId: 1, mode: 'allowed' as const, dailyMinutes: null, exemptFromDaily: true },
    ],
  }
  const youtubeUnassigned = {
    app: { id: 50, name: 'YouTube', slug: 'youtube', templateId: null, icon: '📺', createdAt: '2026-01-01' },
    hosts: ['youtube.com'],
    assignments: [] as { id: number; appId: number; profileId: number; mode: 'blocked'|'allowed'|'time_limited'; dailyMinutes: number|null; exemptFromDaily: boolean }[],
  }
  const tiktok = {
    app: { id: 51, name: 'TikTok', slug: 'tiktok', templateId: null, icon: '🎵', createdAt: '2026-01-01' },
    hosts: ['tiktok.com', 'www.tiktok.com'],
    assignments: [
      { id: 1, appId: 51, profileId: 1, mode: 'blocked' as const, dailyMinutes: null, exemptFromDaily: true },
    ],
  }

  it('empty state with link to /apps when no apps exist', async () => {
    (api.apps.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([])
    const user = userEvent.setup()
    renderPage()
    await screen.findByTestId('profile-card-1')
    await expand(1, user)
    await user.click(screen.getByTestId('profile-apps-toggle-1'))
    const section = await screen.findByTestId('profile-1-apps-section')
    expect(within(section).getByText(/No apps yet/i)).toBeInTheDocument()
    expect(within(section).getByTestId('profile-1-apps-section-empty-link')).toHaveAttribute('href', '/apps')
  })

  it('renders one row per assigned app and reflects current assignment', async () => {
    (api.apps.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([youtube, tiktok])
    const user = userEvent.setup()
    renderPage()
    await screen.findByTestId('profile-card-1')
    await expand(1, user)
    await user.click(screen.getByTestId('profile-apps-toggle-1'))
    await screen.findByTestId('app-row-50')
    expect(screen.getByTestId('app-row-51')).toBeInTheDocument()
    // TikTok is currently blocked for profile 1; the block button shows checked state.
    const tiktokBlock = screen.getByTestId('app-row-51-block')
    expect(tiktokBlock.textContent).toMatch(/✓/)
  })

  // #1007: only assigned apps appear in the per-profile picker by default.
  it('hides unassigned apps; "+ Add app" reveals them via picker', async () => {
    (api.apps.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([youtubeUnassigned, tiktok])
    const user = userEvent.setup()
    renderPage()
    await screen.findByTestId('profile-card-1')
    await expand(1, user)
    await user.click(screen.getByTestId('profile-apps-toggle-1'))
    await screen.findByTestId('app-row-51')
    // YouTube has no assignment → row hidden.
    expect(screen.queryByTestId('app-row-50')).not.toBeInTheDocument()
    // Picker reveals it.
    await user.click(screen.getByTestId('profile-1-apps-section-add'))
    const picker = await screen.findByTestId('profile-1-apps-section-picker')
    expect(within(picker).getByTestId('profile-1-apps-section-picker-add-50')).toBeInTheDocument()
    expect(within(picker).queryByTestId('profile-1-apps-section-picker-add-51')).not.toBeInTheDocument()
  })

  it('profile with zero assignments shows none-assigned hint, not all apps', async () => {
    // Both apps belong to profile 2's universe (none assigned to profile 1).
    const ytForProfile2 = {
      app: youtube.app, hosts: youtube.hosts,
      assignments: [{ id: 9, appId: 50, profileId: 2, mode: 'allowed' as const, dailyMinutes: null, exemptFromDaily: true }],
    }
    ;(api.apps.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([ytForProfile2])
    const user = userEvent.setup()
    renderPage()
    await screen.findByTestId('profile-card-1')
    await expand(1, user)
    await user.click(screen.getByTestId('profile-apps-toggle-1'))
    expect(await screen.findByTestId('profile-1-apps-section-none-assigned')).toBeInTheDocument()
    expect(screen.queryByTestId('app-row-50')).not.toBeInTheDocument()
  })

  it('picker filter narrows the unassigned list by name', async () => {
    const slack = {
      app: { id: 52, name: 'Slack', slug: 'slack', templateId: null, icon: '💬', createdAt: '2026-01-01' },
      hosts: ['slack.com'],
      assignments: [] as typeof tiktok.assignments,
    }
    ;(api.apps.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([youtubeUnassigned, slack, tiktok])
    const user = userEvent.setup()
    renderPage()
    await screen.findByTestId('profile-card-1')
    await expand(1, user)
    await user.click(screen.getByTestId('profile-apps-toggle-1'))
    await screen.findByTestId('app-row-51')
    await user.click(screen.getByTestId('profile-1-apps-section-add'))
    await user.type(screen.getByTestId('profile-1-apps-section-picker-filter'), 'slack')
    expect(screen.getByTestId('profile-1-apps-section-picker-add-52')).toBeInTheDocument()
    expect(screen.queryByTestId('profile-1-apps-section-picker-add-50')).not.toBeInTheDocument()
  })

  it('adding from picker calls setPolicy with mode=allowed', async () => {
    (api.apps.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([youtubeUnassigned, tiktok])
    const user = userEvent.setup()
    renderPage()
    await screen.findByTestId('profile-card-1')
    await expand(1, user)
    await user.click(screen.getByTestId('profile-apps-toggle-1'))
    await screen.findByTestId('app-row-51')
    await user.click(screen.getByTestId('profile-1-apps-section-add'))
    await user.click(await screen.findByTestId('profile-1-apps-section-picker-add-50'))
    await waitFor(() =>
      expect(api.apps.setPolicy).toHaveBeenCalledWith(50, 1, { mode: 'allowed', dailyMinutes: null }),
    )
  })

  // #1007: exempt-from-daily toggle on the time-limit row.
  it('time-limited row shows counts-toward-daily checkbox, defaulted unchecked (exempt=true)', async () => {
    const khan = {
      app: { id: 60, name: 'Khan', slug: 'khan', templateId: null, icon: '📚', createdAt: '2026-01-01' },
      hosts: ['khanacademy.org'],
      assignments: [
        { id: 3, appId: 60, profileId: 1, mode: 'time_limited' as const, dailyMinutes: 60, exemptFromDaily: true },
      ],
    }
    ;(api.apps.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([khan])
    const user = userEvent.setup()
    renderPage()
    await screen.findByTestId('profile-card-1')
    await expand(1, user)
    await user.click(screen.getByTestId('profile-apps-toggle-1'))
    const cb = await screen.findByTestId('app-row-60-counts-toward-daily') as HTMLInputElement
    expect(cb.checked).toBe(false)
    await user.click(cb)
    await waitFor(() =>
      expect(api.apps.setPolicy).toHaveBeenCalledWith(60, 1, { mode: 'time_limited', dailyMinutes: 60, exemptFromDaily: false }),
    )
  })

  it('checkbox is not rendered when app is not time-limited', async () => {
    (api.apps.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([tiktok])
    const user = userEvent.setup()
    renderPage()
    await screen.findByTestId('profile-card-1')
    await expand(1, user)
    await user.click(screen.getByTestId('profile-apps-toggle-1'))
    await screen.findByTestId('app-row-51')
    expect(screen.queryByTestId('app-row-51-counts-toward-daily')).not.toBeInTheDocument()
  })

  it('clicking block calls setPolicy with mode=blocked', async () => {
    (api.apps.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([youtube])
    const user = userEvent.setup()
    renderPage()
    await screen.findByTestId('profile-card-1')
    await expand(1, user)
    await user.click(screen.getByTestId('profile-apps-toggle-1'))
    await user.click(await screen.findByTestId('app-row-50-block'))
    await waitFor(() =>
      expect(api.apps.setPolicy).toHaveBeenCalledWith(50, 1, { mode: 'blocked', dailyMinutes: null }),
    )
  })

  it('clicking allow calls setPolicy with mode=allowed', async () => {
    (api.apps.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([youtube])
    const user = userEvent.setup()
    renderPage()
    await screen.findByTestId('profile-card-1')
    await expand(1, user)
    await user.click(screen.getByTestId('profile-apps-toggle-1'))
    await user.click(await screen.findByTestId('app-row-50-allow'))
    await waitFor(() =>
      expect(api.apps.setPolicy).toHaveBeenCalledWith(50, 1, { mode: 'allowed', dailyMinutes: null }),
    )
  })

  it('typing a positive value into the minutes input then blurring saves as time_limited', async () => {
    (api.apps.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([youtube])
    const user = userEvent.setup()
    renderPage()
    await screen.findByTestId('profile-card-1')
    await expand(1, user)
    await user.click(screen.getByTestId('profile-apps-toggle-1'))
    const input = await screen.findByTestId('app-row-50-minutes') as HTMLInputElement
    await user.type(input, '45')
    // Tab away — the input IS the time-limit control, no separate button.
    await user.tab()
    await waitFor(() =>
      expect(api.apps.setPolicy).toHaveBeenCalledWith(50, 1, { mode: 'time_limited', dailyMinutes: 45, exemptFromDaily: true }),
    )
  })

  it('zero/negative minutes shows inline error and does not save', async () => {
    (api.apps.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([youtube])
    const user = userEvent.setup()
    renderPage()
    await screen.findByTestId('profile-card-1')
    await expand(1, user)
    await user.click(screen.getByTestId('profile-apps-toggle-1'))
    const input = await screen.findByTestId('app-row-50-minutes') as HTMLInputElement
    await user.type(input, '0')
    await user.tab()
    expect(api.apps.setPolicy).not.toHaveBeenCalled()
    expect(await screen.findByTestId('app-row-50-error')).toHaveTextContent(/minutes > 0/i)
  })

  it('blank minutes on blur is a no-op when the app is not time-limited', async () => {
    (api.apps.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([youtube])
    const user = userEvent.setup()
    renderPage()
    await screen.findByTestId('profile-card-1')
    await expand(1, user)
    await user.click(screen.getByTestId('profile-apps-toggle-1'))
    const input = await screen.findByTestId('app-row-50-minutes')
    await user.click(input)
    await user.tab()
    expect(api.apps.setPolicy).not.toHaveBeenCalled()
    expect(screen.queryByTestId('app-row-50-error')).not.toBeInTheDocument()
  })

  it('clearing the minutes input on a time-limited app reverts to Allow', async () => {
    const khan = {
      app: { id: 60, name: 'Khan', slug: 'khan', templateId: null, icon: '📚', createdAt: '2026-01-01' },
      hosts: ['khanacademy.org'],
      assignments: [
        { id: 3, appId: 60, profileId: 1, mode: 'time_limited' as const, dailyMinutes: 60, exemptFromDaily: true },
      ],
    }
    ;(api.apps.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([khan])
    const user = userEvent.setup()
    renderPage()
    await screen.findByTestId('profile-card-1')
    await expand(1, user)
    await user.click(screen.getByTestId('profile-apps-toggle-1'))
    const input = await screen.findByTestId('app-row-60-minutes') as HTMLInputElement
    expect(input.value).toBe('60')
    await user.clear(input)
    await user.tab()
    await waitFor(() =>
      expect(api.apps.setPolicy).toHaveBeenCalledWith(60, 1, { mode: 'allowed', dailyMinutes: null }),
    )
  })

  it('clearing an assigned app calls deletePolicy', async () => {
    (api.apps.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([tiktok])
    const user = userEvent.setup()
    renderPage()
    await screen.findByTestId('profile-card-1')
    await expand(1, user)
    await user.click(screen.getByTestId('profile-apps-toggle-1'))
    await user.click(await screen.findByTestId('app-row-51-clear'))
    await waitFor(() =>
      expect(api.apps.deletePolicy).toHaveBeenCalledWith(51, 1),
    )
  })

  // #978 — the "save this profile first" hint used to surface inside the
  // ProfileEditor modal's AppsSection when isNew=true. Post-cleanup the
  // create flow is a name-only inline form; AppsSection is no longer
  // mounted in the create surface, so the hint is unreachable from the UI.

  it('Manage apps link points at /apps', async () => {
    (api.apps.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([youtube])
    const user = userEvent.setup()
    renderPage()
    await screen.findByTestId('profile-card-1')
    await expand(1, user)
    await user.click(screen.getByTestId('profile-apps-toggle-1'))
    expect(await screen.findByTestId('profile-1-apps-section-manage-link')).toHaveAttribute('href', '/apps')
  })
})

// #1380 — per-app schedule rules in the assignment editor. Each rule attaches
// a #1069 named schedule (via the shared SchedulePicker) with a mode
// (allowed-during / blocked-during); the rule set rides the assignment's
// UpsertAppAssignmentRequest as additive `scheduleRules`. Autosave: add/remove
// persists immediately (no Save button).
describe('ProfilesPage — per-app schedule rules (#1380)', () => {
  const bedtime = { id: 10, name: 'Bedtime', description: null, windows: [] }
  const schoolHours = { id: 11, name: 'School hours', description: null, windows: [] }

  function ytWithRules(scheduleRules: { scheduleId: number; mode: 'allowed_during' | 'blocked_during' }[]) {
    return {
      app: { id: 50, name: 'YouTube', slug: 'youtube', templateId: null, icon: '📺', createdAt: '2026-01-01' },
      hosts: ['youtube.com'],
      assignments: [
        { id: 2, appId: 50, profileId: 1, mode: 'allowed' as const, dailyMinutes: null, exemptFromDaily: true, scheduleRules },
      ],
    }
  }

  async function openAppsSection(user: ReturnType<typeof userEvent.setup>) {
    renderPage()
    await screen.findByTestId('profile-card-1')
    await expand(1, user)
    await user.click(screen.getByTestId('profile-apps-toggle-1'))
    await screen.findByTestId('app-row-50')
  }

  it('renders attached schedule rules with their schedule name and mode', async () => {
    (api.apps.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([ytWithRules([{ scheduleId: 10, mode: 'allowed_during' }])])
    ;(api.schedules.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([bedtime, schoolHours])
    const user = userEvent.setup()
    await openAppsSection(user)
    const rule = await screen.findByTestId('app-row-50-schedule-rule-10-allowed_during')
    expect(within(rule).getByText('Bedtime')).toBeInTheDocument()
    expect(rule.textContent).toMatch(/Allowed during/i)
  })

  it('attaching a named schedule as allowed-during calls setPolicy with the rule', async () => {
    (api.apps.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([ytWithRules([])])
    ;(api.schedules.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([bedtime, schoolHours])
    const user = userEvent.setup()
    await openAppsSection(user)
    await user.selectOptions(screen.getByTestId('app-row-50-schedule-picker-select'), '10')
    await user.click(screen.getByTestId('app-row-50-schedule-mode-allowed_during'))
    await user.click(screen.getByTestId('app-row-50-schedule-add'))
    await waitFor(() =>
      expect(api.apps.setPolicy).toHaveBeenCalledWith(50, 1, {
        mode: 'allowed',
        dailyMinutes: null,
        exemptFromDaily: true,
        scheduleRules: [{ scheduleId: 10, mode: 'allowed_during' }],
      }),
    )
  })

  it('attaching a named schedule as blocked-during calls setPolicy with the rule', async () => {
    (api.apps.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([ytWithRules([])])
    ;(api.schedules.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([bedtime, schoolHours])
    const user = userEvent.setup()
    await openAppsSection(user)
    await user.selectOptions(screen.getByTestId('app-row-50-schedule-picker-select'), '11')
    await user.click(screen.getByTestId('app-row-50-schedule-mode-blocked_during'))
    await user.click(screen.getByTestId('app-row-50-schedule-add'))
    await waitFor(() =>
      expect(api.apps.setPolicy).toHaveBeenCalledWith(50, 1, {
        mode: 'allowed',
        dailyMinutes: null,
        exemptFromDaily: true,
        scheduleRules: [{ scheduleId: 11, mode: 'blocked_during' }],
      }),
    )
  })

  it('removing a rule persists the assignment without it', async () => {
    (api.apps.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([ytWithRules([{ scheduleId: 10, mode: 'allowed_during' }])])
    ;(api.schedules.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([bedtime, schoolHours])
    const user = userEvent.setup()
    await openAppsSection(user)
    await user.click(await screen.findByTestId('app-row-50-schedule-rule-10-allowed_during-remove'))
    await waitFor(() =>
      // empty rule set → scheduleRules omitted from the additive payload;
      // the assignment's exemptFromDaily flag is preserved across the replace.
      expect(api.apps.setPolicy).toHaveBeenCalledWith(50, 1, { mode: 'allowed', dailyMinutes: null, exemptFromDaily: true }),
    )
  })

  it('an allowed-during rule surfaces the exempt-from-daily cap copy', async () => {
    (api.apps.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([ytWithRules([{ scheduleId: 10, mode: 'allowed_during' }])])
    ;(api.schedules.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([bedtime])
    const user = userEvent.setup()
    await openAppsSection(user)
    const exempt = await screen.findByTestId('app-row-50-schedule-exempt')
    expect(exempt.textContent).toMatch(/daily (time )?limit/i)
  })
})

// #976 — apps subsection inside the expanded card (per-app policy
// editor). Default collapsed; opening reveals the same AppsSection the
// modal uses, scoped to a profile-specific testid prefix. Post-#764 the
// transitional legacy extraBlocked/extraAllowed textareas are gone.
describe('ProfilesPage — apps subsection (#976)', () => {
  it('subsection summary reports the assigned-app count', async () => {
    (api.apps.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([
      {
        app: { id: 50, name: 'YouTube', slug: 'youtube', templateId: null, icon: '▶', createdAt: '2026-01-01' },
        hosts: ['youtube.com'],
        assignments: [
          { id: 1, appId: 50, profileId: 1, mode: 'allowed' as const, dailyMinutes: null, exemptFromDaily: true },
        ],
      },
    ])
    const user = userEvent.setup()
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    await expand(1, user)
    const toggle = await screen.findByTestId('profile-apps-toggle-1')
    expect(within(kidsCard).getByTestId('profile-apps-subsection-1')).toHaveTextContent(/Apps/)
    expect(within(kidsCard).getByTestId('profile-apps-subsection-1')).toHaveTextContent(/1 assigned/)
    // Body collapsed: inline AppsSection not mounted yet.
    expect(screen.queryByTestId('profile-1-apps-section')).not.toBeInTheDocument()
    await user.click(toggle)
    expect(await screen.findByTestId('profile-1-apps-section')).toBeInTheDocument()
    // Inline app row rendered.
    expect(screen.getByTestId('app-row-50')).toBeInTheDocument()
  })

  it('subsection hidden for non-admins', async () => {
    mockAuth = { isAdmin: false }
    const user = userEvent.setup()
    renderPage()
    await screen.findByTestId('profile-card-1')
    await expand(1, user)
    expect(screen.queryByTestId('profile-apps-toggle-1')).not.toBeInTheDocument()
  })
})

describe('ProfilesPage — #973 inline name subsection (autosave)', () => {
  it('renders the inline name editor pre-filled when the card is expanded', async () => {
    const user = userEvent.setup()
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    await expand(1, user)
    const input = within(kidsCard).getByTestId('profile-name-input-1') as HTMLInputElement
    expect(input.value).toBe('Kids')
  })

  it('debounced autosave fires once per change with the new name baked into the full PUT body', async () => {
    const user = userEvent.setup()
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    await expand(1, user)
    const input = within(kidsCard).getByTestId('profile-name-input-1')

    await user.clear(input)
    await user.type(input, 'Kiddos')

    // Real 500ms debounce — give waitFor a window wide enough to cover it.
    await waitFor(
      () =>
        expect(api.profiles.update).toHaveBeenCalledWith(
          1,
          expect.objectContaining({
            name: 'Kiddos',
            blockedCategories: ['adult', 'gambling'],
            paused: false,
            failureMode: 'block-all',
            crossDeviceOverlapMode: 'sum',
            // round-trips schedules + timeLimit unchanged
            timeLimit: 120,
          }),
        ),
      { timeout: 2000 },
    )
    expect(api.profiles.update).toHaveBeenCalledTimes(1)
  })

  it('blank name surfaces an error and is not persisted', async () => {
    const user = userEvent.setup()
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    await expand(1, user)
    const input = within(kidsCard).getByTestId('profile-name-input-1')
    await user.clear(input)
    await waitFor(
      () => {
        const badge = within(kidsCard).getByTestId('profile-name-status-1')
        expect(badge).toHaveAttribute('data-status', 'error')
      },
      { timeout: 2000 },
    )
    expect(api.profiles.update).not.toHaveBeenCalled()
  })
})

describe('ProfilesPage — #973 inline devices subsection', () => {
  const orphanDevice: Device = {
    id: 200, mac: 'aa:bb:cc:dd:ee:99', name: 'Spare Laptop', profileId: null,
    profileName: null, lastSeenIp: null, lastSeenAt: null,
  }

  it('renders assigned devices with a Remove button and a picker of unassigned devices', async () => {
    (api.devices.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([
      phoneDevice, tabletDevice, orphanDevice,
    ])
    const user = userEvent.setup()
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    await expand(1, user)
    const sub = within(kidsCard).getByTestId('profile-devices-subsection-1')
    expect(within(sub).getByTestId('profile-device-100')).toBeInTheDocument()
    expect(within(sub).getByTestId('profile-device-100-detach')).toBeInTheDocument()
    expect(within(sub).getByTestId('profile-device-add-200')).toBeInTheDocument()
    // Devices already on another profile do not show up in the picker.
    expect(within(sub).queryByTestId('profile-device-add-101')).not.toBeInTheDocument()
  })

  it('clicking + on an unassigned device PATCHes /devices with the profileId', async () => {
    (api.devices.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([
      phoneDevice, tabletDevice, orphanDevice,
    ])
    const user = userEvent.setup()
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    await expand(1, user)
    await user.click(within(kidsCard).getByTestId('profile-device-add-200'))
    await waitFor(() =>
      expect(api.devices.patch).toHaveBeenCalledWith('aa:bb:cc:dd:ee:99', { profileId: 1 }),
    )
  })

  it('clicking Remove on an assigned device PATCHes /devices with profileId=null', async () => {
    const user = userEvent.setup()
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    await expand(1, user)
    await user.click(within(kidsCard).getByTestId('profile-device-100-detach'))
    await waitFor(() =>
      expect(api.devices.patch).toHaveBeenCalledWith('aa:bb:cc:dd:ee:01', { profileId: null }),
    )
  })

  it('hides the editable subsection for non-admins (read-only listing instead)', async () => {
    mockAuth = { isAdmin: false }
    const user = userEvent.setup()
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    await expand(1, user)
    expect(within(kidsCard).queryByTestId('profile-devices-subsection-1')).not.toBeInTheDocument()
    // The pre-#973 read-only listing is the fallback for non-admins.
    expect(within(kidsCard).getByTestId('profile-devices-1')).toBeInTheDocument()
  })
})

describe('ProfilesPage — per-app usage bar in Apps section (#1061)', () => {
  const youtubeTimeLimited = {
    app: { id: 50, name: 'YouTube', slug: 'youtube', templateId: null, icon: '📺', createdAt: '2026-01-01' },
    hosts: ['youtube.com'],
    assignments: [
      { id: 2, appId: 50, profileId: 1, mode: 'time_limited' as const, dailyMinutes: 60, exemptFromDaily: true },
    ],
  }
  const youtubeAllowed = {
    app: { id: 50, name: 'YouTube', slug: 'youtube', templateId: null, icon: '📺', createdAt: '2026-01-01' },
    hosts: ['youtube.com'],
    assignments: [
      { id: 2, appId: 50, profileId: 1, mode: 'allowed' as const, dailyMinutes: null, exemptFromDaily: true },
    ],
  }

  it('renders a usage bar with used/cap text on a time-limited app', async () => {
    (api.apps.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([youtubeTimeLimited])
    ;(api.profiles.usageByApp as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({
      profileId: 1, profileName: 'Kids', from: '2026-05-26', to: '2026-05-26',
      apps: [{
        appId: 50, appName: 'YouTube', appIcon: '📺', appIconType: 'emoji',
        proportionalSeconds: 1800, presenceSeconds: 1800, hosts: [],
      }],
    })
    const user = userEvent.setup()
    renderPage()
    await screen.findByTestId('profile-card-1')
    await expand(1, user)
    await user.click(screen.getByTestId('profile-apps-toggle-1'))
    const bar = await screen.findByTestId('app-row-50-usage')
    // 1800s → 30m of 60m limit.
    expect(bar).toHaveTextContent('30m')
    expect(bar).toHaveTextContent('1:00')
    // Bar fill width matches 30/60 = 50%.
    const fill = bar.querySelectorAll('div')[1] as HTMLDivElement
    expect(fill.style.width).toBe('50%')
    // Under cap → emerald, not red.
    expect(fill.className).toContain('bg-brand-accent')
  })

  it('shows the bar in red once usage meets/exceeds the cap', async () => {
    (api.apps.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([youtubeTimeLimited])
    ;(api.profiles.usageByApp as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({
      profileId: 1, profileName: 'Kids', from: '2026-05-26', to: '2026-05-26',
      apps: [{
        appId: 50, appName: 'YouTube', appIcon: '📺', appIconType: 'emoji',
        proportionalSeconds: 4200, presenceSeconds: 4200, hosts: [],
      }],
    })
    const user = userEvent.setup()
    renderPage()
    await screen.findByTestId('profile-card-1')
    await expand(1, user)
    await user.click(screen.getByTestId('profile-apps-toggle-1'))
    const bar = await screen.findByTestId('app-row-50-usage')
    const fill = bar.querySelectorAll('div')[1] as HTMLDivElement
    // 70m > 60m → clamped to 100%, painted red.
    expect(fill.style.width).toBe('100%')
    expect(fill.className).toContain('bg-red-500')
  })

  // #1433 — a no-limit app no longer shows the used/cap progress bar
  // (`-usage`), but it DOES surface today's plain time-used (`-used`).
  it('does not render the cap bar for an app without a daily limit', async () => {
    (api.apps.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([youtubeAllowed])
    ;(api.profiles.usageByApp as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({
      profileId: 1, profileName: 'Kids', from: '2026-05-26', to: '2026-05-26',
      apps: [{
        appId: 50, appName: 'YouTube', appIcon: '📺', appIconType: 'emoji',
        proportionalSeconds: 1800, presenceSeconds: 1800, hosts: [],
      }],
    })
    const user = userEvent.setup()
    renderPage()
    await screen.findByTestId('profile-card-1')
    await expand(1, user)
    await user.click(screen.getByTestId('profile-apps-toggle-1'))
    await screen.findByTestId('app-row-50')
    expect(screen.queryByTestId('app-row-50-usage')).not.toBeInTheDocument()
  })
})

// #1433 — every app row surfaces today's time-used, not just time-limited
// ones. Limited apps keep the "used / cap" bar; no-limit apps show a plain
// "Xm today" readout fed by the same per-app proportional minutes.
describe('ProfilesPage — per-app time-used for no-limit apps (#1433)', () => {
  const youtubeAllowed = {
    app: { id: 50, name: 'YouTube', slug: 'youtube', templateId: null, icon: '📺', createdAt: '2026-01-01' },
    hosts: ['youtube.com'],
    assignments: [
      { id: 2, appId: 50, profileId: 1, mode: 'allowed' as const, dailyMinutes: null, exemptFromDaily: true },
    ],
  }
  const tiktokBlocked = {
    app: { id: 51, name: 'TikTok', slug: 'tiktok', templateId: null, icon: '🎵', createdAt: '2026-01-01' },
    hosts: ['tiktok.com'],
    assignments: [
      { id: 3, appId: 51, profileId: 1, mode: 'blocked' as const, dailyMinutes: null, exemptFromDaily: true },
    ],
  }

  it('renders "Xm today" with no cap for an allowed (no-limit) app', async () => {
    (api.apps.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([youtubeAllowed])
    ;(api.profiles.usageByApp as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({
      profileId: 1, profileName: 'Kids', from: '2026-05-26', to: '2026-05-26',
      apps: [{
        appId: 50, appName: 'YouTube', appIcon: '📺', appIconType: 'emoji',
        proportionalSeconds: 1380, presenceSeconds: 1380, hosts: [],
      }],
    })
    const user = userEvent.setup()
    renderPage()
    await screen.findByTestId('profile-card-1')
    await expand(1, user)
    await user.click(screen.getByTestId('profile-apps-toggle-1'))
    // 1380s → 23m, shown as plain "23m today" with no "/ cap".
    const used = await screen.findByTestId('app-row-50-used')
    expect(used).toHaveTextContent('23m today')
    expect(used.textContent).not.toContain('/')
    // No progress-bar variant for a no-limit app.
    expect(screen.queryByTestId('app-row-50-usage')).not.toBeInTheDocument()
  })

  it('surfaces time-used for a blocked app too', async () => {
    (api.apps.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([tiktokBlocked])
    ;(api.profiles.usageByApp as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({
      profileId: 1, profileName: 'Kids', from: '2026-05-26', to: '2026-05-26',
      apps: [{
        appId: 51, appName: 'TikTok', appIcon: '🎵', appIconType: 'emoji',
        proportionalSeconds: 300, presenceSeconds: 300, hosts: [],
      }],
    })
    const user = userEvent.setup()
    renderPage()
    await screen.findByTestId('profile-card-1')
    await expand(1, user)
    await user.click(screen.getByTestId('profile-apps-toggle-1'))
    const used = await screen.findByTestId('app-row-51-used')
    expect(used).toHaveTextContent('5m today')
  })

  it('omits the readout for a no-limit app with zero usage', async () => {
    (api.apps.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([youtubeAllowed])
    ;(api.profiles.usageByApp as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({
      profileId: 1, profileName: 'Kids', from: '2026-05-26', to: '2026-05-26',
      apps: [],
    })
    const user = userEvent.setup()
    renderPage()
    await screen.findByTestId('profile-card-1')
    await expand(1, user)
    await user.click(screen.getByTestId('profile-apps-toggle-1'))
    await screen.findByTestId('app-row-50')
    expect(screen.queryByTestId('app-row-50-used')).not.toBeInTheDocument()
  })
})

// #1086 — an app-policy edit (toggle exemptFromDaily, switch mode, remove the
// assignment) feeds the server-side daily-cap math (`buildProfileTimeStatus`),
// so the profile-wide used/cap text + bar must refetch. The summary is served
// by `api.time.summaryAll` via the ['time','status','summary','today'] query;
// invalidating the ['time','status'] subtree refetches it. Before the fix the
// AppRow only refetched the apps list, so summaryAll was called exactly once
// (the initial mount) and the headline bar went stale until the 30s window.
describe('ProfilesPage — app-policy edits refresh the profile-wide time bar (#1086)', () => {
  const khanTimeLimited = {
    app: { id: 60, name: 'Khan', slug: 'khan', templateId: null, icon: '📚', createdAt: '2026-01-01' },
    hosts: ['khanacademy.org'],
    assignments: [
      { id: 3, appId: 60, profileId: 1, mode: 'time_limited' as const, dailyMinutes: 60, exemptFromDaily: true },
    ],
  }

  async function openApps(user: ReturnType<typeof userEvent.setup>) {
    renderPage()
    await screen.findByTestId('profile-card-1')
    await expand(1, user)
    await user.click(screen.getByTestId('profile-apps-toggle-1'))
  }

  it('toggling "Counts toward daily limit" invalidates the time-status summary', async () => {
    (api.apps.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([khanTimeLimited])
    const user = userEvent.setup()
    await openApps(user)
    const cb = await screen.findByTestId('app-row-60-counts-toward-daily')
    expect(api.time.summaryAll).toHaveBeenCalledTimes(1)
    await user.click(cb)
    await waitFor(() =>
      expect(api.apps.setPolicy).toHaveBeenCalledWith(60, 1, { mode: 'time_limited', dailyMinutes: 60, exemptFromDaily: false }),
    )
    await waitFor(() => expect(api.time.summaryAll).toHaveBeenCalledTimes(2))
  })

  it('switching an app between modes invalidates the time-status summary', async () => {
    (api.apps.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([khanTimeLimited])
    const user = userEvent.setup()
    await openApps(user)
    await user.click(await screen.findByTestId('app-row-60-block'))
    await waitFor(() =>
      expect(api.apps.setPolicy).toHaveBeenCalledWith(60, 1, { mode: 'blocked', dailyMinutes: null }),
    )
    await waitFor(() => expect(api.time.summaryAll).toHaveBeenCalledTimes(2))
  })

  it('removing an app assignment invalidates the time-status summary', async () => {
    (api.apps.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([khanTimeLimited])
    const user = userEvent.setup()
    await openApps(user)
    await user.click(await screen.findByTestId('app-row-60-clear'))
    await waitFor(() => expect(api.apps.deletePolicy).toHaveBeenCalledWith(60, 1))
    await waitFor(() => expect(api.time.summaryAll).toHaveBeenCalledTimes(2))
  })
})

// #1473 — blocked categories are now editable inline on the profile card.
// The read-only chips are replaced (for admins) with a checklist of the
// blocklist catalog; toggling a category autosaves blockedCategories via the
// same full-profile PUT the Blocklists matrix uses.
describe('ProfilesPage — inline blocked-categories editor (#1473)', () => {
  it('renders the catalog with the profile’s current categories pre-selected', async () => {
    const user = userEvent.setup()
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    await expand(1, user)

    // Kids has ['adult', 'gambling'] selected; 'social' / 'malware' are off.
    const adult = await within(kidsCard).findByTestId('profile-category-toggle-1-adult')
    expect(adult).toBeChecked()
    expect(within(kidsCard).getByTestId('profile-category-toggle-1-gambling')).toBeChecked()
    expect(within(kidsCard).getByTestId('profile-category-toggle-1-social')).not.toBeChecked()
    expect(within(kidsCard).getByTestId('profile-category-toggle-1-malware')).not.toBeChecked()
    // Catalog comes from the shared /blocklists fetch.
    expect(api.blocklists.list).toHaveBeenCalled()
  })

  it('toggling an unselected category ADDS it to blockedCategories via the full PUT', async () => {
    const user = userEvent.setup()
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    await expand(1, user)

    const social = await within(kidsCard).findByTestId('profile-category-toggle-1-social')
    await user.click(social)

    await waitFor(() => expect(api.profiles.update).toHaveBeenCalled())
    const calls = (api.profiles.update as unknown as ReturnType<typeof vi.fn>).mock.calls
    const [id, body] = calls[calls.length - 1]
    expect(id).toBe(1)
    expect([...body.blockedCategories].sort()).toEqual(['adult', 'gambling', 'social'])
    // other fields carried through unchanged
    expect(body.name).toBe('Kids')
    expect(body.timeLimit).toBe(120)
    expect(body.failureMode).toBe('block-all')
  })

  it('toggling a selected category REMOVES it from blockedCategories', async () => {
    const user = userEvent.setup()
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    await expand(1, user)

    const adult = await within(kidsCard).findByTestId('profile-category-toggle-1-adult')
    await user.click(adult)

    await waitFor(() => expect(api.profiles.update).toHaveBeenCalled())
    const calls = (api.profiles.update as unknown as ReturnType<typeof vi.fn>).mock.calls
    const [id, body] = calls[calls.length - 1]
    expect(id).toBe(1)
    expect(body.blockedCategories).toEqual(['gambling'])
  })

  it('reflects the new selection after the profile refetches', async () => {
    const listFn = api.profiles.list as unknown as ReturnType<typeof vi.fn>
    listFn.mockResolvedValueOnce([kidsProfile, adultsProfile])
    listFn.mockResolvedValue([
      { ...kidsProfile, profile: { ...kidsProfile.profile, blockedCategories: ['adult', 'gambling', 'social'] } },
      adultsProfile,
    ])
    const user = userEvent.setup()
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    await expand(1, user)
    await user.click(await within(kidsCard).findByTestId('profile-category-toggle-1-social'))

    await waitFor(() =>
      expect(within(kidsCard).getByTestId('profile-category-toggle-1-social')).toBeChecked(),
    )
  })

  it('non-admins see read-only chips, not the editable checklist', async () => {
    mockAuth = { isAdmin: false }
    const user = userEvent.setup()
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    await expand(1, user)
    expect(within(kidsCard).queryByTestId('profile-category-toggle-1-adult')).not.toBeInTheDocument()
    // The pre-#1473 read-only chips remain the non-admin fallback.
    expect(within(kidsCard).getByText('adult')).toBeInTheDocument()
    expect(within(kidsCard).getByText('gambling')).toBeInTheDocument()
  })
})

describe('ProfilesPage — per-profile default-deny toggle (#1320)', () => {
  it('toggling default-deny persists via the full-profile PUT, preserving other fields', async () => {
    const user = userEvent.setup()
    renderPage()
    await screen.findByTestId('profile-card-1')
    await expand(1, user)

    // off by default for the Kids fixture
    const toggle = await screen.findByTestId('profile-default-deny-toggle-1')
    expect(toggle).not.toBeChecked()

    await user.click(toggle)

    await waitFor(() => expect(api.profiles.update).toHaveBeenCalled())
    const calls = (api.profiles.update as unknown as ReturnType<typeof vi.fn>).mock.calls
    const [id, body] = calls[calls.length - 1]
    expect(id).toBe(1)
    expect(body.defaultDeny).toBe(true)
    // other fields carried through the PUT unchanged
    expect(body.name).toBe('Kids')
    expect(body.blockedCategories).toEqual(['adult', 'gambling'])
    expect(body.failureMode).toBe('block-all')
    expect(body.timeLimit).toBe(120)
  })

  // #1472 — default-deny is the profile's most fundamental posture, so it
  // renders FIRST in the expanded card, before the devices and apps subsections.
  it('renders the default-deny subsection before the devices and apps subsections', async () => {
    const user = userEvent.setup()
    renderPage()
    await screen.findByTestId('profile-card-1')
    await expand(1, user)

    const defaultDeny = await screen.findByTestId('profile-default-deny-1')
    const devices     = screen.getByTestId('profile-devices-subsection-1')
    const apps        = screen.getByTestId('profile-apps-subsection-1')

    // default-deny precedes both devices and apps in DOM order
    expect(defaultDeny.compareDocumentPosition(devices))
      .toBe(Node.DOCUMENT_POSITION_FOLLOWING)
    expect(defaultDeny.compareDocumentPosition(apps))
      .toBe(Node.DOCUMENT_POSITION_FOLLOWING)
  })
})
