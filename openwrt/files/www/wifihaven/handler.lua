-- uhttpd-mod-lua handler for the block-page listener (127.0.0.1:8081 / [::1]:8081).
--
-- Wired in install.sh via:
--   option lua_prefix '/'
--   option lua_handler '/www/wifihaven/handler.lua'
--
-- uhttpd loads this file once, then dispatches every request to handle_request.
-- The lookup logic lives in wifihaven.block_page so it can be unit-tested
-- without uhttpd in the loop.

local block_page = require("wifihaven.block_page")
local paths      = require("wifihaven.paths")

local function read_file(path)
  local f = io.open(path, "r")
  if not f then return nil end
  local c = f:read("*a")
  f:close()
  return c
end

function handle_request(env)
  local remote = (env and env.REMOTE_ADDR) or ""
  local host   = (env and env.HTTP_HOST)   or ""
  host = host:gsub(":%d+$", "")  -- strip port if present

  local arp = read_file("/proc/net/arp")
  local mac = block_page.parse_arp(arp, remote)

  local reasons = read_file(paths.block_page_reasons)
  local reason  = mac and block_page.parse_reasons(reasons, mac) or nil
  -- No MAC-wide reason means the DNAT was triggered by a per-(MAC, host) drop,
  -- not a whole-MAC block. Look up the host in the per-MAC classifier file to
  -- distinguish extraBlocked from category-blocklist hits (#594). Falls back
  -- to "ExtraBlocked" if the classifier file is missing or the lookup misses
  -- (#576 default).
  if mac and not reason then
    local hosts_content = read_file(paths.block_page_hosts)
    local source = block_page.parse_blocked_hosts(hosts_content, mac, host)
    if source == "extra_blocked" then
      reason = "ExtraBlocked"
    elseif source then
      reason = source  -- e.g. "category:ads"
    else
      reason = "ExtraBlocked"
    end
  end

  local api_url = read_file(paths.block_page_api_url)
  if api_url then api_url = api_url:gsub("%s+$", "") end
  -- #1174: SPA base URL is preferred over api_url for the redirect dest.
  -- Absent file or empty content → block_page falls back to api_url.
  local spa_url = read_file(paths.block_page_spa_url)
  if spa_url then spa_url = spa_url:gsub("%s+$", "") end

  local body = block_page.render_html(api_url, spa_url, host, mac, reason)

  uhttpd.send("Status: 200 OK\r\n")
  uhttpd.send("Content-Type: text/html; charset=utf-8\r\n")
  uhttpd.send("Cache-Control: no-store\r\n")
  uhttpd.send("Content-Length: " .. tostring(#body) .. "\r\n")
  uhttpd.send("\r\n")
  uhttpd.send(body)
end
