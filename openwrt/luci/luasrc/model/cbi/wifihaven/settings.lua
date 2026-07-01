-- CBI form for /etc/config/wifihaven.
-- Exposes the cadence knobs documented in #748 plus the API endpoint /
-- enrollment identifiers. Edits land in UCI on Save & Apply; the agent
-- re-reads them at startup, so changing a cadence requires a service
-- restart (`/etc/init.d/wifihaven restart`) — surfaced as a note below.

local m = Map("wifihaven", translate("WifiHaven"),
  translate("Configure the WifiHaven router agent. Cadence changes take effect after restarting the wifihaven service."))

local s = m:section(TypedSection, "wifihaven", translate("Agent"))
s.anonymous = true
s.addremove = false

-- ── Connection / enrollment ─────────────────────────────────────────────────

local api_url = s:option(Value, "api_url", translate("API URL"),
  translate("Base URL of the WifiHaven API server (no trailing slash)."))
api_url.placeholder = "http://192.168.1.1:8080"

local router_id = s:option(Value, "router_id", translate("Router ID"),
  translate("UUID returned during enrollment."))
router_id.rmempty = false

local router_token = s:option(Value, "router_token", translate("Router token"),
  translate("Long-lived bearer token obtained during enrollment."))
router_token.password = true
router_token.rmempty = false

local lan_prefix = s:option(Value, "lan_prefix", translate("LAN prefix"),
  translate("Subnet prefix used to identify outbound flows (e.g. 192.168.1.)."))
lan_prefix.placeholder = "192.168.1."

-- ── Cadence (see #748) ──────────────────────────────────────────────────────

local policy_int = s:option(Value, "policy_poll_interval", translate("Policy poll interval (s)"),
  translate("How often to GET /api/router/policy. Lower = faster propagation of admin changes, more API load. Suggested 3–30."))
policy_int.datatype = "uinteger"
policy_int.placeholder = "5"

local usage_int = s:option(Value, "usage_report_interval", translate("Usage report interval (s)"),
  translate("How often to POST /api/router/usage. Lower = finer timeline buckets. Must be evenly divisible by the activity sample interval. Suggested 30–300."))
usage_int.datatype = "uinteger"
usage_int.placeholder = "60"

local metrics_int = s:option(Value, "metrics_report_interval", translate("Metrics push interval (s)"),
  translate("How often to push the agent's cumulative observability metrics to /api/router/metrics. Independent of the policy poll; counters self-heal on a missed push. Suggested 30–300."))
metrics_int.datatype = "uinteger"
metrics_int.placeholder = "60"

local activity_sample_int = s:option(Value, "activity_sample_int", translate("Activity sample interval (s)"),
  translate("How often to sample per-MAC activity for activeSeconds accounting. Lower = more accurate, more CPU. Suggested 5–30. Must evenly divide usage_report_interval."))
activity_sample_int.datatype = "uinteger"
activity_sample_int.placeholder = "10"

local batch = s:option(Value, "event_batch_size", translate("Event batch size"),
  translate("How many connection_attempt events to accumulate before flushing."))
batch.datatype = "uinteger"
batch.placeholder = "50"

local flush_int = s:option(Value, "event_flush_interval", translate("Event flush interval (s)"),
  translate("Force-flush the event batch after this many seconds even if not full."))
flush_int.datatype = "uinteger"
flush_int.placeholder = "10"

local debug_opt = s:option(Flag, "debug", translate("Verbose logging"),
  translate("Emit debug-level entries to syslog."))
debug_opt.default = "0"

-- ── WebSocket transport (see #1023 / #2037) ─────────────────────────────────
-- The ws sidecar toggle lives in its own named `config ws 'ws'` section
-- (read as wifihaven.ws.<opt>), so it needs a NamedSection distinct from the
-- anonymous default `wifihaven` section above. Flipping this replaces the CLI
-- `uci set wifihaven.ws.enabled=1`; the init script only starts the sidecar
-- when enabled=1, so a service restart is required (same note as the cadences).
local ws = m:section(NamedSection, "ws", "ws", translate("WebSocket transport (experimental)"))
ws.addremove = false

local ws_enabled = ws:option(Flag, "enabled", translate("Enable WebSocket transport"),
  translate("When on, the agent maintains a persistent WebSocket to the API for live policy push plus usage/event upload, and the HTTP poll goes dormant (see #2037). Default off; requires a wifihaven service restart to take effect."))
ws_enabled.default = "0"
ws_enabled.rmempty = false

return m
