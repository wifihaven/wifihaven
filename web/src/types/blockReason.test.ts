import { describe, it, expect } from 'vitest'
import { blockReasonText } from './blockReason'

describe('blockReasonText', () => {
  // #1637: bare `extraBlocked` covers every per-host eb_<host> drop —
  // household global_blocks, per-profile extraBlocked, and app-driven
  // blocks all collapse to this kind on the conntrack ingest path. Labeling
  // it "(household)" misled operator triage in #1636 (Google OAuth) and
  // #1666 (Khan Academy). The post-#1645 `extraBlockedBy` arm carries the
  // matched host explicitly; the bare arm stays source-agnostic.
  it('renders extraBlocked source-agnostically (#1637)', () => {
    expect(blockReasonText({ kind: 'extraBlocked' })).toBe('blocked (host rule)')
  })

  // #1645: post-rollout the router emits the matched eb_<host> rule's host
  // on per-host drops; the SPA names the matched host so triage can see it
  // at a glance (eb_youtubei_googleapis_com matched, not just "blocked").
  it('renders extraBlockedBy with the matched host (#1645)', () => {
    expect(
      blockReasonText({ kind: 'extraBlockedBy', host: 'youtubei.googleapis.com' }),
    ).toBe('blocked: matched youtubei.googleapis.com')
  })
})
