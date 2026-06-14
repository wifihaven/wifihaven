-- sni_reassembly.lua — per-(TCP 4-tuple) ClientHello reassembly buffer (#1653).
--
-- Background: sni.lua's parse_client_hello v1 (#573) handles a TLS ClientHello
-- that fits in a single captured TCP segment. As TLS 1.3 hybrid PQ key shares
-- roll out, the ClientHello increasingly exceeds one MSS (~1460 B), so the
-- server_name extension falls into segment 2 and the v1 parser sees an
-- "incomplete" first segment and returns nil. This module stitches the bytes
-- of a single flow's handshake together across segments, then feeds the
-- concatenated payload back into the SAME parse primitive (no fork).
--
-- Memory safety on the router (tmpfs / low RAM — see AGENTS.md
-- §bounded-tmp-writes / #1647): the buffer is bounded three ways and every
-- bound is pinned by a test in sni_reassembly_spec.lua.
--   - per-flow byte cap   (cap_bytes; default 8 KB) — bigger than this is
--                         suspicious / adversarial; the flow is dropped, not
--                         truncated, so we never feed a partial buffer back
--                         into the parser with confusing semantics.
--   - concurrent-flow cap (cap_flows; default 64) — LRU eviction by oldest
--                         `last` access timestamp. Worst-case memory =
--                         cap_flows * cap_bytes ≈ 512 KB, an order of
--                         magnitude under the smallest supported device's
--                         free RAM headroom.
--   - TTL                 (ttl_s; default 5 s) — TLS handshakes complete in
--                         well under one second; 5 s is generous and stops a
--                         half-open flow from sitting in the buffer forever.
--                         Evaluated lazily on `feed` for the key being
--                         touched; a periodic `evict_expired()` sweep clears
--                         the rest (the sidecar calls it on its 30 s tick).
--
-- The clock is injected (opts.now_fn) so tests can advance time
-- deterministically — same pattern as wifihaven.clock elsewhere in the agent.
--
-- Public API:
--   M.new(opts) -> buffer
--     opts.cap_bytes (int, default 8192)
--     opts.cap_flows (int, default 64)
--     opts.ttl_s     (int, default 5)
--     opts.now_fn    (function() -> int seconds; default os.time)
--
--   buffer:feed(key, payload) -> result, sni_string?
--     key is the 4-tuple string (caller decides the format).
--     payload is the raw TCP payload bytes of one segment.
--     result ∈ {
--       "parsed"          — single-segment CH parsed cleanly; no buffer entry.
--       "reassembled"     — multi-segment CH stitched and parsed; buffer cleared.
--       "ech"             — ECH-bearing ClientHello (#1650). sni_string is the
--                           outer/public SNI when present, nil otherwise. This
--                           bucket overrides parsed/reassembled so operators
--                           see ECH adoption distinctly.
--       "incomplete"      — buffered (or updated) for the next segment.
--       "dropped_byte_cap"— would exceed per-flow byte cap; flow dropped.
--       "not_handshake"   — payload (with no buffered context) is not a TLS
--                           handshake start — typically a continuation segment
--                           for a flow we never saw segment 1 of.
--       "no_sni"          — well-formed ClientHello with no usable SNI.
--       "malformed"       — structurally invalid framing.
--     }
--
--   buffer:size()           -> current number of buffered flows.
--   buffer:evict_expired()  -> int (removed) — sweep all entries older than TTL.

local sni = require("wifihaven.sni")

local M = {}

function M.new(opts)
  opts = opts or {}
  local self = {
    buffers   = {},
    n         = 0,
    cap_bytes = opts.cap_bytes or 8192,
    cap_flows = opts.cap_flows or 64,
    ttl_s     = opts.ttl_s     or 5,
    now_fn    = opts.now_fn    or os.time,
  }
  return setmetatable(self, { __index = M })
end

function M:size()
  return self.n
end

function M:_drop(key)
  if self.buffers[key] then
    self.buffers[key] = nil
    self.n = self.n - 1
  end
end

-- O(n) scan over `buffers` for the entry with the smallest `last`. n is
-- bounded by cap_flows (default 64) so this is trivial — a heap/list would
-- add bug surface without measurable savings.
function M:_evict_lru()
  local oldest_key, oldest_t = nil, math.huge
  for k, v in pairs(self.buffers) do
    if v.last < oldest_t then
      oldest_t = v.last
      oldest_key = k
    end
  end
  if oldest_key then self:_drop(oldest_key) end
end

function M:evict_expired()
  local now = self.now_fn()
  local cutoff = now - self.ttl_s
  local removed = 0
  -- Setting buffers[k] = nil during pairs() iteration is well-defined in Lua
  -- for the key currently being visited.
  for k, v in pairs(self.buffers) do
    if v.created < cutoff then
      self.buffers[k] = nil
      removed = removed + 1
    end
  end
  self.n = self.n - removed
  return removed
end

function M:feed(key, payload)
  local now = self.now_fn()

  -- Lazy TTL on the key being touched. A stale entry is dropped before we
  -- consider appending — a continuation arriving 5 s after segment 1 is in
  -- practice a separate handshake (or a stuck flow we no longer care about).
  local entry = self.buffers[key]
  if entry and (now - entry.created) > self.ttl_s then
    self:_drop(key)
    entry = nil
  end

  local data
  if entry then
    -- Continuation: would the concatenation exceed the byte cap? Drop the
    -- flow entirely rather than feed a partial buffer back into the parser
    -- — the partial would be ambiguous semantically and could attribute
    -- the wrong SNI to a downstream cache lookup. The `truncated` bucket on
    -- the existing sni_clienthellos_total counter is the right home for
    -- the "we saw bytes but bailed" signal.
    if (#entry.data + #payload) > self.cap_bytes then
      self:_drop(key)
      return "dropped_byte_cap"
    end
    data = entry.data .. payload
  else
    -- No buffer yet: a single segment whose payload alone already exceeds
    -- the byte cap is dropped — anything that large is either malformed or
    -- adversarial.
    if #payload > self.cap_bytes then
      return "dropped_byte_cap"
    end
    data = payload
  end

  local sn, reason = sni.parse_client_hello(data)

  -- ECH (#1650) is terminal regardless of whether an outer/public SNI was
  -- present. Override the parsed/reassembled bucket so operators see the
  -- ECH-attributed fraction distinctly on sni_clienthellos_total{result=ech}.
  if reason == "ech" then
    if entry then self:_drop(key) end
    return "ech", sn
  end

  if sn then
    -- Success — single-source-of-truth parser owns the decode.
    if entry then
      self:_drop(key)
      return "reassembled", sn
    end
    return "parsed", sn
  end

  if reason == "incomplete" then
    -- Buffer (or update). A `not_handshake` first byte without an existing
    -- buffer goes through the no-entry branch below — we only get here when
    -- the payload begins as a valid TLS handshake whose declared length
    -- runs past the captured bytes.
    if entry then
      entry.data = data
      entry.last = now
    else
      if self.n >= self.cap_flows then
        self:_evict_lru()
      end
      self.buffers[key] = { data = data, created = now, last = now }
      self.n = self.n + 1
    end
    return "incomplete"
  end

  -- Definitive failure: clear any buffered partial for this key and surface
  -- the reason verbatim. The sidecar maps these onto the existing
  -- sni_clienthellos_total{result} enum.
  if entry then self:_drop(key) end
  return reason
end

return M
