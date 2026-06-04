#!/usr/bin/env bash
#
# project-board-sync.sh — reconcile the WifiHaven GitHub Projects v2 board to the
# checked-in source of truth, idempotently.
#
# This script is the ONE way the board's Epic taxonomy and per-issue Epic/Status
# assignments are maintained. Ad-hoc edits in the GitHub UI drift; running this
# converges the board back to what the repo says. Two concurrent hand-edits once
# raced and corrupted the board — this script exists so that never recurs.
#
# Sources of truth (in this repo):
#   - scripts/project-epics.tsv  : issue-number <TAB> epic-name  (open issues only)
#   - docs/project-board.md      : the human-readable taxonomy + conventions
#   - CANONICAL_EPICS below      : the exact Epic single-select option set
#
# What it does (all idempotent — safe to re-run):
#   1. Ensure the Epic field's options are exactly CANONICAL_EPICS (only mutates
#      when they differ; option ids are re-fetched at runtime, never hardcoded).
#   2. Delete board items whose underlying issue is closed (board tracks OPEN only).
#   3. Add a board item for any open issue that is missing one.
#   4. Set every open item's Epic (from the TSV) and Status (from issue labels:
#      in-progress -> In Progress; blocked/blocked-on-* -> Blocked; else Todo).
#
# Requirements: gh (authenticated, with project + read:project scopes), jq, python3.
#
# Usage:
#   scripts/project-board-sync.sh            # apply changes
#   DRY_RUN=1 scripts/project-board-sync.sh  # print intended changes, mutate nothing
#
set -euo pipefail

OWNER="${PROJECT_OWNER:-wifihaven}"
PROJECT_NUMBER="${PROJECT_NUMBER:-1}"
REPO="${PROJECT_REPO:-wifihaven/wifihaven}"
DRY_RUN="${DRY_RUN:-0}"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TSV="${HERE}/project-epics.tsv"

# The canonical Epic option set. All GRAY except Cost Reduction (GREEN).
# Keep this list in sync with the taxonomy table in docs/project-board.md.
CANONICAL_EPICS=(
  "Observability & Metrics"
  "Global Policy & Default-Deny"
  "Dashboard & UX"
  "Blocklists & Enforcement"
  "DNS-Bypass & Attribution"
  "CI/CD & Ops"
  "E2E Test Coverage"
  "Router Agent & Release"
  "Block Page"
  "Usage & Screen-Time"
  "Database & Data Lifecycle"
  "API Contract & Type Safety"
  "Notifications & Alerting"
  "Security & Access Control"
  "Schedules"
  "Mobile App"
  "Launch Branding & Naming"
  "Cost Reduction"
)
epic_color() { [[ "$1" == "Cost Reduction" ]] && echo GREEN || echo GRAY; }

log()  { printf '%s\n' "$*" >&2; }
run()  { if [[ "$DRY_RUN" == "1" ]]; then log "DRY: $*"; else "$@"; fi; }

command -v gh   >/dev/null || { log "gh not found"; exit 1; }
command -v jq   >/dev/null || { log "jq not found"; exit 1; }
command -v python3 >/dev/null || { log "python3 not found"; exit 1; }
[[ -f "$TSV" ]] || { log "missing $TSV"; exit 1; }

# ---- 0. Resolve project + field ids at runtime (never hardcode option ids) ----
log "==> Resolving project #$PROJECT_NUMBER for @$OWNER"
PROJECT_JSON="$(gh project view "$PROJECT_NUMBER" --owner "$OWNER" --format json)"
PROJECT_ID="$(jq -r '.id' <<<"$PROJECT_JSON")"
[[ -n "$PROJECT_ID" && "$PROJECT_ID" != "null" ]] || { log "could not resolve project id"; exit 1; }

