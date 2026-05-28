-- nflog.lua — tail of the kernel nflog stream for forward-chain drops (#1122).
--
-- Why this module exists
-- ----------------------
-- conntrack -E NEW only fires for flows that are *confirmed* at the
-- postrouting hook (IPCT_NEW is emitted from __nf_conntrack_confirm). A
-- forward-chain drop on @blocked_macs / eb_<host> / bl_<id> / blockIpOnly
-- happens between prerouting and postrouting, so dropped flows never get
-- confirmed, never emit IPCT_NEW, and the conntrack watcher never sees
-- them. The Logs page therefore goes silent for blocked traffic — #1103.
--
-- Fix: every per-MAC drop rule emitted by render.lua now carries
--   `log group 1 counter drop comment "wh_drop:<mac>:<reason>"`
-- and this module tails the resulting nflog stream + synthesizes a
-- `connection_attempt` event for each dropped packet with allowed=false
-- and reason set from the comment. The event then takes the same ingest
-- + retry-queue path the conntrack watcher uses (#330).
--
-- Source of nflog lines
-- ---------------------
-- The reader is injectable as `read_fn`. Production wiring lives in
-- wifihaven-agent and uses io.popen("nft monitor trace ..."). Tests pass
-- a closure over a canned list so the parser is exercised without a live
-- kernel.
--
-- Line shape
-- ----------
-- The exact line framing varies by nflog consumer (ulogd2, nft monitor,
-- nflog2syslog), but every variant carries a recognisable
--   wh_drop:<mac>:<reason>
-- token followed by KEY=VALUE pairs that include SRC= and DST=. The parser
-- below ignores everything outside of these tokens, so it survives reframing
-- (timestamp prefix, kernel-log decoration, etc.).
--
-- Public API
-- ----------
--   nflog.parse_line(line)        -> {mac, reason, src_ip, dst_ip} | nil
--   nflog.build_event(opts)       -> connection_attempt event table
--   nflog.new_dedup(opts)         -> { allow(mac, dst_ip, hname) -> bool }
--   nflog.run(cfg)                -> blocking; reads from cfg.read_fn until nil

local M = {}

-- Defer to conntrack.gen_event_id for the idempotency-key generator so the
-- nflog path stamps event ids in exactly the same way (and tests can stub it
-- via the same package.loaded indirection conntrack uses).
local function default_gen_event_id()
  local ok, conntrack = pcall(require, "wifihaven.conntrack")
  if ok and conntrack and conntrack.gen_event_id then
    return conntrack.gen_event_id()
  end
  return nil
end

-- ---------------------------------------------------------------------------
-- parse_line(line) -> { mac, reason, src_ip, dst_ip } | nil
--
-- Extracts the four fields the agent needs to synthesize a connection_attempt
-- from a single nflog line. Returns nil for lines that don't carry the
-- wh_drop: token or that are missing SRC= / DST= (incomplete kernel-log
-- buffers, races during ruleset swap).
--
-- The mac portion of wh_drop:<mac>:<reason> is six colon-separated hex pairs
-- (17 chars); the reason runs to the next whitespace and may itself contain
-- colons (e.g. `category:ads`).
-- ---------------------------------------------------------------------------
function M.parse_line(line)
  if not line then return nil end
  -- Match the wh_drop:<mac>:<reason> token. `mac` is exactly six pairs of
  -- hex separated by colons. `reason` is greedy through any non-space chars
  -- so `category:ads` survives as one token. Anchoring on `wh_drop:` lets
  -- the parser ignore leading kernel-log fluff.
  local mac, reason = line:match("wh_drop:([0-9a-fA-F:][0-9a-fA-F:][0-9a-fA-F:][0-9a-fA-F:][0-9a-fA-F:][0-9a-fA-F:][0-9a-fA-F:][0-9a-fA-F:][0-9a-fA-F:][0-9a-fA-F:][0-9a-fA-F:][0-9a-fA-F:][0-9a-fA-F:][0-9a-fA-F:][0-9a-fA-F:][0-9a-fA-F:][0-9a-fA-F]):(%S+)")
  if not mac then return nil end
  local src_ip = line:match("SRC=(%S+)")
  local dst_ip = line:match("DST=(%S+)")
  if not (src_ip and dst_ip) then return nil end
  return { mac = mac:lower(), reason = reason, src_ip = src_ip, dst_ip = dst_ip }
end

-- ---------------------------------------------------------------------------
-- build_event(opts) -> connection_attempt event table
--
-- opts: { mac, dst_ip, hostname?, reason, ts, gen_event_id_fn? }
--
-- Mirrors conntrack.build_event's wire shape (#391 HostId tagged union, #338
-- eventId idempotency key) so the API ingest path can't tell synthesized
-- drop events from organically-observed flows.
-- ---------------------------------------------------------------------------
function M.build_event(opts)
  local host
  if opts.hostname then
    host = { type = "fqdn", value = opts.hostname }
  else
    local kind = (opts.dst_ip and opts.dst_ip:find(":", 1, true)) and "ipv6" or "ipv4"
    host = { type = kind, value = opts.dst_ip }
  end
  local gen = opts.gen_event_id_fn or default_gen_event_id
  return {
    ["type"] = "connection_attempt",
    mac      = opts.mac,
    host     = host,
    destIp   = opts.dst_ip,
    allowed  = false,
    reason   = opts.reason,
    ts       = opts.ts,
    eventId  = gen(),
  }
end

-- ---------------------------------------------------------------------------
-- new_dedup(opts) -> { allow(mac, dst_ip, hname) -> bool }
--
-- A SYN flood at a blocked MAC can produce thousands of identical nflog
-- entries per second. We collapse to one event per (mac, dst_ip, hname)
-- per `window_seconds`-window so the batch doesn't balloon. The window
-- is short on purpose — long enough to swallow retry storms (TCP SYN
-- retry up to 5 attempts ~= 60s), short enough that a quiet drop a minute
-- later still surfaces.
--
-- opts: { window_seconds = 60, now_fn = function() return monotonic_seconds() end }
-- ---------------------------------------------------------------------------
function M.new_dedup(opts)
  opts = opts or {}
  local window  = opts.window_seconds or 60
  local now_fn  = opts.now_fn or function()
    local ok, clock = pcall(require, "wifihaven.clock")
    if ok then return clock.monotonic_seconds() end
    return os.time()
  end
  local seen = {}
  local self = {}

  function self.allow(mac, dst_ip, hname)
    local now = now_fn()
    -- Use the literal "<nil>" sentinel so a nil hname (no DNS attribution)
    -- gets its own bucket distinct from any attributed hostname for the
    -- same (mac, dst_ip).
    local key  = (mac or "?") .. "|" .. (dst_ip or "?") .. "|" .. (hname or "<nil>")
    local last = seen[key]
    if last and (now - last) < window then return false end
    seen[key] = now
    return true
  end

  -- Optional GC sweep — callers running long-lived loops should periodically
  -- invoke gc(now) to drop entries older than the window. The tests don't
  -- need this so it's exposed but unused there.
  function self.gc(now)
    now = now or now_fn()
    for k, t in pairs(seen) do
      if (now - t) >= window then seen[k] = nil end
    end
  end

  return self
end

-- ---------------------------------------------------------------------------
-- run(cfg) — blocking event loop; called from wifihaven-agent
--
-- cfg: {
--   read_fn         function() -> string|nil   read next nflog line; nil = EOF
--   batcher         { add(event) }             (typically the conntrack batcher)
--   lookup_hostname function(ip) -> string|nil dns_log cache lookup (optional)
--   ts_fn           function() -> ISO8601      timestamp for each event
--   now_fn          function() -> seconds      monotonic seconds for dedup
--   window_seconds  int                        dedup window (default 60)
--   log             logger                     optional; default stderr
--   on_event        function(ev)               optional; fires after batcher.add
-- }
-- ---------------------------------------------------------------------------
function M.run(cfg)
  local read_fn = cfg.read_fn
  local batcher = cfg.batcher
  local ts_fn   = cfg.ts_fn or function() return os.date("!%Y-%m-%dT%H:%M:%SZ") end
  local lookup  = cfg.lookup_hostname or function(_) return nil end
  local dedup   = M.new_dedup({
    window_seconds = cfg.window_seconds or 60,
    now_fn         = cfg.now_fn,
  })

  while true do
    local line = read_fn()
    if not line then break end
    local parsed = M.parse_line(line)
    if parsed then
      local hname = lookup(parsed.dst_ip)
      if dedup.allow(parsed.mac, parsed.dst_ip, hname) then
        local ev = M.build_event({
          mac      = parsed.mac,
          dst_ip   = parsed.dst_ip,
          hostname = hname,
          reason   = parsed.reason,
          ts       = ts_fn(),
        })
        batcher.add(ev)
        if cfg.on_event then cfg.on_event(ev) end
      end
    end
  end
end

return M
