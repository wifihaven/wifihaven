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

local M = {}

-- ---------------------------------------------------------------------------
-- Line parsers
-- ---------------------------------------------------------------------------

-- A dnsmasq log line under --log-queries=extra has the shape (with or without
-- a syslog prefix, depending on log-facility):
--
--   [<syslog prefix> dnsmasq[pid]: ]<qid> <client_ip>/<port> query[A] <qname> from <src_ip>
--   [<syslog prefix> dnsmasq[pid]: ]<qid> <client_ip>/<port> reply <name> is <value>
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

function M.parse_reply_line(line)
  if type(line) ~= "string" or line == "" then return nil end
  local qid, name, value = line:match("(%d+)%s+%S+/%d+%s+reply%s+(%S+)%s+is%s+(%S+)")
  if not qid then return nil end
  if value == "<CNAME>" then
    return { qid = qid, name = name, ip = nil }
  end
  if is_ipv4(value) then
    return { qid = qid, name = name, ip = value }
  end
  -- NXDOMAIN, NODATA-IPvX, IPv6 literals, etc. — skip.
  return nil
end

-- ---------------------------------------------------------------------------
-- Cache instance
-- ---------------------------------------------------------------------------

function M.new(opts)
  opts                = opts or {}
  local ttl           = opts.ttl_seconds or 3600
  local max_entries   = opts.max_entries or 10000
  local max_pending   = opts.max_pending or 1024
  local now_fn        = opts.now_fn      or os.time

  -- ip → { hostname, ts, seq }
  local entries       = {}
  local entry_count   = 0
  -- qid → { qname, ts, seq }
  local pending       = {}
  local pending_count = 0
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
    local original = p and p.qname or r.name
    if r.ip then
      store(r.ip, original, now)
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
  end

  function self.size() return entry_count end

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
