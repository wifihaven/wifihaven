-- uhttpd-mod-lua handler for the block-page listener (127.0.0.1:8081 / [::1]:8081).
--
-- Wired in install.sh via:
--   option lua_prefix '/'
--   option lua_handler '/www/familydns/handler.lua'
--
-- uhttpd loads this file once, then dispatches every request to handle_request.
-- The lookup logic lives in familydns.block_page so it can be unit-tested
-- without uhttpd in the loop.

local block_page = require("familydns.block_page")

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

  local reasons = read_file("/var/run/familydns/blocked_reasons")
  local reason  = mac and block_page.parse_reasons(reasons, mac) or nil

  local api_url = read_file("/var/run/familydns/api_url")
  if api_url then api_url = api_url:gsub("%s+$", "") end

  local body = block_page.render_html(api_url, host, mac, reason)

  uhttpd.send("Status: 200 OK\r\n")
  uhttpd.send("Content-Type: text/html; charset=utf-8\r\n")
  uhttpd.send("Cache-Control: no-store\r\n")
  uhttpd.send("Content-Length: " .. tostring(#body) .. "\r\n")
  uhttpd.send("\r\n")
  uhttpd.send(body)
end
