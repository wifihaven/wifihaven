// #2164 (multi-tenant P5-8, design docs/design/multi-tenant-launch.md §4): single-identifier login.
// The login form has ONE identifier field (email / slug/username / bare username). For a BARE
// username, the SPA composes `slug/username` client-side using the last successfully-used household
// **slug**, remembered in a long-lived `wh_household` cookie. The cookie is (re)written on every
// successful login from the slug the SERVER resolved (LoginResponse.householdSlug).
//
// This cookie is a UX HINT ONLY — never an authentication input. The server authenticates the single
// identifier the SPA POSTs together with the password; it does NOT read this cookie. Tampering with
// it can only change which slug a bare username is composed against, which still has to resolve to a
// real household and pass the password server-side. So it is deliberately:
//   - readable by JS (not HttpOnly) — the SPA reads it to pre-fill the field.
//   - not `Secure` — self-hosted installs may serve the SPA over plain http on the LAN, and a
//     non-sensitive slug hint should survive there too.
//   - Path=/, SameSite=Lax — a plain first-party preference, sent on normal navigations.
//   - effectively-never-expiring (~10 years) — cookies cannot literally never expire.

/** The cookie name that remembers the last-used household slug. */
export const HOUSEHOLD_COOKIE_NAME = 'wh_household'

/** ~10 years in seconds — "never expires" in practice. */
export const HOUSEHOLD_COOKIE_MAX_AGE_SECONDS = 60 * 60 * 24 * 365 * 10

interface CookieWriter {
  get cookie(): string
  set cookie(value: string)
}

function defaultCookieJar(): CookieWriter | null {
  return typeof document !== 'undefined' ? (document as unknown as CookieWriter) : null
}

/** Read the remembered household slug, or `null` if none is set. `jar` is injectable for tests. */
export function getHouseholdCookie(jar: CookieWriter | null = defaultCookieJar()): string | null {
  if (!jar) return null
  const match = jar.cookie
    .split(';')
    .map(c => c.trim())
    .find(c => c.startsWith(`${HOUSEHOLD_COOKIE_NAME}=`))
  if (!match) return null
  const value = decodeURIComponent(match.slice(HOUSEHOLD_COOKIE_NAME.length + 1))
  return value.length > 0 ? value : null
}

/**
 * Remember `slug` (a household slug) for the next login. A blank/empty slug — a household with no
 * slug yet — clears the cookie instead of storing an empty value, so a bare username stays bare
 * (server resolves the default household). `jar` is injectable for tests.
 */
export function setHouseholdCookie(
  slug: string | null | undefined,
  jar: CookieWriter | null = defaultCookieJar(),
): void {
  if (!jar) return
  const trimmed = (slug ?? '').trim()
  if (!trimmed) {
    // Clear: expire with the same Path the value was set with (a cookie is keyed by name+Path).
    jar.cookie = [`${HOUSEHOLD_COOKIE_NAME}=`, 'Path=/', 'SameSite=Lax', 'Max-Age=0'].join('; ')
    return
  }
  jar.cookie = [
    `${HOUSEHOLD_COOKIE_NAME}=${encodeURIComponent(trimmed)}`,
    'Path=/',
    'SameSite=Lax',
    `Max-Age=${HOUSEHOLD_COOKIE_MAX_AGE_SECONDS}`,
  ].join('; ')
}
