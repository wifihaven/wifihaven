#!/usr/bin/env bash
# If a PR adds a new Flyway migration, it must contain ONLY the migration
# plus documentation / agent-direction updates (Markdown). No source code,
# no test changes, no CI config, no anything else.
#
# Why so strict? The real backward-compatibility gate is the existing
# api.test feature suite (TestDatabase + embedded Postgres) — every
# Flyway migration on the classpath is applied before tests run, including
# the new one in this PR, and the existing test fixtures exercise the
# unchanged production code paths in api/src/**. If image-(N-1) cannot
# drive image-N's schema, the tests fail.
#
# Allowing test changes alongside the migration silently bypasses that
# gate: a test author can adapt the fixtures to the new shape and the
# back-compat regression becomes invisible until production rolls back.
# Same for CI config. Only docs (which can never alter execution) are
# safe to ship in the same PR.
#
# Allowed alongside a new migration:
#   - api/resources/db/migration/**   (more migration files)
#   - **/*.md                         (docs, AGENTS.md, CLAUDE.md, READMEs)
#
# Disallowed: anything else — source code, tests, CI workflows, scripts,
# fixtures, contract goldens, build files, configs. Code that adopts the
# new schema (including new tests for it) lands in a follow-up PR.
#
# This gate is unconditional — there is no label opt-out (#2098). Ship the
# migration alone, then the consuming code in a follow-up PR.
#
# See AGENTS.md §migrations-back-compat for the invariant this enforces.
set -euo pipefail

BASE="${1:?usage: check-migration-isolation.sh <base-ref>}"

added_migrations="$(
  git diff --name-status --diff-filter=A "${BASE}...HEAD" \
    -- 'api/resources/db/migration/' \
    | awk -F'\t' '$1=="A" && $2 ~ /\.sql$/ {print $2}'
)"

if [[ -z "${added_migrations}" ]]; then
  echo "No new migrations added; isolation check skipped."
  exit 0
fi

echo "New migration(s) in this PR:"
printf '  %s\n' ${added_migrations}
echo

changed=()
while IFS= read -r line; do
  changed+=("${line}")
done < <(git diff --name-only "${BASE}...HEAD")

is_allowed() {
  local f="$1"
  # Migrations directory: more migrations are fine in the same PR.
  case "$f" in
    api/resources/db/migration/*.sql) return 0 ;;
  esac
  # Markdown: docs + agent-direction (AGENTS.md, CLAUDE.md, READMEs).
  case "$f" in
    *.md) return 0 ;;
  esac
  return 1
}

bad=()
for f in "${changed[@]}"; do
  [[ -z "$f" ]] && continue
  is_allowed "$f" && continue
  bad+=("$f")
done

if [[ ${#bad[@]} -gt 0 ]]; then
  echo "BLOCKED: this PR adds a migration AND touches non-migration / non-docs files:" >&2
  printf '  %s\n' "${bad[@]}" >&2
  cat >&2 <<'MSG'

Migrations must land alone, alongside docs only. The existing
api.test feature suite is the back-compat gate: it applies every
migration on the classpath against embedded Postgres and runs the
unchanged production code from api/src/** against the new schema.
If image-(N-1)'s code cannot drive image-N's schema, those existing
tests fail.

That gate only works when nothing else changes in the PR. Test
edits, CI tweaks, and code edits all silently break it — a fixture
author can update the assertions to the new shape; a CI change can
skip the suite; a code change shifts the test subject.

Split this PR:
  1) Schema-only PR. Migration + docs only. Existing tests gate it.
  2) Follow-up PR with the code that adopts the new schema. New
     tests for the new shape go here, not in the schema PR.

This gate is unconditional — there is no label opt-out. See
AGENTS.md §migrations-back-compat.
MSG
  exit 1
fi

echo "Migration isolation OK: only migration + docs changes in this PR."
