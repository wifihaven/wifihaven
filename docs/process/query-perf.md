# Query performance — EXPLAIN before merge

This was originally in AGENTS.md §"Validate query performance before merge"; see AGENTS.md for the TOC.

## Validate query performance before merge {#query-explain-before-merge}

**Any PR that introduces or materially changes a SQL query must prove the
query's plan at prod scale before it merges.** This applies to new
Doobie/SQL in `*RepoLive` implementations and to any rollup/analytics
query. It does *not* apply to trivially-bounded lookups by primary key.

This rule exists because the 2026-05-31 prod incident was a missing index.
The [#809](https://github.com/wifihaven/wifihaven/issues/809) byte-rollup
attribution shipped a `LEFT JOIN LATERAL` over `connection_events` with no
`(mac, dest_ip, ts)` index, so each `traffic_reports` row triggered a
full-day sequential scan. It only manifested in prod under real row counts
— pegging managed-Postgres CPU at 100%, exhausting the HikariCP pool, and
crash-looping the API. A single `EXPLAIN (ANALYZE)` against prod-shaped
data at PR time would have caught it; instead it was diagnosed by hand
mid-incident (root-caused in
[#1254](https://github.com/wifihaven/wifihaven/issues/1254)/[#1256](https://github.com/wifihaven/wifihaven/issues/1256),
[#1240](https://github.com/wifihaven/wifihaven/issues/1240)).

Before the PR merges:

1. **Identify the access pattern.** State the new/changed query, the
   tables and columns it filters and joins on, and the expected prod
   row-count growth of those tables. Call out any table on the
   unbounded-growth list (`traffic_reports`, `connection_events`,
   `block_events`, and the rollups derived from them).
2. **Observe real performance (read-only).** Run
   `EXPLAIN (ANALYZE, BUFFERS)` against **prod or a prod-shaped dataset**.
   Watch for sequential scans on large tables, nested-loop lateral joins
   without a supporting index, and runtime that scales with **table size
   rather than result size**.
3. **Add the needed indexes in the SAME PR.** If the plan shows a missing
   index, add the migration that creates it (partial/covering as
   appropriate) and re-run `EXPLAIN (ANALYZE, BUFFERS)` to confirm the
   plan flips to an index scan. (Mind the migration-isolation rule above:
   if the index migration must ship without code, split per the two-PR
   workflow.) Capture the before/after plan summary in the PR description.
4. **Document the expectation in the PR.** Note which tables the query
   touches and why the chosen indexes cover it, so reviewers can
   sanity-check at scale.

### Safety rules for running EXPLAIN against prod

- **Read-only ONLY.** Only `SELECT` / `EXPLAIN` may be run against prod.
  **Never `EXPLAIN ANALYZE` a writing statement against prod** — it
  executes the write. Wrap-in-transaction-and-rollback is **not** an
  acceptable substitute on prod for write paths; use a prod-shaped scratch
  DB for those.
- **Never echo credentials.** Use the existing prod-DSN handling, capture
  the DSN into a shell var, and mask passwords in any printed output
  (matches the live-debug conventions).
- **Bound the work.** `SET statement_timeout='5s';` before a heavy
  `EXPLAIN (ANALYZE, BUFFERS)` so an accidental expensive plan can't itself
  stress prod.
- **Prefer a prod-shaped dataset or replica where one exists.** Run there
  first; fall back to a prod read-only `EXPLAIN` only when the real prod
  data shape is what's being validated.

> Out of scope here: automated query-plan regression testing in CI — a
> heavier, separate idea. This rule is just the agent-workflow guardrail.
