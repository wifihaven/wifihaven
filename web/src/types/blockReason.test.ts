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

  // #1637: when the row carries the connection's destination host, surface
  // it on the bare `extraBlocked` arm too — the dst host is the host that
  // tripped the eb_<host> drop, and renderers already have it on the row.
  it('renders extraBlocked with the dst host when supplied (#1637)', () => {
    expect(
      blockReasonText({ kind: 'extraBlocked' }, { host: 'oauth.google.com' }),
    ).toBe('blocked: host oauth.google.com')
  })

  // #1645: post-rollout the router emits the matched eb_<host> rule's host
  // on per-host drops; the SPA names the matched host so triage can see it
  // at a glance (eb_youtubei_googleapis_com matched, not just "blocked").
  it('renders extraBlockedBy with the matched host (#1645)', () => {
    expect(
      blockReasonText({ kind: 'extraBlockedBy', host: 'youtubei.googleapis.com' }),
    ).toBe('blocked: matched youtubei.googleapis.com')
  })

  // The matched-rule host on `extraBlockedBy` is authoritative — never get
  // shadowed by the row's dst host fallback. (Suffix matches mean the two
  // can differ; the matched rule is what triage needs.)
  it('prefers the matched rule host over the row dst host on extraBlockedBy', () => {
    expect(
      blockReasonText(
        { kind: 'extraBlockedBy', host: 'googleapis.com' },
        { host: 'youtubei.googleapis.com' },
      ),
    ).toBe('blocked: matched googleapis.com')
  })
})
