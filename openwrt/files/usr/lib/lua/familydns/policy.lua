-- policy.lua — policy snapshot fetcher and atomic config applier
--
-- Public API:
--   policy.fetch(api_url, router_token, etag, http_get_fn)
--     → snapshot_table|nil, etag|nil
--     http_get_fn(url, headers) → status_code, body, response_headers
--     Returns (nil, etag) on 304, (nil, nil) on error, (snapshot, etag) on 200.
--
--   policy.apply(snapshot, write_fn, reload_fn, log, dns_check_fn)
--     → bool (true on success)
--     write_fn(path, content) → ok, err
--     reload_fn(cmd)          → exit_code
--     dns_check_fn(domain)    → string|nil (optional; result of resolving
--                               `domain` via the router's own resolver — used
--                               by the #328 smoke probe to detect a dnsmasq
--                               reload that silently no-op'd. Nil/empty/
--                               "0.0.0.0"/"::" mean sinkholed (OK); anything
--                               else triggers a WARN log. Defaults to a
--                               `dig @127.0.0.1` shell call.)

local M = {}

local render = require("familydns.render")

-- Last measured router-vs-API clock drift in seconds (signed; positive means
-- the router clock is ahead of the API). nil until the first successful poll
-- whose response carried a parseable Date header. Issue #312.
M._last_skew = nil

-- log is injectable for tests; default uses the real logger wrapper.
local function default_log()
  local ok, l = pcall(require, "familydns.log")
  if ok then return l end
  -- Fallback to a stderr shim when the module isn't on the path (e.g. older
  -- test harnesses).
  return {
    info  = function(fmt, ...) io.stderr:write(string.format(fmt .. "\n", ...)) end,
    err   = function(fmt, ...) io.stderr:write(string.format(fmt .. "\n", ...)) end,
    warn  = function(fmt, ...) io.stderr:write(string.format(fmt .. "\n", ...)) end,
    debug = function() end,
  }
end

-- Percent-encode characters that would break a query-string value:
-- the canonical HTTP etag is wrapped in literal `"` characters, which a raw
-- concatenation would emit into the URL and break server-side routing.
-- Encodes `"`, space, `?`, `#`, `&`, `=`, `+`, `%`, control bytes, and any
-- non-ASCII byte. Leaves `:` and other pchars (e.g. in `sha256:abc`) alone.
local function urlencode(s)
  return (s:gsub("[%c%z\"%%%+%&%=%?%# ]", function(c)
    return string.format("%%%02X", string.byte(c))
  end):gsub("[\128-\255]", function(c)
    return string.format("%%%02X", string.byte(c))
  end))
end

-- ---------------------------------------------------------------------------
-- RFC 1123 / RFC 850 / asctime Date-header → epoch-seconds. We only ever
-- need to handle the canonical HTTP/1.1 form (RFC 7231 §7.1.1.1 mandates
-- it from servers), but accept the lowercase month/day too.
-- ---------------------------------------------------------------------------
local MONTHS = {
  Jan = 1, Feb = 2, Mar = 3, Apr = 4, May = 5, Jun = 6,
  Jul = 7, Aug = 8, Sep = 9, Oct = 10, Nov = 11, Dec = 12,
}

local function parse_http_date(s)
  if type(s) ~= "string" then return nil end
  -- "Fri, 08 May 2026 14:00:00 GMT"
  local day, mon, year, hour, min, sec =
    s:match("(%d+)%s+(%a+)%s+(%d+)%s+(%d+):(%d+):(%d+)")
  if not day then return nil end
  local m = MONTHS[mon]
  if not m then return nil end
  -- Compose a UTC epoch via os.time on a struct with all fields. Lua's
  -- os.time interprets the table as local time, so we use the same trick as
  -- everywhere else: compute UTC offset via os.difftime(os.time(), os.time(os.date("!*t"))).
  local t = os.time({
    year = tonumber(year), month = m, day = tonumber(day),
    hour = tonumber(hour), min = tonumber(min), sec = tonumber(sec),
  })
  if not t then return nil end
  local utc_offset = os.difftime(os.time(), os.time(os.date("!*t", os.time())))
  return t + utc_offset
end

-- Case-insensitive header lookup; curl can emit "Date" or "date" depending
-- on the parse path, and tests pass either form.
local function header_lookup(headers, name)
  if type(headers) ~= "table" then return nil end
  local target = name:lower()
  for k, v in pairs(headers) do
    if type(k) == "string" and k:lower() == target then return v end
  end
  return nil
end

