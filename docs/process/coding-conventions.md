# Coding conventions

This was originally in AGENTS.md §"Coding conventions"; see AGENTS.md for the TOC.

## Coding conventions

- **Effects**: always `ZIO[R, E, A]`, never throw exceptions. Use `ZIO.attempt` to wrap unsafe code.
- **Errors**: domain errors as sealed traits, not strings. Use `ZIO.fail` with typed errors.
- **Config**: always via `zio-config` + HOCON. Never hardcode values or use `sys.env` directly. Required config **fails loud** — a missing/invalid required key crashes boot with a typed validation error, never a silent no-op; a genuinely-optional feature is off by an explicit named flag, not by an absent secret. See [`no-dark-by-default.md`](no-dark-by-default.md).
- **DB**: all queries in repository classes. No SQL outside of `*RepoLive` implementations.
- **Layers**: wire dependencies via `ZLayer`. No global mutable state.
- **Tests**: use `ZIO Test` spec style. Integration tests use Testcontainers PostgreSQL.
- **Formatting**: `scalafmt` enforced in CI. Run `mill __.reformat` before committing.
- **Imports**: managed by `scalafix OrganizeImports`. Run `mill __.fix` before committing.
