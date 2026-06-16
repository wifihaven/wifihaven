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
