// #2492 — the single place the client's mirror of the server's `must_change_password`
// flag is stored. It lives in localStorage next to token/username/role because it must
// survive a page reload: the forced-change state is what keeps the /account banner up,
// keeps RequirePwChanged gating the rest of the app, and makes AccountPage route the user
// forward after a successful change. Kept in React state alone (the pre-#2492 shape) any
// reload silently dropped it while the API kept 403ing every other route.
//
// Written by: login (from the server's LoginResponse), and the transport's
// password_change_required 403 handler (client.ts) — the two places the server tells us.
// Cleared by: a successful password change, and logout.
const KEY = 'mustChangePassword'

export function readMustChangePassword(): boolean {
  return localStorage.getItem(KEY) === 'true'
}

export function setMustChangePassword(value: boolean): void {
  if (value) localStorage.setItem(KEY, 'true')
  else localStorage.removeItem(KEY)
}
