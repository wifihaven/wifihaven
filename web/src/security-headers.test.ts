import { describe, it, expect } from 'vitest'
// Vite `?raw` import inlines the file's text — no node fs/types needed, and it
// resolves the same `web/public/_headers` Cloudflare Pages ships.
import headers from '../public/_headers?raw'

// #2082/#2115 SSOT test-pin (per docs/process/single-source-of-truth.md — ACCEPT + TEST-PIN):
// the CSP is deliberately duplicated across two deploy targets — the self-hosted API
// (api/src/SecurityHeaders.scala, pinned by api SecurityHeadersSpec) and the Cloudflare
// Pages copy (web/public/_headers) — because the two connect-src values legitimately
// differ (ws:/wss: vs concrete hosts). The directives that MUST agree are pinned here so a
// drop from one copy fails a test instead of riding on a "kept in sync" comment. The
// load-bearing shared directives are default-src 'self' (blocks JWT exfiltration) and the
// img-src app-icon allowlist (#2115: dropping it silently breaks every app favicon).
// The `?raw` import above is the WHOLE file — comment lines included — so asserting host literals
// against it is unsound: a host named only in a `#` comment would satisfy the assertion even after
// being dropped from the policy. Assert against the extracted directive line instead, so every pin
// below can only be satisfied by the CSP that Cloudflare actually serves.
const csp = headers.split('\n').find((l) => l.trim().startsWith('Content-Security-Policy:')) ?? ''
// The img-src directive on its own, so an img-src pin can't be satisfied by the host appearing in
// some other directive (script-src/connect-src).
const imgSrc = csp.split(';').find((d) => d.trim().startsWith('img-src')) ?? ''
// Same scoping rationale for the directives pinned by #2468: a font host allowed only under
// style-src does not let the browser fetch the woff2 file, and the analytics beacon needs both
// script-src (to execute) and connect-src (to POST its report back).
const fontSrc = csp.split(';').find((d) => d.trim().startsWith('font-src')) ?? ''
const scriptSrc = csp.split(';').find((d) => d.trim().startsWith('script-src')) ?? ''
const connectSrc = csp.split(';').find((d) => d.trim().startsWith('connect-src')) ?? ''

describe('web/public/_headers CSP (Cloudflare Pages copy)', () => {
  it('sets a Content-Security-Policy line', () => {
    expect(headers).toContain('Content-Security-Policy:')
    // Guards the extraction the pins below depend on: if this is empty, every `csp` assertion
    // becomes vacuous.
    expect(csp).not.toBe('')
    expect(imgSrc).not.toBe('')
    expect(fontSrc).not.toBe('')
    expect(scriptSrc).not.toBe('')
    expect(connectSrc).not.toBe('')
  })

  it("keeps default-src 'self' — the load-bearing anti-exfiltration directive", () => {
    expect(csp).toContain("default-src 'self'")
  })

  it("keeps frame-ancestors 'none' — anti-clickjacking", () => {
    expect(csp).toContain("frame-ancestors 'none'")
  })

  it('allowlists the app-icon host so app favicons render (#2115)', () => {
    expect(imgSrc).toContain('https://icons.duckduckgo.com')
  })

  // #2240: the Plain support chat widget is blocked until these hosts are allowlisted. Exact hosts
  // from Plain's documented chat-widget CSP (https://help.plain.com/article/chat), UK region — kept
  // in sync with the API copy (api/src/SecurityHeaders.scala, pinned by api SecurityHeadersSpec).
  it('allowlists Plain chat-widget hosts across script/connect/style/img (#2240)', () => {
    expect(csp).toContain('https://chat.cdn-plain.com') // script-src
    expect(csp).toContain('https://chat.uk.plain.com') // connect-src (UK region)
    expect(csp).toContain('https://fonts.googleapis.com') // style-src
    expect(imgSrc).toContain('https://i0.wp.com') // img-src (Gravatar agent avatars)
    expect(csp).toContain(
      'https://prod-uk-services-attachm-attachmentsuploadbucket2-1l2e4906o2asm.s3.eu-west-2.amazonaws.com',
    ) // connect-src attachment-upload bucket
    expect(imgSrc).toContain(
      'https://prod-uk-services-workspac-workspacefilespublicbuck-vs4gjqpqjkh6.s3.amazonaws.com',
    ) // img-src workspace-logo bucket
    expect(imgSrc).toContain(
      'https://prod-uk-services-attachm-attachmentsbucket28b3ccf-uwfssb4vt2us.s3.eu-west-2.amazonaws.com',
    ) // img-src attachment bucket
  })

  // #2418: the SDK renders an agent message's avatar from `actor.avatarUrl`, which for an API_USER
  // machine user is https://static-assets.plain.com/email-images/machine-user.png (and the bundle
  // hard-codes .../avatars/ari-avatar.svg for AI_AGENT). Plain's published CSP list omits this host,
  // so it broke every AI reply's avatar until #2418. Pinned against the extracted img-src directive
  // (not the raw file) so the comment above can't satisfy the assertion on its own.
  it("allowlists Plain's machine-user avatar host so AI replies show an avatar (#2418)", () => {
    expect(imgSrc).toContain('https://static-assets.plain.com')
  })

  // Over-broadening guard: Plain documents no iframe, so the additions must stay exact hosts — no
  // wildcard, and frame-ancestors 'none' unchanged. Mixed targets on purpose: the positive is
  // asserted on the extracted directive (a comment must not be able to satisfy it), while the two
  // negatives are asserted on the whole file so a wildcard can't hide in a comment either.
  // #2468: style-src allowed https://fonts.googleapis.com (the Google Fonts CSS) but there was no
  // font-src at all, so the woff2 files — served from https://fonts.gstatic.com — fell back to
  // default-src 'self' and were blocked. web/index.html loads Inter from Google Fonts, so the SPA
  // rendered in the system fallback font in every environment from #2082 until this pin. Plain's
  // documented widget CSP omits font-src too but its SDK pulls the same Google Fonts CSS, so the
  // one gstatic host covers both.
  it('allowlists the Google Fonts file host so Inter actually loads (#2468)', () => {
    expect(fontSrc).toContain("'self'")
    expect(fontSrc).toContain('https://fonts.gstatic.com')
  })

  // #2468: Cloudflare Pages auto-injects https://static.cloudflareinsights.com/beacon.min.js when
  // Web Analytics is enabled on the project, and the beacon POSTs its report back to the same host —
  // so it needs script-src (to execute) AND connect-src (to report). Without both, Web Analytics is
  // enabled but collects nothing while logging a CSP violation on every page load. This host is
  // deliberately NOT in the API copy (api/src/SecurityHeaders.scala): the self-hosted deploy has no
  // Cloudflare in front of it, so the beacon is never injected there.
  it('allowlists the Cloudflare Web Analytics beacon in script-src and connect-src (#2468)', () => {
    expect(scriptSrc).toContain('https://static.cloudflareinsights.com')
    expect(connectSrc).toContain('https://static.cloudflareinsights.com')
  })

  it('does not introduce wildcards or loosen frame-ancestors for Plain (#2240)', () => {
    expect(csp).toContain("frame-ancestors 'none'")
    expect(headers).not.toContain('*.plain.com')
    expect(headers).not.toContain('https://*')
  })
})
