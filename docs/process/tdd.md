# TDD workflow

This was originally in AGENTS.md §"TDD workflow (required for new features and bug fixes)"; see AGENTS.md for the TOC.

## TDD workflow (required for new features and bug fixes)

For any new feature or bug fix, follow test-driven development:

1. **Write the test first.** Before implementing, write the unit and/or feature test(s) that describe the desired behavior. For bugs, the test should fail in the way the bug manifests; for features, it should describe the new behavior.
2. **Validate the test logic before implementing.**
   - **Interactive sessions:** show the test to the user and ask them to confirm it correctly describes the intended behavior.
   - **Autonomous / spawned sessions:** commit the failing test as its own commit before any implementation commit. The red-green progression must be visible in the PR's commit history. The reviewer of the PR is the validator.
3. **Only after the test exists, implement the code** to make it pass.

This applies to both unit tests and feature tests — pick whichever level fits the change (see [docs/process/testing.md](testing.md) — "Testing philosophy").