local function maybe_update_skew(response_headers, log)
  local date_hdr = header_lookup(response_headers, "Date")
  if not date_hdr then return end
  local api_epoch = parse_http_date(date_hdr)
  if not api_epoch then
    log.debug("policy.fetch: unparseable Date header: %q", tostring(date_hdr))
    return
  end
  local drift = os.time() - api_epoch
  M._last_skew = drift
  if math.abs(drift) > 60 then
    log.warn(
      "policy.fetch: router clock skew %ds vs API (router is %s); " ..
      "usage windows may be misattributed — check NTP on the router",
      drift, (drift > 0) and "ahead" or "behind")
  end
end

-- ---------------------------------------------------------------------------
-- policy.clock_skew → number|nil
-- ---------------------------------------------------------------------------
-- Returns the most recently measured router-vs-API clock drift in seconds
-- (positive = router ahead). nil until the first successful poll whose
-- response had a parseable Date header. Issue #312.
function M.clock_skew()
  return M._last_skew
end

-- ---------------------------------------------------------------------------
-- policy.fetch
-- ---------------------------------------------------------------------------
function M.fetch(api_url, router_token, etag, http_get_fn, log)
  log = log or default_log()
  local url = api_url .. "/api/router/policy"
  if etag then
    url = url .. "?since=" .. urlencode(etag)
  end

  local hdrs = { ["Authorization"] = "Bearer " .. router_token }
  if etag then
    hdrs["If-None-Match"] = etag
  end

  log.debug("policy.fetch: GET url=%s etag=%s", url, tostring(etag))
  local status, body, resp_headers = http_get_fn(url, hdrs)
  log.debug("policy.fetch: response status=%s bodyLen=%d",
            tostring(status), body and #body or 0)

  if status == 200 or status == 304 then
    maybe_update_skew(resp_headers, log)
  end

  if status == 304 then
    return nil, etag
  elseif status == 200 then
    local ok, snap_or_err = pcall(function()
      local jsonc = require("luci.jsonc")
      local parsed = jsonc.parse(body)
      if parsed == nil then
        error("luci.jsonc.parse returned nil (invalid JSON)")
      end
      return parsed
    end)
    if ok and snap_or_err then
      local snap = snap_or_err
      local new_etag = (snap.etag ~= nil) and snap.etag or etag
      log.debug("policy.fetch: parsed snapshot devices=%d profiles=%d etag=%s",
                snap.devices and #snap.devices or 0,
                snap.profiles and #snap.profiles or 0,
                tostring(new_etag))
      return snap, new_etag
    end
    -- snap_or_err holds the error message when pcall returns false (e.g. the
    -- luci.jsonc require itself failed, or parse raised/returned nil).
    local body_str = body and tostring(body) or ""
    if #body_str > 200 then body_str = body_str:sub(1, 200) .. "...(truncated)" end
    log.err("policy.fetch: JSON parse failed: %s (body=%q)",
            tostring(snap_or_err), body_str)
    return nil, nil
  else
    local body_str = body and tostring(body) or ""
    if #body_str > 200 then body_str = body_str:sub(1, 200) .. "...(truncated)" end
    log.err("policy.fetch: unexpected status %s body=%q",
            tostring(status), body_str)
    return nil, nil
  end
end

-- ---------------------------------------------------------------------------
-- policy.apply
-- ---------------------------------------------------------------------------
-- write_fn(path, content) must return (truthy, nil) on success or (nil, err_string).
-- reload_fn(cmd) is called with a shell command string; return value is ignored.
--
-- dns_check_fn is optional. When the rendered dnsmasq.conf contains at least
-- one `address=/<domain>/#` line, we probe the first such domain via the
-- router's own resolver after the dnsmasq restart and WARN if it doesn't
-- look sinkholed (#328 — detects the silent no-op when dnsmasq fails to
-- restart, or when conf-dir wasn't actually re-read).

-- Default smoke probe: `dig @127.0.0.1` and return the first line of stdout.
local function default_dns_check(domain)
  local cmd = string.format(
    "dig @127.0.0.1 -p 53 %s +short +time=2 +tries=1 2>/dev/null",
    domain)
  local f = io.popen(cmd, "r")
  if not f then return nil end
  local out = f:read("*l")
  f:close()
  return out
end

local SINKHOLE_RESULTS = {
  ["0.0.0.0"] = true,
  ["::"]      = true,
  ["::0"]     = true,
}

local function looks_sinkholed(result)
  if result == nil or result == "" then return true end
  return SINKHOLE_RESULTS[result] == true
end

-- Extract the first `address=/<domain>/#` line from the rendered dnsmasq
-- content. Returns nil if there are no such lines (e.g. no extraBlocked
-- entries in any profile).
local function first_blocked_domain(dnsmasq_content)
  return dnsmasq_content:match("\naddress=/([^/\n]+)/#")
      or dnsmasq_content:match("^address=/([^/\n]+)/#")
end

function M.apply(snapshot, write_fn, reload_fn, log, dns_check_fn)
  log = log or default_log()
  local dnsmasq_content = render.dnsmasq(snapshot)
  local nft_content     = render.nft(snapshot)

  local ok1, err1 = write_fn("/tmp/dnsmasq.d/familydns.conf", dnsmasq_content)
  if not ok1 then
    log.err("policy.apply: write dnsmasq conf failed: %s", tostring(err1))
    return false
  end

  local ok2, err2 = write_fn("/tmp/nftables.d/familydns.nft", nft_content)
  if not ok2 then
    log.err("policy.apply: write nft file failed: %s", tostring(err2))
    return false
  end

  log.debug("policy.apply: wrote dnsmasq=%dB nft=%dB; restarting",
            #dnsmasq_content, #nft_content)
  -- #328: must be `restart`, not `reload`. SIGHUP doesn't re-read conf-dir,
  -- so `reload` leaves new address=/, dhcp-host=, and ipset= directives
  -- silently inactive until something else restarts the service.
  reload_fn("/etc/init.d/dnsmasq restart")
  -- Single atomic `nft -f`. The rendered file's prelude removes both the
  -- boot default-deny skeleton (table inet familydns_boot — #308) and any
  -- prior runtime table in one transaction, then installs the new ruleset.
  reload_fn("nft -f /tmp/nftables.d/familydns.nft")

  -- #328 smoke probe. Skip cleanly when there's nothing to probe.
  local probe_domain = first_blocked_domain(dnsmasq_content)
  if probe_domain then
    local check = dns_check_fn or default_dns_check
    local result = check(probe_domain)
    if not looks_sinkholed(result) then
      log.warn(
        "policy.apply: smoke check failed; dnsmasq may not have restarted — " ..
        "got %q for %s (expected sinkhole)",
        tostring(result), probe_domain)
    end
  end

  return true
end

-- ---------------------------------------------------------------------------
-- Flash persistence (#309). Survives reboot during API outage so the agent
-- comes back up enforcing the last-known policy instead of dropping to the
-- default-deny boot skeleton (§1 / #308) until the first poll succeeds.
-- ---------------------------------------------------------------------------

local SNAPSHOT_PATH = "/etc/familydns/policy.json"

-- save_snapshot(snap, write_fn, rename_fn) → bool
--   write_fn(path, content)  → ok, err
--   rename_fn(from_path, to) → ok[, err]
-- Atomic: writes to <path>.tmp then renames over <path>. Skips the rename
-- if the write fails, so a torn or partial write never replaces a good
-- on-disk snapshot.
function M.save_snapshot(snap, write_fn, rename_fn, log)
  log = log or default_log()
  local jsonc = require("luci.jsonc")
  local body = jsonc.stringify(snap)
  local tmp = SNAPSHOT_PATH .. ".tmp"
  local ok, err = write_fn(tmp, body)
  if not ok then
    log.err("policy.save_snapshot: write %s failed: %s", tmp, tostring(err))
    return false
  end
  local rok, rerr = rename_fn(tmp, SNAPSHOT_PATH)
  if not rok then
    log.err("policy.save_snapshot: rename %s → %s failed: %s",
            tmp, SNAPSHOT_PATH, tostring(rerr))
    return false
  end
  return true
end

-- load_snapshot(read_fn) → snapshot|nil
--   read_fn(path) → content|nil (nil if the file doesn't exist)
-- Returns nil on missing/empty/corrupt content so the caller can fall back
-- to a fresh-start posture without crashing.
function M.load_snapshot(read_fn, log)
  log = log or default_log()
  local content = read_fn(SNAPSHOT_PATH)
  if not content or content == "" then return nil end
  local ok, snap_or_err = pcall(function()
    local jsonc = require("luci.jsonc")
    local parsed = jsonc.parse(content)
    if parsed == nil then error("invalid JSON") end
    return parsed
  end)
  if not ok then
    log.warn("policy.load_snapshot: failed to parse cached snapshot: %s",
             tostring(snap_or_err))
    return nil
  end
  return snap_or_err
end

-- ---------------------------------------------------------------------------
-- Successful-poll timestamp tracking (#309, consumed by #311).
-- ---------------------------------------------------------------------------

M.last_successful_poll_ts = nil

function M.mark_poll_success(now)
  M.last_successful_poll_ts = now
end

function M.poll_age_seconds(now)
  if not M.last_successful_poll_ts then return math.huge end
  return now - M.last_successful_poll_ts
end

-- Test-only: reset module-level poll state between specs.
function M.reset_poll_state()
  M.last_successful_poll_ts = nil
end

return M
