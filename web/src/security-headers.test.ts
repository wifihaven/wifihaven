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
// style-src does not let the browser fetch the woff2 file. connect-src is extracted to pin the
// analytics beacon host OUT of it — the auto-injected beacon reports same-origin, so it needs
// script-src only; see the two beacon tests below for the sourcing.
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

  it('allowlists the Google Fonts stylesheet host so Inter loads', () => {
    expect(csp.split(';').find((d) => d.trim().startsWith('style-src')) ?? '').toContain(
      'https://fonts.googleapis.com',
    )
  })

  // #2468: style-src allowed https://fonts.googleapis.com (the Google Fonts CSS) but there was no
  // font-src at all, so the woff2 files — served from https://fonts.gstatic.com — fell back to
  // default-src 'self' and were blocked. web/index.html loads Inter from Google Fonts, so the SPA
  // rendered in the system fallback font in every environment from #2082 until this pin.
  it('allowlists the Google Fonts file host so Inter actually loads (#2468)', () => {
    expect(fontSrc).toContain("'self'")
    expect(fontSrc).toContain('https://fonts.gstatic.com')
  })

  // #2468: Cloudflare Pages injects https://static.cloudflareinsights.com/beacon.min.js and
  // script-src blocked it, so Web Analytics was enabled but collecting nothing. This host is
  // deliberately NOT in the API copy (api/src/SecurityHeaders.scala): the self-hosted deploy has no
  // Cloudflare in front of it, so the beacon is never injected there.
  it('allowlists the Cloudflare Web Analytics beacon script (#2468)', () => {
    expect(scriptSrc).toContain('https://static.cloudflareinsights.com')
  })

  // #2468: the beacon host must NOT be in connect-src. Per Cloudflare's Web Analytics FAQ the
  // report goes to `connect-src 'self'` under AUTOMATIC injection (proxied — which is what these
  // Pages custom domains are, infra/cloudflare/main.tf) and only to cloudflareinsights.com under
  // MANUAL embedding; the auto-injected beacon POSTs same-origin to /cdn-cgi/rum, already covered
  // by 'self'. connect-src is load-bearing against JWT exfiltration, so widening it for a request
  // the vendor documents will never be made is a real loosening — pinned negative so it does not
  // creep back in on the "the beacon reports to its own host" assumption (which is wrong).
  it('does not widen connect-src for the beacon — auto-injected reports are same-origin (#2468)', () => {
    expect(connectSrc).not.toContain('cloudflareinsights.com')
    expect(connectSrc).toContain("'self'")
  })

  // #2954: the Plain support widget was removed. Its script/connect/img hosts must be gone from the
  // policy (a lingering third-party script-src is attack surface), and no wildcard may replace them.
  // Asserted on the extracted directives, and on the whole file for the wildcard, so a comment cannot
  // satisfy or hide either.
  it('carries no Plain hosts and no wildcards (#2954)', () => {
    expect(csp).not.toContain('plain.com')
    expect(csp).not.toContain('cdn-plain')
    expect(csp).not.toContain('amazonaws.com')
    expect(scriptSrc).not.toContain('https://chat')
    expect(csp).toContain("frame-ancestors 'none'")
    expect(headers).not.toContain('https://*')
  })
})
