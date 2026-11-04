#!/usr/bin/env bash
# #2960: suspend wifihaven-api-staging between deploys instead of paying for an
# always-on `standard` instance. Render does not bill a suspended service.
#
#   render-staging-power.sh resume    resume (if suspended) and wait for /api/health 200
#   render-staging-power.sh suspend   suspend (if not already)
#   render-staging-power.sh status    print `suspended` / `not_suspended`
#   render-staging-power.sh sweep     suspend if no staging-consuming workflow run is in
#                                     flight (needs `gh` + GITHUB_REPOSITORY)
#
# Requires RENDER_API_KEY (repo secret). There is no fallback: a missing key
# fails the job, so a CD run never proceeds against a staging that was never
# resumed (docs/process/no-dark-by-default.md).
set -euo pipefail

: "${RENDER_API_KEY:?RENDER_API_KEY is required (repo secret, see #2960)}"
# wifihaven-api-staging. Read from GET /v1/services?name=wifihaven-api-staging.
SERVICE_ID="srv-d8549fgjo89c73buvkf0"
STAGING_API_URL="${STAGING_API_URL:-https://api-staging.wifihaven.net}"
# Same 30-minute envelope as deploy-staging's served-sha poll. Overridable for tests.
RESUME_TIMEOUT_SECONDS="${RESUME_TIMEOUT_SECONDS:-1800}"
POLL_SECONDS="${POLL_SECONDS:-10}"
# Every workflow that resumes staging. A reusable gate called from a CD run is part of
# that run, so the gate files only show up here for standalone dispatches.
CONSUMERS=(master-api-ui.yml master-router.yml e2e-vm-gate3a.yml e2e-vm-gate3b.yml)
API="https://api.render.com/v1/services/${SERVICE_ID}"

render() {
  curl --fail --silent --show-error --max-time 30 \
    -H "Authorization: Bearer ${RENDER_API_KEY}" -H "Accept: application/json" "$@"
}

status() { render "$API" | jq -r '.suspended'; }

suspend() {
  if [ "$(status)" = "suspended" ]; then
    echo "Staging already suspended."
  else
    render -X POST "${API}/suspend" >/dev/null
    echo "Staging suspend requested."
  fi
}

resume() {
  if [ "$(status)" = "suspended" ]; then
    render -X POST "${API}/resume" >/dev/null
    echo "Staging resume requested."
  else
    echo "Staging already running."
  fi
  local deadline=$(( $(date +%s) + RESUME_TIMEOUT_SECONDS ))
  while :; do
    if curl --fail --silent --max-time 15 "${STAGING_API_URL}/api/health" >/dev/null; then
      echo "Staging is healthy."
      return 0
    fi
    (( $(date +%s) < deadline )) || break
    echo "Waiting for staging /api/health ..."
    sleep "$POLL_SECONDS"
  done
  echo "Timed out waiting for staging to become healthy after resume." >&2
  return 1
}

# Prints the number of non-completed runs across CONSUMERS. Fails (non-zero, nothing
# printed) if any count can't be read, so a caller can never mistake "unknown" for idle.
in_flight() {
  local total=0 w n
  for w in "${CONSUMERS[@]}"; do
    n="$(gh run list -R "$GITHUB_REPOSITORY" --workflow "$w" -L 20 --json status \
          -q '[.[] | select(.status != "completed")] | length')" || {
      echo "Could not list runs of $w." >&2; return 1; }
    [[ "$n" =~ ^[0-9]+$ ]] || { echo "Unexpected run count for $w: '$n'." >&2; return 1; }
    total=$(( total + n ))
  done
  echo "$total"
}

# Check, suspend, check again. A run created before the re-check is caught by it and
# staging is resumed; a run created after it finds staging suspended and resumes it
# itself. One window remains: a run whose resume step passes between the first check
# and the suspend POST talks to a suspended staging until the re-check resumes it.
# deploy-staging's 30-minute served-sha poll absorbs that.
sweep() {
  : "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required for sweep}"
  local n
  n="$(in_flight)" || { echo "Leaving staging up: in-flight check failed." >&2; return 1; }
  if [ "$n" -gt 0 ]; then
    echo "$n staging-consuming run(s) in flight; leaving staging up."
    return 0
  fi
  suspend
  if ! n="$(in_flight)"; then
    echo "Re-check failed after suspending; resuming staging to be safe." >&2
    resume
    return 1
  fi
  if [ "$n" -gt 0 ]; then
    echo "A run started while suspending; resuming staging."
    resume
  fi
}

case "${1:-}" in
  status) status ;;
  suspend) suspend ;;
  resume) resume ;;
  sweep) sweep ;;
  *)
    echo "usage: $0 resume|suspend|status|sweep" >&2
    exit 2
    ;;
esac
