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

  // #2240: the Plain support chat widget is blocked until these hosts are allowlisted. Exact hosts
  // from Plain's documented chat-widget CSP (https://help.plain.com/article/chat), UK region — kept
  // in sync with the API copy (api/src/SecurityHeaders.scala, pinned by api SecurityHeadersSpec).
  it('allowlists Plain chat-widget hosts across script/connect/style/img (#2240)', () => {
    expect(headers).toContain('https://chat.cdn-plain.com') // script-src
    expect(headers).toContain('https://chat.uk.plain.com') // connect-src (UK region)
    expect(headers).toContain('https://fonts.googleapis.com') // style-src
    expect(headers).toContain('https://i0.wp.com') // img-src (Gravatar agent avatars)
    expect(headers).toContain(
      'https://prod-uk-services-attachm-attachmentsuploadbucket2-1l2e4906o2asm.s3.eu-west-2.amazonaws.com',
    ) // connect-src attachment-upload bucket
    expect(headers).toContain(
      'https://prod-uk-services-workspac-workspacefilespublicbuck-vs4gjqpqjkh6.s3.amazonaws.com',
    ) // img-src workspace-logo bucket
    expect(headers).toContain(
      'https://prod-uk-services-attachm-attachmentsbucket28b3ccf-uwfssb4vt2us.s3.eu-west-2.amazonaws.com',
    ) // img-src attachment bucket
  })

  // #2418: the SDK renders an agent message's avatar from `actor.avatarUrl`, which for an API_USER
  // machine user is https://static-assets.plain.com/email-images/machine-user.png (and the bundle
  // hard-codes .../avatars/ari-avatar.svg for AI_AGENT). Plain's published CSP list omits this host,
  // so it broke every AI reply's avatar until #2418. Pinned so it can't be dropped again.
  it("allowlists Plain's machine-user avatar host so AI replies show an avatar (#2418)", () => {
    expect(headers).toContain('https://static-assets.plain.com')
  })

  // Over-broadening guard: Plain documents no iframe, so the additions must stay exact hosts — no
  // wildcard, and frame-ancestors 'none' unchanged.
  it('does not introduce wildcards or loosen frame-ancestors for Plain (#2240)', () => {
    expect(headers).toContain("frame-ancestors 'none'")
    expect(headers).not.toContain('*.plain.com')
    expect(headers).not.toContain('https://*')
  })
})
