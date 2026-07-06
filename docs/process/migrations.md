# Database migrations

This was originally in AGENTS.md §"Database migrations" (and its three subsections); see AGENTS.md for the TOC.

## Database migrations

Migrations live in `api/resources/db/migration/` as `V{n}__{description}.sql`. They run automatically on API startup via Flyway. Never edit existing migrations — always add a new one.

Tests run the same Flyway migrations from `classpath:db/migration` against the embedded Postgres in `TestDatabase`, so there is one source of truth — never maintain a parallel test schema. To change the schema, add a new `V{n}__...sql` migration; do not edit `V1__init.sql` (or any other already-applied migration).

### Schema changes land in their own PR {#migrations-back-compat}

**A PR that adds a Flyway migration may contain only the migration and
documentation (`*.md`) updates.** No source code. **No test changes.**
No CI tweaks. No fixtures, no scripts, no build files. The follow-up
PR — the one that adopts the new schema — carries all of that.

This is the durable defense against the
[#1176](https://github.com/wifihaven/wifihaven/issues/1176) class of
bug (rollback blocked because the applied schema is incompatible with
the previously deployed image). The gate is the existing feature-test
suite:

- `api/test/**` runs embedded Postgres via `TestDatabase`, which
  applies **every** Flyway migration on the classpath, **including the
  new one in this PR**, before any test runs.
- The test fixtures exercise the **existing** production code paths in
  `api/src/**` — image-(N-1)'s code.
- If image-(N-1)'s queries, inserts, or wire shapes can't survive
  DB-at-V<N>, those existing tests fail. That is the gate.

**The gate only works when nothing else in the PR can move.** Test
edits silently bypass it: a fixture author can update the assertions
to the new shape, and the back-compat regression goes undetected. CI
changes can disable the suite entirely. Source changes shift the test
subject. So all of those are forbidden in a migration PR — not just
production source.

Docs are the one exception because they cannot alter execution.

Allowed alongside a new migration:

- `api/resources/db/migration/**.sql` — additional migration files
- `**/*.md` — documentation, including `AGENTS.md`, `CLAUDE.md`,
  READMEs, and anything under `docs/`

Anything else is rejected by
[`check-migration-isolation.sh`](../../.github/scripts/check-migration-isolation.sh).
That includes `api/test/**` and `shared/test/**` — new tests probing
the new schema shape belong in the follow-up PR, not here.

The workflow is two PRs:

1. **PR 1 — schema only.** Just `V<N>__….sql` (plus any doc updates
   that describe the new shape). The existing `api.test` suite is the
   gate: if it passes, the migration is backward-compatible with
   image-(N-1) — modulo coverage gaps, so close those *before* you
   open the migration PR by adding tests in a prior PR that exercises
   the lines the migration will touch.
2. **PR 2 — code adopts the new schema.** Lands after PR 1 is merged
   and deployed. This is where new repo methods, route handlers, wire
   shapes, and the tests that cover them go.

This gate is unconditional — there is no label opt-out (#2098). The
split above is cheap; ship the schema first, then the code.

### Migrations that are fast on dev/staging can be minutes-long on prod {#migrations-prod-data-volume}

**A migration's runtime is dominated by prod data volume, which is
orders of magnitude larger than dev/staging.** The embedded Postgres in
`TestDatabase` is empty; staging carries days of data; prod carries the
full accumulated history. A migration that completes in milliseconds
under test can take **many minutes** on prod — and the API runs Flyway
on startup, on the critical path, so a slow migration blocks the
container from binding its port and **Render fails the deploy at the
15-minute port-scan timeout**.

This actually happened: the V41/V42 partition migrations
([#805](https://github.com/wifihaven/wifihaven/issues/805),
[#806](https://github.com/wifihaven/wifihaven/issues/806)) rewrite and
re-index the two highest-growth tables (`traffic_reports`,
`connection_events`). Their own comments assert *"at current volume
this is seconds"* — true against test/embedded volume, false at prod
scale, where the `ATTACH PARTITION` full-scan validation plus index
rebuilds ran past 15 minutes and the forward-roll deploy timed out
(post-mortem: [#1197](https://github.com/wifihaven/wifihaven/issues/1197)).

**Before writing or reviewing any migration, ask: does this touch a
table whose row count grows unbounded in prod?** The unbounded-growth
tables are the event/usage surfaces — `traffic_reports`,
`connection_events`, `block_events`, and the rollup tables that derive
from them. A schema-metadata-only change (add a column, add an
index on a *small* table, add a lookup table) is safe. A change that
**scans, rewrites, copies, re-indexes, or `ATTACH`/`VALIDATE`s** one of
the growth tables is not — assume minutes, not seconds.

For such a migration:

- **Never trust a "this is fast" comment that was measured on
  dev/staging.** State the assumption explicitly and flag that it is
  unverified at prod scale.
- **Prefer index builds that don't hold the critical path.**
  `CREATE INDEX CONCURRENTLY` avoids the long exclusive lock — but it
  **cannot run inside a transaction**, and Flyway wraps each migration
  in one by default. Splitting it out needs a non-transactional Flyway
  migration (`-- flyway:transactional=false` is not our current setup),
  so treat it as a design decision, not a one-liner.
- **Have an offline plan.** If the migration genuinely must rewrite a
  growth table, coordinate with the operator: it may need to run
  out-of-band (operator-applied, deploy paused) rather than on the
  startup critical path, or the table may need to be truncated/archived
  first if the historical data is expendable. The #1197 unblock was a
  one-time `TRUNCATE traffic_reports, connection_events;` — acceptable
  only because losing ~1 week of history was acceptable; do not assume
  that's always on the table.
- **Estimate against prod row counts, not test fixtures.** Ask the
  operator for the live row count of the affected table before deciding
  the migration is safe to run on the startup path.

### One-shot migration/backfill/seeder code is deleted after it deploys {#delete-deployed-one-shots}

Every application-level (Scala) one-shot migration, backfill, or seeder that
gates on a "did this already?" check lands with a follow-up issue under the
[#1608](https://github.com/wifihaven/wifihaven/issues/1608) umbrella to delete
it. Once the deploy has propagated AND any legacy data the migration consumes
is gone, the Scala code is removed (along with its tests and call site) — the
Flyway SQL migration stays as history, but the application-level migration
code does not. Leaving it in place risks live bugs from re-runs (see #1602,
where a still-invoked seeder resurrected a deleted schedule on every boot)
and bloats startup and test surface.
