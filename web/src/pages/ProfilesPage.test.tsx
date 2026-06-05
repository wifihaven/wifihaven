import { describe, it, expect, beforeEach, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import type { Device, ProfileDetail, ProfileTimeSummary, User } from '@/types/api'
import { withQuery } from '@/test/queryWrapper'

vi.mock('@/api/client', () => ({
  api: {
    profiles: {
      list: vi.fn(),
      create: vi.fn(),
      update: vi.fn(),
      delete: vi.fn(),
      setUsers: vi.fn(),
      usageByApp: vi.fn(),
    },
    // #978 — the old ProfileEditor modal listed blocklist categories from
    // /blocklists; the inline app-policy subsection owns that surface now,
    // so ProfilesPage no longer calls api.blocklists.list.
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
  schedules: [
    { id: 10, profileId: 1, name: 'Bedtime', days: ['mon', 'tue'], startLocal: '21:00', endLocal: '07:00', tz: 'UTC' },
  ],
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
  ;(api.devices.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([phoneDevice, tabletDevice])
  ;(api.devices.patch as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(undefined)
  ;(api.users.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([aliceUser, bobUser, carolUser])
  ;(api.household.get as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({
    dailyResetTime: '00:00',
    dailyResetTz: 'America/Los_Angeles',
  })
  ;(api.apps.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([])
  ;(api.apps.setPolicy as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(undefined)
  ;(api.apps.deletePolicy as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(undefined)
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
    const user = userEvent.setup()
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    expect(within(kidsCard).queryByText('Bedtime')).not.toBeInTheDocument()
    await user.click(within(kidsCard).getByTestId('profile-row-toggle-1'))
    expect(within(kidsCard).getByText('Bedtime')).toBeInTheDocument()
    expect(within(kidsCard).getByText('120 min')).toBeInTheDocument()
    expect(within(kidsCard).getByText('21:00 → 07:00')).toBeInTheDocument()
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
  it('Pause button is rendered in the collapsed row and fires update without expanding', async () => {
    const user = userEvent.setup()
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    // collapsed body is hidden — no schedule subsection visible
    expect(within(kidsCard).queryByText('Bedtime')).not.toBeInTheDocument()
    const pauseBtn = within(kidsCard).getByTestId('profile-row-pause-1')
    await user.click(pauseBtn)
    await waitFor(() =>
      expect(api.profiles.update).toHaveBeenCalledWith(
        1,
        expect.objectContaining({ paused: true, name: 'Kids' }),
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
      schedules: [],
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
  it('collapsed-by-default subsection shows daily-limit + schedule summary', async () => {
    const user = userEvent.setup()
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    await expand(1, user)

    const sub = within(kidsCard).getByTestId('profile-time-subsection-1')
    // Collapsed: editor body is hidden, but the summary readout matches what the
    // expanded card used to show pre-#975 ("Daily limit: 120 min" + "Bedtime …").
    expect(within(sub).getByText(/Daily limit:/)).toBeInTheDocument()
    expect(within(sub).getByText('120 min')).toBeInTheDocument()
    expect(within(sub).getByText('Bedtime')).toBeInTheDocument()
    expect(within(sub).getByText(/21:00 → 07:00/)).toBeInTheDocument()
    // Editable inputs only appear after expand.
    expect(within(sub).queryByTestId('profile-time-limit-1')).not.toBeInTheDocument()

    await user.click(within(sub).getByTestId('profile-time-toggle-1'))
    expect(within(sub).getByTestId('profile-time-limit-1')).toHaveValue(120)
    expect(within(sub).getByTestId('profile-time-overlap-sum-1')).toBeChecked()
  })

  it('autosaves the daily cap after debounce — single PUT, no Save button', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    try {
      renderPage()
      const kidsCard = await screen.findByTestId('profile-card-1')
      await expand(1, user)
      await user.click(within(kidsCard).getByTestId('profile-time-toggle-1'))

      const input = within(kidsCard).getByTestId('profile-time-limit-1')
      // Set atomically: char-by-char typing under fake timers lets the 700ms
      // debounce fire on a transient partial value, producing extra PUTs.
      fireEvent.change(input, { target: { value: '90' } })

      // Pre-debounce: no save yet.
      expect(api.profiles.update).not.toHaveBeenCalled()

      await vi.advanceTimersByTimeAsync(700)

      expect(api.profiles.update).toHaveBeenCalledTimes(1)
      expect(api.profiles.update).toHaveBeenLastCalledWith(
        1,
        expect.objectContaining({ timeLimit: 90, name: 'Kids' }),
      )

      // "Saved" indicator surfaces after the PUT resolves.
      await waitFor(() => {
        const status = within(kidsCard).getByTestId('profile-time-status-1')
        expect(status).toHaveAttribute('data-status', 'saved')
        expect(status).toHaveTextContent('Saved')
      })
    } finally {
      vi.useRealTimers()
    }
  })

  it('autosaves the cross-device overlap toggle', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    try {
      renderPage()
      const kidsCard = await screen.findByTestId('profile-card-1')
      await expand(1, user)
      await user.click(within(kidsCard).getByTestId('profile-time-toggle-1'))

      await user.click(within(kidsCard).getByTestId('profile-time-overlap-dedup-1'))
      await vi.advanceTimersByTimeAsync(700)

      expect(api.profiles.update).toHaveBeenLastCalledWith(
        1,
        expect.objectContaining({ crossDeviceOverlapMode: 'dedup' }),
      )
    } finally {
      vi.useRealTimers()
    }
  })

  it('clearing the daily cap sends timeLimit:null', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    try {
      renderPage()
      const kidsCard = await screen.findByTestId('profile-card-1')
      await expand(1, user)
      await user.click(within(kidsCard).getByTestId('profile-time-toggle-1'))

      await user.clear(within(kidsCard).getByTestId('profile-time-limit-1'))
      await vi.advanceTimersByTimeAsync(700)

      expect(api.profiles.update).toHaveBeenLastCalledWith(
        1,
        expect.objectContaining({ timeLimit: null }),
      )
    } finally {
      vi.useRealTimers()
    }
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
    expect(within(kidsCard).queryByTestId('profile-time-add-schedule-1')).not.toBeInTheDocument()
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
})