FIELDS_JSON="$(gh project field-list "$PROJECT_NUMBER" --owner "$OWNER" --format json --limit 100)"
EPIC_FIELD_ID="$(jq -r '.fields[] | select(.name=="Epic") | .id' <<<"$FIELDS_JSON")"
STATUS_FIELD_ID="$(jq -r '.fields[] | select(.name=="Status") | .id' <<<"$FIELDS_JSON")"
[[ -n "$EPIC_FIELD_ID"  && "$EPIC_FIELD_ID"  != "null" ]] || { log "no Epic field";  exit 1; }
[[ -n "$STATUS_FIELD_ID" && "$STATUS_FIELD_ID" != "null" ]] || { log "no Status field"; exit 1; }

# ---- 1. Ensure Epic options == CANONICAL_EPICS (mutate only on drift) ----
current_epics="$(jq -r '.fields[] | select(.name=="Epic") | .options[].name' <<<"$FIELDS_JSON")"
want_epics="$(printf '%s\n' "${CANONICAL_EPICS[@]}")"
if [[ "$current_epics" == "$want_epics" ]]; then
  log "==> Epic options already canonical (${#CANONICAL_EPICS[@]})"
else
  log "==> Epic options drifted — resetting to the canonical ${#CANONICAL_EPICS[@]}"
  # Build the full mutation with the option list embedded (no GraphQL variables —
  # option names are a fixed, quote-free allowlist, so inlining is safe and avoids
  # gh's complex-variable plumbing). GitHub regenerates option ids on this call,
  # which clears existing Epic assignments; step 4 re-asserts them all from the TSV.
  Q="$(CANON="$(printf '%s\x1f' "${CANONICAL_EPICS[@]}" | sed 's/\x1f$//')" \
       FID="$EPIC_FIELD_ID" python3 - <<'PY'
import json,os
names=os.environ["CANON"].split("\x1f")
fid=os.environ["FID"]
def color(n): return "GREEN" if n=="Cost Reduction" else "GRAY"
opts=", ".join('{name:%s, color:%s, description:""}'%(json.dumps(n),color(n)) for n in names)
print('mutation{updateProjectV2Field(input:{fieldId:%s, singleSelectOptions:[%s]}){'
      'projectV2Field{... on ProjectV2SingleSelectField{id}}}}' % (json.dumps(fid),opts))
PY
)"
  run gh api graphql -f query="$Q" >/dev/null
  # re-fetch so option ids reflect the new set
  FIELDS_JSON="$(gh project field-list "$PROJECT_NUMBER" --owner "$OWNER" --format json --limit 100)"
fi

# name -> option id maps
epic_opt_map="$(jq -r '.fields[] | select(.name=="Epic")   | .options[] | "\(.name)\t\(.id)"' <<<"$FIELDS_JSON")"
status_opt_map="$(jq -r '.fields[] | select(.name=="Status") | .options[] | "\(.name)\t\(.id)"' <<<"$FIELDS_JSON")"
epic_id_for()   { awk -F'\t' -v n="$1" '$1==n{print $2; exit}' <<<"$epic_opt_map"; }
status_id_for() { awk -F'\t' -v n="$1" '$1==n{print $2; exit}' <<<"$status_opt_map"; }

# ---- 2/3. Reconcile item set with open issues ----
log "==> Loading open issues + board items"
# Stash the large JSON payloads in temp files and read them from python via paths,
# so issue titles/labels with quotes or backslashes can never break interpolation.
TMPDIR_SYNC="$(mktemp -d)"; trap 'rm -rf "$TMPDIR_SYNC"' EXIT
export OPEN_FILE="$TMPDIR_SYNC/open.json"
export ITEMS_FILE="$TMPDIR_SYNC/items.json"
export TSV REPO
gh issue list -R "$REPO" --state open --limit 400 --json number,labels >"$OPEN_FILE"
gh project item-list "$PROJECT_NUMBER" --owner "$OWNER" --format json --limit 800 >"$ITEMS_FILE"

# closed items to delete: board item whose issue is not in the open set
mapfile -t DELETE_IDS < <(python3 - <<'PY'
import json,os
open_set={i["number"] for i in json.load(open(os.environ["OPEN_FILE"]))}
items=json.load(open(os.environ["ITEMS_FILE"]))["items"]
for it in items:
    c=it.get("content") or {}
    if c.get("type")=="Issue" and c.get("number") not in open_set:
        print(it["id"])
PY
)
log "    items to delete (closed issues): ${#DELETE_IDS[@]}"
for iid in "${DELETE_IDS[@]:-}"; do
  [[ -z "$iid" ]] && continue
  run gh project item-delete "$PROJECT_NUMBER" --owner "$OWNER" --id "$iid" >/dev/null
