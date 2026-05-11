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
function M.fetch(api_url, router_token, etag, http_get_fn)
  local url = api_url .. "/api/router/policy"
  if etag then
    url = url .. "?since=" .. urlencode(etag)
  end

  local hdrs = { ["Authorization"] = "Bearer " .. router_token }
  if etag then
    hdrs["If-None-Match"] = etag
  end

  local status, body, _ = http_get_fn(url, hdrs)

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
      return snap, new_etag
    end
    -- snap_or_err holds the error message when pcall returns false (e.g. the
    -- luci.jsonc require itself failed, or parse raised/returned nil).
    local body_str = body and tostring(body) or ""
    if #body_str > 200 then body_str = body_str:sub(1, 200) .. "...(truncated)" end
    io.stderr:write(string.format(
      "[familydns] policy.fetch: JSON parse failed: %s (body=%q)\n",
      tostring(snap_or_err), body_str))
    return nil, nil
  else
    local body_str = body and tostring(body) or ""
    if #body_str > 200 then body_str = body_str:sub(1, 200) .. "...(truncated)" end
    io.stderr:write(string.format(
      "[familydns] policy.fetch: unexpected status %s body=%q\n",
      tostring(status), body_str))
    return nil, nil
  end
end

-- ---------------------------------------------------------------------------
-- policy.apply
-- ---------------------------------------------------------------------------
-- write_fn(path, content) must return (truthy, nil) on success or (nil, err_string).
-- reload_fn(cmd) is called with a shell command string; return value is ignored.
function M.apply(snapshot, write_fn, reload_fn)
  local dnsmasq_content = render.dnsmasq(snapshot)
  local nft_content     = render.nft(snapshot)

  local ok1, err1 = write_fn("/tmp/dnsmasq.d/familydns.conf", dnsmasq_content)
  if not ok1 then
    io.stderr:write(string.format(
      "[familydns] policy.apply: write dnsmasq conf failed: %s\n", tostring(err1)))
    return false
  end

  local ok2, err2 = write_fn("/tmp/nftables.d/familydns.nft", nft_content)
  if not ok2 then
    io.stderr:write(string.format(
      "[familydns] policy.apply: write nft file failed: %s\n", tostring(err2)))
    return false
  end

  reload_fn("/etc/init.d/dnsmasq reload")
  -- Delete the table first so sets/chains don't conflict on re-import
  reload_fn("nft delete table inet familydns 2>/dev/null; nft -f /tmp/nftables.d/familydns.nft")

  return true
end

return M
