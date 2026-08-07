import { describe, it, expect, beforeEach, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import type { Device, ProfileDetail } from '@/types/api'
import { withQuery } from '@/test/queryWrapper'

vi.mock('@/api/client', () => ({
  api: {
    devices: {
      list: vi.fn(),
      upsert: vi.fn(),
      patch: vi.fn(),
      delete: vi.fn(),
    },
    profiles: {
      list: vi.fn(),
      create: vi.fn(),
    },
    alerts: {
      list: vi.fn(),
      approve: vi.fn(),
      deny: vi.fn(),
      createAccessRequest: vi.fn(),
    },
    household: {
      get: vi.fn(),
    },
  },
}))

vi.mock('@/hooks/useAuth', () => ({
  useAuth: () => mockAuth,
}))

import { api } from '@/api/client'
import { DevicesPage } from './DevicesPage'

function renderPage(initialEntries: string[] = ['/devices']) {
  return render(withQuery(
    <MemoryRouter initialEntries={initialEntries}>
      <DevicesPage />
    </MemoryRouter>,
  ))
}

let mockAuth = { isAdmin: true, isWriter: true }

const ipad: Device = {
  id: 1, mac: 'aa:bb:cc:dd:ee:01', name: "Kid's iPad",
  profileId: 1, profileName: 'Kids',
  lastSeenIp: null, lastSeenAt: null,
}
const laptop: Device = {
  id: 2, mac: 'aa:bb:cc:dd:ee:02', name: 'Work Laptop',
  profileId: 2, profileName: null,
  lastSeenIp: null, lastSeenAt: null,
}

const kidsProfile: ProfileDetail = {
  profile: { id: 1, name: 'Kids', blockedCategories: [], paused: false, failureMode: 'block-all', crossDeviceOverlapMode: 'sum', pauseMode: 'soft', defaultDeny: false },
  timeLimit: null,
}
const adultsProfile: ProfileDetail = {
  profile: { id: 2, name: 'Adults', blockedCategories: [], paused: false, failureMode: 'last-known-good', crossDeviceOverlapMode: 'sum', pauseMode: 'soft', defaultDeny: false },
  timeLimit: null,
}

beforeEach(() => {
  vi.resetAllMocks()
  mockAuth = { isAdmin: true, isWriter: true }
  ;(api.devices.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([ipad, laptop])
  ;(api.profiles.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([kidsProfile, adultsProfile])
  ;(api.profiles.create as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 7 })
  ;(api.devices.upsert as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 99 })
  ;(api.devices.delete as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(undefined)
  ;(api.devices.patch as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(undefined)
  ;(api.alerts.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([])
  ;(api.alerts.approve as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(undefined)
  ;(api.alerts.deny as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(undefined)
  ;(api.household.get as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({
    dailyResetTime: '00:00',
    dailyResetTz: 'UTC',
    heartbeatFilter: { enabled: false, bytesThreshold: 0, heartbeatHostPatterns: [] },
    unmanagedMacPolicy: { policy: 'allow', blockPage: true },
  })
})

describe('DevicesPage — list', () => {
  it('renders names, MAC, and profile pills', async () => {
    renderPage()
    expect(await screen.findByText("Kid's iPad")).toBeInTheDocument()
    expect(screen.getByText('aa:bb:cc:dd:ee:01')).toBeInTheDocument()
    expect(screen.getByText('Kids')).toBeInTheDocument()
    expect(screen.getByText('No profile')).toBeInTheDocument()
  })
})

describe('DevicesPage — add', () => {
  it('opens modal with default profile, fills fields, and calls upsert', async () => {
    const user = userEvent.setup()
    renderPage()
    await screen.findByText("Kid's iPad")
    await user.click(screen.getByRole('button', { name: /\+ Add Device/ }))

    await user.type(screen.getByPlaceholderText('aa:bb:cc:dd:ee:ff'), 'aa:bb:cc:dd:ee:99')
    await user.type(screen.getByPlaceholderText("Kid's iPad"), 'New Phone')

    await user.selectOptions(screen.getByRole('combobox'), '2')

    await user.click(screen.getByRole('button', { name: /^Save$/ }))

    await waitFor(() =>
      expect(api.devices.upsert).toHaveBeenCalledWith({
        mac: 'aa:bb:cc:dd:ee:99',
        name: 'New Phone',
        profileId: 2,
      })
    )
    await waitFor(() => expect(api.devices.list).toHaveBeenCalledTimes(2))
  })
})

describe('DevicesPage — add device with inline profile creation (#2367)', () => {
  const firstProfile: ProfileDetail = {
    profile: { id: 7, name: 'First Kid', blockedCategories: [], paused: false, failureMode: 'last-known-good', crossDeviceOverlapMode: 'sum', pauseMode: 'soft', defaultDeny: false },
    timeLimit: null,
  }

  it('zero-profile household: Add Device prompts to create the first profile inline, then saves the device with it', async () => {
    (api.devices.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([])
    ;(api.profiles.list as unknown as ReturnType<typeof vi.fn>)
      .mockResolvedValueOnce([])
      .mockResolvedValue([firstProfile])
    ;(api.profiles.create as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 7 })

    const user = userEvent.setup()
    renderPage()
    await screen.findByText('No devices yet.')
    await user.click(screen.getByRole('button', { name: /\+ Add Device/ }))

    // Empty household: no profile <select> to pick from — the inline creator
    // is shown instead so the first device can still be onboarded.
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument()
    const nameInput = await screen.findByTestId('add-device-new-profile-name')
    await user.type(nameInput, 'First Kid')
    await user.click(screen.getByTestId('add-device-create-profile'))

    await waitFor(() =>
      expect(api.profiles.create).toHaveBeenCalledWith(
        expect.objectContaining({ name: 'First Kid' }),
      )
    )

    await user.type(screen.getByPlaceholderText('aa:bb:cc:dd:ee:ff'), 'aa:bb:cc:dd:ee:77')
    await user.type(screen.getByPlaceholderText("Kid's iPad"), 'First iPad')
    await user.click(screen.getByRole('button', { name: /^Save$/ }))

    await waitFor(() =>
      expect(api.devices.upsert).toHaveBeenCalledWith({
        mac: 'aa:bb:cc:dd:ee:77',
        name: 'First iPad',
        profileId: 7,
      })
    )
  })

  it('with existing profiles: "+ New profile…" option opens the inline creator and selects the created profile', async () => {
    (api.devices.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([])
    ;(api.profiles.list as unknown as ReturnType<typeof vi.fn>)
      .mockResolvedValueOnce([kidsProfile, adultsProfile])
      .mockResolvedValue([kidsProfile, adultsProfile, firstProfile])
    ;(api.profiles.create as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 7 })

    const user = userEvent.setup()
    renderPage()
    await screen.findByText('No devices yet.')
    await user.click(screen.getByRole('button', { name: /\+ Add Device/ }))

    // Choosing the sentinel reveals the creator without leaving the flow.
    await user.selectOptions(screen.getByRole('combobox'), '__new__')
    // The with-profiles copy, not the first-profile copy.
    const creator = await screen.findByTestId('add-device-new-profile')
    expect(
      within(creator).getByText('Name the new profile — it will be assigned to this device.'),
    ).toBeInTheDocument()
    expect(
      within(creator).queryByText('Create your first profile to assign this device.'),
    ).not.toBeInTheDocument()
    const nameInput = await screen.findByTestId('add-device-new-profile-name')
    await user.type(nameInput, 'First Kid')
    await user.click(screen.getByTestId('add-device-create-profile'))

    await waitFor(() =>
      expect(api.profiles.create).toHaveBeenCalledWith(
        expect.objectContaining({ name: 'First Kid' }),
      )
    )

    await user.type(screen.getByPlaceholderText('aa:bb:cc:dd:ee:ff'), 'aa:bb:cc:dd:ee:77')
    await user.type(screen.getByPlaceholderText("Kid's iPad"), 'First iPad')
    await user.click(screen.getByRole('button', { name: /^Save$/ }))

    await waitFor(() =>
      expect(api.devices.upsert).toHaveBeenCalledWith({
        mac: 'aa:bb:cc:dd:ee:77',
        name: 'First iPad',
        profileId: 7,
      })
    )
  })

  it('blank profile name is rejected with an inline error', async () => {
    (api.devices.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([])
    ;(api.profiles.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([])

    const user = userEvent.setup()
    renderPage()
    await screen.findByText('No devices yet.')
    await user.click(screen.getByRole('button', { name: /\+ Add Device/ }))

    await screen.findByTestId('add-device-new-profile-name')
    await user.click(screen.getByTestId('add-device-create-profile'))

    expect(await screen.findByTestId('add-device-new-profile-error')).toBeInTheDocument()
    expect(api.profiles.create).not.toHaveBeenCalled()
  })

  // #2560 — the empty-household copy is a real, reachable state (openCreate puts
  // a zero-profile household straight into the creator), not dead code. Pinned so
  // a future refactor can't quietly orphan it again.
  it('zero-profile household: the "create your first profile" copy is shown', async () => {
    (api.devices.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([])
    ;(api.profiles.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([])

    const user = userEvent.setup()
    renderPage()
    await screen.findByText('No devices yet.')
    await user.click(screen.getByRole('button', { name: /\+ Add Device/ }))

    expect(
      await screen.findByText('Create your first profile to assign this device.'),
    ).toBeInTheDocument()
  })

  // The creator's name input autofocuses when the operator PICKED it (the
  // select they used may have been disabled on the way, dropping focus to
  // <body>) — but not when the dialog auto-opens into it on an empty household,
  // where stealing focus would jump them past the MAC field they must fill.
  it('does not steal focus from the MAC field when the dialog auto-opens the creator', async () => {
    (api.devices.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([])
    ;(api.profiles.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([])

    const user = userEvent.setup()
    renderPage()
    await screen.findByText('No devices yet.')
    await user.click(screen.getByRole('button', { name: /\+ Add Device/ }))

    const nameInput = await screen.findByTestId('add-device-new-profile-name')
    expect(nameInput).not.toHaveFocus()
  })

  it('focuses the name input when the operator picks "+ New profile…"', async () => {
    (api.devices.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([])
    ;(api.profiles.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([kidsProfile, adultsProfile])

    const user = userEvent.setup()
    renderPage()
    await screen.findByText('No devices yet.')
    await user.click(screen.getByRole('button', { name: /\+ Add Device/ }))
    await user.selectOptions(screen.getByTestId('add-device-profile-select'), '__new__')

    expect(await screen.findByTestId('add-device-new-profile-name')).toHaveFocus()
  })

  // The same guard the row editor gets: closing or re-picking mid-request would
  // leave the profile created server-side and never assigned, silently. The
  // modal owns these controls, so the creator mirrors its pending state out.
  it('freezes the modal select and Cancel while a create is in flight', async () => {
    (api.devices.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([])
    ;(api.profiles.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([kidsProfile, adultsProfile])
    let release: (v: { id: number }) => void = () => {}
    ;(api.profiles.create as unknown as ReturnType<typeof vi.fn>).mockReturnValue(
      new Promise<{ id: number }>(res => { release = res }),
    )

    const user = userEvent.setup()
    renderPage()
    await screen.findByText('No devices yet.')
    await user.click(screen.getByRole('button', { name: /\+ Add Device/ }))
    await user.selectOptions(screen.getByTestId('add-device-profile-select'), '__new__')
    await user.type(screen.getByTestId('add-device-new-profile-name'), 'Teens')

    expect(screen.getByTestId('add-device-profile-select')).toBeEnabled()
    expect(screen.getByTestId('add-device-cancel')).toBeEnabled()

    await user.click(screen.getByTestId('add-device-create-profile'))

    await waitFor(() => expect(screen.getByTestId('add-device-cancel')).toBeDisabled())
    expect(screen.getByTestId('add-device-profile-select')).toBeDisabled()
    expect(screen.getByTestId('add-device-create-profile')).toBeDisabled()

    release({ id: 7 })
    await waitFor(() => expect(screen.getByTestId('add-device-cancel')).toBeEnabled())
  })

  // #2560 — the operator on the new prod household reported "it won't let me
  // create a profile" with nothing on screen explaining why. `createProfile`
  // awaited `mutateAsync` with no catch and no mutation `onError`, so a server
  // rejection produced an unhandled promise and a silently inert button: the
  // `-new-profile-error` slot only ever carried the blank-name validation.
  it('a failing createProfile surfaces the server error inline', async () => {
    (api.devices.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([])
    ;(api.profiles.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([])
    ;(api.profiles.create as unknown as ReturnType<typeof vi.fn>).mockRejectedValue(
      new Error('profile limit reached'),
    )

    const user = userEvent.setup()
    renderPage()
    await screen.findByText('No devices yet.')
    await user.click(screen.getByRole('button', { name: /\+ Add Device/ }))

    await user.type(await screen.findByTestId('add-device-new-profile-name'), 'First Kid')
    await user.click(screen.getByTestId('add-device-create-profile'))

    expect(await screen.findByTestId('add-device-new-profile-error')).toHaveTextContent(
      'profile limit reached',
    )
    // The failure path must also release the caller's freeze. A wedged
    // `createPending` would disable Cancel while Save is already disabled by
    // `creatingProfile`, leaving no way out of the dialog.
    expect(screen.getByTestId('add-device-cancel')).toBeEnabled()
    expect(screen.getByTestId('add-device-create-profile')).toBeEnabled()
  })

  // Escape closes the modal exactly like Cancel, so it has to honour the same
  // in-flight guard instead of routing around it.
  it('Escape does not close the modal while a create is in flight', async () => {
    (api.devices.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([])
    ;(api.profiles.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([kidsProfile, adultsProfile])
    let release: (v: { id: number }) => void = () => {}
    ;(api.profiles.create as unknown as ReturnType<typeof vi.fn>).mockReturnValue(
      new Promise<{ id: number }>(res => { release = res }),
    )

    const user = userEvent.setup()
    renderPage()
    await screen.findByText('No devices yet.')
    await user.click(screen.getByRole('button', { name: /\+ Add Device/ }))
    await user.selectOptions(screen.getByTestId('add-device-profile-select'), '__new__')
    await user.type(screen.getByTestId('add-device-new-profile-name'), 'Teens')

    // Escape works before the create starts.
    await user.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByTestId('add-device-cancel')).not.toBeInTheDocument())

    await user.click(screen.getByRole('button', { name: /\+ Add Device/ }))
    await user.selectOptions(screen.getByTestId('add-device-profile-select'), '__new__')
    await user.type(screen.getByTestId('add-device-new-profile-name'), 'Teens')
    await user.click(screen.getByTestId('add-device-create-profile'))
    await waitFor(() => expect(screen.getByTestId('add-device-cancel')).toBeDisabled())

    await user.keyboard('{Escape}')
    expect(screen.getByTestId('add-device-cancel')).toBeInTheDocument()

    release({ id: 7 })
    await waitFor(() => expect(screen.getByTestId('add-device-cancel')).toBeEnabled())
  })
})

// #2560 — the row editor's <select> offered only "No profile" plus the
// profiles that already existed, so putting a device on a NEW profile meant
// leaving /devices for /profiles and coming back. The creator is now the same
// component the Add-Device modal mounts (single-source-of-truth).
//
// Note on the zero-profile household: it cannot reach this surface at all.
// `devices.profile_id` is `REFERENCES profiles(id) ON DELETE SET NULL`
// (api/resources/db/migration/V1__init.sql:57), so deleting the last profile
// nulls every device's assignment and drops them into the Unmanaged section,
// whose only affordance is Enroll → the Add-Device modal. These tests therefore
// pin the reachable case — a household that has profiles and wants another.
describe('DevicesPage — row editor inline profile creation (#2560)', () => {
  const newProfile: ProfileDetail = {
    profile: { id: 7, name: 'Teens', blockedCategories: [], paused: false, failureMode: 'last-known-good', crossDeviceOverlapMode: 'sum', pauseMode: 'soft', defaultDeny: false },
    timeLimit: null,
  }

  it('"+ New profile…" is offered alongside the existing profiles and assigns the created one', async () => {
    (api.devices.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([ipad])
    ;(api.profiles.list as unknown as ReturnType<typeof vi.fn>)
      .mockResolvedValueOnce([kidsProfile, adultsProfile])
      .mockResolvedValue([kidsProfile, adultsProfile, newProfile])
    ;(api.profiles.create as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 7 })

    vi.useFakeTimers({ shouldAdvanceTime: true })
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    try {
      renderPage()
      const row = await screen.findByTestId(`device-row-${ipad.mac}`)
      await user.click(within(row).getByRole('button', { name: /Edit/ }))
      const editor = await screen.findByTestId(`device-editor-${ipad.mac}`)
      const select = within(editor).getByTestId(`device-profile-select-${ipad.mac}`)

      // The existing options are still there — this is additive, not a swap.
      expect(within(editor).getByRole('option', { name: 'Kids' })).toBeInTheDocument()
      expect(within(editor).getByRole('option', { name: 'No profile' })).toBeInTheDocument()
      expect(within(editor).getByRole('option', { name: '+ New profile…' })).toBeInTheDocument()

      await user.selectOptions(select, '__new__')
      await user.type(
        within(editor).getByTestId(`device-${ipad.mac}-new-profile-name`),
        'Teens',
      )
      await user.click(within(editor).getByTestId(`device-${ipad.mac}-create-profile`))

      await waitFor(() =>
        expect(api.profiles.create).toHaveBeenCalledWith(
          expect.objectContaining({ name: 'Teens' }),
        )
      )

      // Assigned by the row's own debounced autosave — no second save path.
      await vi.advanceTimersByTimeAsync(700)
      await waitFor(() =>
        expect(api.devices.patch).toHaveBeenCalledWith(ipad.mac, { profileId: 7 })
      )
    } finally {
      vi.useRealTimers()
    }
  })

  // Scoped to the creator being OPEN, not just to a request being in flight:
  // the row autosaves, so a pick made between "Create profile" and the response
  // would be silently overwritten when the created profile lands, and "Done"
  // would abandon the flow. Cancel is therefore the only exit, and must always
  // be offered — see the zero-profiles case below.
  it('freezes the profile select and Done while the creator is open', async () => {
    (api.devices.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([ipad])
    ;(api.profiles.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([kidsProfile, adultsProfile])

    const user = userEvent.setup()
    renderPage()
    const row = await screen.findByTestId(`device-row-${ipad.mac}`)
    await user.click(within(row).getByRole('button', { name: /Edit/ }))
    const editor = await screen.findByTestId(`device-editor-${ipad.mac}`)

    await user.selectOptions(
      within(editor).getByTestId(`device-profile-select-${ipad.mac}`),
      '__new__',
    )

    expect(within(editor).getByTestId(`device-profile-select-${ipad.mac}`)).toBeDisabled()
    expect(within(editor).getByRole('button', { name: 'Done' })).toBeDisabled()
    // Cancel is the way back out.
    await user.click(within(editor).getByRole('button', { name: /^Cancel$/ }))
    expect(within(editor).getByTestId(`device-profile-select-${ipad.mac}`)).toBeEnabled()
    expect(api.devices.patch).not.toHaveBeenCalled()
  })

  it('a failing createProfile surfaces the server error inline in the row editor', async () => {
    (api.devices.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([ipad])
    ;(api.profiles.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([kidsProfile, adultsProfile])
    ;(api.profiles.create as unknown as ReturnType<typeof vi.fn>).mockRejectedValue(
      new Error('profile limit reached'),
    )

    const user = userEvent.setup()
    renderPage()
    const row = await screen.findByTestId(`device-row-${ipad.mac}`)
    await user.click(within(row).getByRole('button', { name: /Edit/ }))
    const editor = await screen.findByTestId(`device-editor-${ipad.mac}`)

    await user.selectOptions(
      within(editor).getByTestId(`device-profile-select-${ipad.mac}`),
      '__new__',
    )
    await user.type(
      within(editor).getByTestId(`device-${ipad.mac}-new-profile-name`),
      'Teens',
    )
    await user.click(within(editor).getByTestId(`device-${ipad.mac}-create-profile`))

    expect(
      await within(editor).findByTestId(`device-${ipad.mac}-new-profile-error`),
    ).toHaveTextContent('profile limit reached')
    expect(api.devices.patch).not.toHaveBeenCalled()
  })
})

describe('DevicesPage — inline autosave edit (#1000)', () => {
  const mac = 'aa:bb:cc:dd:ee:01'

  it('expands an inline editor pre-filled with the device values; no Save button', async () => {
    const user = userEvent.setup()
    renderPage()
    const ipadRow = await screen.findByTestId(`device-row-${mac}`)
    await user.click(within(ipadRow).getByRole('button', { name: /Edit/ }))

    const editor = await screen.findByTestId(`device-editor-${mac}`)
    expect(within(editor).getByDisplayValue("Kid's iPad")).toBeInTheDocument()
    expect(
      (within(editor).getByTestId(`device-profile-select-${mac}`) as HTMLSelectElement).value,
    ).toBe('1')
    // Autosave: no explicit Save button on the edit affordance.
    expect(within(editor).queryByRole('button', { name: /^Save$/ })).not.toBeInTheDocument()
  })

  it('renaming fires a single debounced PATCH {name} and shows "Saved just now"', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    try {
      renderPage()
      const ipadRow = await screen.findByTestId(`device-row-${mac}`)
      await user.click(within(ipadRow).getByRole('button', { name: /Edit/ }))
      const editor = await screen.findByTestId(`device-editor-${mac}`)
      const input = within(editor).getByTestId(`device-name-input-${mac}`)

      // Set atomically: typing char-by-char under fake timers lets the 700ms
      // debounce fire on a transient partial value, producing extra PATCHes.
      fireEvent.change(input, { target: { value: 'Living Room iPad' } })
      expect(api.devices.patch).not.toHaveBeenCalled()

      await vi.advanceTimersByTimeAsync(700)

      expect(api.devices.patch).toHaveBeenCalledTimes(1)
      expect(api.devices.patch).toHaveBeenCalledWith(mac, { name: 'Living Room iPad' })
      await waitFor(() =>
        expect(within(editor).getByTestId(`device-save-status-${mac}`)).toHaveAttribute(
          'data-status',
          'saved',
        ),
      )
    } finally {
      vi.useRealTimers()
    }
  })

  it('reassigning the profile inline fires PATCH {profileId}', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    try {
      renderPage()
      const ipadRow = await screen.findByTestId(`device-row-${mac}`)
      await user.click(within(ipadRow).getByRole('button', { name: /Edit/ }))
      const editor = await screen.findByTestId(`device-editor-${mac}`)

      await user.selectOptions(within(editor).getByTestId(`device-profile-select-${mac}`), '2')
      await vi.advanceTimersByTimeAsync(700)

      expect(api.devices.patch).toHaveBeenCalledWith(mac, { profileId: 2 })
    } finally {
      vi.useRealTimers()
    }
  })

  // #2366 — the inline editor <select> must carry a "No profile" option so
  // (a) a device with no current profile shows a selection that matches state
  // (not a phantom first-profile the browser paints when value='' matches no
  // <option>), (b) assigning from "No profile" is a genuine onChange that fires
  // autosave, and (c) a profile can be removed (PATCH {profileId:null}).
  it('a device with no current profile shows "No profile" selected; picking one fires PATCH {profileId}', async () => {
    // profileId points at a since-deleted profile → the device lists as managed
    // (profileId !== null) but renders a "No profile" pill and, pre-fix, the
    // editor painted the first real profile as selected while state stayed
    // orphaned → clicking Done fired no PATCH (the silent no-op).
    const orphan: Device = {
      id: 3, mac: 'aa:bb:cc:dd:ee:03', name: 'Old Tablet',
      profileId: 999, profileName: null,
      lastSeenIp: null, lastSeenAt: null,
    }
    ;(api.devices.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([orphan])
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    try {
      renderPage()
      const row = await screen.findByTestId('device-row-aa:bb:cc:dd:ee:03')
      await user.click(within(row).getByRole('button', { name: /Edit/ }))
      const editor = await screen.findByTestId('device-editor-aa:bb:cc:dd:ee:03')
      const select = within(editor).getByTestId(
        'device-profile-select-aa:bb:cc:dd:ee:03',
      ) as HTMLSelectElement
      // The displayed selection matches state: "No profile", not a phantom.
      expect(within(editor).getByRole('option', { name: 'No profile' })).toBeInTheDocument()
      expect(select.value).toBe('')

      await user.selectOptions(select, '1')
      await vi.advanceTimersByTimeAsync(700)

      expect(api.devices.patch).toHaveBeenCalledWith('aa:bb:cc:dd:ee:03', { profileId: 1 })
    } finally {
      vi.useRealTimers()
    }
  })

  it('removing a profile inline fires PATCH {profileId:null}', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    try {
      renderPage()
      const ipadRow = await screen.findByTestId(`device-row-${mac}`)
      await user.click(within(ipadRow).getByRole('button', { name: /Edit/ }))
      const editor = await screen.findByTestId(`device-editor-${mac}`)
      const select = within(editor).getByTestId(
        `device-profile-select-${mac}`,
      ) as HTMLSelectElement
      expect(select.value).toBe('1') // starts assigned to Kids

      await user.selectOptions(select, '')
      await vi.advanceTimersByTimeAsync(700)

      expect(api.devices.patch).toHaveBeenCalledWith(mac, { profileId: null })
    } finally {
      vi.useRealTimers()
    }
  })

  it('PATCH failure surfaces inline error + Retry; dirty value retained; Retry re-sends', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    try {
      (api.devices.patch as unknown as ReturnType<typeof vi.fn>).mockRejectedValueOnce(
        new Error('boom'),
      )
      renderPage()
      const ipadRow = await screen.findByTestId(`device-row-${mac}`)
      await user.click(within(ipadRow).getByRole('button', { name: /Edit/ }))
      const editor = await screen.findByTestId(`device-editor-${mac}`)
      const input = within(editor).getByTestId(`device-name-input-${mac}`)

      fireEvent.change(input, { target: { value: 'Den iPad' } })
      await vi.advanceTimersByTimeAsync(700)

      const status = within(editor).getByTestId(`device-save-status-${mac}`)
      await waitFor(() => expect(status).toHaveAttribute('data-status', 'error'))
      expect(input).toHaveValue('Den iPad') // dirty value preserved

      await user.click(within(editor).getByTestId(`device-save-status-${mac}-retry`))
      await waitFor(() => expect(api.devices.patch).toHaveBeenCalledTimes(2))
    } finally {
      vi.useRealTimers()
    }
  })
})

describe('DevicesPage — delete', () => {
  it('confirms then calls api.devices.delete', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const user = userEvent.setup()
    renderPage()
    const ipadRow = await screen.findByTestId('device-row-aa:bb:cc:dd:ee:01')
    await user.click(within(ipadRow).getByRole('button', { name: /Remove/ }))
    expect(confirmSpy).toHaveBeenCalled()
    await waitFor(() => expect(api.devices.delete).toHaveBeenCalledWith('aa:bb:cc:dd:ee:01'))
    confirmSpy.mockRestore()
  })

  it('does not call delete when confirm is cancelled', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false)
    const user = userEvent.setup()
    renderPage()
    const ipadRow = await screen.findByTestId('device-row-aa:bb:cc:dd:ee:01')
    await user.click(within(ipadRow).getByRole('button', { name: /Remove/ }))
    expect(api.devices.delete).not.toHaveBeenCalled()
    confirmSpy.mockRestore()
  })
})

describe('DevicesPage — highlight from ?mac= (#298)', () => {
  it('rings the matching device row when ?mac=... is set', async () => {
    // jsdom doesn't implement scrollIntoView — stub to avoid a runtime throw.
    HTMLElement.prototype.scrollIntoView = vi.fn()
    renderPage(['/devices?mac=aa:bb:cc:dd:ee:01'])
    const row = await screen.findByTestId('device-row-aa:bb:cc:dd:ee:01')
    await waitFor(() => expect(row.className).toContain('ring-brand-accent'))
  })

  it('does not ring any row when ?mac= is not set', async () => {
    renderPage()
    const row = await screen.findByTestId('device-row-aa:bb:cc:dd:ee:01')
    expect(row.className).not.toContain('ring-brand-accent')
  })
})

describe('DevicesPage — role gating', () => {
  it('hides admin-only buttons for non-admins', async () => {
    mockAuth = { isAdmin: false, isWriter: false }
    renderPage()
    await screen.findByText("Kid's iPad")
    expect(screen.queryByRole('button', { name: /\+ Add Device/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Edit/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Remove/ })).not.toBeInTheDocument()
  })
})

describe('DevicesPage — new-device alerts banner (#711)', () => {
  const alert = {
    id: 42,
    kind: 'new_device' as const,
    status: 'pending' as const,
    mac: 'aa:bb:cc:99:99:99',
    deviceName: 'device-999999',
    profileId: null,
    profileName: null,
    host: null,
    requestKind: null,
    note: null,
    grantedMinutes: null,
    createdAt: '2026-05-22T12:00:00Z',
    decidedAt: null,
    decidedBy: null,
  }

  it('does not render banner when there are no pending alerts', async () => {
    renderPage()
    await screen.findByText("Kid's iPad")
    expect(screen.queryByTestId('new-device-alerts-banner')).not.toBeInTheDocument()
  })

  it('renders the banner with the MAC + Dismiss when an alert is pending', async () => {
    (api.alerts.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([alert])
    renderPage()
    const banner = await screen.findByTestId('new-device-alerts-banner')
    expect(within(banner).getByText('aa:bb:cc:99:99:99')).toBeInTheDocument()
    expect(within(banner).getByRole('button', { name: /Dismiss/ })).toBeInTheDocument()
  })

  it('clicks Dismiss → calls api.alerts.approve(id) and refetches', async () => {
    (api.alerts.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([alert])
    const user = userEvent.setup()
    renderPage()
    await screen.findByTestId('new-device-alerts-banner')
    await user.click(screen.getByRole('button', { name: /Dismiss/ }))
    await waitFor(() => expect(api.alerts.approve).toHaveBeenCalledWith(42))
    // banner refetch invoked
    await waitFor(() => expect(api.alerts.list).toHaveBeenCalledTimes(2))
  })

  it('non-admins see the banner but no Dismiss button', async () => {
    mockAuth = { isAdmin: false, isWriter: false }
    ;(api.alerts.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([alert])
    renderPage()
    const banner = await screen.findByTestId('new-device-alerts-banner')
    expect(within(banner).getByText('aa:bb:cc:99:99:99')).toBeInTheDocument()
    expect(within(banner).queryByRole('button', { name: /Dismiss/ })).not.toBeInTheDocument()
  })

  it('shows "Enable browser notifications" when Notification.permission is default', async () => {
    class FakeN { static permission: NotificationPermission = 'default'; static requestPermission = vi.fn(async () => 'granted' as NotificationPermission); constructor() {} }
    // @ts-expect-error inject
    window.Notification = FakeN
    ;(api.alerts.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([alert])
    renderPage()
    await screen.findByTestId('new-device-alerts-banner')
    const btn = await screen.findByTestId('enable-notifications-btn')
    const user = userEvent.setup()
    await user.click(btn)
    expect(FakeN.requestPermission).toHaveBeenCalled()
    // @ts-expect-error cleanup
    delete window.Notification
  })

  describe('inline edit on click (#1052)', () => {
    it('clicking the alert row opens the inline editor with the MAC visible', async () => {
      (api.alerts.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([alert])
      const user = userEvent.setup()
      renderPage()
      await screen.findByTestId('new-device-alerts-banner')
      await user.click(screen.getByTestId(`new-device-alert-row-${alert.mac}`))
      const editor = await screen.findByTestId('new-device-alert-editor')
      expect(within(editor).getByText(alert.mac)).toBeInTheDocument()
      expect(within(editor).getByDisplayValue('device-999999')).toBeInTheDocument()
    })

    it('Save calls PATCH /devices then approve(id) in that order', async () => {
      (api.alerts.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([alert])
      const order: string[] = []
      ;(api.devices.patch as unknown as ReturnType<typeof vi.fn>).mockImplementation(async () => {
        order.push('patch')
      })
      ;(api.alerts.approve as unknown as ReturnType<typeof vi.fn>).mockImplementation(async () => {
        order.push('approve')
      })

      const user = userEvent.setup()
      renderPage()
      await screen.findByTestId('new-device-alerts-banner')
      await user.click(screen.getByTestId(`new-device-alert-row-${alert.mac}`))
      const editor = await screen.findByTestId('new-device-alert-editor')

      const nameInput = within(editor).getByDisplayValue('device-999999')
      await user.clear(nameInput)
      await user.type(nameInput, 'Living Room TV')
      await user.selectOptions(within(editor).getByTestId('new-device-alert-profile'), '2')

      await user.click(within(editor).getByRole('button', { name: /^Save$/ }))

      await waitFor(() =>
        expect(api.devices.patch).toHaveBeenCalledWith(alert.mac, {
          name: 'Living Room TV',
          profileId: 2,
        })
      )
      await waitFor(() => expect(api.alerts.approve).toHaveBeenCalledWith(alert.id))
      expect(order).toEqual(['patch', 'approve'])
    })

    it('Cancel closes editor without dismissing the alert', async () => {
      (api.alerts.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([alert])
      const user = userEvent.setup()
      renderPage()
      await screen.findByTestId('new-device-alerts-banner')
      await user.click(screen.getByTestId(`new-device-alert-row-${alert.mac}`))
      await screen.findByTestId('new-device-alert-editor')
      await user.click(screen.getByRole('button', { name: /^Cancel$/ }))
      await waitFor(() =>
        expect(screen.queryByTestId('new-device-alert-editor')).not.toBeInTheDocument()
      )
      expect(api.devices.patch).not.toHaveBeenCalled()
      expect(api.alerts.approve).not.toHaveBeenCalled()
      expect(api.alerts.deny).not.toHaveBeenCalled()
    })

    it('saving without picking a profile denies (not approves) the alert', async () => {
      (api.alerts.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([alert])
      const user = userEvent.setup()
      renderPage()
      await screen.findByTestId('new-device-alerts-banner')
      await user.click(screen.getByTestId(`new-device-alert-row-${alert.mac}`))
      const editor = await screen.findByTestId('new-device-alert-editor')

      const nameInput = within(editor).getByDisplayValue('device-999999')
      await user.clear(nameInput)
      await user.type(nameInput, 'Mystery Device')
      // leave profile select at default '— No profile —'
      await user.click(within(editor).getByRole('button', { name: /^Save$/ }))

      await waitFor(() =>
        expect(api.devices.patch).toHaveBeenCalledWith(alert.mac, { name: 'Mystery Device' })
      )
      await waitFor(() => expect(api.alerts.deny).toHaveBeenCalledWith(alert.id))
      expect(api.alerts.approve).not.toHaveBeenCalled()
    })
  })

  it('hides "Enable browser notifications" when permission is already granted', async () => {
    class FakeN { static permission: NotificationPermission = 'granted'; static requestPermission = vi.fn(); constructor() {} }
    // @ts-expect-error inject
    window.Notification = FakeN
    ;(api.alerts.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([alert])
    renderPage()
    await screen.findByTestId('new-device-alerts-banner')
    expect(screen.queryByTestId('enable-notifications-btn')).not.toBeInTheDocument()
    // @ts-expect-error cleanup
    delete window.Notification
  })
})

// #2522 — every device route (PUT /api/devices, PATCH/DELETE /api/devices/{mac}) and both
// alert actions (POST /api/alerts/{id}/approve|deny) are `requireWriter`, so an adult gets the
// full device-editing surface. A child keeps the read-only view.
describe('DevicesPage — adult capability (#2522)', () => {
  it('gives an adult the device-editing affordances', async () => {
    mockAuth = { isAdmin: false, isWriter: true }
    renderPage()
    await screen.findByText("Kid's iPad")
    expect(screen.getByRole('button', { name: /Add Device/ })).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: 'Edit' }).length).toBeGreaterThan(0)
  })

  it('keeps a child out of the device-editing affordances', async () => {
    mockAuth = { isAdmin: false, isWriter: false }
    renderPage()
    await screen.findByText("Kid's iPad")
    expect(screen.queryByRole('button', { name: /Add Device/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument()
  })
})

// #2607 — the new-device alert is the natural onboarding entry point: a device
// appears, the operator clicks the alert to deal with it. On a household with
// zero profiles this surface was a dead end — its <select> offered only the
// null option, so the operator had to leave for /profiles and come back. It now
// mounts the same `ProfilePicker` the Add-Device modal and the row editor do.
describe('DevicesPage — new-device alert inline profile creation (#2607)', () => {
  const alert = {
    id: 42,
    kind: 'new_device' as const,
    status: 'pending' as const,
    mac: 'aa:bb:cc:99:99:99',
    deviceName: 'device-999999',
    profileId: null,
    profileName: null,
    host: null,
    requestKind: null,
    note: null,
    grantedMinutes: null,
    createdAt: '2026-05-22T12:00:00Z',
    decidedAt: null,
    decidedBy: null,
  }

  const newProfile: ProfileDetail = {
    profile: { id: 7, name: 'First Kid', blockedCategories: [], paused: false, failureMode: 'last-known-good', crossDeviceOverlapMode: 'sum', pauseMode: 'soft', defaultDeny: false },
    timeLimit: null,
  }

  async function openEditor() {
    const user = userEvent.setup()
    renderPage()
    await screen.findByTestId('new-device-alerts-banner')
    await user.click(screen.getByTestId(`new-device-alert-row-${alert.mac}`))
    return { user, editor: await screen.findByTestId('new-device-alert-editor') }
  }

  it('zero-profile household: the alert opens straight into the creator, and the device is assigned without leaving the page', async () => {
    (api.devices.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([])
    ;(api.alerts.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([alert])
    ;(api.profiles.list as unknown as ReturnType<typeof vi.fn>)
      .mockResolvedValueOnce([])
      .mockResolvedValue([newProfile])
    ;(api.profiles.create as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 7 })

    const { user, editor } = await openEditor()

    await user.type(
      await within(editor).findByTestId('new-device-alert-new-profile-name'),
      'First Kid',
    )
    await user.click(within(editor).getByTestId('new-device-alert-create-profile'))

    await waitFor(() =>
      expect(api.profiles.create).toHaveBeenCalledWith(
        expect.objectContaining({ name: 'First Kid' }),
      )
    )

    await user.click(within(editor).getByTestId('new-device-alert-save'))

    await waitFor(() =>
      expect(api.devices.patch).toHaveBeenCalledWith(alert.mac, { profileId: 7 })
    )
    // A profile means the operator decided the device belongs here — approve.
    await waitFor(() => expect(api.alerts.approve).toHaveBeenCalledWith(alert.id))
  })

  it('with existing profiles: "+ New profile…" is offered alongside them and assigns the created one', async () => {
    (api.devices.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([])
    ;(api.alerts.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([alert])
    ;(api.profiles.list as unknown as ReturnType<typeof vi.fn>)
      .mockResolvedValueOnce([kidsProfile, adultsProfile])
      .mockResolvedValue([kidsProfile, adultsProfile, newProfile])
    ;(api.profiles.create as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 7 })

    const { user, editor } = await openEditor()

    expect(within(editor).getByRole('option', { name: 'Kids' })).toBeInTheDocument()
    expect(within(editor).getByRole('option', { name: '+ New profile…' })).toBeInTheDocument()

    await user.selectOptions(within(editor).getByTestId('new-device-alert-profile'), '__new__')
    await user.type(
      within(editor).getByTestId('new-device-alert-new-profile-name'),
      'First Kid',
    )
    await user.click(within(editor).getByTestId('new-device-alert-create-profile'))

    await waitFor(() =>
      expect(api.profiles.create).toHaveBeenCalledWith(
        expect.objectContaining({ name: 'First Kid' }),
      )
    )

    await user.click(within(editor).getByTestId('new-device-alert-save'))
    await waitFor(() =>
      expect(api.devices.patch).toHaveBeenCalledWith(alert.mac, { profileId: 7 })
    )
  })

  // The label the three surfaces had drifted on ("No profile" vs
  // "— No profile —"). Settled on the plain form, which is also what the device
  // row's own pill reads.
  it('uses the one settled null-option label', async () => {
    (api.alerts.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([alert])
    const { editor } = await openEditor()
    expect(within(editor).getByRole('option', { name: 'No profile' })).toBeInTheDocument()
    expect(within(editor).queryByRole('option', { name: '— No profile —' })).not.toBeInTheDocument()
  })

  // Saving mid-create would race the assignment against the profile landing;
  // closing would leave the profile created server-side and never assigned,
  // with nothing on screen saying so.
  it('freezes Save while the creator is open', async () => {
    (api.alerts.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([alert])
    const { user, editor } = await openEditor()

    expect(within(editor).getByTestId('new-device-alert-save')).toBeEnabled()
    await user.selectOptions(within(editor).getByTestId('new-device-alert-profile'), '__new__')

    expect(within(editor).getByTestId('new-device-alert-save')).toBeDisabled()
    expect(within(editor).getByTestId('new-device-alert-profile')).toBeDisabled()

    await user.click(within(editor).getByTestId('new-device-alert-cancel-profile'))
    expect(within(editor).getByTestId('new-device-alert-save')).toBeEnabled()
  })
})
