# Every React Query key carries the household {#query-cache-scoping}

This was added to AGENTS.md's TOC as *"Every React Query key carries the
household"*; see AGENTS.md for the entry.

## The rule {#query-cache-scoping-rule}

**Never write a query key as an inline array. Every key comes from `qk`
(`web/src/api/queryKeys.ts`), and every new key is added to `unscopedKeys`
there.** `qk` is that map wrapped once by `withHouseholdScope`
(`web/src/api/queryScope.ts`), which prefixes the session's household onto
every key it produces.

This covers `queryKey:` on a `useQuery`, and the key argument to
`getQueryData` / `setQueryData` / `invalidateQueries` / `removeQueries` /
`refetchQueries` — including bare *invalidation prefixes* like
`['time','status']`, which are the easiest ones to miss.

## Why {#query-cache-scoping-why}

The SPA creates **one** `QueryClient` for the page (`web/src/main.tsx`), and it
outlives any single session. An unscoped key is therefore a cache entry two
different households share. That is
[#2603](https://github.com/wifihaven/wifihaven/issues/2603): household B's
session rendered household A's devices, profiles and blocked-connection rows,
for as long as the per-endpoint `staleTime` (five minutes on `devices` /
`profiles`, with no self-correction — react-query does not revalidate a query
while it is fresh).

`useAuth` also calls `queryClient.clear()` on login and logout, which is the
primary fix. The key scoping is the defence in depth for a clear that gets
missed, so it only holds if it is *complete* — a single unscoped key is a hole
in it.

## The scope value {#query-cache-scoping-value}

The JWT's `hh` claim (`api/src/auth/AuthService.scala` mints it; `verify`
rejects a token without one, [#2218](https://github.com/wifihaven/wifihaven/issues/2218)).

Not `username`: post-[#2140](https://github.com/wifihaven/wifihaven/issues/2140)
usernames are unique only *within* a household, so two households can each have
an `alice`. Not the `wh_household` cookie: it is a UX hint the server never
reads, and a household with no slug does not have one.

A token that cannot be decoded falls back to a digest **of that token**, not to
a shared `anon`. Collapsing two undecodable sessions onto one scope would
recreate the collision this exists to prevent; `anon` is reserved for having no
token at all.

The digest is a 32-bit FNV-1a, so that separation is probabilistic (~2⁻³²
per pair), not guaranteed. That is acceptable *here* and nowhere else: the
branch only fires for a token `AuthService.verify` rejects anyway, and
`queryClient.clear()` on identity change — not this digest — is the primary
fix. Do not lean on it as if it were a real identity.

## The gate {#query-cache-scoping-gate}

Convention alone did not hold — seven inline keys had accumulated by the time
#2603 was found. The rule is enforced mechanically by `no-restricted-syntax` in
`web/.eslintrc.cjs`, which fails the build on an array-literal `queryKey:` or an
array-literal first argument to `get`/`setQueryData`.

If you are adding a key and the linter fires, the fix is to add it to
`unscopedKeys` — not to disable the rule. That includes the case that looks
like a false positive: composing onto an already-scoped key
(`queryKey: [...qk.devices(), 'extra']`) is flagged, and the answer is to give
that variant its own `unscopedKeys` entry, which is the shape the rule exists
to push you toward.

**The gate catches the literal form; the convention still owns the rest.** A key
built through a variable (`const k = ['foo']; useQuery({ queryKey: k })`) is not
caught, and the rarer accessors (`getQueryState`, `getQueriesData`,
`setQueriesData`) are not in the selector — none of those appear in `web/src`
today. Read the linter as a backstop against the mistake that actually happened
seven times, not as proof that an unscoped key is impossible.
