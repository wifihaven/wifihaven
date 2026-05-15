-- policy.lua — policy snapshot fetcher and atomic config applier
--
-- Public API:
--   policy.fetch(api_url, router_token, etag, http_get_fn)
--     → snapshot_table|nil, etag|nil
--     http_get_fn(url, headers) → status_code, body, response_headers
--     Returns (nil, etag) on 304, (nil, nil) on error, (snapshot, etag) on 200.
--
--   policy.apply(snapshot, write_fn, reload_fn, log, opts)
--     → bool (true on success)
--     write_fn(path, content) → ok, err
--     reload_fn(cmd)          → exit_code
--     opts (optional table)   → bag of options. Recognised keys:
--       opts.read_fn(path) → string|nil — used to read the on-disk copy of
--                                   the rendered dnsmasq.conf for the
--                                   change-detection check (#414). Defaults
--                                   to a plain io.open read.
--       opts.dns_check_fn(domain) → string|nil — result of resolving `domain`
--                                   via the router's own resolver, used by
--                                   the #328 smoke probe. After #351 DNS no
--                                   longer sinkholes blocked hosts (Truth 1:
--                                   blocking is at the connection layer); the
--                                   probe now confirms that dnsmasq is
--                                   answering at all, by checking that an
--                                   extraBlocked host resolves to a real IP
--                                   (NOT 0.0.0.0 / :: / NXDOMAIN). A
--                                   sinkhole-shaped answer means dnsmasq
--                                   is still applying a stale rendering
--                                   (failed restart) and triggers a WARN.
--       opts.poll_failed → forwarded to render.nft for the #385/#422
--                                 failover branch (three modes: block-all,
--                                 allow-all, last-known-good). True iff
--                                 the most-recent poll attempt did not
--                                 succeed; failover trips immediately on
--                                 a single failure (no 300s cushion).

local M = {}

local render = require("familydns.render")

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
  local status, body, _resp_headers = http_get_fn(url, hdrs)
  log.debug("policy.fetch: response status=%s bodyLen=%d",
            tostring(status), body and #body or 0)

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
      local function count_keys(t)
        if type(t) ~= "table" then return 0 end
        local n = 0
        for _ in pairs(t) do n = n + 1 end
        return n
      end
      log.debug("policy.fetch: parsed snapshot devices=%d profiles=%d etag=%s",
                count_keys(snap.devices),
                count_keys(snap.profiles),
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
-- opts is an optional table. opts.dns_check_fn (#328 / #351): when the
-- rendered dnsmasq.conf contains at least one `nftset=/<host>/...#eb_...`
-- line (i.e. there is something to enforce), we probe one of those hosts via
-- the router's own resolver after the dnsmasq restart. Post-#351 DNS is
-- NOT the enforcement plane — blocked hosts must resolve to a real IP, so
-- the probe inverts the old semantics: a sinkhole-shaped answer
-- (`0.0.0.0`, `::`, empty / NXDOMAIN) means dnsmasq is still running a
-- stale config that has DNS-layer blocks in it, which is the failure mode
-- we want to catch.
-- opts.poll_failed (#331/#422): forwarded to render.nft so the agent's
-- failover-edge re-render can request the closed-mode drop chain on the
-- first failed poll (and clear it on the next success).

-- Default reader for the change-detection check (#414). Returns nil if the
-- file is absent — first apply on a fresh boot treats that as "changed".
local function default_read(path)
  local f = io.open(path, "r")
  if not f then return nil end
  local content = f:read("*a")
  f:close()
  return content
end

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

-- Sinkhole-shaped DNS answers. Post-#351 these are *failures* (DNS must
-- resolve normally — blocking happens at the connection layer), but we
-- still need to recognise the shape to detect a dnsmasq that's running a
-- stale, sinkhole-containing config.
local BLOCKED_RESULTS = {
  ["0.0.0.0"] = true,
  ["::"]      = true,
  ["::0"]     = true,
}

-- True when the DNS answer indicates the resolver is *blocking* at the
-- DNS layer (sinkhole IP) or returning no answer at all (NXDOMAIN /
-- SERVFAIL / timeout → empty first line from `dig +short`). Both shapes
-- mean we are NOT getting a real upstream answer, which post-#351 is a
-- regression — dnsmasq should resolve every host normally and let nft
-- handle blocking.
local function is_blocked_at_connection(result)
  if result == nil or result == "" then return true end
  return BLOCKED_RESULTS[result] == true
end

-- Extract the first extraBlocked host from the rendered dnsmasq content by
-- looking for an `nftset=/<host>/...#eb_...` directive (the per-host set
-- name prefix is the agent's canonical marker for #351 enforcement; the
-- nftset= form replaces legacy ipset= post-OpenWRT-23.05 per #392). Returns
-- nil if there are no extraBlocked hosts active.
local function first_extrablocked_host(dnsmasq_content)
  return dnsmasq_content:match("\nnftset=/([^/\n]+)/[^\n]-#eb_")
      or dnsmasq_content:match("^nftset=/([^/\n]+)/[^\n]-#eb_")
end

function M.apply(snapshot, write_fn, reload_fn, log, opts)
  log = log or default_log()
  opts = opts or {}
  local dnsmasq_content = render.dnsmasq(snapshot)
  local nft_content     = render.nft(snapshot, opts)

  -- #414: dnsmasq only needs a full restart when its config-dir file
  -- actually changes (dhcp-host= and nftset= directives are NOT picked up
  -- on SIGHUP — see #328). Most policy.apply calls flip only nft-side
  -- state (blocked_macs membership from schedules / pause / time limits,
  -- failover transitions). Compare against the on-disk copy and skip the
  -- restart when the rendered content is byte-identical; this avoids a
  -- DNS service blip on every schedule boundary.
  local read_fn = opts.read_fn or default_read
  local existing_dnsmasq = read_fn("/tmp/dnsmasq.d/familydns.conf")
  local dnsmasq_changed = (existing_dnsmasq ~= dnsmasq_content)

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

  if dnsmasq_changed then
    log.debug("policy.apply: wrote dnsmasq=%dB (changed) nft=%dB; restarting dnsmasq",
              #dnsmasq_content, #nft_content)
    -- #328: must be `restart`, not `reload`. SIGHUP doesn't re-read conf-dir,
    -- so `reload` leaves new dhcp-host= and nftset= directives silently
    -- inactive until something else restarts the service. (Post-#329/#351
    -- there are no `address=` sinkhole directives — but dhcp-host= and
    -- nftset= still need a full restart for the same reason.)
    reload_fn("/etc/init.d/dnsmasq restart")
  else
    log.debug("policy.apply: wrote dnsmasq=%dB (unchanged) nft=%dB; skipping dnsmasq restart",
              #dnsmasq_content, #nft_content)
  end
  -- Single atomic `nft -f`. The rendered file's prelude removes both the
  -- boot default-deny skeleton (table inet familydns_boot — #308) and any
  -- prior runtime table in one transaction, then installs the new ruleset.
  reload_fn("nft -f /tmp/nftables.d/familydns.nft")

  -- #328 / #351 smoke probe. Runs only when we actually restarted dnsmasq:
  -- the probe exists to catch "we restarted but dnsmasq is still serving
  -- a stale config", so it adds nothing when no restart happened.
  if dnsmasq_changed then
    local probe_domain = first_extrablocked_host(dnsmasq_content)
    if probe_domain then
      local check = opts.dns_check_fn or default_dns_check
      local result = check(probe_domain)
      if is_blocked_at_connection(result) then
        log.warn(
          "policy.apply: smoke check failed; dnsmasq may be serving a stale " ..
          "config — got %q for %s (expected a real upstream IP)",
          tostring(result), probe_domain)
      end
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
  -- Both args are wall-clock (os.time()). A backward wall-clock jump (NTP
  -- correction, DST, manual change) can momentarily make `now` smaller than
  -- the stored timestamp; clamp to 0 so callers don't see a negative age
  -- (defensive bandage for #336). The scheduler proper runs on a monotonic
  -- clock and is not affected.
  local age = now - M.last_successful_poll_ts
  if age < 0 then return 0 end
  return age
end

-- Format a poll_age value for log output. Returns "inf" for the cold-boot
-- sentinel (math.huge from poll_age_seconds before any successful poll);
-- otherwise "<seconds>s". string.format("%d", math.huge) does not substitute,
-- so logging poll_age=%ds with a raw value would emit the literal "%ds inf".
function M.format_poll_age(poll_age)
  if poll_age == math.huge then return "inf" end
  return string.format("%ds", poll_age)
end

-- Test-only: reset module-level poll state between specs.
function M.reset_poll_state()
  M.last_successful_poll_ts = nil
end

-- ---------------------------------------------------------------------------
-- Failover transition decision (#331/#422). Pure function so the agent's
-- on_tick can stay thin and the boundary behavior is unit-testable.
--
-- Inputs:
--   in_failover : bool — was the last apply rendered with failover opts?
--   fetch_ok    : bool — did this tick's poll succeed (200 or 304)?
-- Returns:
--   should_apply    : bool       — re-render is needed this tick
--   apply_opts      : table|nil  — opts to pass to policy.apply
--   new_in_failover : bool       — updated state flag
--
-- Semantics (#422): failover trips immediately on a failed poll and lifts
-- immediately on a successful poll. There is no time-based cushion — the
-- previous 300s gate contradicted the per-profile failureMode design intent
-- (block-all should drop traffic the moment we can't reach the API).
-- ---------------------------------------------------------------------------
function M.failover_transition(in_failover, fetch_ok)
  if fetch_ok then
    if in_failover then
      return true, { poll_failed = false }, false
    end
    return false, nil, false
  end
  if not in_failover then
    return true, { poll_failed = true }, true
  end
  return false, nil, true
end

return M
