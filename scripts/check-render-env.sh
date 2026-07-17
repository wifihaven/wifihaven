#!/usr/bin/env bash
#
# check-render-env.sh — validate a downloaded Render .env against WifiHaven's
# config-before-code requirements (#2266, docs/process/no-dark-by-default.md).
#
# Usage:
#   scripts/check-render-env.sh <env-file> [staging|prod]
#
#   # download each service's env from Render (Dashboard → service → Environment
#   # → "Download .env"), then:
#   scripts/check-render-env.sh staging.env staging
#   scripts/check-render-env.sh prod.env    prod
#
# What it checks, in four tiers:
#   1. BOOT-CRITICAL     the ONLY hard-fail: a JWT secret that is PRESENT but invalid
#                        (<32 chars, or the shipped placeholder) — that crashes boot
#                        (AppConfig.validateRequired). An ABSENT JWT secret is not a
#                        failure here (generateValue — see tier 2).
#   2. RENDER-MANAGED    keys sourced via `generateValue`/`fromDatabase` in
#                        render.yaml. These are injected at runtime and usually do
#                        NOT appear in a plain .env export, so absence here is
#                        reported as MANAGED (verify in the dashboard), never a hard
#                        failure.
#   3. CLOUD-RECOMMENDED keys a correct CLOUD deploy sets (HTTP_PORT, CORS + ws Origin
#                        allowlists). Advisory only: `docker/entrypoint.sh` DEFAULTS
#                        each of these, and an empty value is the valid self-hosted
#                        single-origin config, so absence WARNs but never fails.
#   4. FOLLOW-UP GATES   the sync:false secrets each deferred #2266 conversion needs
#                        set BEFORE its PR merges. Reported READY / BLOCKED per
#                        conversion; informational (does not change the exit code).
#
# The exit code is non-zero ONLY when tier 1 finds a present-but-invalid JWT secret —
# the one value in a downloaded .env whose badness definitely crashes boot — so you can
# wire it into CI or a pre-deploy check. Tiers 3 + 4 are advisory (WARN / READY-BLOCKED).
#
# NOTE: this reads a *file*; it cannot see the live Render environment. A key shown
# MANAGED/ABSENT here may still be set in Render (generateValue/fromDatabase, or a
# secret the export omitted) — confirm in the dashboard when in doubt.

set -euo pipefail

