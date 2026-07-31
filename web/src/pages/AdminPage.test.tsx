import { describe, it, expect, beforeEach, vi } from 'vitest'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

vi.mock('@/api/client', () => ({
  api: {
    household: {
      get: vi.fn(),
      update: vi.fn(),
      patch: vi.fn(),
      // #2382 — the escape-hatch card queries these on mount / write.
      getEnforcement: vi.fn(),
      setEnforcement: vi.fn(),
    },
  },
}))

// #2522: the page is reachable by any WRITER (admin or adult) because every card on it patches
// `/api/household/settings` (requireWriter). The one exception — the #2382 kill-switch — is
// `requireAdmin`, so its card is gated separately. Default the mock to an admin so the pre-#2522
// suite below is unchanged.
let mockAuth = { isAdmin: true, isWriter: true }
vi.mock('@/hooks/useAuth', () => ({
  useAuth: () => mockAuth,
}))

import { api } from '@/api/client'
import type { HeartbeatFilter, HouseholdSettings, UnmanagedMacPolicy } from '@/types/api'
import { AdminPage } from './AdminPage'

const DEFAULT_HF: HeartbeatFilter = {
  enabled: false,
  bytesThreshold: 2048,
  heartbeatHostPatterns: [],
}

const DEFAULT_UMM: UnmanagedMacPolicy = { policy: 'allow', blockPage: true }

beforeEach(() => {
  vi.resetAllMocks()
  mockAuth = { isAdmin: true, isWriter: true }
  // Server-of-record: simulates the live API. `patch` deep-merges into this
  // and `get` reads from it, so tests exercise the real round-trip the
  // autosave UI does (PATCH a single field, then re-GET to reconcile).
  let stored: HouseholdSettings = {
    dailyResetTime: '00:00',
    dailyResetTz: 'America/Los_Angeles',
    heartbeatFilter: { ...DEFAULT_HF },
    unmanagedMacPolicy: { ...DEFAULT_UMM },
    blockEncryptedDns: false,
    ambientGateEnabled: false,
    ambientIsolationMaxHosts: 2,
    ambientMinIsolatedDays: 3,
    ambientLearningWindowDays: 14,
    notifyEmail: null,
  }
  ;(api.household.get as unknown as ReturnType<typeof vi.fn>).mockImplementation(
    async () => ({
      ...stored,
      heartbeatFilter: { ...stored.heartbeatFilter },
      unmanagedMacPolicy: { ...stored.unmanagedMacPolicy },
    }),
  )
  ;(api.household.patch as unknown as ReturnType<typeof vi.fn>).mockImplementation(
    async (patch: Partial<HouseholdSettings>) => {
      stored = {
        ...stored,
        ...patch,
        heartbeatFilter: { ...stored.heartbeatFilter, ...(patch.heartbeatFilter ?? {}) },
        unmanagedMacPolicy: {
          ...stored.unmanagedMacPolicy,
          ...(patch.unmanagedMacPolicy ?? {}),
        },
      }
    },
  )
  // #2382 — the escape-hatch flag has its own server-of-record (it is backed by the households
  // table, not household_settings), so mock it separately as a simple get/set round-trip.
  let enforcementDisabled = false
  ;(api.household.getEnforcement as unknown as ReturnType<typeof vi.fn>).mockImplementation(
    async () => ({ enforcementDisabled }),
  )
  ;(api.household.setEnforcement as unknown as ReturnType<typeof vi.fn>).mockImplementation(
    async (next: boolean) => {
      enforcementDisabled = next
      return { enforcementDisabled }
    },
  )
})

