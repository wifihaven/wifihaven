-- ws_metrics.lua — cumulative metrics tally for the wifihaven-ws sidecar (#1848).
--
-- The sidecar is a separate process from the main agent and has no metrics
-- registry of its own (the same split as the dns/sni tails). It keeps a
-- cumulative in-memory tally and serialises it to a tmpfs file (paths.ws_metrics);
-- the main agent parses it on its metrics tick and folds the deltas into the
-- existing /api/router/metrics push via metrics.fold_external — so a sidecar
-- restart that re-bases the tally from 0 is handled as a counter reset, with no
-- double-count, exactly like dns_queries_total / sni_clienthellos_total.
--
-- Wire format (one record per line, tab-separated):
--   counter:  <name>\t<label_value>\t<count>   (3 fields)
--   gauge:    <name>\t<value>                   (2 fields)
-- serialize/parse are a symmetric pure pair, unit-tested so the writer (sidecar)
-- and reader (agent) can never drift. Runs identically under OpenWrt Lua 5.1.
--
-- The metric NAMES + bounded label enums here MUST match the API allowlist
-- (api/src/metrics/Metrics.scala MetricGuard.Allowed) — off-allowlist series are
-- dropped server-side. Series: ws_connect_total{result}, ws_fallback_total{result},
-- ws_frames_sent_total{op}, ws_frames_recv_total{op} (counters); ws_state (gauge).

local M = {}

function M.new()
  return { counters = {}, gauges = {} }
end

-- inc(state, name, label_value, by?) — bump a labelled counter (by defaults 1).
function M.inc(state, name, label_value, by)
  by = by or 1
  local c = state.counters[name]
  if not c then c = {}; state.counters[name] = c end
  c[label_value] = (c[label_value] or 0) + by
end

-- set(state, name, value) — set an unlabelled gauge (e.g. ws_state 1/0).
function M.set(state, name, value)
  state.gauges[name] = value
end

-- serialize(state) → wire text.
function M.serialize(state)
  local lines = {}
  -- Stable order so the file is byte-stable across flushes (eases diffing).
  local names = {}
  for name in pairs(state.counters) do names[#names + 1] = name end
  table.sort(names)
  for _, name in ipairs(names) do
    local labels = {}
    for lv in pairs(state.counters[name]) do labels[#labels + 1] = lv end
    table.sort(labels)
    for _, lv in ipairs(labels) do
      lines[#lines + 1] = string.format("%s\t%s\t%d", name, lv, state.counters[name][lv])
    end
  end
  local gnames = {}
  for name in pairs(state.gauges) do gnames[#gnames + 1] = name end
  table.sort(gnames)
  for _, name in ipairs(gnames) do
    lines[#lines + 1] = string.format("%s\t%s", name, tostring(state.gauges[name]))
  end
  return table.concat(lines, "\n") .. (#lines > 0 and "\n" or "")
end

-- parse(text) → { counters = {[name]={[label]=count}}, gauges = {[name]=value} }.
-- Tolerant: blank/malformed lines are skipped, an empty/nil input yields empty
-- tables (so the agent fold is a clean no-op before the sidecar first writes).
function M.parse(text)
  local out = { counters = {}, gauges = {} }
  if not text or text == "" then return out end
  for line in text:gmatch("[^\n]+") do
    local f1, f2, f3 = line:match("^([^\t]+)\t([^\t]+)\t([^\t]+)$")
    if f1 then
      local n = tonumber(f3)
      if n then
        local c = out.counters[f1]
        if not c then c = {}; out.counters[f1] = c end
        c[f2] = n
      end
    else
      local g1, g2 = line:match("^([^\t]+)\t([^\t]+)$")
      if g1 then
        local v = tonumber(g2)
        if v then out.gauges[g1] = v end
      end
    end
  end
  return out
end

-- flush(state, path, open_fn) — atomically-ish write the serialized tally. The
-- file is small and bounded (a handful of series), so a plain truncating write
-- is fine (single writer = the sidecar). Returns ok, err.
function M.flush(state, path, open_fn)
  open_fn = open_fn or io.open
  local f, err = open_fn(path, "w")
  if not f then return nil, err end
  f:write(M.serialize(state))
  f:close()
  return true
end

return M
