// #2603 (SECURITY): the household discriminator every React Query key carries.
//
// The QueryClient is a process-wide singleton (`main.tsx`), so a household switch inside
// one page load does NOT get a fresh cache. `useAuth` clears the cache on every identity
// change, which is the actual fix; this module is the defence in depth for a clear that
// gets missed. Every key produced by `qk` (api/queries.ts) is prefixed with the value
// below, so two households cannot collide on a key even with a populated cache.
//
// The scope is derived from the JWT rather than from `username` or the `wh_household`
// cookie: post-#2140 usernames are only unique WITHIN a household (two households can
// both have an `alice`), and the cookie is a UX hint the server never reads and which is
// absent for a household with no slug. The token's `hh` claim is the tenancy key itself
// (AuthService mints it; jwt-scala merges the JwtContent fields into the payload
// alongside sub/iat/exp), and `verify` REJECTS a token that lacks it (#2218), so a
// legitimately-issued token always carries one.

const ANON = 'anon'

/**
 * Non-cryptographic 32-bit FNV-1a, used ONLY for the fallback scope below. It exists so a
 * token we cannot decode still gets its own scope instead of collapsing onto `anon` —
 * collapsing is exactly the collision this module prevents. It is a discriminator, never
 * a credential or an integrity check.
 *
 * Being 32-bit, that separation is probabilistic (~2^-32 per pair), not guaranteed. That
 * is acceptable here because the branch only fires for a token `AuthService.verify`
 * rejects anyway, and `queryClient.clear()` on identity change (useAuth) is the primary
 * fix — this is the backstop's backstop. Don't reuse it where a real identity is needed.
 */
function digest(s: string): string {
  let h = 0x811c9dc5
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i)
    h = Math.imul(h, 0x01000193)
  }
  return (h >>> 0).toString(36)
}

/**
 * The cache scope for a token: `hh:<householdId>` for any token whose payload decodes,
 * `tok:<digest>` for one that doesn't (malformed or a shape we don't recognise — the
 * server rejects those anyway, but they must not all land on one shared scope; see
 * `digest` above for how far that separation actually goes), and `anon` for no token at
 * all.
 */
export function householdScopeOf(token: string | null | undefined): string {
  if (!token) return ANON
  const payload = token.split('.')[1]
  if (!payload) return `tok:${digest(token)}`
  try {
    const json = atob(payload.replace(/-/g, '+').replace(/_/g, '/'))
    const hh = (JSON.parse(json) as { hh?: unknown }).hh
    if (typeof hh === 'number' || typeof hh === 'string') return `hh:${hh}`
  } catch {
    // fall through to the token digest
  }
  return `tok:${digest(token)}`
}

// Memoised on the token STRING, not set by a writer. localStorage stays the one source of
// truth for the session (useAuth writes it, api/client.ts clears it on 401), so there is
// no second piece of state that can desync from it — and no setter a future login path
// could forget to call.
let cachedToken: string | null | undefined
let cachedScope = ANON

/** The current session's cache scope. Cheap enough to call once per query key. */
export function currentQueryScope(): string {
  const token = localStorage.getItem('token')
  if (token !== cachedToken) {
    cachedToken = token
    cachedScope = householdScopeOf(token)
  }
  return cachedScope
}

type KeyFactory = (...args: never[]) => readonly unknown[]

/**
 * Prefix every key factory in `raw` with the current scope. Applied ONCE to the whole `qk`
 * map (api/queries.ts) rather than hand-written per key, so a key added later is scoped by
 * construction and cannot forget (docs/process/single-source-of-truth.md).
 *
 * Prefixing preserves react-query's prefix-match invalidation: `qk.profiles()` is
 * `[scope,'profiles']`, which still prefix-matches `[scope,'profiles','global']`.
 */
export function withHouseholdScope<T extends Record<string, KeyFactory>>(
  raw: T,
): { [K in keyof T]: (...args: Parameters<T[K]>) => readonly unknown[] } {
  const scoped: Record<string, KeyFactory> = {}
  for (const name of Object.keys(raw)) {
    scoped[name] = (...args: never[]) => [currentQueryScope(), ...raw[name](...args)]
  }
  return scoped as { [K in keyof T]: (...args: Parameters<T[K]>) => readonly unknown[] }
}
