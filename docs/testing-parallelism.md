# Test parallelism model

The `Scala Build & Test` job in `.github/workflows/ci.yml` runs the
Scala test suite as fast as we can on the standard 4-vCPU
`ubuntu-latest` runner. Two pieces matter:

1. **Mill's worker-JVM parallelism** spreads the suite across the
   runner's vCPUs.
2. **The template-DB pattern in `TestDatabase`** removes the Flyway
   re-run that used to dominate every test's wall time.

If you change either piece, update both this doc and the inline comment
on the `scala` job — they were stale for months before #1188 (the
"sequential to avoid embedded-pg lock contention" claim was wrong, and
hid a much bigger Flyway-per-test hot spot).

## Mill worker JVMs

`TestModule.testParallelism = true` is mill's default. With the suite in
a single test-fork group (also default), mill spawns `min(--jobs,
num_specs)` **worker JVM processes** that pull spec classes from a
shared filesystem-backed queue. Each worker runs one spec at a time
through the ZIO Test SBT framework, then comes back for the next.

CI pins `MILL_JOBS=4` and passes `--jobs "$MILL_JOBS"` to every `mill`
invocation in the job, matching the runner's 4 vCPUs. Mill defaults to
`availableProcessors()`, which would give the same number — pinning is
just so the runner spec and the worker count travel together if either
moves.

Within a worker JVM specs run **sequentially**. Cross-spec concurrency
only happens across workers. We don't try to run multiple specs
concurrently inside one JVM — see the "Why not in-JVM-multi-spec
parallel" note below.

## Template-DB pattern (the actual hot-path fix)

Before #1188, every test called `cleanAndMigrate`, which did `DROP
SCHEMA public CASCADE` + `Flyway.migrate()` against the singleton's
`postgres` database. With 44 migrations that's ~400–500 ms per test;
across the suite it added roughly two minutes of pure migration work.

The fix is to do that work exactly once per JVM into a Postgres
**template database**, then clone the template for every spec.

The first time any spec touches `TestDatabase` in a JVM:

1. Boot one shared `EmbeddedPostgres` (still the JVM-wide singleton).
2. `CREATE DATABASE wh_template` on it.
3. Run the production Flyway migrations into `wh_template`.
4. Apply the test seed tweaks (household_settings row, admin
   `must_change_password=false`) into `wh_template`.

After that, every `TestDatabase.layer` evaluation:

1. Allocates a unique DB name `wh_test_<n>` (atomic counter).
2. Issues `CREATE DATABASE wh_test_<n> TEMPLATE wh_template`, which
   Postgres satisfies with a fast file-level copy — single-digit ms,
   no Flyway runs.
3. Hands the spec a `TestDb(name, ds)` and a `Transactor` wired to
   `ds`.

`cleanAndMigrate` (called per-test by `cleanDb`) now does `DROP
DATABASE wh_test_<n>` + the same `CREATE DATABASE … TEMPLATE`, after a
defensive `pg_terminate_backend` to kick any stale connections off the
target DB so Postgres will let us drop it. Same shape as the bootstrap
allocation, just resetting an existing slot.

### Cross-spec isolation

Per-spec DB names mean specs that run concurrently (across worker JVMs)
never touch each other's schema, data, or — importantly —
**advisory locks**. Postgres advisory locks are per-database, so a
spec acquiring a lock on `wh_test_42` can't be observed by code running
in `wh_test_43`.

This is what broke two tests during the #1188 refactor —
`RollupRepoSpec` and `RetentionSweepJobSpec` were acquiring their
"holder" lock on `pg.getPostgresDatabase` (the `postgres` admin DB)
while the production code under test was running against the per-spec
DB. The holder lock was invisible to the repo and the assertion
race-passed under the old layout. Both tests now draw the connection
from `TestDb.ds` for the same per-spec DB the repo runs against. **If
you write a new test that acquires an advisory lock directly to verify
contention, draw the connection from `TestDb.ds`, not from
`EmbeddedPostgres.getPostgresDatabase`.**

## Why not in-JVM-multi-spec parallel

The next obvious step — running multiple `*Spec` objects concurrently
inside *one* worker JVM, sharing one `EmbeddedPostgres` — needs a
change to the ZIO Test SBT framework, which today processes its task
list sequentially within a JVM. The template-DB pattern would make it
safe (each spec already has its own DB), but the actual parallel
execution machinery isn't there for free.

The current model — one worker JVM per CPU, each with its own
`EmbeddedPostgres` singleton and pool of per-spec template clones —
caps out at runner vCPUs but is the same ceiling we'd hit from
in-JVM-multi-spec without a much bigger refactor. Revisit if we ever
move to a runner where the per-worker PG boot cost is the long pole.

## Local UX

`mill shared.test`, `mill api.test`, `mill __.test` all still work
locally with no flags. Mill defaults `--jobs` to the host's CPU count.
Pass `--jobs 1` for fully deterministic output (useful when debugging
a test-ordering issue, or when comparing a hot-path change like
template-DB to a known-good single-worker baseline).
