// #2140 (multi-tenant P5-8, design docs/design/multi-tenant-launch.md §4): household-aware login.
// Login no longer derives the household from the username, so the login form has a household field.
// To spare returning users from retyping it, the last successfully-used household **slug** is
// remembered in a long-lived `wh_household` cookie and pre-fills the field.
//
// This cookie is a UX HINT ONLY — never an authentication input. The server authenticates the slug
// the SPA POSTs in the login body (which this cookie merely pre-fills) together with the password;
// it does not read this cookie. Tampering with it can only change which household slug pre-fills the
// form, which still has to pass slug + password server-side. So it is deliberately:
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
 * Remember `slug` (a household slug) for the next login. A blank/empty slug — the default,
 * single-household, self-hosted case — clears the cookie instead of storing an empty value, so the
 * field stays hidden for those installs. `jar` is injectable for tests.
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