describe('AdminPage — daily reset autosave (#1002)', () => {
  it('renders live time + tz inputs, no Edit/Save/Cancel buttons', async () => {
    render(<AdminPage />)
    const time = await screen.findByTestId('household-reset-time') as HTMLInputElement
    expect(time.value).toBe('00:00')
    expect(screen.getByTestId('household-reset-tz-select')).toBeInTheDocument()
    expect(screen.queryByTestId('household-edit')).not.toBeInTheDocument()
    expect(screen.queryByTestId('household-save')).not.toBeInTheDocument()
    expect(screen.queryByTestId('household-cancel')).not.toBeInTheDocument()
  })

  it('editing the reset time fires a debounced PATCH {dailyResetTime}', async () => {
    // Real timers + waitFor, matching the non-flaky tz/enabled/policy sibling
    // tests below — no fake-timer juggling. The previous fake-timer version
    // flaked (#1439); the real culprit was a render race, not the clock:
    // `findByTestId` resolves the moment the card's input commits, which under
    // CI load can be BEFORE the card's mount passive effects run. One of those
    // is the value-resync `useEffect(() => setTime(value.dailyResetTime))`. If
    // it fires AFTER our `fireEvent.change`, it reverts the input from '06:00'
    // back to '00:00' and the debounce never commits — PATCH is never sent.
    // `await act` below drains those pending mount effects so they can't
    // clobber the change.
    render(<AdminPage />)
    const time = await screen.findByTestId('household-reset-time') as HTMLInputElement
    // Drain the card's pending mount effects (the value-resync) before
    // interacting, so they run now (a no-op) rather than after our change.
    await act(async () => {})

    // Set the value atomically: typing a `<input type="time">` char-by-char
    // lets the debounce fire on a transient partial value (e.g. '00:59') before
    // the full '06:00' lands. fireEvent.change gives the debounce one final
    // value to latch.
    fireEvent.change(time, { target: { value: '06:00' } })
    // The 500ms debounce hasn't elapsed yet, so no save synchronously.
    expect(api.household.patch).not.toHaveBeenCalled()

    await waitFor(() =>
      expect(api.household.patch).toHaveBeenCalledWith({ dailyResetTime: '06:00' }),
    )
  })

  it('changing the timezone fires PATCH {dailyResetTz}', async () => {
    const user = userEvent.setup()
    render(<AdminPage />)
    await screen.findByTestId('household-reset-time')

    const tzSelect = screen.getByTestId('household-reset-tz-select') as HTMLSelectElement
    await user.selectOptions(tzSelect, 'America/New_York')

    await waitFor(() =>
      expect(api.household.patch).toHaveBeenCalledWith({ dailyResetTz: 'America/New_York' }),
    )
  })

  it('PATCH failure surfaces error + Retry; dirty value retained; Retry re-sends', async () => {
    (api.household.patch as unknown as ReturnType<typeof vi.fn>).mockRejectedValueOnce(
      new Error('boom from server'),
    )
    // Real timers + waitFor + an `act` drain of the card's mount effects — see
    // the reset-time test for why the fake-timer version flaked (#1439).
    const user = userEvent.setup()
    render(<AdminPage />)
    const time = await screen.findByTestId('household-reset-time') as HTMLInputElement
    await act(async () => {})

    fireEvent.change(time, { target: { value: '06:00' } })

    const status = screen.getByTestId('household-save-status')
    await waitFor(() => expect(status).toHaveAttribute('data-status', 'error'))
    expect((screen.getByTestId('household-reset-time') as HTMLInputElement).value).toBe('06:00')

    await user.click(screen.getByTestId('household-save-status-retry'))
    await waitFor(() => expect(api.household.patch).toHaveBeenCalledTimes(2))
  })
})

describe('AdminPage — heartbeat filter autosave (#1002)', () => {
  it('renders live enabled + bytes controls, no Save button', async () => {
    render(<AdminPage />)
    const enabled = await screen.findByTestId('heartbeat-filter-enabled') as HTMLInputElement
    const bytes = screen.getByTestId('heartbeat-filter-bytes') as HTMLInputElement
    expect(enabled.checked).toBe(false)
    expect(bytes.value).toBe('2048')
    expect(screen.queryByTestId('heartbeat-filter-save')).not.toBeInTheDocument()
  })

  it('toggling enabled fires PATCH {heartbeatFilter:{enabled}}', async () => {
    const user = userEvent.setup()
    render(<AdminPage />)
    await screen.findByTestId('heartbeat-filter-enabled')

    await user.click(screen.getByTestId('heartbeat-filter-enabled'))

    await waitFor(() =>
      expect(api.household.patch).toHaveBeenCalledWith({ heartbeatFilter: { enabled: true } }),
    )
  })

  it('editing the bytes threshold fires PATCH {heartbeatFilter:{bytesThreshold}}', async () => {
    // Real timers + waitFor + an `act` drain of the card's mount effects — see
    // the reset-time test for why the fake-timer version flaked (#1439).
    render(<AdminPage />)
    const bytes = await screen.findByTestId('heartbeat-filter-bytes') as HTMLInputElement
    await act(async () => {})

    fireEvent.change(bytes, { target: { value: '8192' } })
    expect(api.household.patch).not.toHaveBeenCalled()

    await waitFor(() =>
      expect(api.household.patch).toHaveBeenCalledWith({
        heartbeatFilter: { bytesThreshold: 8192 },
      }),
    )
  })
})

