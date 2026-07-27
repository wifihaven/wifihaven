// #2492 — the change-password route, in ONE place. It is referenced by three collaborators that
// must agree exactly, or the first-login reload loop comes back:
//   - App.tsx: the route element, and the RequirePwChanged redirect target
//   - api/client.ts: the "already on the change-password page, don't hard-navigate" guard
// A literal copied into each would drift silently — the guard would simply stop matching.
export const ACCOUNT_PATH = '/account'
