-- dns_log.lua — dnsmasq query-log tailer + in-memory hostname cache.
--
-- The conntrack watcher needs to map an outbound flow's destination IP back to
-- the hostname the client actually resolved. dnsmasq's `--log-queries=extra`
-- emits structured `query[A] <name>` and `reply <name> is <ip>` lines that
-- carry this mapping; this module parses those lines and exposes
-- `lookup(dst_ip) → original_qname` so connection_attempt events name the
-- domain the client typed (e.g. `youtube.com`) rather than the final CNAME
-- target (e.g. `youtube-ui.l.google.com`) and rather than the bare IP
-- (architecture.md §7.1 — fixes #259).
--
-- Public API:
--   dns_log.parse_query_line(line)  → { qid, qname } | nil
--   dns_log.parse_reply_line(line)  → { qid, name, ip } | nil   -- ip nil for CNAMEs
--   dns_log.new(opts)               → instance
--     opts: { ttl_seconds=3600, max_entries=10000, max_pending=1024, now_fn=os.time }
--   instance.ingest_line(line)
--   instance.lookup(ip) → string | nil
--   instance.tick(now)             -- evict expired entries (cheap; safe to call often)
--   instance.size() → integer       -- testable
--
-- #1302: classify DNS query outcomes for the dns_queries_total{result} counter.
-- classify_query_result(line) below maps a dnsmasq reply/cached line to a
-- bounded result enum. The dns-tail sidecar tallies these cumulatively and
-- writes them to paths.dns_metrics; the main agent samples that file on its
-- metrics tick and folds the deltas into the in-process registry
-- (metrics.fold_external) so they ship in the 60 s RouterMetricsBatch push
-- (#1206) — the sidecar runs as a separate procd instance and has no registry
-- of its own, so the tmpfs file is the IPC, mirroring the ip→host cache (#259).

local M = {}

-- ---------------------------------------------------------------------------
-- Line parsers
-- ---------------------------------------------------------------------------

-- A dnsmasq log line under --log-queries=extra has the shape (with or without
-- a syslog prefix, depending on log-facility):
--
--   [<syslog prefix> dnsmasq[pid]: ]<qid> <client_ip>/<port> query[A] <qname> from <src_ip>
--   [<syslog prefix> dnsmasq[pid]: ]<qid> <client_ip>/<port> reply <name> is <value>
--   [<syslog prefix> dnsmasq[pid]: ]<qid> <client_ip>/<port> cached <name> is <value>
--
-- `reply` lines are emitted when dnsmasq receives a response from an upstream
-- resolver; `cached` lines are emitted when dnsmasq answers from its own
-- in-memory cache. Both carry the same `<name> is <ip>` payload and both must
-- be ingested — otherwise a client's repeat lookup (or a lookup that happens
-- while dnsmasq's TTL is still good) lands no entry in the agent's IP→host
-- cache, and the resulting connection_attempt event reports hostname=nil
-- (#480, follow-up to #472).
--
-- We match the trailing structured portion and ignore the optional prefix.

function M.parse_query_line(line)
  if type(line) ~= "string" or line == "" then return nil end
  local qid, qname = line:match("(%d+)%s+%S+/%d+%s+query%[[%u%d]+%]%s+(%S+)%s+from%s+")
  if not qid then return nil end
  return { qid = qid, qname = qname }
end

local function is_ipv4(s)
  if type(s) ~= "string" then return false end
  local a, b, c, d = s:match("^(%d+)%.(%d+)%.(%d+)%.(%d+)$")
  if not a then return false end
  for _, oct in ipairs({ a, b, c, d }) do
    local n = tonumber(oct)
    if not n or n < 0 or n > 255 then return false end
  end
  return true
end

-- A lightweight check for an IPv6 literal as dnsmasq writes them in the log:
-- one or more `:`-separated hex groups, possibly with `::` collapse. We don't
-- attempt full RFC 5952 validation — dnsmasq emits canonical addresses, and
-- the value will be passed straight to `nft add element` which does its own
-- validation. Reject anything containing a non-hex/colon character.
local function is_ipv6(s)
  if type(s) ~= "string" then return false end
  if not s:find(":") then return false end
  if s:find("[^%x:]") then return false end
  return true
end
M._is_ipv6 = is_ipv6
M._is_ipv4 = is_ipv4

function M.parse_reply_line(line)
  if type(line) ~= "string" or line == "" then return nil end
  local qid, verb, name, value = line:match("(%d+)%s+%S+/%d+%s+(%a+)%s+(%S+)%s+is%s+(%S+)")
  if not qid or (verb ~= "reply" and verb ~= "cached") then return nil end
  if value == "<CNAME>" then
    return { qid = qid, name = name, ip = nil }
  end
  -- #1668: ingest BOTH v4 and v6 answers. The cache is keyed by IP string and
  -- conntrack flows can be v6 (the agent maintains parallel eb6_/bl6_/
  -- resolved6_ sets for that exact reason). Dropping AAAA replies here was
  -- the root cause of v6 destinations showing up as bare literals in
  -- connection_events.
  if is_ipv4(value) or is_ipv6(value) then
    return { qid = qid, name = name, ip = value }
  end
  -- NXDOMAIN, NODATA-IPvX, etc. — skip.
  return nil
end

-- classify_query_result(line) → bounded result enum string | nil   (#1302)
--
-- Maps a dnsmasq `reply <name> is <value>` / `cached <name> is <value>` line to
-- the bounded `dns_queries_total{result}` enum. Both `reply` (upstream answer)
-- and `cached` (answered from dnsmasq's own cache) lines are terminal outcomes
-- worth counting. The result enum is intentionally small and bounded:
--
--   resolved  — an A/AAAA answer (`is <ipv4>` / `is <ipv6>`)
--   nxdomain  — `is NXDOMAIN`
--   servfail  — `is SERVFAIL`
--   refused   — `is REFUSED`
--   nodata    — `is NODATA` / `is NODATA-IPv4` / `is NODATA-IPv6`
--
-- Non-terminal CNAME hops (`is <CNAME>`) return nil so a single resolution
-- isn't counted once per chain link. This is a per-answer-record tally, not a
-- per-query one: a multi-record answer emits one `reply` line per record, so
-- `resolved` slightly over-counts vs. distinct queries. That's acceptable — the
-- metric exists for query *volume* and the resolved:failure *ratio* an operator
-- alerts on (#1302), not exact per-query accounting. Query (not reply) lines,
-- and any line we don't recognise, return nil.
function M.classify_query_result(line)
  if type(line) ~= "string" or line == "" then return nil end
  local verb, value = line:match("%d+%s+%S+/%d+%s+(%a+)%s+%S+%s+is%s+(%S+)")
  if not verb or (verb ~= "reply" and verb ~= "cached") then return nil end
  if value == "<CNAME>" then return nil end -- non-terminal hop
  if is_ipv4(value) or is_ipv6(value) then return "resolved" end
  if value == "NXDOMAIN" then return "nxdomain" end
  if value == "SERVFAIL" then return "servfail" end
  if value == "REFUSED" then return "refused" end
  if value == "NODATA" or value:match("^NODATA%-") then return "nodata" end
  return nil
end

-- parse_resolved_reply(line) → { client_ip, name, ip, family } | nil
--
-- Extracts the dnsmasq client IP (the device that issued the query — the
-- "source IP" we look up in the DHCP lease table to find the MAC), the
-- answered hostname, and the answer IP from a `reply`/`cached` log line.
-- Used by wifihaven-dns-tail:
--   - #505: per-MAC blockIpOnly resolved_<mac> / resolved6_<mac> sets
--     (keyed by client_ip → MAC); dnsmasq 2.91's `nftset=tag:` is broken
--     (#496) and per-MAC scoping is semantically essential there.
--   - #515: per-host extraBlocked eb_<sanhost> / eb6_<sanhost> sets,
--     populated out-of-band for the same reason — dnsmasq's `nftset=/h/...`
--     path is unreliable in the deployed build (the sets stayed empty
--     under the e2e test that resolved the host through the LAN resolver).
--
-- Unlike parse_reply_line (which only returns qid + name + ip, since the
-- attribution cache doesn't care about family), this returns the family
-- explicitly so dns-tail can pick the right per-MAC resolved_ / resolved6_
-- and eb_ / eb6_ set to populate. Both parsers ingest both families post
-- #1668; the family tag here is for routing the side-effect, not gating it.
-- `family` is "v4" or "v6".
function M.parse_resolved_reply(line)
  if type(line) ~= "string" or line == "" then return nil end
  local qid, client_ip, verb, name, value = line:match(
    "(%d+)%s+(%S+)/%d+%s+(%a+)%s+(%S+)%s+is%s+(%S+)")
  if not qid or (verb ~= "reply" and verb ~= "cached") then return nil end
  -- client_ip in the log line includes a port suffix `/53` etc. — already
  -- stripped by the pattern via `/%d+`. For LAN clients we expect a v4 IP
  -- (DHCPv4 lease maps client_ip → MAC).
  if not is_ipv4(client_ip) then return nil end
  if is_ipv4(value) then
    return { client_ip = client_ip, name = name, ip = value, family = "v4" }
  end
  if is_ipv6(value) then
    return { client_ip = client_ip, name = name, ip = value, family = "v6" }
  end
  -- <CNAME>, NXDOMAIN, NODATA-IPvX → not a usable answer.
  return nil
end

-- format_query_results(tbl) → string   (#1302)
-- Serialise a { [result] = count } tally to newline-delimited "<result>\t<n>"
-- rows in a stable (sorted) order so the file is byte-stable across flushes.
-- This is the wire between the dns-tail sidecar (writer) and the agent (reader).
function M.format_query_results(tbl)
  local keys = {}
  for k in pairs(tbl or {}) do keys[#keys + 1] = k end
  table.sort(keys)
  local lines = {}
  for _, k in ipairs(keys) do
    lines[#lines + 1] = string.format("%s\t%d", k, tbl[k])
  end
  return table.concat(lines, "\n") .. (#lines > 0 and "\n" or "")
end

-- parse_query_results(text) → { [result] = count }   (#1302)
-- Inverse of format_query_results. Tolerant of an absent/empty file (→ {}).
function M.parse_query_results(text)
  local out = {}
  if type(text) ~= "string" or text == "" then return out end
  for line in text:gmatch("[^\n]+") do
    local result, count = line:match("^(%S+)\t(%d+)$")
    if result and count then
      out[result] = tonumber(count)
    end
  end
  return out
end

-- ---------------------------------------------------------------------------
-- Cache instance
-- ---------------------------------------------------------------------------

function M.new(opts)
  opts                = opts or {}
  local ttl           = opts.ttl_seconds or 3600
  local max_entries   = opts.max_entries or 10000
  local max_pending   = opts.max_pending or 1024
  -- CNAME-alias map bound. Distinct CDN target names observed across chains;
  -- shares the entry-cache order of magnitude (#1344).
  local max_aliases   = opts.max_aliases or 10000
  local now_fn        = opts.now_fn      or os.time

  -- ip → { hostname, ts, seq }
  local entries       = {}
  local entry_count   = 0
  -- qid → { qname, ts, seq }
  local pending       = {}
  local pending_count = 0
  -- CNAME target name → { head, ts, seq } (#1344).
  -- Learned from observed CNAME chains: every non-head owner name in a chain
  -- maps to that chain's head (the queried name). Lets us attribute a flow
  -- back to the branded host even when the client re-queries the final CNAME
  -- target directly (Apple devices do this), where the qname on the wire is
  -- the CDN target itself.
  local aliases       = {}
  local alias_count   = 0
  -- monotonically increasing sequence number for LRU-by-insertion eviction
  local seq           = 0

  local function next_seq()
    seq = seq + 1
    return seq
  end

  local function evict_oldest_entry()
    local oldest_ip, oldest_seq
    for ip, e in pairs(entries) do
      if not oldest_seq or e.seq < oldest_seq then
        oldest_ip, oldest_seq = ip, e.seq
      end
    end
    if oldest_ip then
      entries[oldest_ip] = nil
      entry_count = entry_count - 1
    end
  end

  local function evict_oldest_pending()
    local oldest_qid, oldest_seq
    for qid, p in pairs(pending) do
      if not oldest_seq or p.seq < oldest_seq then
        oldest_qid, oldest_seq = qid, p.seq
      end
    end
    if oldest_qid then
      pending[oldest_qid] = nil
      pending_count = pending_count - 1
    end
  end

  local function store(ip, hostname, now)
    if entries[ip] == nil then
      if entry_count >= max_entries then
        evict_oldest_entry()
      end
      entry_count = entry_count + 1
    end
    entries[ip] = { hostname = hostname, ts = now, seq = next_seq() }
  end

  local function remember_query(qid, qname, now)
    if pending[qid] == nil then
      if pending_count >= max_pending then
        evict_oldest_pending()
      end
      pending_count = pending_count + 1
    end
    pending[qid] = { qname = qname, ts = now, seq = next_seq() }
  end

  local function evict_oldest_alias()
    local oldest_name, oldest_seq
    for name, a in pairs(aliases) do
      if not oldest_seq or a.seq < oldest_seq then
        oldest_name, oldest_seq = name, a.seq
      end
    end
    if oldest_name then
      aliases[oldest_name] = nil
      alias_count = alias_count - 1
    end
  end

  -- resolve_head(name) → the branded chain head `name` is a CNAME alias of, or
  -- `name` itself when it is not a known alias. Bounded multi-hop with a cycle
  -- guard so a re-queried intermediate hop (whose own alias was learned from a
  -- longer chain) still resolves to the ultimate head (#1344). Expired alias
  -- edges are ignored (and lazily dropped) so a stale CDN→brand mapping can't
  -- pin an IP to the wrong brand forever.
  local function resolve_head(name)
    local now  = now_fn()
    local seen = {}
    for _ = 1, 8 do
      local a = aliases[name]
      if not a then break end
      if (now - a.ts) > ttl then
        aliases[name] = nil
        alias_count = alias_count - 1
        break
      end
      if seen[name] then break end
      seen[name] = true
      name = a.head
    end
    return name
  end

  local function remember_alias(target, head, now)
    if target == head then return end
    if aliases[target] == nil then
      if alias_count >= max_aliases then
        evict_oldest_alias()
      end
      alias_count = alias_count + 1
    end
    aliases[target] = { head = head, ts = now, seq = next_seq() }
  end

  local self = {}

  function self.ingest_line(line)
    local now = now_fn()
    local q = M.parse_query_line(line)
    if q then
      remember_query(q.qid, q.qname, now)
      return
    end
    local r = M.parse_reply_line(line)
    if not r then return end
    local p = pending[r.qid]
    -- The chain head is the queried name (pending qname); fall back to the
    -- reply owner when we never saw the query line. Resolve it through the
    -- alias map so a re-queried CNAME target attributes back to the branded
    -- host the target was first observed under (#1344).
    local head = resolve_head(p and p.qname or r.name)
    -- Record the CNAME-alias edge: this reply's owner name (r.name) is a name
    -- reachable within `head`'s chain, so a later DIRECT query for r.name can
    -- be attributed back to `head`. Skip the head's own line (target == head).
    if r.name ~= head then
      remember_alias(r.name, head, now)
    end
    if r.ip then
      store(r.ip, head, now)
    end
  end

  function self.lookup(ip)
    local e = entries[ip]
    if not e then return nil end
    if (now_fn() - e.ts) > ttl then
      entries[ip] = nil
      entry_count = entry_count - 1
      return nil
    end
    return e.hostname
  end

  function self.tick(_now)
    local now = _now or now_fn()
    for ip, e in pairs(entries) do
      if (now - e.ts) > ttl then
        entries[ip] = nil
        entry_count = entry_count - 1
      end
    end
    for qid, p in pairs(pending) do
      if (now - p.ts) > ttl then
        pending[qid] = nil
        pending_count = pending_count - 1
      end
    end
    for name, a in pairs(aliases) do
      if (now - a.ts) > ttl then
        aliases[name] = nil
        alias_count = alias_count - 1
      end
    end
  end

  function self.size() return entry_count end

  -- resolve_head(name) → the branded chain head `name` is a CNAME alias of, or
  -- `name` unchanged when it is not a known alias (#1344). Exposed publicly so
  -- the wifihaven-dns-tail sidecar can reuse the SAME learned alias memory to
  -- recover the brand for a directly-queried CDN target before suffix-matching
  -- it against the kernel ea_/eb_ sets (#1346) and the category bl_ sets
  -- (#1348). Internally `resolve_head` is already used by ingest_line; this is
  -- a thin public passthrough.
  function self.resolve_head(name)
    return resolve_head(name)
  end

  -- insert_sni(dst_ip, server_name, now) — record an (ip → hostname) mapping
  -- learned from a TLS ClientHello SNI extension (#573). The wifihaven-sni-tail
  -- sidecar feeds these in via the same line stream as dnsmasq replies; this
  -- helper runs the server_name through the SAME CNAME-alias map (resolve_head)
  -- DNS ingest uses, so an SNI to a CDN leaf target attributes back to the
  -- branded chain head we already learned from the DNS path (#1344). A
  -- subsequent cache.lookup(dst_ip) then matches the same eb_/ea_/bl_ suffix
  -- walks the DNS-derived entries do — no router code is aware of the SNI
  -- origin. nil-safe.
  function self.insert_sni(dst_ip, server_name, now)
    if type(dst_ip) ~= "string" or dst_ip == "" then return end
    if type(server_name) ~= "string" or server_name == "" then return end
    now = now or now_fn()
    local head = resolve_head(server_name)
    store(dst_ip, head, now)
  end

  -- Serialise the live cache to a single newline-delimited string.
  -- Format: "<ip>\t<hostname>\t<ts>\n" per non-expired entry.
  function self.dump_text()
    local now = now_fn()
    local lines = {}
    for ip, e in pairs(entries) do
      if (now - e.ts) <= ttl then
        lines[#lines + 1] = string.format("%s\t%s\t%d", ip, e.hostname, e.ts)
      end
    end
    return table.concat(lines, "\n") .. (#lines > 0 and "\n" or "")
  end

  return self
end

-- ---------------------------------------------------------------------------
-- #1348: category-blocklist (bl_) dns-tail populator support.
--
-- bl_<id> / bl6_<id> category drop-sets are declared per blocklist id and
-- populated at DNS resolve time by dnsmasq's `nftset=/<member>/...#bl_<id>`
-- directive — which fires only for queries whose name suffix-matches a member
-- host. When a client re-queries a member's CNAME target DIRECTLY it lands on a
-- CDN-anycast IP dnsmasq never added to bl_<id>, so the category drop misses
-- and blocked content becomes reachable (a silent filter bypass). dns-tail
-- closes that gap exactly as it does for eb_ (#515): on each `reply <name> is
-- <ip>` it resolves <name> through the CNAME-alias map (resolve_head) and, if
-- the recovered brand is a blocklist member, adds the IP to that member's bl_
-- set. dns-tail knows which bl_ sets EXIST (from `nft list table`) but not
-- their MEMBERSHIP, so render.lua writes a host → set index it consumes here.
--
-- parse_blocklist_member_index(text) → { [host] = { {v4=set4, v6=set6}, … } }
-- Inverse of render.blocklist_member_index. Each line is
-- "<host>\t<bl_set>\t<bl6_set>"; a host present in multiple blocklists yields
-- multiple rows (one {v4,v6} pair each).
function M.parse_blocklist_member_index(text)
  local out = {}
  if type(text) ~= "string" or text == "" then return out end
  for line in text:gmatch("[^\n]+") do
    local host, set4, set6 = line:match("^(%S+)\t(%S+)\t(%S+)$")
    if host then
      local rows = out[host]
      if not rows then rows = {}; out[host] = rows end
      rows[#rows + 1] = { v4 = set4, v6 = set6 }
    end
  end
  return out
end

-- populate_bl(reply, member_index, resolve_head_fn, add_fn) → matched?
--
--   reply           — a parse_resolved_reply result { name, ip, family }.
--   member_index    — a parse_blocklist_member_index output.
--   resolve_head_fn — cache.resolve_head (attributes a directly-queried CDN
--                     target back to the branded chain head). nil-safe: when
--                     absent the answered name is used verbatim.
--   add_fn          — injected `add_fn(set_name, ip)` (dns-tail passes the real
--                     nft add-element; tests pass a collector).
--
-- Resolves reply.name → branded head, then walks the head's label suffixes
-- against the member index (mirroring dnsmasq's `/member/` subdomain match: a
-- member matches itself and every subdomain). For every matching member it
-- calls add_fn with the family-appropriate bl_/bl6_ set name, so an IP only
-- reachable via a directly-queried CNAME target still lands in the category
-- drop-set. A host in multiple blocklists adds to each. Returns true iff at
-- least one member matched (testability).
function M.populate_bl(reply, member_index, resolve_head_fn, add_fn)
  if type(reply) ~= "table" or not reply.name or not reply.ip then return false end
  if type(member_index) ~= "table" or type(add_fn) ~= "function" then return false end
  local key  = (reply.family == "v6") and "v6" or "v4"
  local name = resolve_head_fn and resolve_head_fn(reply.name) or reply.name
  local matched = false
  while name and name ~= "" do
    local rows = member_index[name]
    if rows then
      for _, r in ipairs(rows) do
        local set = r[key]
        if set then add_fn(set, reply.ip) end
      end
      matched = true
    end
    local dot = name:find("%.")
    if not dot then break end
    name = name:sub(dot + 1)
  end
  return matched
end

-- Parses the output of an instance's `dump_text()` and returns a plain
-- `{ip → hostname}` table with entries older than `ttl_seconds` (relative to
-- `now`) filtered out. Used by the main agent to consume the snapshot file
-- the dns-tail sidecar writes.
function M.load_table(text, ttl_seconds, now)
  local out = {}
  if type(text) ~= "string" or text == "" then return out end
  for line in text:gmatch("[^\n]+") do
    local ip, hostname, ts = line:match("^(%S+)\t(%S+)\t(%d+)$")
    if ip and hostname and ts then
      local ts_n = tonumber(ts)
      if ts_n and (now - ts_n) <= ttl_seconds then
        out[ip] = hostname
      end
    end
  end
  return out
end

return M
