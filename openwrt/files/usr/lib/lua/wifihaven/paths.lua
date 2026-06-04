-- Shared IPC paths used by the wifihaven agent, dns-tail sidecar, and the
-- uhttpd block-page handler. Centralised here (#738) so a future rename
-- touches one file instead of three — handler.lua in particular lives under
-- /www and is easy to miss.
local M = {}

-- Block-page IPC (#437, #594): the agent writes after every policy apply,
-- the uhttpd lua handler reads on each request.
M.block_page_api_url  = "/var/run/wifihaven/api_url"
M.block_page_reasons  = "/var/run/wifihaven/blocked_reasons"
M.block_page_hosts    = "/var/run/wifihaven/blocked_hosts"

-- ip → hostname cache (#259): wifihaven-dns-tail writes, wifihaven-agent reads.
M.dns_cache = "/tmp/wifihaven-dns-cache.txt"

-- DNS query-result tally (#1302): wifihaven-dns-tail writes cumulative
-- per-result counts ("<result>\t<count>" lines) on each flush; wifihaven-agent
-- samples it on its metrics tick and folds the deltas into
-- dns_queries_total{result} for the /api/router/metrics push. The sidecar has
-- no metrics registry of its own, so this tmpfs file is the IPC (same pattern
-- as dns_cache).
M.dns_metrics = "/tmp/wifihaven-dns-metrics.txt"

-- blocklist member → bl_ set index (#1348): policy.apply writes after each
-- snapshot apply (in lockstep with /tmp/dnsmasq.d/wifihaven.conf), the
-- wifihaven-dns-tail bl_ populator reads it on its set-refresh cadence.
M.bl_member_index = "/tmp/wifihaven-bl-members.txt"

return M
