#!/usr/bin/env bash
# Reject modifications, deletions, or renames of existing Flyway migration
# files. New migrations (added files) are allowed. Editing an applied
# migration causes a checksum mismatch on production startup (see #60).
# Enforced by CI on PRs targeting main (see #61).
#
# Also rejects duplicate version numbers in the resulting tree — if two
# files share the same V<n>__ prefix, Flyway refuses to start. This
# guardrail was added after #883, where two PRs independently grabbed V27.
#
# Emergency escape hatch: set ALLOW_MIGRATION_MUTATIONS=1 in the workflow
# environment to skip the mutation check (the duplicate-version check
# still runs). Added for the #883 dup-V27 rename.
# TODO(#885): remove the escape-hatch wiring after the #883 PR has merged.
set -euo pipefail

BASE="${1:?usage: check-migrations.sh <base-ref>}"
DIR="api/resources/db/migration"

changes="$(git diff --name-status "${BASE}"...HEAD -- "${DIR}")"

bad=0

if [[ -n "${changes}" ]]; then
  if [[ "${ALLOW_MIGRATION_MUTATIONS:-0}" == "1" ]]; then
    echo "ALLOW_MIGRATION_MUTATIONS=1 — skipping migration immutability check."
    echo "Changes detected (not enforced):"
    printf '%s\n' "${changes}"
  else
    while IFS= read -r line; do
      [[ -z "${line}" ]] && continue
      status="$(printf '%s' "${line}" | cut -f1)"
      files="$(printf '%s' "${line}" | cut -f2-)"
      case "${status}" in
        A)
          echo "OK (added):    ${files}"
          ;;
        *)
          echo "BLOCKED (${status}): ${files}" >&2
          bad=1
          ;;
      esac
    done <<< "${changes}"

    if [[ ${bad} -ne 0 ]]; then
      cat >&2 <<'MSG'

Migration files in api/resources/db/migration must not be modified, deleted,
or renamed once merged to main. Editing an applied migration causes a Flyway
checksum mismatch on production startup (see issue #60). Add a new
V{n+1}__... migration instead.

This rule is enforced by .github/scripts/check-migrations.sh (issue #61).
MSG
      exit 1
    fi
  fi
else
  echo "No migration changes."
fi

# Duplicate version detection: scan the resulting tree for V<n>__ files
# that share a version number. Catches cross-PR collisions like #883,
# where two PRs both shipped V27.
dups="$(
  find "${DIR}" -maxdepth 1 -type f -name 'V*__*.sql' -print \
    | sed -E 's#.*/V([0-9]+)__.*#\1#' \
    | sort \
    | uniq -d
)"

if [[ -n "${dups}" ]]; then
  echo >&2
  echo "BLOCKED: duplicate Flyway migration version(s) detected in ${DIR}:" >&2
  while IFS= read -r v; do
    [[ -z "${v}" ]] && continue
    echo "  version ${v}:" >&2
    find "${DIR}" -maxdepth 1 -type f -name "V${v}__*.sql" -print \
      | sed 's/^/    /' >&2
  done <<< "${dups}"
  cat >&2 <<'MSG'

Two migrations share the same V<n>__ prefix. Flyway refuses to start in
this state ("Found more than one migration with version N"). Renumber
one of them to the next available version. See #883.
MSG
  exit 1
fi

echo "No duplicate migration versions."