describe('AdminPage — unmanaged-MAC policy autosave (#1002)', () => {
  it('selecting block fires PATCH {unmanagedMacPolicy:{policy}}', async () => {
    const user = userEvent.setup()
    render(<AdminPage />)
    await screen.findByTestId('unmanaged-mac-policy-block')

    await user.click(screen.getByTestId('unmanaged-mac-policy-block'))

    await waitFor(() =>
      expect(api.household.patch).toHaveBeenCalledWith({
        unmanagedMacPolicy: { policy: 'block' },
      }),
    )
  })

  it('block-page checkbox is disabled while policy is allow', async () => {
    render(<AdminPage />)
    const card = await screen.findByTestId('unmanaged-mac-policy-card')
    const blockPage = within(card).getByTestId('unmanaged-mac-policy-block-page') as HTMLInputElement
    expect(blockPage.disabled).toBe(true)
  })
})

describe('AdminPage — block-encrypted-DNS toggle (#1913)', () => {
  it('renders the toggle reflecting the stored setting (off by default)', async () => {
    render(<AdminPage />)
    const toggle = await screen.findByTestId('block-encrypted-dns-enabled') as HTMLInputElement
    expect(toggle.checked).toBe(false)
  })

  it('toggling on fires PATCH {blockEncryptedDns:true}', async () => {
    const user = userEvent.setup()
    render(<AdminPage />)
    await screen.findByTestId('block-encrypted-dns-enabled')

    await user.click(screen.getByTestId('block-encrypted-dns-enabled'))

    await waitFor(() =>
      expect(api.household.patch).toHaveBeenCalledWith({ blockEncryptedDns: true }),
    )
  })

  it('reflects a stored true after the round-trip (persists via the API client)', async () => {
    const user = userEvent.setup()
    render(<AdminPage />)
    const toggle = await screen.findByTestId('block-encrypted-dns-enabled') as HTMLInputElement

    await user.click(toggle)

    // The card re-reads the server-of-record after autosave; the persisted
    // value must come back checked.
    await waitFor(() =>
      expect((screen.getByTestId('block-encrypted-dns-enabled') as HTMLInputElement).checked).toBe(true),
    )
  })
})

describe('AdminPage — ambient anchor-gate toggle (#2077)', () => {
  it('renders the toggle reflecting the stored setting (off by default)', async () => {
    render(<AdminPage />)
    const toggle = await screen.findByTestId('ambient-gate-enabled') as HTMLInputElement
    expect(toggle.checked).toBe(false)
  })

  it('toggling on fires PATCH {ambientGateEnabled:true} and persists', async () => {
    const user = userEvent.setup()
    render(<AdminPage />)
    await screen.findByTestId('ambient-gate-enabled')

    await user.click(screen.getByTestId('ambient-gate-enabled'))

    await waitFor(() =>
      expect(api.household.patch).toHaveBeenCalledWith({ ambientGateEnabled: true }),
    )
    await waitFor(() =>
      expect((screen.getByTestId('ambient-gate-enabled') as HTMLInputElement).checked).toBe(true),
    )
  })
})

