import { describe, it, expect, beforeEach, vi } from 'vitest'
import { useState } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { withQuery } from '@/test/queryWrapper'

vi.mock('@/api/client', () => ({
  api: { profiles: { list: vi.fn(), create: vi.fn() } },
}))

import { api } from '@/api/client'
import { InlineProfileCreator } from './InlineProfileCreator'

beforeEach(() => {
  vi.resetAllMocks()
  ;(api.profiles.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([])
})

// #2560 — `onPendingChange` is how a caller freezes the controls IT owns (the
// Add-Device modal's select and Cancel) for exactly the window a create is in
// flight. The callback is held in a ref so the unmount reset can depend on [];
// an effect depending on the callback re-runs its cleanup on every identity
// change, so a caller passing an inline arrow would emit a spurious `false` on
// every render. Both current callers happen to pass a stable value, so without
// this test the ref could be reverted to the simpler form unnoticed.
describe('InlineProfileCreator — onPendingChange (#2560)', () => {
  function Host({ onPendingChange }: { onPendingChange: (p: boolean) => void }) {
    const [, force] = useState(0)
    return (
      <div>
        <button onClick={() => force(n => n + 1)}>rerender</button>
        <InlineProfileCreator
          testIdPrefix="t"
          canCancel
          // A BRAND-NEW function identity on every render — the case the ref exists for.
          onPendingChange={p => onPendingChange(p)}
          onCreated={() => {}}
          onCancel={() => {}}
        />
      </div>
    )
  }

  it('does not emit spurious pending changes when the callback identity churns', async () => {
    const calls: boolean[] = []
    const user = userEvent.setup()
    render(withQuery(<Host onPendingChange={p => calls.push(p)} />))

    // Mount reports the initial state once, and reports it as NOT pending —
    // asserting the value, not just the count, so a regression that froze the
    // caller's controls the moment the creator opened would fail here. Waiting
    // on the exact array also avoids latching a baseline mid-mount if the mount
    // phase ever emits more than one call (e.g. under StrictMode).
    await waitFor(() => expect(calls).toEqual([false]))

    await user.click(screen.getByRole('button', { name: 'rerender' }))
    await user.click(screen.getByRole('button', { name: 'rerender' }))
    await user.click(screen.getByRole('button', { name: 'rerender' }))

    // Re-rendering with a fresh callback each time must emit nothing: `pending`
    // never changed. With the effect keyed on the callback it emitted a
    // cleanup-`false` plus a sync-`false` per render instead.
    expect(calls).toEqual([false])
  })

  it('reports the rising and falling edge exactly once across a create', async () => {
    let release: (v: { id: number }) => void = () => {}
    ;(api.profiles.create as unknown as ReturnType<typeof vi.fn>).mockReturnValue(
      new Promise<{ id: number }>(res => { release = res }),
    )
    const calls: boolean[] = []
    const user = userEvent.setup()
    render(withQuery(<Host onPendingChange={p => calls.push(p)} />))
    await waitFor(() => expect(calls).toEqual([false]))

    await user.type(screen.getByTestId('t-new-profile-name'), 'Teens')
    await user.click(screen.getByTestId('t-create-profile'))
    await waitFor(() => expect(calls).toContain(true))

    release({ id: 7 })
    // The mount edge is kept in the expectation rather than cleared, so a
    // missing or wrongly-valued mount report shows up here too.
    await waitFor(() => expect(calls).toEqual([false, true, false]))
  })
})
