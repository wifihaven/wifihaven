#!/usr/bin/env bash
# #2960: suspend wifihaven-api-staging between deploys instead of paying for an
# always-on `standard` instance. Render does not bill a suspended service.
#
#   render-staging-power.sh resume    resume (if suspended) and wait for /api/health 200
#   render-staging-power.sh suspend   suspend (if not already)
#   render-staging-power.sh status    print `suspended` / `not_suspended`
#
# Requires RENDER_API_KEY (repo secret). There is no fallback: a missing key
# fails the job, so a CD run never proceeds against a staging that was never
# resumed (docs/process/no-dark-by-default.md).
set -euo pipefail

: "${RENDER_API_KEY:?RENDER_API_KEY is required (repo secret, see #2960)}"
# wifihaven-api-staging. Read from GET /v1/services?name=wifihaven-api-staging.
SERVICE_ID="srv-d8549fgjo89c73buvkf0"
STAGING_API_URL="${STAGING_API_URL:-https://api-staging.wifihaven.net}"
API="https://api.render.com/v1/services/${SERVICE_ID}"

render() {
  curl --fail --silent --show-error --max-time 30 \
    -H "Authorization: Bearer ${RENDER_API_KEY}" -H "Accept: application/json" "$@"
}

status() { render "$API" | jq -r '.suspended'; }

case "${1:-}" in
  status)
    status
    ;;
  suspend)
    if [ "$(status)" = "suspended" ]; then
      echo "Staging already suspended."
    else
      render -X POST "${API}/suspend" >/dev/null
      echo "Staging suspend requested."
    fi
    ;;
  resume)
    if [ "$(status)" = "suspended" ]; then
      render -X POST "${API}/resume" >/dev/null
      echo "Staging resume requested."
    else
      echo "Staging already running."
    fi
    # Same 30-minute envelope as deploy-staging's served-sha poll.
    deadline=$(( $(date +%s) + 1800 ))
    while (( $(date +%s) < deadline )); do
      if curl --fail --silent --max-time 15 "${STAGING_API_URL}/api/health" >/dev/null; then
        echo "Staging is healthy."
        exit 0
      fi
      echo "Waiting for staging /api/health ..."
      sleep 10
    done
    echo "Timed out waiting for staging to become healthy after resume." >&2
    exit 1
    ;;
  *)
    echo "usage: $0 resume|suspend|status" >&2
    exit 2
    ;;
esac
