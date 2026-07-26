package wifihaven.api

import zio.http.*

/**
 * #2082: response security headers for every API response (JSON routes, and — self-hosted only —
 * the bundled SPA static files served from the same origin, per the SPA-hosting split in
 * AGENTS.md). No HSTS/CSP/X-Frame-Options were set anywhere before this; the SPA stores the JWT in
 * `localStorage`, so an absent CSP would let a future XSS exfiltrate it to any host, and an absent
 * `frame-ancestors`/X-Frame-Options would let the auth-free block page (and the SPA) be
 * clickjacked.
 *
 * The cloud deploy's SPA is served separately from Cloudflare Pages (not this API), so it needs its
 * own `_headers` file — see `web/public/_headers`. The router's own block page is served by uhttpd,
 * not this API — see `openwrt/files/www/wifihaven/handler.lua`.
 *
 * CSP is deliberately permissive on style-src/img-src (`'unsafe-inline'` / `data:`) to avoid
 * breaking the Vite/React/Tailwind build's inline style attributes and embedded icons — the
 * load-bearing protections here are `default-src 'self'` (blocks XSS exfiltration to an
 * attacker-controlled host) and `frame-ancestors 'none'` (blocks clickjacking); tightening
 * script/style further is a defense-in-depth follow-up, not required to close the audit finding.
 */
object SecurityHeaders {
  // #2240: Plain support chat-widget hosts. The widget (web/src/components/SupportWidget.tsx)
  // injects https://chat.cdn-plain.com/index.js; the SDK then loads Google-Fonts CSS, workspace
  // logo / attachment images from Plain-owned S3 buckets + Gravatar avatars, and connects to
  // Plain's UK region + the attachment-upload S3 bucket. These are exactly Plain's documented
  // chat-widget CSP (https://help.plain.com/article/chat); the UK hosts match the API's
  // core-api.uk.plain.com default (SupportConfig.apiBase). Plain documents NO iframe, so no
  // frame-src host is added. Kept in sync with the Cloudflare Pages copy in web/public/_headers.
  private val PlainScriptSrc  = "https://chat.cdn-plain.com"
  private val PlainConnectSrc =
    "https://chat.uk.plain.com " +
      "https://prod-uk-services-attachm-attachmentsuploadbucket2-1l2e4906o2asm.s3.eu-west-2.amazonaws.com"
  private val PlainStyleSrc   = "https://fonts.googleapis.com"
  // #2418: static-assets.plain.com is NOT in Plain's documented list but the SDK hard-depends on it
  // for machine-user/AI-agent avatars: an agent message's `actor.avatarUrl` for an API_USER machine
  // user is https://static-assets.plain.com/email-images/machine-user.png (verified live against the
  // staging workspace via chat.uk.plain.com/chat/getMessages), and the SDK bundle also hard-codes
  // https://static-assets.plain.com/avatars/ari-avatar.svg for AI_AGENT machine users. Without this
  // host every AI reply renders a broken-image placeholder instead of the support avatar.
  private val PlainImgSrc     =
    "https://prod-uk-services-workspac-workspacefilespublicbuck-vs4gjqpqjkh6.s3.amazonaws.com " +
      "https://prod-uk-services-attachm-attachmentsbucket28b3ccf-uwfssb4vt2us.s3.eu-west-2.amazonaws.com " +
      "https://static-assets.plain.com " +
      "https://i0.wp.com"

  val ContentSecurityPolicy: String =
    s"default-src 'self'; script-src 'self' $PlainScriptSrc; style-src 'self' 'unsafe-inline' $PlainStyleSrc; " +
      // connect-src explicitly lists ws:/wss: alongside 'self': the SPA-websocket
      // upgrade (GET /api/ws, web/src/api/wsClient.ts) is same-origin in the
      // self-hosted deploy, but 'self' isn't guaranteed by all browsers to cover a
      // scheme upgrade from http(s) to ws(s) under CSP.
      // img-src allows https://icons.duckduckgo.com: every app template
      // (api/resources/app_templates/*.yml, icon_type: url) renders its favicon from
      // https://icons.duckduckgo.com/ip3/<domain>.ico, so the icon host must be
      // allowlisted or the Apps page shows broken icons (#2115). This CSP is
      // duplicated for the Cloudflare Pages deploy in web/public/_headers
      // (connect-src legitimately differs: this file uses `ws: wss:`, _headers
      // lists concrete hosts). The shared directives that must agree — default-src
      // 'self', img-src icon host, frame-ancestors 'none', and the Plain hosts —
      // are pinned on this side by SecurityHeadersSpec and on the _headers side by
      // web/src/security-headers.test.ts, so neither can silently drop them.
      s"img-src 'self' data: https://icons.duckduckgo.com $PlainImgSrc; connect-src 'self' ws: wss: $PlainConnectSrc; " +
      "frame-ancestors 'none'; base-uri 'self'; object-src 'none'"

  def wrap[Env, Err](routes: Routes[Env, Err]): Routes[Env, Err] =
    routes
      @@ Middleware.addHeader("X-Content-Type-Options", "nosniff")
      @@ Middleware.addHeader("X-Frame-Options", "DENY")
      @@ Middleware.addHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
      @@ Middleware.addHeader("Content-Security-Policy", ContentSecurityPolicy)
}
