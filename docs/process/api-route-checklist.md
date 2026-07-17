# Adding a new API route

This was originally in AGENTS.md §"Adding a new API route"; see AGENTS.md for the TOC.

## Adding a new API route

1. Add request/response types to `shared/src/Models.scala`
2. Add repo method to the trait in `api/src/db/Database.scala`
3. Implement in `api/src/db/Repos.scala`
4. Add route in the appropriate file under `api/src/routes/`
5. Register route in `api/src/Main.scala`
6. Add tests in `api/test/src/`
7. Add TypeScript API call in `web/src/api/`

If the route (or the service behind it) depends on a **secret / env var /
config value**, that config must **fail loud** when it's missing — a typed
`zio-config` startup validation error, not a route that silently no-ops or
degrades to off. Set the config in every target environment before (or
atomically with) this code. A genuinely-optional dependency is off by an
explicit named flag (logged at startup, surfaced in a health/config endpoint),
never by the absence of a key. See
[`no-dark-by-default.md`](no-dark-by-default.md).
