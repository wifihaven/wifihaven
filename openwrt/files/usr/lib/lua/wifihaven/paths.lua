-- Shared IPC paths used by the wifihaven agent, dns-tail sidecar, and the
-- uhttpd block-page handler. Centralised here (#738) so a future rename
-- touches one file instead of three — handler.lua in particular lives under
-- /www and is easy to miss.
local M = {}

-- Block-page IPC (#437, #594): the agent writes after every policy apply,
-- the uhttpd lua handler reads on each request.
M.block_page_api_url  = "/var/run/wifihaven/api_url"
-- #1174: public SPA base URL (e.g. https://wifihaven.net). The block-page
-- handler prefers this over api_url so the kid's browser lands on the SPA
-- host, not the API host (which 404s non-/api paths in cloud deployments).
-- Written by the agent after every snapshot apply from snapshot.spaBaseUrl;
-- absent or empty when the snapshot doesn't carry that field.
M.block_page_spa_url  = "/var/run/wifihaven/spa_url"
M.block_page_reasons  = "/var/run/wifihaven/blocked_reasons"
M.block_page_hosts    = "/var/run/wifihaven/blocked_hosts"

-- ip → hostname cache (#259): wifihaven-dns-tail writes, wifihaven-agent reads.
M.dns_cache = "/tmp/wifihaven-dns-cache.txt"

return M
