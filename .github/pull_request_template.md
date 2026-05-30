## Summary

<!-- What does this PR change and why? Keep it short — link issues for detail. -->

## Test plan

<!-- Bulleted checklist of how this was tested. -->

## Migration checklist

- [ ] If this PR adds a Flyway migration, it touches **only** the migration + tests + docs + CI config — no `api/src/**`, `shared/src/**`, router agents, or `web/src/**`. The existing feature-test suite is the back-compat gate; that only works when migrations land alone. Code that uses the new schema goes in a follow-up PR. See [AGENTS.md §migrations-back-compat](../blob/main/AGENTS.md#migrations-back-compat).
