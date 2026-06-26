// #1969 (SPA-ws S2, design docs/design/spa-websocket.md §2.1/§4.2): the browser
// `WebSocket` constructor can't set an `Authorization` header (§0.2.1), so the
// operator JWT rides a tightly-scoped `wh_ws` cookie set just before connect and
// cleared right after the socket opens. This module owns ONLY that cookie dance —
// the full ws client (subscriptions, reconnect, cache-patching) is S5. There is no
// second auth surface: the same JWT REST already sends as a bearer is what the
// server verifies at upgrade via the existing AuthService (AGENTS.md SSOT).
//
// Cookie scoping IS the security boundary (§4.2), not secrecy:
//   - Path=/api/ws       → sent only on the ws upgrade, never on REST calls (no
//                          broad cookie ⇒ no CSRF surface added to the REST API).
//   - SameSite=Strict    → the browser won't attach it to a cross-site-initiated
//                          upgrade; with the server Origin allowlist (§8) this
//                          closes cross-site websocket hijacking (CSWSH).
//   - Secure             → HTTPS only (http://localhost is a secure context for dev).
//   - Max-Age short (~60s)→ the cookie is meant to live only across the upgrade; the
//                          SPA clears it on open, and the short Max-Age self-expires a
//                          lingering cookie if the tab dies between set and open.
//   - Domain=wifihaven.net in cloud (so it reaches api. from app. — same registrable
//                          domain, first-party) — host-only for the self-hosted
//                          same-origin build (derived from VITE_API_BASE_URL).

// Same env var the REST client keys off (client.ts): empty (same-origin) for the
// JVM-bundled self-hosted build, absolute https://api.wifihaven.net for the cloud
// Cloudflare-Pages build.
const VITE_API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? ''

// ~60s: long enough to cover set→connect, short enough that a lingering cookie
// self-expires quickly if the tab is killed before the open clears it (§4.2).
export const WS_COOKIE_MAX_AGE_SECONDS = 60

/** The cookie name shared with the server's upgrade verify (SpaWsRoutes reads `wh_ws`). */
export const WS_COOKIE_NAME = 'wh_ws'

/**
 * The registrable-domain `Domain=` attribute to scope the cookie across `app.` → `api.` in cloud,
 * or `''` (host-only) for the self-hosted same-origin build. The distinguishing signal is exactly
 * the one the REST client already keys off (§2.1/§8): the cloud build sets `VITE_API_BASE_URL` to an
 * absolute cross-subdomain API URL (so the SPA on `app.wifihaven.net` needs `Domain=wifihaven.net`
 * to reach `api.wifihaven.net`), while the self-hosted build leaves it EMPTY (same origin) — which
 * must be a host-only cookie. So: an empty base ⇒ host-only; a non-empty base ⇒ the registrable
 * domain of that API host (unless it's localhost / an IP / a single-label host, which stay
 * host-only).
 */
export function wsCookieDomain(apiBaseUrl: string = VITE_API_BASE_URL): string {
  // Empty/relative base ⇒ same-origin self-hosted build ⇒ host-only (no Domain attribute).
  if (!apiBaseUrl) return ''
  let host: string
  try {
    host = new URL(apiBaseUrl).hostname
  } catch {
    return ''
  }
  // localhost / IP literals / single-label hosts ⇒ host-only.
  if (host === 'localhost' || /^[0-9.]+$/.test(host) || !host.includes('.')) return ''
  // Scope to the registrable domain: the last two labels (e.g. api.wifihaven.net → wifihaven.net).
  // Good enough for our *.wifihaven.net deployment; multi-part public suffixes (co.uk) aren't used.
  return host.split('.').slice(-2).join('.')
}

interface CookieWriter {
  get cookie(): string
  set cookie(value: string)
}

function defaultCookieJar(): CookieWriter | null {
  return typeof document !== 'undefined' ? (document as unknown as CookieWriter) : null
}

/**
 * Set the tightly-scoped `wh_ws` cookie carrying `jwt`, just before opening the ws (§4.2). Pass a
 * falsy `jwt` and it is a no-op (an unauthenticated SPA never opens the socket). `jar` is injectable
 * for tests.
 */
export function setWsAuthCookie(
  jwt: string | null | undefined,
  jar: CookieWriter | null = defaultCookieJar(),
): void {
  if (!jwt || !jar) return
  const domain = wsCookieDomain()
  const attrs = [
    `${WS_COOKIE_NAME}=${jwt}`,
    'Path=/api/ws',
    'SameSite=Strict',
    'Secure',
    `Max-Age=${WS_COOKIE_MAX_AGE_SECONDS}`,
  ]
  if (domain) attrs.push(`Domain=${domain}`)
  jar.cookie = attrs.join('; ')
}

/**
 * Clear the `wh_ws` cookie immediately after the socket opens (§4.2) — the credential should not sit
 * around once the upgrade is done. Expire it with the SAME Path (+ Domain) it was set with, since a
 * cookie is keyed by (name, Path, Domain). `jar` is injectable for tests.
 */
export function clearWsAuthCookie(jar: CookieWriter | null = defaultCookieJar()): void {
  if (!jar) return
  const domain = wsCookieDomain()
  const attrs = [`${WS_COOKIE_NAME}=`, 'Path=/api/ws', 'SameSite=Strict', 'Secure', 'Max-Age=0']
  if (domain) attrs.push(`Domain=${domain}`)
  jar.cookie = attrs.join('; ')
}
