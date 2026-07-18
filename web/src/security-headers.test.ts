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
describe('web/public/_headers CSP (Cloudflare Pages copy)', () => {
  it('sets a Content-Security-Policy line', () => {
    expect(headers).toContain('Content-Security-Policy:')
  })

  it("keeps default-src 'self' — the load-bearing anti-exfiltration directive", () => {
    expect(headers).toContain("default-src 'self'")
  })

  it("keeps frame-ancestors 'none' — anti-clickjacking", () => {
    expect(headers).toContain("frame-ancestors 'none'")
  })

  it('allowlists the app-icon host so app favicons render (#2115)', () => {
    expect(headers).toContain('img-src')
    expect(headers).toContain('https://icons.duckduckgo.com')
  })
})
