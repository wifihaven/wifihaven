import { describe, it, expect, beforeEach, vi } from 'vitest'
import { useState } from 'react'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { withQuery } from '@/test/queryWrapper'
import type { ProfileDetail } from '@/types/api'

vi.mock('@/api/client', () => ({
  api: { profiles: { list: vi.fn(), create: vi.fn() } },
}))

import { api } from '@/api/client'
import { ProfilePicker, NO_PROFILE_LABEL } from './ProfilePicker'

function profile(id: number, name: string): ProfileDetail {
  return {
    profile: {
      id, name, blockedCategories: [], paused: false, failureMode: 'block-all',
      crossDeviceOverlapMode: 'sum', pauseMode: 'soft', defaultDeny: false,
    },
    timeLimit: null,
  }
}

const kids = profile(1, 'Kids')

beforeEach(() => {
  vi.resetAllMocks()
  ;(api.profiles.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([])
  ;(api.profiles.create as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 7 })
})

function Host({
  profiles, isLoading = false, isError = false, allowNone = true, initial = null,
}: {
  profiles: ProfileDetail[]
  isLoading?: boolean
  isError?: boolean
  allowNone?: boolean
  initial?: number | null
}) {
  const [value, setValue] = useState<number | null>(initial)
  const [blocked, setBlocked] = useState(false)
  return (
    <div>
      <ProfilePicker
        profiles={profiles}
        isLoading={isLoading}
        isError={isError}
        value={value}
        onChange={setValue}
        allowNone={allowNone}
        selectTestId="pick"
        testIdPrefix="host"
        onCommitBlockedChange={setBlocked}
      />
      <p data-testid="value">{value === null ? 'none' : String(value)}</p>
      <p data-testid="blocked">{blocked ? 'yes' : 'no'}</p>
    </div>
  )
}

