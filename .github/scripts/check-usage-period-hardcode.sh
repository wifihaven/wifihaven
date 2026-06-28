#!/usr/bin/env bash
# Guardrail (#2020): the usage-report PERIOD is single-sourced at the agent —
# `openwrt/files/etc/config/wifihaven` → `usage_report_interval` (default 60s,
# configurable) — and the real per-report window rides every `traffic_reports`
# row as `period_start`/`period_end`. The API must NEVER hold its own copy of
# that period. It once did: #846 hardcoded a 5-minute raw window
# (`UsageTraffic.floorTo(Raw) % 300`, `stepOf(Raw) = ofMinutes(5)`) five days
# after the agent moved off 300s (#529), and the API copy silently drifted —
# the #2018 bug (the dashboard `raw` gauge read ~5x smoothed).
#
# This check fails the build if a PR ADDS a hardcoded usage-period literal
# (`% 300`, `* 300`, `/ 300`, a bare `300`, or `ofMinutes(5)` / `ofSeconds(300)`)
# to the narrow set of usage/traffic bucket + rollup code paths where such a
# literal would be re-introducing a fixed period assumption. Derive the window
# from the row span (`UsageTraffic.windowFor` / `period_start`/`period_end`),
# never from a constant.
#
# Scope: only the files below, and only ADDED *code* lines (full-comment lines
# are ignored, so it's fine to mention `% 300` / `ofMinutes(5)` in an
# explanatory comment). Legitimate uses of one of these literals in a scoped
# file (a genuine display-bucket constant, a page size, a timeout) can opt out
# by putting the marker `usage-period-ok` on the same line.
#
# See docs/process/single-source-of-truth.md ("Usage-report period" worked
# example) and AGENTS.md #single-source-of-truth.
set -euo pipefail

BASE="${1:?usage: check-usage-period-hardcode.sh <base-ref>}"

# The bucket / window / rollup derivation paths. A hardcoded period literal in
# any of these is almost certainly a re-introduced fixed-period assumption.
SCOPED_FILES=(
  "api/src/usage/UsageTraffic.scala"
  "api/src/usage/UsageTrafficQuery.scala"
  "api/src/usage/AppUsedRollupService.scala"
  "api/src/usage/TimeUsedRollupJob.scala"
  "api/src/usage/RollupJobs.scala"
  "api/src/db/RollupRepo.scala"
  "api/src/routes/SpaPush.scala"
  "api/src/routes/UsageRoutes.scala"
)

# Patterns that smell like a hardcoded usage period.
#   % 300 / * 300 / / 300  — arithmetic against a 300s grid
#   ofMinutes(5) / ofSeconds(300) — the dead 5-min agent default
#   a bare 300 token        — likely a period in seconds
PERIOD_RE='(%[[:space:]]*300|\*[[:space:]]*300|/[[:space:]]*300|ofMinutes\([[:space:]]*5[[:space:]]*\)|ofSeconds\([[:space:]]*300[[:space:]]*\)|(^|[^0-9])300([^0-9]|$))'

violations=0

for f in "${SCOPED_FILES[@]}"; do
  # Added lines only (three-dot merge-base diff, no context lines).
  while IFS= read -r line; do
    # `git diff --unified=0` prefixes added lines with a single '+'.
    [[ "${line}" == +++* ]] && continue
    [[ "${line}" != +* ]] && continue
    code="${line:1}"

    # Ignore explicit opt-outs.
    if [[ "${code}" == *usage-period-ok* ]]; then
      continue
    fi

    # Ignore full-comment lines (leading // or * or /*). Inline trailing
    # comments on a real code line are still scanned — the code part counts.
    trimmed="${code#"${code%%[![:space:]]*}"}"
    if [[ "${trimmed}" == //* || "${trimmed}" == \** || "${trimmed}" == /\** ]]; then
      continue
    fi

    if [[ "${code}" =~ ${PERIOD_RE} ]]; then
      if [[ ${violations} -eq 0 ]]; then
        echo "ERROR: new hardcoded usage-period literal in a usage/traffic path (#2020)."
        echo "The usage-report period is data-derived (period_start/period_end, driven by the"
        echo "agent's usage_report_interval). Derive the window from the row span"
        echo "(UsageTraffic.windowFor), never from a constant. If this literal is a legitimate"
        echo "display-bucket / page-size / timeout, add the marker 'usage-period-ok' on the line."
        echo
      fi
      echo "  ${f}: +${code}"
      violations=$((violations + 1))
    fi
  done < <(git diff --unified=0 "${BASE}...HEAD" -- "${f}")
done

if [[ ${violations} -gt 0 ]]; then
  echo
  echo "Found ${violations} hardcoded usage-period literal(s). See the message above."
  exit 1
fi

echo "No new hardcoded usage-period literals in the scoped usage/traffic paths."
