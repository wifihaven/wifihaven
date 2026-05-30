#!/usr/bin/env bash
# If a PR adds a new Flyway migration, it must not also change production
# source code (Scala, Lua router agent, Python OPNsense agent, frontend
# src/, contract producers). Migration-only PRs guarantee that the existing
# feature-test suite runs the OLD code against the NEW schema via the
# embedded Postgres in TestDatabase — that is the real backward-compatibility
# gate: if image-(N-1)'s code can't drive image-N's schema, the tests fail.
#
# Allowed alongside a new migration:
#   - api/resources/db/migration/**  (additional migration files)
#   - api/test/**, shared/test/**    (tests — these run the old code paths)
#   - **/*.md, .github/**            (docs, CI config)
#   - .claude/**                     (local agent state — never in CI)
#
# Denied (anything else under known production source roots):
#   - api/src/**, shared/src/**, openwrt/files/**, openwrt/*.lua (non-test),
#     opnsense/agent.py and other non-test python, web/src/**,
#     docker/fake-router.py and other contract producers.
#
# Escape hatch: apply the `migration-coupled-justified` label on the PR
# and the workflow skips this job. Use it sparingly, with a written
# justification in the PR body.
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
  case "$f" in
    api/resources/db/migration/*) return 0 ;;
    api/test/*|shared/test/*|openwrt/test/*|opnsense/test/*) return 0 ;;
    .github/*) return 0 ;;
    .claude/*) return 0 ;;
    *.md|*/*.md|*/*/*.md|*/*/*/*.md) return 0 ;;
  esac
  return 1
}

is_denied_prod() {
  local f="$1"
  case "$f" in
    api/src/*|shared/src/*) return 0 ;;
    openwrt/files/*) return 0 ;;
    openwrt/*.lua) return 0 ;;
    opnsense/agent.py|opnsense/wifihaven_*.py) return 0 ;;
    web/src/*) return 0 ;;
    docker/fake-router.py) return 0 ;;
    shared/contract/*) return 0 ;;
  esac
  return 1
}

bad=()
for f in "${changed[@]}"; do
  [[ -z "$f" ]] && continue
  if is_allowed "$f"; then continue; fi
  if is_denied_prod "$f"; then bad+=("$f"); fi
done

if [[ ${#bad[@]} -gt 0 ]]; then
  echo "BLOCKED: this PR adds a migration AND touches production source:" >&2
  printf '  %s\n' "${bad[@]}" >&2
  cat >&2 <<'MSG'

Schema changes must land in their own PR. The reason: the existing
feature-test suite in api/test/** runs against embedded Postgres
loaded with all migrations (including the new one) but the
production code paths are unchanged. That's the real backward-
compatibility gate — if image-(N-1) cannot drive image-N's schema,
the existing tests will fail.

When the migration and the code that uses it land together, the
tests are updated in lock-step with the schema and the back-compat
regression is invisible until production rolls back.

Split this PR:
  1) Migration-only PR (schema change). Existing tests gate it.
  2) Follow-up PR that adapts the code to use the new schema.

If the change genuinely must be atomic (rare — please justify in the
PR body), apply the `migration-coupled-justified` label to skip this
check. See AGENTS.md §migrations-back-compat.
MSG
  exit 1
fi

echo "Migration isolation OK: only test / docs / CI changes accompany the new migration."
