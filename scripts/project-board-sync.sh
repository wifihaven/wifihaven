#!/usr/bin/env bash
#
# project-board-sync.sh — idempotently sync open issues onto WifiHaven Project #2.
#
# For every open issue in wifihaven/wifihaven this script:
#   1. ensures the issue is an item on org Project #2,
#   2. sets its Epic from scripts/project-epics.tsv (issue -> epic name),
#   3. sets its Status from the issue's labels (in-progress -> In Progress, else Todo).
#
# It is safe to re-run: items already on the board are reused, and field writes
# are no-ops when the value already matches.
#
# Requires: gh (authenticated, with the `project` + `read:project` scopes), jq.
#
set -euo pipefail

OWNER="wifihaven"
REPO="wifihaven/wifihaven"
PROJECT_NUMBER=2
PROJECT_ID="PVT_kwDOEQNiBs4BZwFe"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TSV="${SCRIPT_DIR}/project-epics.tsv"

# Single-select option ids are NOT stable across field edits: recreating a field
# (e.g. to add an option) regenerates every option id. So we resolve them by NAME
# at runtime from the live field definition instead of hardcoding them.
echo "==> Resolving field + option ids from Project #${PROJECT_NUMBER}"
FIELDS_JSON="$(gh project field-list "${PROJECT_NUMBER}" --owner "${OWNER}" --format json --limit 100)"

field_id() { echo "${FIELDS_JSON}" | jq -r --arg n "$1" '.fields[]|select(.name==$n)|.id'; }
option_id() { echo "${FIELDS_JSON}" | jq -r --arg f "$1" --arg o "$2" '.fields[]|select(.name==$f)|.options[]|select(.name==$o)|.id'; }

EPIC_FIELD_ID="$(field_id Epic)"
STATUS_FIELD_ID="$(field_id Status)"
STATUS_TODO="$(option_id Status Todo)"
STATUS_IN_PROGRESS="$(option_id Status 'In Progress')"

[[ -n "${EPIC_FIELD_ID}" && -n "${STATUS_FIELD_ID}" && -n "${STATUS_TODO}" && -n "${STATUS_IN_PROGRESS}" ]] \
  || { echo "FATAL: could not resolve Epic/Status field or Status options" >&2; exit 1; }

echo "==> Loading epic assignments from ${TSV}"
declare -A EPIC_OF
while IFS=$'\t' read -r num epic; do
  [[ -z "${num}" || "${num}" == \#* ]] && continue
  EPIC_OF["${num}"]="${epic}"
done < "${TSV}"

echo "==> Fetching open issues (number + labels) from ${REPO}"
ISSUES_JSON="$(gh issue list -R "${REPO}" --state open --limit 300 --json number,url,labels)"

echo "==> Fetching existing project items"
ITEMS_JSON="$(gh project item-list "${PROJECT_NUMBER}" --owner "${OWNER}" --format json --limit 1000)"
declare -A ITEM_OF
while IFS=$'\t' read -r num itemid; do
  [[ -z "${num}" || "${num}" == "null" ]] && continue
  ITEM_OF["${num}"]="${itemid}"
done < <(echo "${ITEMS_JSON}" | jq -r '.items[] | select(.content.number != null) | "\(.content.number)\t\(.id)"')

added=0; skipped=0
declare -A IN_PROGRESS

while IFS=$'\t' read -r num url labels; do
  [[ -z "${num}" ]] && continue

  # Derive status from labels.
  status_opt="${STATUS_TODO}"
  if echo "${labels}" | tr ',' '\n' | grep -qx "in-progress"; then
    status_opt="${STATUS_IN_PROGRESS}"
    IN_PROGRESS["${num}"]=1
  fi

  # Ensure the issue is on the board.
  item_id="${ITEM_OF[${num}]:-}"
  if [[ -z "${item_id}" ]]; then
    item_id="$(gh project item-add "${PROJECT_NUMBER}" --owner "${OWNER}" --url "${url}" --format json | jq -r '.id')"
    ITEM_OF["${num}"]="${item_id}"
    added=$((added+1))
    echo "  + added #${num} -> ${item_id}"
  else
    skipped=$((skipped+1))
  fi

  # Set Epic (option id resolved by name from the live field).
  epic="${EPIC_OF[${num}]:-}"
  if [[ -n "${epic}" ]]; then
    epic_opt="$(option_id Epic "${epic}")"
    if [[ -z "${epic_opt}" ]]; then
      echo "  ! #${num}: unknown epic '${epic}' — skipping epic set" >&2
    else
      gh project item-edit --id "${item_id}" --project-id "${PROJECT_ID}" \
        --field-id "${EPIC_FIELD_ID}" --single-select-option-id "${epic_opt}" >/dev/null
    fi
  else
    echo "  ! #${num}: no epic in TSV" >&2
  fi

  # Set Status.
  gh project item-edit --id "${item_id}" --project-id "${PROJECT_ID}" \
    --field-id "${STATUS_FIELD_ID}" --single-select-option-id "${status_opt}" >/dev/null

done < <(echo "${ISSUES_JSON}" | jq -r '.[] | "\(.number)\t\(.url)\t\([.labels[].name] | join(","))"')

echo "==> Done. added=${added} reused=${skipped} in-progress=${#IN_PROGRESS[@]}"
