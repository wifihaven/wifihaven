import { describe, it, expect } from 'vitest'
import { blockReasonText } from './blockReason'
import type { BlockReason } from './api'

describe('blockReasonText', () => {
  it('renders extraBlocked as the generic household label', () => {
    expect(blockReasonText({ kind: 'extraBlocked' })).toBe('blocked (household)')
  })

  // #1645: post-rollout the router emits the matched eb_<host> rule's host
  // on per-host drops; the SPA names the matched host so triage can see it
  // at a glance (eb_youtubei_googleapis_com matched, not just "blocked").
  it('renders extraBlockedBy with the matched host (#1645)', () => {
    expect(
      blockReasonText({ kind: 'extraBlockedBy', host: 'youtubei.googleapis.com' }),
    ).toBe('blocked: matched youtubei.googleapis.com')
  })

  // #2846: the SPA deploys separately from the API, so the API can emit a reason kind this build
  // has never heard of. It must render the generic label, never undefined (a blank cell) or the
  // raw kind string.
  it('renders a kind this build does not know as plain "blocked" (#2846)', () => {
    const future = { kind: 'notYetKnownReason' } as unknown as BlockReason
    expect(blockReasonText(future)).toBe('blocked')
  })
})
