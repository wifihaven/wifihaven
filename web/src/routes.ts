// #2492 — the change-password route, in ONE place. It is referenced by three collaborators that
// must agree exactly, or the first-login reload loop comes back:
//   - App.tsx: the route element, and the RequirePwChanged redirect target
//   - api/client.ts: the "already on the change-password page, don't hard-navigate" guard
//   - LoginPage.tsx: the first-login redirect that sends a forced-change user there
//   - Layout.tsx: the account link in the nav
// A literal copied into each would drift silently — the guard would simply stop matching.
export const ACCOUNT_PATH = '/account'

export const LOGIN_PATH = '/login'

// #2850: where login sends a visitor afterwards. RequireAuth records it in router state; the 401
// handler in api/client.ts (a full-page redirect, which loses router state) puts it in `?next=`.
// Only an in-app path is honoured, so neither can be turned into an open redirect.
export function safeReturnPath(from: unknown): string | null {
  if (typeof from !== 'string') return null
  if (!from.startsWith('/') || from.startsWith('//') || from.startsWith('/\\')) return null
  if (from === LOGIN_PATH || from.startsWith(`${LOGIN_PATH}?`)) return null
  return from
}

/**
 * #2850: the login URL for a session that just died on `pathname + search`. `/` and the
 * change-password page are not worth returning to (login decides the forced-change route itself),
 * so they get the bare login page.
 */
export function loginUrlReturningTo(pathname: string, search = ''): string {
  if (pathname === '/' || pathname.replace(/\/+$/, '') === ACCOUNT_PATH) return LOGIN_PATH
  const next = safeReturnPath(pathname + (search ?? ''))
  return next ? `${LOGIN_PATH}?next=${encodeURIComponent(next)}` : LOGIN_PATH
}
