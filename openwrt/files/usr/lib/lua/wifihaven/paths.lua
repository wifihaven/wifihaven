-- Shared IPC paths used by the wifihaven agent, dns-tail sidecar, and the
-- uhttpd block-page handler. Centralised here (#738) so a future rename
-- touches one file instead of three — handler.lua in particular lives under
-- /www and is easy to miss.
local M = {}

-- Block-page IPC: the agent writes the API URL after every policy apply,
-- the uhttpd lua handler reads it on each request. The per-MAC reason and
-- per-(MAC, host) classifier files (#437 / #594) were removed in #1618 —
-- post-#1615/#1617 the SPA derives the canonical reason from
-- GET /api/blocked, so the on-disk IPC has no readers left.
M.block_page_api_url  = "/var/run/wifihaven/api_url"

-- Block-page household token (#2566/#2569/#2322): the agent fetches an opaque,
-- API-signed, router-bound token from GET /api/router/block-page-token and
-- writes it here; the uhttpd lua handler reads it on each request and appends it
-- to the SPA redirect as `&bpt=`. It is what lets the unauthenticated
-- GET /api/blocked and POST /api/access-requests resolve THIS router's household
-- instead of guessing household 1. Bounded: a single short line, truncated and
-- rewritten (never appended to), so it needs no rotation belt.
M.block_page_token    = "/var/run/wifihaven/block_page_token"

-- ip → hostname cache (#259): wifihaven-dns-tail writes, wifihaven-agent reads.
M.dns_cache = "/tmp/wifihaven-dns-cache.txt"

-- CNAME-alias map snapshot (#1572): wifihaven-dns-tail writes in lockstep with
-- dns_cache and reloads it on startup via cache.seed_aliases. Persists the
-- target→branded-head edges learned from observed CNAME chains so a sidecar
-- restart does not lose the ability to attribute a directly-queried CDN edge
-- name back to the brand the client originally typed. Same atomic-write/
-- tmpfs-IPC pattern as dns_cache; the agent does not read this file.
M.dns_aliases = "/tmp/wifihaven-dns-aliases.txt"

-- DNS query-result tally (#1302): wifihaven-dns-tail writes cumulative
-- per-result counts ("<result>\t<count>" lines) on each flush; wifihaven-agent
-- samples it on its metrics tick and folds the deltas into
-- dns_queries_total{result} for the /api/router/metrics push. The sidecar has
-- no metrics registry of its own, so this tmpfs file is the IPC (same pattern
-- as dns_cache).
M.dns_metrics = "/tmp/wifihaven-dns-metrics.txt"

-- ea_/ea6_ carve backfill batch (#2208): policy.apply renders the whole
-- extraAllowed carve seeding into this single nft ruleset file and loads it with
-- one `nft -f`, instead of spawning one `nft add element` process per element
-- (the v0.3.19→v0.3.20 apply-latency regression). Lives under the same
-- /tmp/nftables.d confdir the main ruleset uses (created at startup). Overwritten
-- (truncated) on every apply, so it is naturally bounded by the carve size — no
-- rotation belt needed (cf. docs/process/router-agent-bounded-writes.md, which
-- governs APPEND-growth files, not truncate-rewrite ones).
M.ea_backfill_nft = "/tmp/nftables.d/wifihaven-ea-backfill.nft"

-- blocklist member → bl_ set index (#1348): policy.apply writes after each
-- snapshot apply (in lockstep with /tmp/dnsmasq.d/wifihaven.conf), the
-- wifihaven-dns-tail bl_ populator reads it on its set-refresh cadence.
M.bl_member_index = "/tmp/wifihaven-bl-members.txt"

-- SNI capture spool (#573): wifihaven-sni-tail parses the first packet of every
-- outbound TCP/443 flow off the LAN bridge, extracts the TLS ClientHello SNI,
-- and appends one "SNI\t<dst_ip>\t<src_mac>\t<server_name>" line per capture to
-- this tmpfs file. wifihaven-dns-tail tails it alongside the dnsmasq query log
-- and routes SNI-prefixed lines through cache.insert_sni, so the shared
-- ip→hostname cache (paths.dns_cache) stays single-writer (dns-tail). This
-- improves connection-event fqdn ATTRIBUTION only (cache.lookup); it does NOT
-- feed the eb_/ea_/bl_ nft drop-set populators, which fire solely on dnsmasq
-- `reply <name> is <ip>` lines — SNI is observed after the flow's first packet,
-- too late to populate a drop set for that flow.
M.sni_capture = "/tmp/wifihaven-sni.log"

-- SNI capture result tally (#573): wifihaven-sni-tail writes cumulative
-- per-result counts ("<result>\t<count>" lines, same format as dns_metrics) on
-- each periodic flush; wifihaven-agent samples it on its metrics tick and folds
-- the deltas into sni_clienthellos_total{result}. result ∈ {parsed, truncated,
-- no_sni, not_ip, not_tcp, malformed, ech (#1650)} (pre-#1652 agents also emit ipv6_skipped,
-- ageing out as the fleet rolls forward). Same tmpfs-IPC pattern as
-- dns_metrics — the sidecar has no metrics registry of its own.
M.sni_metrics = "/tmp/wifihaven-sni-metrics.txt"

-- nflog drop spool (#1126): wifihaven-nflog-tail tails `logread -f`, greps the
-- nft `log prefix "wh_drop:…"` forward-drop records, and appends them here; the
-- main agent drains new complete lines from this tmpfs spool on its cooperative
-- tick (nflog.drain_file) and synthesizes connection_attempt events. Same
-- own-the-blocking-read / tmpfs-IPC split as the dns-tail cache (#259), so the
-- agent never needs non-blocking I/O.
M.nflog_drops = "/tmp/wifihaven-nflog.log"

-- ── websocket sidecar IPC (#1848) ────────────────────────────────────────────
-- The wifihaven-ws sidecar (ws_loop.lua) is a separate procd process from the
-- main agent (which owns the conntrack loop). These three tmpfs files are the
-- IPC between them, all bounded (docs/process/router-agent-bounded-writes.md).

-- Outbound frame spool: the main agent APPENDS one NDJSON `{op,payload}` frame
-- line per outbound usage/events body when ws is enabled; the sidecar DRAINS new
-- complete lines by byte offset (ws_spool — same lock-free single-writer/single-
-- reader pattern as nflog_drops). Self-bounded at ws_spool_max_bytes (oldest-
-- first drop) plus a copytruncate rotation belt.
M.ws_outbound = "/tmp/wifihaven-ws-outbound.jsonl"

-- #2634: the spool has a companion ledger file alongside it (`<spool>.written`)
-- holding the total bytes ever appended. The reader keeps its cursor in stream
-- coordinates and derives the file's first surviving byte as `written - size`,
-- which is correct whether the missing prefix was EVICTED by the cap or emptied
-- by the copytruncate cron — an evicted-bytes counter only describes the first,
-- and the two can mask each other. Deliberately NOT a constant here:
-- `ws_spool.ledger_path(spool)` is the single definition of the name, so there is
-- no second copy of the suffix to drift. Fixed-size (one integer rewritten in
-- place), so unlike the spool it needs no rotation.

-- ws-health sentinel: the sidecar touches this file's mtime on every successful
-- send/recv while the socket is up, and removes it on disconnect. The main agent
-- stats it on its tick: a FRESH sentinel (mtime within ws_fallback_after) means
-- "the sidecar owns outbound" → tee usage/events to the spool above; a STALE or
-- absent sentinel means the link is down past the fallback window → the agent
-- resumes HTTP posting (design §3.1). Absent unless the sidecar is running, so
-- a crashed sidecar reads as "link down" and the agent falls back (#2608).
M.ws_health = "/tmp/wifihaven-ws-health"

-- ws metrics tally: the sidecar has no metrics registry of its own (like the
-- dns/sni tails), so it writes a cumulative tally here (ws_metrics format) and
-- the main agent folds the deltas into the existing /api/router/metrics push on
-- its metrics tick. A sidecar restart re-bases the tally from 0, which
-- fold_external treats as a reset.
M.ws_metrics = "/tmp/wifihaven-ws-metrics.txt"

-- ws apply trigger (#2229): after the sidecar persists a pushed snapshot to
-- policy.json it writes "<etag>\t<uptime>" here (etag of the pushed snapshot;
-- CLOCK_MONOTONIC seconds from /proc/uptime at persist time — a system-wide
-- clock both processes read, since busybox lacks `stat -c %Y`). The main agent
-- reads this tiny file every on_tick (cheap, no full-snapshot parse) and applies
-- the pushed snapshot IMMEDIATELY when the etag differs from the applied one —
-- event-driven, instead of waiting up to ws.apply_interval for the poll-of-disk
-- gate. The uptime stamp lets the agent observe ws_push_apply_latency_seconds
-- (persist→apply) without a shared wall clock. Bounded: a single short line,
-- overwritten (not appended) on every push. Absent unless ws is enabled.
M.ws_pending = "/tmp/wifihaven-ws-pending"

return M