describe('AdminPage — disable-enforcement escape hatch (#2382)', () => {
  it('renders the toggle reflecting the stored flag (off by default)', async () => {
    render(<AdminPage />)
    const toggle = await screen.findByTestId('disable-enforcement-toggle') as HTMLInputElement
    expect(toggle.checked).toBe(false)
  })

  it('warns that it needs the server up and points to the on-router hatch', async () => {
    render(<AdminPage />)
    await screen.findByTestId('disable-enforcement-toggle')
    const card = screen.getByTestId('disable-enforcement-card')
    expect(card.textContent).toMatch(/only while the WifiHaven server is reachable/i)
    expect(card.textContent).toMatch(/on-router escape hatch/i)
  })

  it('toggling on calls setEnforcement(true) and reflects the persisted flag', async () => {
    const user = userEvent.setup()
    render(<AdminPage />)
    await screen.findByTestId('disable-enforcement-toggle')

    await user.click(screen.getByTestId('disable-enforcement-toggle'))

    await waitFor(() =>
      expect(api.household.setEnforcement).toHaveBeenCalledWith(true),
    )
    await waitFor(() =>
      expect((screen.getByTestId('disable-enforcement-toggle') as HTMLInputElement).checked).toBe(true),
    )
  })

  it('does NOT fire a spurious write when it loads an already-disabled household', async () => {
    // The card loads its flag asynchronously, so the async "false → loaded true" transition must not
    // be mistaken for a user edit (which would PUT + invalidate the snapshot on every page visit).
    vi.mocked(api.household.getEnforcement).mockResolvedValue({ enforcementDisabled: true })
    render(<AdminPage />)
    const toggle = await screen.findByTestId('disable-enforcement-toggle') as HTMLInputElement
    expect(toggle.checked).toBe(true)
    // Well past the 500ms debounce: no save should have been scheduled from the load.
    await new Promise(r => setTimeout(r, 800))
    expect(api.household.setEnforcement).not.toHaveBeenCalled()
  })
})

// #2522 — the kill-switch is the one ACCOUNT control on an otherwise policy-editing page. An adult
// gets the settings cards and does NOT get the escape hatch (PUT /api/household/enforcement is
// still `requireAdmin`, so rendering it would be an affordance that 403s).
describe('AdminPage — capability split (#2522)', () => {
  it('shows the household settings cards to an adult', async () => {
    mockAuth = { isAdmin: false, isWriter: true }
    render(<AdminPage />)
    expect(await screen.findByTestId('household-reset-time')).toBeInTheDocument()
    expect(screen.getByTestId('household-reset-tz-select')).toBeInTheDocument()
  })

  it('denies an adult the escape-hatch TOGGLE (PUT is requireAdmin)', async () => {
    mockAuth = { isAdmin: false, isWriter: true }
    render(<AdminPage />)
    await screen.findByTestId('disable-enforcement-status')
    expect(screen.queryByTestId('disable-enforcement-toggle')).not.toBeInTheDocument()
    expect(screen.queryByTestId('disable-enforcement-save-status')).not.toBeInTheDocument()
  })

  // The READ is `requireAuth` on purpose: "nothing is being blocked right now" is the most
  // confusing state to debug unaided, so an adult is TOLD even though they cannot change it.
  it('still tells an adult when all blocking is off', async () => {
    mockAuth = { isAdmin: false, isWriter: true }
    vi.mocked(api.household.getEnforcement).mockResolvedValue({ enforcementDisabled: true })
    render(<AdminPage />)
    const line = await screen.findByTestId('disable-enforcement-status')
    expect(line.textContent).toMatch(/All blocking is currently OFF/i)
  })

  it('never writes the hatch on an adult session', async () => {
    mockAuth = { isAdmin: false, isWriter: true }
    render(<AdminPage />)
    await screen.findByTestId('disable-enforcement-status')
    await new Promise(r => setTimeout(r, 800))
    expect(api.household.setEnforcement).not.toHaveBeenCalled()
  })

  // Positive control for the two negatives above. It asserts the TOGGLE, not the card: post-fix
  // the card renders for an adult too, so asserting the card would pass either way.
  it('still hands an admin the escape-hatch toggle, not just the status line', async () => {
    render(<AdminPage />)
    expect(await screen.findByTestId('disable-enforcement-toggle')).toBeInTheDocument()
    expect(screen.getByTestId('disable-enforcement-save-status')).toBeInTheDocument()
    expect(screen.queryByTestId('disable-enforcement-status')).not.toBeInTheDocument()
  })
})