// #2607 — the three device-assignment surfaces each hand-rolled this control,
// and the two that could not create a profile had drifted to a different
// null-option label. One component, one label.
describe('ProfilePicker (#2607)', () => {
  it('uses the one settled null-option label', async () => {
    render(withQuery(<Host profiles={[kids]} />))
    const select = await screen.findByTestId('pick')
    expect(within(select).getByRole('option', { name: NO_PROFILE_LABEL })).toBeInTheDocument()
    expect(NO_PROFILE_LABEL).toBe('No profile')
    expect(within(select).queryByRole('option', { name: '— No profile —' })).not.toBeInTheDocument()
  })

  it('omits the null option when the caller does not allow it', async () => {
    render(withQuery(<Host profiles={[kids]} allowNone={false} initial={1} />))
    const select = await screen.findByTestId('pick')
    expect(within(select).queryByRole('option', { name: NO_PROFILE_LABEL })).not.toBeInTheDocument()
  })

  // The empty-household behaviour, settled uniformly: every surface that mounts
  // the picker opens straight into the creator, because an empty select is a
  // dead end wherever it appears.
  it('empty household: opens straight into the creator and assigns what it creates', async () => {
    const user = userEvent.setup()
    render(withQuery(<Host profiles={[]} />))

    const nameInput = await screen.findByTestId('host-new-profile-name')
    // Auto-opened, so focus stays with whatever field the surrounding form put
    // it on — the operator did not ask to be here.
    expect(nameInput).not.toHaveFocus()

    await user.type(nameInput, 'First Kid')
    await user.click(screen.getByTestId('host-create-profile'))

    await waitFor(() => expect(screen.getByTestId('value')).toHaveTextContent('7'))
    expect(api.profiles.create).toHaveBeenCalledWith(
      expect.objectContaining({ name: 'First Kid' }),
    )
  })

  // docs/process/loading-states.md — a pending profile list must not be
  // indistinguishable from a genuinely empty one. It matters more than usual
  // here: "no profiles" is what drives auto-opening the creator, so treating
  // loading as empty would shove every operator into profile creation on load.
  it('does not treat a still-loading profile list as an empty household', async () => {
    render(withQuery(<Host profiles={[]} isLoading />))
    expect(await screen.findByTestId('host-profile-loading')).toBeInTheDocument()
    expect(screen.queryByTestId('host-new-profile')).not.toBeInTheDocument()
    expect(screen.queryByTestId('pick')).not.toBeInTheDocument()
  })

  it('focuses the name input when the operator picked "+ New profile…" themselves', async () => {
    const user = userEvent.setup()
    render(withQuery(<Host profiles={[kids]} />))
    await user.selectOptions(screen.getByTestId('pick'), '__new__')
    expect(await screen.findByTestId('host-new-profile-name')).toHaveFocus()
  })

  it('freezes the select while the creator is open, and reports that outward', async () => {
    const user = userEvent.setup()
    render(withQuery(<Host profiles={[kids]} />))
    expect(screen.getByTestId('blocked')).toHaveTextContent('no')

    await user.selectOptions(screen.getByTestId('pick'), '__new__')
    expect(screen.getByTestId('pick')).toBeDisabled()
    expect(screen.getByTestId('blocked')).toHaveTextContent('yes')

    await user.click(screen.getByTestId('host-cancel-profile'))
    expect(screen.getByTestId('pick')).toBeEnabled()
    expect(screen.getByTestId('blocked')).toHaveTextContent('no')
  })

  // An auto-opened creator must not block a caller whose CURRENT value is
  // already committable. `allowNone` is exactly that declaration: the alert
  // editor can save with no profile (it denies the alert), so being dropped
  // into the creator on an empty household must not take that away.
  it('an auto-opened creator does not block a caller that can commit "no profile"', async () => {
    render(withQuery(<Host profiles={[]} />))
    await screen.findByTestId('host-new-profile-name')
    expect(screen.getByTestId('blocked')).toHaveTextContent('no')
  })

  // …whereas a caller with no null state has nothing valid to commit yet.
  it('an auto-opened creator does block a caller with no null state', async () => {
    render(withQuery(<Host profiles={[]} allowNone={false} />))
    await screen.findByTestId('host-new-profile-name')
    expect(screen.getByTestId('blocked')).toHaveTextContent('yes')
  })

  // docs/process/loading-states.md names three states, and a failed fetch is the
  // third. Without this the empty `profiles` array a failed query leaves behind
  // is read as "zero-profile household" and every surface auto-opens the
  // creator, telling an operator with twenty profiles to create their first.
  it('a failed profile fetch is an error, not an empty household', async () => {
    render(withQuery(<Host profiles={[]} isError />))
    expect(await screen.findByTestId('host-profile-error')).toBeInTheDocument()
    expect(screen.queryByTestId('host-new-profile')).not.toBeInTheDocument()
    expect(screen.queryByTestId('pick')).not.toBeInTheDocument()
  })

  // A failed fetch is one more "nothing to hand over", so it follows the same
  // rule as an auto-opened creator rather than a rule of its own: it blocks a
  // caller with no null state, and leaves a caller that has one alone. The alert
  // editor's deny path must survive a profiles outage.
  it('a failed fetch blocks committing only for a caller with no null state', async () => {
    const { unmount } = render(withQuery(<Host profiles={[]} isError />))
    await screen.findByTestId('host-profile-error')
    expect(screen.getByTestId('blocked')).toHaveTextContent('no')
    unmount()

    render(withQuery(<Host profiles={[]} isError allowNone={false} />))
    await screen.findByTestId('host-profile-error')
    expect(screen.getByTestId('blocked')).toHaveTextContent('yes')
  })

  // #2366 — the select must never paint a selection the caller's state does not
  // hold. With `allowNone={false}` there is no `''` option, so a null value would
  // render as the first profile while the caller still believes nothing is picked.
  it('never displays a selection the caller does not hold', async () => {
    render(withQuery(<Host profiles={[kids]} allowNone={false} />))
    await waitFor(() => expect(screen.getByTestId('value')).toHaveTextContent('1'))
    expect(screen.getByTestId('pick')).toHaveValue('1')
  })

  // On an empty household there is no select to fall back to when `allowNone`
  // is false, so offering Cancel would leave the operator with no way to pick
  // a profile at all.
  it('offers no Cancel when there is nothing to go back to', async () => {
    render(withQuery(<Host profiles={[]} allowNone={false} />))
    await screen.findByTestId('host-new-profile-name')
    expect(screen.queryByTestId('host-cancel-profile')).not.toBeInTheDocument()
  })
})
