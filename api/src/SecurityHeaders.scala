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
  val ContentSecurityPolicy: String =
    "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; " +
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
      // 'self', img-src icon host, frame-ancestors 'none' — are pinned on this
      // side by SecurityHeadersSpec and on the _headers side by
      // web/src/security-headers.test.ts, so neither can silently drop them.
      "img-src 'self' data: https://icons.duckduckgo.com; connect-src 'self' ws: wss:; frame-ancestors 'none'; " +
      "base-uri 'self'; object-src 'none'"

  def wrap[Env, Err](routes: Routes[Env, Err]): Routes[Env, Err] =
    routes
      @@ Middleware.addHeader("X-Content-Type-Options", "nosniff")
      @@ Middleware.addHeader("X-Frame-Options", "DENY")
      @@ Middleware.addHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
      @@ Middleware.addHeader("Content-Security-Policy", ContentSecurityPolicy)
}
