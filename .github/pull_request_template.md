## Summary

<!-- What does this PR change and why? Keep it short — link issues for detail. -->

## Test plan

<!-- Bulleted checklist of how this was tested. -->

## Migration checklist

- [ ] If this PR adds a Flyway migration, it touches **only** the migration file(s) and Markdown docs — no source, no tests, no CI config, no fixtures. Tests for the new schema shape go in the follow-up PR that adopts it. The existing feature-test suite is the back-compat gate, and it only works when nothing else in the PR can move. See [AGENTS.md §migrations-back-compat](../blob/main/AGENTS.md#migrations-back-compat).
