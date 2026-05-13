-- policy.lua — policy snapshot fetcher and atomic config applier
--
-- Public API:
--   policy.fetch(api_url, router_token, etag, http_get_fn)
--     → snapshot_table|nil, etag|nil
--     http_get_fn(url, headers) → status_code, body, response_headers
--     Returns (nil, etag) on 304, (nil, nil) on error, (snapshot, etag) on 200.
--
--   policy.apply(snapshot, write_fn, reload_fn)
--     → bool (true on success)
--     write_fn(path, content) → ok, err
--     reload_fn(cmd)          → exit_code

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
  local status, body, _ = http_get_fn(url, hdrs)
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
function M.apply(snapshot, write_fn, reload_fn, log)
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

  log.debug("policy.apply: wrote dnsmasq=%dB nft=%dB; reloading",
            #dnsmasq_content, #nft_content)
  reload_fn("/etc/init.d/dnsmasq reload")
  -- Delete the table first so sets/chains don't conflict on re-import
  reload_fn("nft delete table inet familydns 2>/dev/null; nft -f /tmp/nftables.d/familydns.nft")

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