done

# add missing open issues
mapfile -t ADD_URLS < <(python3 - <<'PY'
import json,os
items=json.load(open(os.environ["ITEMS_FILE"]))["items"]
have={ (it.get("content") or {}).get("number") for it in items if (it.get("content") or {}).get("type")=="Issue" }
repo=os.environ["REPO"]
for i in json.load(open(os.environ["OPEN_FILE"])):
    if i["number"] not in have:
        print(f"https://github.com/{repo}/issues/{i['number']}")
PY
)
log "    open issues missing an item: ${#ADD_URLS[@]}"
for url in "${ADD_URLS[@]:-}"; do
  [[ -z "$url" ]] && continue
  run gh project item-add "$PROJECT_NUMBER" --owner "$OWNER" --url "$url" >/dev/null
done

# refresh items after add/delete so we have item ids for every open issue
if [[ "$DRY_RUN" != "1" && ( ${#DELETE_IDS[@]} -gt 0 || ${#ADD_URLS[@]} -gt 0 ) ]]; then
  gh project item-list "$PROJECT_NUMBER" --owner "$OWNER" --format json --limit 800 >"$ITEMS_FILE"
fi

# ---- 4. Set Epic + Status on every open item ----
log "==> Asserting Epic + Status on open items"
# build issue -> {itemId, status-name} and issue -> epic-name(from TSV)
ASSIGN="$(python3 - <<'PY'
import json,os
open_map={}
for i in json.load(open(os.environ["OPEN_FILE"])):
    labs={l["name"] for l in i["labels"]}
    if "in-progress" in labs: st="In Progress"
    elif any(l=="blocked" or l.startswith("blocked-on") for l in labs): st="Blocked"
    else: st="Todo"
    open_map[i["number"]]=st
epic={}
with open(os.environ["TSV"]) as f:
    next(f)
    for line in f:
        line=line.rstrip("\n")
        if not line: continue
        n,e=line.split("\t"); epic[int(n)]=e
items=json.load(open(os.environ["ITEMS_FILE"]))["items"]
for it in items:
    c=it.get("content") or {}
    if c.get("type")!="Issue": continue
    n=c.get("number")
    if n not in open_map: continue
    print("\t".join([it["id"], str(n), open_map[n], epic.get(n,"")]))
PY
)"

# Status option-id fallback: if 'Blocked' option doesn't exist, fall back to Todo
blocked_id="$(status_id_for "Blocked")"
n_set=0; n_skip=0
while IFS=$'\t' read -r item_id num status_name epic_name; do
  [[ -z "$item_id" ]] && continue
  eid="$(epic_id_for "$epic_name")"
  if [[ -z "$eid" ]]; then
    log "    WARN #$num: no Epic option for '$epic_name' — skipping epic"; n_skip=$((n_skip+1))
  else
    run gh project item-edit --project-id "$PROJECT_ID" --id "$item_id" \
      --field-id "$EPIC_FIELD_ID" --single-select-option-id "$eid" >/dev/null
  fi
  if [[ "$status_name" == "Blocked" && -z "$blocked_id" ]]; then status_name="Todo"; fi
  sid="$(status_id_for "$status_name")"
  if [[ -z "$sid" ]]; then
    log "    WARN #$num: no Status option for '$status_name' — skipping status"
  else
    run gh project item-edit --project-id "$PROJECT_ID" --id "$item_id" \
      --field-id "$STATUS_FIELD_ID" --single-select-option-id "$sid" >/dev/null
  fi
  n_set=$((n_set+1))
done <<<"$ASSIGN"

log "==> Done. items reconciled: $n_set (epic-skips: $n_skip), deleted: ${#DELETE_IDS[@]}, added: ${#ADD_URLS[@]}"
