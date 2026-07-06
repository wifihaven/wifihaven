-- uhttpd-mod-lua handler for the block-page listener (127.0.0.1:8081 / [::]:8081).
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

  -- #679 / #1617: the redirect URL no longer carries reason=. The SPA derives
  -- the canonical block reason server-side via GET /api/blocked
  -- (PolicyService.decide), so there is nothing for this handler to look up.

  local api_url = read_file(paths.block_page_api_url)
  if api_url then api_url = api_url:gsub("%s+$", "") end

  local body = block_page.render_html(api_url, host, mac)

  uhttpd.send("Status: 200 OK\r\n")
  uhttpd.send("Content-Type: text/html; charset=utf-8\r\n")
  uhttpd.send("Cache-Control: no-store\r\n")
  -- #2082: the block page is unauthenticated by design (any LAN client can be
  -- redirected here), so it must not be embeddable/framed by another origin
  -- (clickjacking) and must not have its content-type sniffed. The block page's
  -- own render_html emits an inline <script>/<style>, so CSP keeps
  -- 'unsafe-inline' for those directives rather than breaking the redirect —
  -- default-src 'self' + frame-ancestors 'none' are the load-bearing lines.
  uhttpd.send("X-Content-Type-Options: nosniff\r\n")
  uhttpd.send("X-Frame-Options: DENY\r\n")
  uhttpd.send(
    "Content-Security-Policy: default-src 'self'; script-src 'self' 'unsafe-inline'; " ..
    "style-src 'self' 'unsafe-inline'; frame-ancestors 'none'; base-uri 'self'; " ..
    "object-src 'none'\r\n"
  )
  uhttpd.send("Content-Length: " .. tostring(#body) .. "\r\n")
  uhttpd.send("\r\n")
  uhttpd.send(body)
end
