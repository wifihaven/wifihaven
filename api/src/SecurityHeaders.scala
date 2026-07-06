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
      "img-src 'self' data:; connect-src 'self' ws: wss:; frame-ancestors 'none'; " +
      "base-uri 'self'; object-src 'none'"

  def wrap[Env, Err](routes: Routes[Env, Err]): Routes[Env, Err] =
    routes
      @@ Middleware.addHeader("X-Content-Type-Options", "nosniff")
      @@ Middleware.addHeader("X-Frame-Options", "DENY")
      @@ Middleware.addHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
      @@ Middleware.addHeader("Content-Security-Policy", ContentSecurityPolicy)
}