# ── args ─────────────────────────────────────────────────────────────────────
if [ $# -lt 1 ]; then
  echo "usage: $0 <env-file> [staging|prod]" >&2
  exit 2
fi
ENV_FILE="$1"
ENV_LABEL="${2:-unknown}"

if [ ! -f "$ENV_FILE" ]; then
  echo "error: env file not found: $ENV_FILE" >&2
  exit 2
fi

# ── colour (only if stdout is a tty) ─────────────────────────────────────────
if [ -t 1 ]; then
  R=$'\033[31m'; G=$'\033[32m'; Y=$'\033[33m'; B=$'\033[34m'; DIM=$'\033[2m'; N=$'\033[0m'
else
  R=; G=; Y=; B=; DIM=; N=
fi

fail_count=0

# getval KEY — echo the value of KEY from ENV_FILE (surrounding quotes/space
# stripped); empty string if unset. Matches `KEY=` / `export KEY=` exactly (so
# WIFIHAVEN_DB_HOST does not match WIFIHAVEN_DB_HOSTNAME).
getval() {
  local key="$1" line val
  line=$(grep -E "^[[:space:]]*(export[[:space:]]+)?${key}=" "$ENV_FILE" 2>/dev/null | tail -1 || true)
  [ -z "$line" ] && { printf ''; return; }
  val="${line#*=}"
  # trim leading/trailing whitespace
  val="${val#"${val%%[![:space:]]*}"}"
  val="${val%"${val##*[![:space:]]}"}"
  # strip one layer of surrounding matching quotes
  case "$val" in
    \"*\") val="${val#\"}"; val="${val%\"}" ;;
    \'*\') val="${val#\'}"; val="${val%\'}" ;;
  esac
  printf '%s' "$val"
}

# present KEY — 0 if KEY is set to a non-empty value, else 1.
present() { [ -n "$(getval "$1")" ]; }

line() { printf '%s\n' "────────────────────────────────────────────────────────────────────"; }

echo
printf '%sWifiHaven Render env check%s  file=%s  env=%s%s\n' "$B" "$N" "$ENV_FILE" "$ENV_LABEL" ""
line

# ── 1. BOOT-CRITICAL (the only hard-fail) ────────────────────────────────────
printf '%s1) BOOT-CRITICAL (present-but-invalid crashes boot -> hard fail)%s\n' "$B" "$N"

# JWT secret value constraints. These literals MIRROR the Scala guard in
# JwtConfig (api/src/Config.scala): `MinSecretLength = 32` and the `change-this`
# placeholder reject in JwtConfig.validate — update BOTH together if that changes.
# An ABSENT secret is NOT failed here: it is generateValue in render.yaml and
# usually not in a .env export (reported MANAGED in tier 2 instead).
check_jwt_secret() {
  local key="WIFIHAVEN_JWT_SECRET" v
  v="$(getval "$key")"
  if [ -z "$v" ]; then
    printf '   %s[MANAGED]%s  %-38s %s\n' "$Y" "$N" "$key" \
      "generateValue in render.yaml — verify present in dashboard"
    return
  fi
  local len=${#v}
  if [ "$len" -lt 32 ]; then  # JwtConfig.MinSecretLength
    printf '   %s[BAD]%s     %-38s %s\n' "$R" "$N" "$key" "set but only ${len} chars (needs >= 32) — boot WILL crash"
    fail_count=$((fail_count + 1))
  elif case "$v" in change-this*) true ;; *) false ;; esac; then  # JwtConfig placeholder reject
    printf '   %s[BAD]%s     %-38s %s\n' "$R" "$N" "$key" "still the shipped placeholder — boot WILL crash"
    fail_count=$((fail_count + 1))
  else
    printf '   %s[OK]%s      %-38s %s\n' "$G" "$N" "$key" "${len} chars"
  fi
}

check_jwt_secret

echo

# ── 2. RENDER-MANAGED (generateValue / fromDatabase) ─────────────────────────
printf '%s2) RENDER-MANAGED (injected at runtime — often absent from a .env export)%s\n' "$B" "$N"

check_managed() {
  local key="$1" desc="$2"
  if present "$key"; then
    printf '   %s[OK]%s      %-38s %s\n' "$G" "$N" "$key" "$desc"
  else
    printf '   %s[MANAGED]%s  %-38s %s\n' "$Y" "$N" "$key" "$desc — verify in dashboard"
  fi
}

check_managed WIFIHAVEN_DB_HOST            "fromDatabase"
check_managed WIFIHAVEN_DB_PORT            "fromDatabase"
check_managed WIFIHAVEN_DB_NAME            "fromDatabase"
check_managed WIFIHAVEN_DB_USER            "fromDatabase"
check_managed WIFIHAVEN_DB_PASSWORD       "fromDatabase"
check_managed WIFIHAVEN_METRICS_SCRAPE_TOKEN "generateValue — also the metrics-token follow-up gate"

echo

# ── 3. CLOUD-RECOMMENDED (advisory — never fails) ────────────────────────────
# entrypoint.sh DEFAULTS each of these (HTTP_PORT :=8080; the two origin lists :="")
# and an empty origin list is the valid self-hosted single-origin config, so absence
# is a WARN for a cloud deploy, NOT a boot failure — it does not touch the exit code.
printf '%s3) CLOUD-RECOMMENDED (warn only — entrypoint defaults these; empty is a valid self-hosted config)%s\n' "$B" "$N"

warn_if_empty() {
  local key="$1" desc="$2"
  if present "$key"; then
    printf '   %s[OK]%s      %-38s %s\n' "$G" "$N" "$key" "$desc"
  else
    printf '   %s[WARN]%s    %-38s %s\n' "$Y" "$N" "$key" "$desc — unset; set it on a cloud deploy"
  fi
}

warn_if_empty WIFIHAVEN_HTTP_PORT           "API listen port (defaults 8080)"
warn_if_empty WIFIHAVEN_ALLOWED_ORIGINS     "CORS origins (cloud SPA is cross-origin) (#612)"
warn_if_empty WIFIHAVEN_WS_ALLOWED_ORIGINS  "SPA websocket Origin allowlist (#1969)"

echo

# ── 4. FOLLOW-UP GATES (#2266 deferred conversions) ──────────────────────────
printf '%s4) FOLLOW-UP CONVERSION GATES (set these BEFORE the matching PR merges)%s\n' "$B" "$N"

# report_group "<title>" "<mode: all|any>" KEY1 KEY2 ...
#   all → every key must be set for READY.  any → at least one set (unused here but handy).
report_group() {
  local title="$1" mode="$2"; shift 2
  local missing="" set_any=0 k
  for k in "$@"; do
    if present "$k"; then set_any=1; else missing="$missing $k"; fi
  done
  if { [ "$mode" = all ] && [ -z "$missing" ]; } || { [ "$mode" = any ] && [ "$set_any" = 1 ]; }; then
    printf '   %s[READY]%s   %s\n' "$G" "$N" "$title"
    for k in "$@"; do printf '            %s· %s%s\n' "$DIM" "$k" "$N"; done
  else
    printf '   %s[BLOCKED]%s %s\n' "$Y" "$N" "$title"
    printf '            missing:%s\n' "$missing"
  fi
}

# metrics-token → required-on-cloud (the safe first promotion; token is generateValue).
if present WIFIHAVEN_METRICS_SCRAPE_TOKEN; then
  printf '   %s[READY]%s   metrics.scrapeToken -> required-on-cloud\n' "$G" "$N"
  printf '            %s· WIFIHAVEN_METRICS_SCRAPE_TOKEN%s\n' "$DIM" "$N"
else
  printf '   %s[VERIFY]%s  metrics.scrapeToken -> required-on-cloud\n' "$Y" "$N"
  printf '            WIFIHAVEN_METRICS_SCRAPE_TOKEN not in this file; it is generateValue in\n'
  printf '            render.yaml so it is almost certainly set — confirm in dashboard before promoting.\n'
fi

report_group "email -> named enabled flag (only if email should be ON here)" all \
  WIFIHAVEN_EMAIL_RESEND_API_KEY WIFIHAVEN_EMAIL_FROM_ADDRESS

report_group "stripe -> named enabled flag (only if billing should be ON here)" all \
  WIFIHAVEN_STRIPE_SECRET_KEY WIFIHAVEN_STRIPE_WEBHOOK_SECRET \
  WIFIHAVEN_STRIPE_PRICE_MONTHLY WIFIHAVEN_STRIPE_PRICE_ANNUAL

report_group "support WIDGET -> named enabled flag (needs BOTH)" all \
  WIFIHAVEN_SUPPORT_PLAIN_APP_ID WIFIHAVEN_SUPPORT_PLAIN_IDENTITY_SECRET

report_group "support WRITE API -> named enabled flag" all \
  WIFIHAVEN_SUPPORT_PLAIN_API_KEY

report_group "loki log export -> named logExport.enabled" all \
  GRAFANA_CLOUD_LOKI_URL GRAFANA_CLOUD_LOKI_USER GRAFANA_CLOUD_LOKI_PASSWORD

echo
line

# ── summary ──────────────────────────────────────────────────────────────────
if [ "$fail_count" -eq 0 ]; then
  printf '%sPASS%s  no boot-critical problem in %s (%s)\n' "$G" "$N" "$ENV_FILE" "$ENV_LABEL"
  printf '%sTiers 3 (cloud-recommended) + 4 (follow-up gates) are advisory — a WARN/BLOCKED\n' "$DIM"
  printf 'there does not fail the check; it just flags cloud-hygiene / not-yet-ready items.%s\n' "$N"
  exit 0
else
  printf '%sFAIL%s  %d boot-critical problem(s) in %s (%s) — fix before deploy\n' "$R" "$N" "$fail_count" "$ENV_FILE" "$ENV_LABEL"
  exit 1
fi
