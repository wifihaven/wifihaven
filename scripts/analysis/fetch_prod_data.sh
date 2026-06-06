#!/usr/bin/env bash
# Read-only extraction of a week of prod presence data for the replay harness
# (#1467). Writes JSON files into a local data directory (gitignored) that
# presence_replay.py then consumes offline. This script touches prod; the
# analysis does not.
#
#   * READ-ONLY: only SELECTs are issued, under a statement_timeout.
#   * NEVER echoes the DSN or any credential.
#   * Pulls traffic_reports + connection_events for the target devices over the
#     date range, plus the live household_settings (the defaults under test) and
#     device names.
#
# Usage:
#   export PROD_DSN='postgresql://...'            # read-only; out of band
#   ./fetch_prod_data.sh [OUT_DIR] [FROM] [TO]
#
# Defaults: OUT_DIR=./data, FROM/TO span the last full week present in the data.
# The DSN can also be sourced from a Render Postgres connection-info call — see
# README.md. Devices default to the kid devices (the undercount-prone profiles);
# override with KID_MACS='aa:bb:..,cc:dd:..'.

set -euo pipefail

OUT_DIR="${1:-$(dirname "$0")/data}"
FROM="${2:-}"
TO="${3:-}"

if [[ -z "${PROD_DSN:-}" ]]; then
  echo "error: set PROD_DSN (read-only) in the environment, out of band." >&2
  echo "       see README.md for the Render connection-info recipe." >&2
  exit 2
fi

# Default to the kid devices (Kid Mac + the kid iPads) — the request-driven,
# undercount-prone profiles the design (§2b) measured. Override via KID_MACS.
KID_MACS="${KID_MACS:-ca:ef:a1:72:6a:a3,a6:05:9a:63:83:af,04:72:ef:d6:e4:5a,26:74:fc:f9:4e:9e}"
# turn the comma list into a SQL IN-list of quoted literals
IN_LIST="'$(echo "$KID_MACS" | sed "s/,/','/g")'"

mkdir -p "$OUT_DIR"

PSQL=(psql "$PROD_DSN" -v ON_ERROR_STOP=1 --no-psqlrc -At)
TIMEOUT="SET statement_timeout='30s';"

# Resolve the date range to the last 7 days present if not given.
if [[ -z "$FROM" || -z "$TO" ]]; then
  read -r DMIN DMAX < <("${PSQL[@]}" -F' ' -c "${TIMEOUT} SELECT max(date) - 6, max(date) FROM traffic_reports WHERE mac IN (${IN_LIST});")
  FROM="${FROM:-$DMIN}"
  TO="${TO:-$DMAX}"
fi
echo "[fetch] range ${FROM} .. ${TO}; devices: ${KID_MACS}; out: ${OUT_DIR}" >&2

# household_settings — the live defaults under test. Emit as a single JSON object.
"${PSQL[@]}" -c "${TIMEOUT}
  SELECT json_build_object(
    'heartbeat_filter_enabled', heartbeat_filter_enabled,
    'heartbeat_bytes_threshold', heartbeat_bytes_threshold,
    'heartbeat_host_patterns', array_to_json(heartbeat_host_patterns),
    'presence_continuation_seconds', presence_continuation_seconds
  ) FROM household_settings WHERE id = 1;" > "$OUT_DIR/settings.json"

# device names (mac -> name)
"${PSQL[@]}" -c "${TIMEOUT}
  SELECT coalesce(json_object_agg(mac, name), '{}'::json)
  FROM devices WHERE mac IN (${IN_LIST});" > "$OUT_DIR/devices.json"

# traffic_reports — epoch-second times, bytes summed, for §2b + the heartbeat replay.
"${PSQL[@]}" -c "${TIMEOUT}
  SELECT coalesce(json_agg(row_to_json(t)), '[]'::json) FROM (
    SELECT mac,
           date::text AS date,
           extract(epoch FROM period_start)::bigint AS period_start,
           extract(epoch FROM period_end)::bigint   AS period_end,
           host_value AS host,
           host_type,
           (bytes_in + bytes_out)::bigint AS bytes,
           active_seconds
    FROM traffic_reports
    WHERE mac IN (${IN_LIST}) AND date BETWEEN '${FROM}' AND '${TO}'
  ) t;" > "$OUT_DIR/traffic_reports.json"

# connection_events — per-request timestamps (no bytes) for the §2d invariance proof.
"${PSQL[@]}" -c "${TIMEOUT}
  SELECT coalesce(json_agg(row_to_json(t)), '[]'::json) FROM (
    SELECT mac,
           host_value AS host,
           host_type,
           extract(epoch FROM ts)::bigint AS ts
    FROM connection_events
    WHERE mac IN (${IN_LIST})
      AND ts >= '${FROM}'::date AND ts < ('${TO}'::date + 1)
  ) t;" > "$OUT_DIR/connection_events.json"

echo "[fetch] wrote settings.json devices.json traffic_reports.json connection_events.json" >&2
wc -c "$OUT_DIR"/*.json >&2 || true
