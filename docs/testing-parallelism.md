# Test parallelism model

The `Scala Build & Test` job in `.github/workflows/ci.yml` runs `mill
shared.test` then `mill api.test` on an 8-vCPU GitHub-hosted larger
runner (`ubuntu-latest-8-cores`). Both modules are tuned to use the full
runner without any spec-level refactor; this doc explains how, so the next
person to touch CI doesn't re-introduce the "sequential" comment that used
to live on the test step.

## What's parallel

Mill's `TestModule` ships with `testParallelism = true` by default. With a
single test fork group (Mill's default — `testForkGrouping` returns one
inner list of all spec FQCNs), Mill spawns up to `--jobs` **worker JVM
processes** that share a filesystem-backed queue of spec class names. Each
worker pulls one spec, runs it via the ZIO Test SBT framework, then comes
back for the next.

Inside one worker JVM, specs run sequentially through the framework — but
nothing in that JVM races with another worker's JVM. Crucially each
worker has its own `EmbeddedPostgres` singleton (the `@volatile var
pgInstance` in [`api/test/src/TestDatabase.scala`](../api/test/src/TestDatabase.scala)
is JVM-local), so the per-test `DROP SCHEMA public CASCADE` + Flyway
migrate done in `TestDatabase.cleanAndMigrate` only ever touches the PG
that the running worker owns. That's why we can use a shared singleton
without locking even though it would race if multiple specs in one JVM hit
it concurrently.

## Worker count is pinned to runner vCPUs

The `scala` job sets `MILL_JOBS: "8"` and every `mill` invocation in that
job is launched as `mill --jobs "$MILL_JOBS" …`. The default Mill
behaviour is `availableProcessors()`, which works fine, but pinning the
value:

- keeps the runner spec and the worker count in the same diff (if we go
  back to `ubuntu-latest`, both numbers move together), and
- removes ambiguity if a future runner image exposes a different vCPU
  count than expected.

The trade-off in worker count is **JVM + PG boot cost vs spec parallelism**.
Each worker pays:

- ~5–8s of JVM + Mill classpath startup, and
- ~2–5s of `EmbeddedPostgres.start` (initdb + postmaster).

…before it runs its first spec. So workers = vCPUs is roughly the sweet
spot; significantly more workers just multiplies that fixed cost without
buying you more concurrent CPU work.

## Why not in-JVM parallel

The obvious next step — run multiple specs concurrently *inside one JVM*,
sharing a single `EmbeddedPostgres` — is blocked by the fact that
`TestDatabase.cleanAndMigrate` resets the schema on the shared DB. To
make that safe under concurrency every spec would need its own database
(per-spec `CREATE DATABASE` + Flyway) and the bootstrap layer + every
spec's `cleanDb` helper would have to be reworked. That's a ~50-file
mechanical edit for a win that's bounded by the same vCPU ceiling we
already hit with multiple worker JVMs, so we picked the cheaper path
first. If we ever outgrow the per-worker PG boot cost (e.g. on a
much-larger runner where boot dominates), revisit.

## Local UX

`mill shared.test`, `mill api.test`, `mill __.test` all still work
locally with no flags. Mill defaults `--jobs` to the host's CPU count,
which matches the CI behaviour. Pass `--jobs 1` for fully deterministic
output (useful when debugging a test-ordering issue).
