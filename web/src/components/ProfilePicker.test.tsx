import { describe, it, expect, beforeEach, vi } from 'vitest'
import { useState } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
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
  profiles, isLoading = false, allowNone = true, initial = null,
}: {
  profiles: ProfileDetail[]
  isLoading?: boolean
  allowNone?: boolean
  initial?: number | null
}) {
  const [value, setValue] = useState<number | null>(initial)
  const [creating, setCreating] = useState(false)
  return (
    <div>
      <ProfilePicker
        profiles={profiles}
        isLoading={isLoading}
        value={value}
        onChange={setValue}
        allowNone={allowNone}
        selectTestId="pick"
        testIdPrefix="host"
        onCreatingChange={setCreating}
      />
      <p data-testid="value">{value === null ? 'none' : String(value)}</p>
      <p data-testid="creating">{creating ? 'yes' : 'no'}</p>
    </div>
  )
}

// #2607 — the three device-assignment surfaces each hand-rolled this control,
// and the two that could not create a profile had drifted to a different
// null-option label. One component, one label.
describe('ProfilePicker (#2607)', () => {
  it('uses the one settled null-option label', async () => {
    render(withQuery(<Host profiles={[kids]} />))
    expect(await screen.findByRole('option', { name: NO_PROFILE_LABEL })).toBeInTheDocument()
    expect(NO_PROFILE_LABEL).toBe('No profile')
    expect(screen.queryByRole('option', { name: '— No profile —' })).not.toBeInTheDocument()
  })

  it('omits the null option when the caller does not allow it', () => {
    render(withQuery(<Host profiles={[kids]} allowNone={false} initial={1} />))
    expect(screen.queryByRole('option', { name: NO_PROFILE_LABEL })).not.toBeInTheDocument()
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
    expect(screen.getByTestId('creating')).toHaveTextContent('no')

    await user.selectOptions(screen.getByTestId('pick'), '__new__')
    expect(screen.getByTestId('pick')).toBeDisabled()
    expect(screen.getByTestId('creating')).toHaveTextContent('yes')

    await user.click(screen.getByTestId('host-cancel-profile'))
    expect(screen.getByTestId('pick')).toBeEnabled()
    expect(screen.getByTestId('creating')).toHaveTextContent('no')
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
