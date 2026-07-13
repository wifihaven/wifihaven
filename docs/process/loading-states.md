# Loading states — never render real-looking data before it loads

This was originally added to AGENTS.md §"Never render real-looking data before
real data has loaded"; see AGENTS.md for the TOC entry.

## Never render real-looking data before real data has loaded {#loading-states}

**A pending/loading query must show a loading state (spinner/skeleton), NOT a
placeholder that looks like a real value** — `0m`, `0`, an empty count, "no
usage", "no traffic", or an empty chart. Rendering `0` while data is still
loading is **indistinguishable from a genuine zero**, so a slow/broken query
reads to the operator (and to you, debugging) as real all-clear data.

This has already bitten us for real. When the per-profile time-status query was
slow — a missing index on the multi-tenant household predicate — the `/profiles`
page rendered **`0m` for every profile**. That is indistinguishable from
"nobody used any time today," so a **performance** problem masqueraded as a
**data-loss** bug and cost real diagnosis time. The trap: the page gated its
loader on the profiles/devices queries but not the *summary* query, and read
`usedMins = summary?.usedMins ?? 0` — coercing a not-yet-loaded summary to `0`.
(Original report #1098; the dashboard variant — LIVE BANDWIDTH flashing
`0 B/s` — is #2041/#2056, fixed with a skeleton the same way.)

### Distinguish three states explicitly in every data view

1. **loading** — the query is pending and there is no prior data. Show a
   spinner or skeleton, never a zero/empty placeholder.
2. **error** — the query failed. Show an error affordance (message, retry),
   **not** a zero. A failed fetch is not "zero events."
3. **loaded** — real data is present. Render it — and a genuine `0` / `0m` /
   empty list here is correct and *should* render as `0m`. A loaded zero and a
   loading zero must look different on screen.

### In react-query terms

- Gate on **`isPending` / `isLoading` and `isError` before reading `data`**.
  Never `query.data ?? 0` / `?? []` and then render that as if it were a real
  value while the query is still pending — that is exactly the coercion that
  produced the `0m` incident.
- `data` is `undefined` while pending. Treat `undefined` as *"don't know yet"*
  (→ loading), not as *"zero"*.
- When a value is refreshed by a WebSocket push on top of an initial GET seed
  (the dashboard pattern), the seed's pending state still gates the first
  paint — don't paint `0`/`—` before the seed arrives (#2017).

### What to reuse

- Full-page gate: `PageLoader` (`web/src/pages/DashboardPage.tsx`) — used by
  most pages via `if (loading) return <PageLoader />`. Correct **only** when
  *every* query the page's first paint depends on is included in `loading`.
  The `/profiles` bug was a `PageLoader` gate that forgot the summary query.
- Inline value/skeleton: the shared `Skeleton` component
  (`web/src/components/Skeleton.tsx`) — a themed `animate-pulse` block for a
  single number/label that loads independently of the page shell (KPI tiles,
  per-row usage). Prefer it over a bare "0".
- Inline spinner + "Loading…": `Spinner` in `TrafficUsagePage` / `LogsPage`
  for streamed/paged lists.

### Regression guard

Any view that renders a query-backed number/count gets a vitest case pinning
the loading→loaded transition: **while the query is pending it shows the
loading state (not `0`/`0m`); once loaded with a genuine `0` it shows the real
`0`/`0m`.** See `web/src/pages/ProfilesPage.test.tsx` for the pattern.
