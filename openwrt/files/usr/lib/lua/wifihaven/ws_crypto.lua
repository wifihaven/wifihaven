-- ws_crypto.lua — SHA-1 + Base64 + random for the websocket handshake (#1845).
--
-- The handshake needs exactly three primitives, ALL only at connect time (once
-- per connection, never on the steady-state data path), so cost is irrelevant:
--   * SHA-1 of the 60-byte key||GUID string  (verify Sec-WebSocket-Accept)
--   * Base64 encode                          (Sec-WebSocket-Key + the digest)
--   * 16 random bytes                         (the client nonce)
--
-- Three backends, same shape as quic.lua's crypto injection:
--   * M.pure()            — dependency-free pure-Lua SHA-1/Base64 + a seeded
--                           PRNG. Used by the busted tests on macOS (no native
--                           deps) and as a fallback. The PRNG is NOT for the
--                           production nonce; a CSPRNG backs that on the target.
--   * M.luaossl()         — luaossl-backed (`openssl.digest`/`openssl.rand`).
--                           luaossl is ALREADY pulled in as cqueues' TLS
--                           dependency, so the ws client gets a CSPRNG + native
--                           SHA-1 for free with no extra package. Base64 stays
--                           pure (luaossl exposes no base64; it's connect-time
--                           only). PREFERRED on the router.
--   * M.openssl(openssl)  — lua-openssl (zhaozg) backed, the crypto lib already
--                           in the agent DEPENDS. Offered for symmetry if the
--                           sidecar would rather not also load luaossl's crypto
--                           submodules; the TLS context still needs luaossl.
--
-- ws_frame.lua takes these as injected fns, so the framing logic never imports
-- a backend directly and stays unit-testable.

local M = {}

-- ── pure-Lua Base64 ─────────────────────────────────────────────────────────
local B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

local function base64_encode(data)
  local out = {}
  local len = #data
  local i = 1
  while i <= len do
    local b1 = data:byte(i)
    local b2 = data:byte(i + 1)
    local b3 = data:byte(i + 2)
    local n = b1 * 65536 + (b2 or 0) * 256 + (b3 or 0)
    local c1 = math.floor(n / 262144) % 64
    local c2 = math.floor(n / 4096) % 64
    local c3 = math.floor(n / 64) % 64
    local c4 = n % 64
    out[#out + 1] = B64:sub(c1 + 1, c1 + 1)
    out[#out + 1] = B64:sub(c2 + 1, c2 + 1)
    out[#out + 1] = b2 and B64:sub(c3 + 1, c3 + 1) or "="
    out[#out + 1] = b3 and B64:sub(c4 + 1, c4 + 1) or "="
    i = i + 3
  end
  return table.concat(out)
end
M.base64_encode = base64_encode

-- ── pure-Lua SHA-1 (RFC 3174), arithmetic only ──────────────────────────────
-- 32-bit helpers built on Lua doubles (exact to 2^53), so no `bit` lib needed.
local function band32(a, b)
  local r, p = 0, 1
  for _ = 1, 32 do
    local abit = a % 2
    local bbit = b % 2
    if abit == 1 and bbit == 1 then r = r + p end
    a = (a - abit) / 2; b = (b - bbit) / 2; p = p * 2
  end
  return r
end
local function bor32(a, b)
  local r, p = 0, 1
  for _ = 1, 32 do
    local abit = a % 2
    local bbit = b % 2
    if abit == 1 or bbit == 1 then r = r + p end
    a = (a - abit) / 2; b = (b - bbit) / 2; p = p * 2
  end
  return r
end
local function bxor32(a, b)
  local r, p = 0, 1
  for _ = 1, 32 do
    local abit = a % 2
    local bbit = b % 2
    if abit ~= bbit then r = r + p end
    a = (a - abit) / 2; b = (b - bbit) / 2; p = p * 2
  end
  return r
end
local function bnot32(a) return 4294967295 - a end
-- 32-bit left rotate.
local function rol32(v, n)
  local hi = (v * (2 ^ n)) % 4294967296
  local lo = math.floor(v / (2 ^ (32 - n)))
  return hi + lo
end

local function sha1_bin(msg)
  local h0, h1, h2, h3, h4 =
    0x67452301, 0xEFCDAB89, 0x98BADCFE, 0x10325476, 0xC3D2E1F0

  local ml = #msg * 8
  msg = msg .. "\128"
  while (#msg % 64) ~= 56 do msg = msg .. "\0" end
  -- 64-bit big-endian length; our messages are tiny so the high word is 0.
  msg = msg .. string.char(0, 0, 0, 0,
    math.floor(ml / 16777216) % 256,
    math.floor(ml / 65536) % 256,
    math.floor(ml / 256) % 256,
    ml % 256)

  for chunk = 1, #msg, 64 do
    local w = {}
    for i = 0, 15 do
      local p = chunk + i * 4
      w[i] = msg:byte(p) * 16777216 + msg:byte(p + 1) * 65536
           + msg:byte(p + 2) * 256 + msg:byte(p + 3)
    end
    for i = 16, 79 do
      w[i] = rol32(bxor32(bxor32(bxor32(w[i - 3], w[i - 8]), w[i - 14]), w[i - 16]), 1)
    end

    local a, b, c, d, e = h0, h1, h2, h3, h4
    for i = 0, 79 do
      local f, k
      if i < 20 then
        f = bor32(band32(b, c), band32(bnot32(b), d)); k = 0x5A827999
      elseif i < 40 then
        f = bxor32(bxor32(b, c), d); k = 0x6ED9EBA1
      elseif i < 60 then
        f = bor32(bor32(band32(b, c), band32(b, d)), band32(c, d)); k = 0x8F1BBCDC
      else
        f = bxor32(bxor32(b, c), d); k = 0xCA62C1D6
      end
      local tmp = (rol32(a, 5) + f + e + k + w[i]) % 4294967296
      e = d; d = c; c = rol32(b, 30); b = a; a = tmp
    end

    h0 = (h0 + a) % 4294967296
    h1 = (h1 + b) % 4294967296
    h2 = (h2 + c) % 4294967296
    h3 = (h3 + d) % 4294967296
    h4 = (h4 + e) % 4294967296
  end

  local function u32(v)
    return string.char(
      math.floor(v / 16777216) % 256,
      math.floor(v / 65536) % 256,
      math.floor(v / 256) % 256,
      v % 256)
  end
  return u32(h0) .. u32(h1) .. u32(h2) .. u32(h3) .. u32(h4)
end
M.sha1_bin = sha1_bin

-- NOT cryptographically secure — fine for tests, NOT for the production nonce.
local function weak_rand(n)
  local out = {}
  for i = 1, n do out[i] = string.char(math.random(0, 255)) end
  return table.concat(out)
end

-- Pure backend: { sha1_bin, base64, rand }. Use in tests / as a fallback.
function M.pure()
  return { sha1_bin = sha1_bin, base64 = base64_encode, rand = weak_rand }
end

-- luaossl backend (preferred on the router — luaossl is already present as
-- cqueues' TLS dependency). SHA-1 + CSPRNG native; base64 stays pure (luaossl
-- exposes none, and it's a once-per-connect cost).
function M.luaossl()
  local digest = require("openssl.digest")
  local rand   = require("openssl.rand")
  return {
    sha1_bin = function(s) return digest.new("sha1"):update(s):final() end,
    base64   = base64_encode,
    rand     = function(n) return rand.bytes(n) end,
  }
end

-- lua-openssl (zhaozg) backend. `openssl` is the module the
-- agent already depends on. Falls back to the pure SHA-1/Base64 if a given
-- entry point is missing in the installed lua-openssl build, but ALWAYS uses
-- openssl.random for the nonce (the one primitive that must be a CSPRNG).
function M.openssl(openssl)
  local digest_sha1 = function(s)
    -- lua-openssl: openssl.digest.new("sha1"):final(s) returns hex by default;
    -- pass false (or the binary flag) for raw bytes depending on build. We do
    -- the simplest portable thing: hash with the pure impl if the binary form
    -- isn't exposed. The handshake runs once per connect, so this is moot cost.
    local ok, d = pcall(function()
      return openssl.digest.digest("sha1", s, true) -- raw=true → 20 bytes
    end)
    if ok and d and #d == 20 then return d end
    return sha1_bin(s)
  end
  local b64 = function(s)
    local ok, e = pcall(function() return openssl.base64(s, true, true) end)
    if ok and e then return (e:gsub("%s+", "")) end
    return base64_encode(s)
  end
  local rand = function(n)
    return openssl.random(n) -- CSPRNG; let it error loudly if unavailable.
  end
  return { sha1_bin = digest_sha1, base64 = b64, rand = rand }
end

return M
