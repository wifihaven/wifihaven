-- Test-only shim for OpenWrt's luci-lib-jsonc (luci.jsonc). The agent uses that
-- C module in production; it isn't on luarocks, so local/CI tests load this
-- shim instead. It MUST faithfully reproduce luci.jsonc's observed behavior so
-- that bytes generated here — above all the committed golden fixtures under
-- shared/contract/router-to-api/ — equal what the router actually emits on the
-- wire (#1367).
--
-- We use lua-cjson for PARSE only. For STRINGIFY we hand-roll an encoder,
-- because cjson diverges from luci.jsonc in ways that have already shipped
-- production bugs (#1365) or would corrupt fixture fidelity:
--
--   case             | luci.jsonc (prod) | lua-cjson
--   -----------------+-------------------+-----------------------
--   empty Lua table  | []                | {}
--   fractional double| %.17g full prec.  | capped at 16 sig figs
--   forward slash    | a\/b              | a\/b (matches)
--
-- The number/empty rules were captured directly from the real module on an
-- OpenWrt target; see test/jsonc_shim_spec.lua for the pinned expectations.
local cjson = require("cjson")

local M = {}

-- luci.jsonc.null sentinel (a real value on the router). Backed by cjson's so
-- that a parsed JSON `null` round-trips through stringify back to `null`.
M.null = cjson.null

function M.parse(s)
  local ok, val = pcall(cjson.decode, s)
  if not ok then return nil end
  return val
end

-- JSON string escaping matching luci.jsonc / json-c: the two mandatory escapes
-- (" and \), the shorthand control escapes, `/` → `\/` (json-c default), and
-- `\uXXXX` for any remaining control character.
local SHORT = {
  ['"'] = '\\"', ['\\'] = '\\\\', ['/'] = '\\/',
  ['\b'] = '\\b', ['\f'] = '\\f', ['\n'] = '\\n',
  ['\r'] = '\\r', ['\t'] = '\\t',
}
local function escape_str(s)
  return '"' .. (s:gsub('[%z\1-\31"\\/]', function(c)
    return SHORT[c] or string.format("\\u%04x", string.byte(c))
  end)) .. '"'
end

-- luci.jsonc prints integral doubles with no decimal point (880 → "880",
-- 1.0 → "1") and fractional doubles at full IEEE-754 round-trip precision
-- (0.82 → "0.81999999999999995"). `%.17g` reproduces both exactly — verified
-- against the real module (#1367).
local function format_num(n)
  return string.format("%.17g", n)
end

-- An empty Lua table is ambiguous; luci.jsonc always renders it as `[]`, never
-- `{}`. A table with only contiguous integer keys 1..n is an array; anything
-- else is an object.
local function is_array(t)
  local n = 0
  for k in pairs(t) do
    if type(k) ~= "number" then return false end
    n = n + 1
  end
  for i = 1, n do
    if t[i] == nil then return false end
  end
  return true -- empty table → array, matching luci.jsonc
end

local encode
encode = function(v)
  if v == nil or v == M.null then return "null" end
  local t = type(v)
  if t == "boolean" then return v and "true" or "false" end
  if t == "number" then return format_num(v) end
  if t == "string" then return escape_str(v) end
  if t == "table" then
    if is_array(v) then
      local parts = {}
      for i = 1, #v do parts[i] = encode(v[i]) end
      return "[" .. table.concat(parts, ",") .. "]"
    end
    -- Object. luci/json-c key order is unspecified; we sort for determinism
    -- (no caller depends on luci's order — every consumer parses the bytes
    -- back, and the contract fixture generator imposes its own key order).
    local keys = {}
    for k in pairs(v) do keys[#keys + 1] = k end
    table.sort(keys, function(a, b) return tostring(a) < tostring(b) end)
    local parts = {}
    for _, k in ipairs(keys) do
      parts[#parts + 1] = escape_str(tostring(k)) .. ":" .. encode(v[k])
    end
    return "{" .. table.concat(parts, ",") .. "}"
  end
  error("luci.jsonc shim: cannot encode value of type " .. t)
end

function M.stringify(v)
  return encode(v)
end

return M
