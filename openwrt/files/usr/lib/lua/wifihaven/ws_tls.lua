-- ws_tls.lua — build the client TLS context for the wifihaven-ws sidecar.
--
-- Split out of ws_client.lua for #2153: the wss:// prod cutover (#1023) failed
-- every handshake with `starttls: -1935895353` because luaossl's
-- `openssl.ssl.context.new()` starts with an EMPTY trust store, so VERIFY_PEER
-- had nothing to chain against. The plain-ws:// dev/test benches never took the
-- TLS branch, so it shipped untested. Two things were wrong and both are fixed
-- here (each PROVEN on the live Lua-5.1 + packaged-cqueues/luaossl router target,
-- see the #2153 PR for the on-device handshake evidence):
--
--   1. Empty trust store. Load the system CA bundle
--      (/etc/ssl/certs/ca-certificates.crt, shipped by the `ca-bundle` package
--      which is now a hard DEPENDS) into the context store before starttls.
--   2. No hostname check. cqueues/luaossl does NOT apply the equivalent of
--      X509_VERIFY_PARAM_set1_host automatically: a valid-chain cert for ANY
--      host would otherwise pass — a control-channel MITM hole. build_context
--      binds the connect host to the context verify param
--      (openssl.x509.verify_param:setHost() + context:setParam()).
--
-- #2182 — the context verify-param binding above is NOT enough on its own. It
-- takes effect on newer luaossl/OpenSSL (proven on-device on OpenWrt 25.12 /
-- luaossl-20220711 / OpenSSL 3.5: wrong.host.badssl.com is REJECTED at
-- starttls), but it is a silent NO-OP on the OLDER packaged-feed luaossl the CI
-- VM / prod images ship — there the same wrong-name cert was ACCEPTED (Gate 3a,
-- run 29259421130). luaossl exposes no X509_check_host wrapper (cert:checkHost
-- is absent on-target), so ws_client ALSO does an explicit post-handshake peer
-- identity check via M.peer_dns_names() + M.host_matches() below — pure-Lua
-- RFC 6125 matching that holds on every luaossl version. The context binding is
-- kept as defence-in-depth (it rejects earlier, at the handshake, where it works).
--
-- The luaossl modules are INJECTED (the `deps` table) rather than required at
-- the top of this file, so build_context() is unit-testable on the macOS busted
-- host — which has neither cqueues nor luaossl — via ws_tls_spec.lua. ws_client
-- passes the real modules on the router.

local M = {}

-- OpenWrt's system CA bundle, materialized by the `ca-bundle` package. Proven
-- present + sufficient on the target (`openssl s_client` verifies against it).
M.CA_BUNDLE = "/etc/ssl/certs/ca-certificates.crt"

-- build_context(deps, host, opts) -> ctx
--   deps = { context =, store =, verify_param = }  (luaossl submodules)
--   host = the hostname parsed from the wss:// URI (SNI + hostname verify target)
--   opts.insecure = true  → skip ALL verification (self-signed loopback ONLY)
function M.build_context(deps, host, opts)
  opts = opts or {}
  local ctx = deps.context.new("TLS", false)      -- client context (server=false)

  if opts.insecure then
    -- Escape hatch for self-signed loopback testing. No store, no hostname bind.
    ctx:setVerify(deps.context.VERIFY_NONE)
    return ctx
  end

  ctx:setVerify(deps.context.VERIFY_PEER)

  -- (1) Load the system CA trust store — context.new() has none of its own.
  local st = deps.store.new()
  local ok = pcall(function() st:add(M.CA_BUNDLE) end)
  if not ok then
    -- Bundle path absent (should not happen with ca-bundle in DEPENDS); fall
    -- back to OpenSSL's compiled-in default paths. Both are proven on-target.
    st:addDefaults()
  end
  ctx:setStore(st)

  -- (2) Enforce HOSTNAME verification, not just chain verification. Bind the
  -- verify param to the exact connect host so a valid-chain cert for another
  -- name is rejected (proven with a wrong-hostname negative test on-target).
  local vp = deps.verify_param.new()
  vp:setHost(host)
  ctx:setParam(vp)

  return ctx
end

-- peer_dns_names(cert) -> { "name", … }  (lowercased DNS identities)
-- Read the certificate's DNS identities for the explicit post-handshake host
-- check (see M.host_matches / the #2182 note above). luaossl has no
-- X509_check_host wrapper, so we pull the names out and match in pure Lua.
--   * SubjectAltName dNSName entries are the authoritative modern identity list.
--   * The legacy CN is used ONLY when the cert carries no dNSName SAN (matching
--     OpenSSL/browser behaviour: SAN present ⇒ CN ignored).
-- Every luaossl call is pcall-guarded so a cert missing a field degrades to
-- "no names" (→ host_matches returns false → connection rejected) rather than
-- erroring. luaossl's altname is iterated via its __pairs metamethod directly:
-- Lua 5.1's global pairs() does NOT honour __pairs, so `pairs(san)` would fail.
function M.peer_dns_names(cert)
  local names, seen = {}, {}
  local function add(v)
    if type(v) == "string" and v ~= "" then
      v = v:lower()
      if not seen[v] then seen[v] = true; names[#names + 1] = v end
    end
  end

  local ok_san, san = pcall(function() return cert:getSubjectAlt() end)
  if ok_san and san then
    local mt = getmetatable(san)
    local iter = mt and mt.__pairs
    if iter then
      pcall(function()
        local f, s, c = iter(san)
        for typ, val in f, s, c do
          if typ == "DNS" then add(val) end
        end
      end)
    end
  end

  if #names == 0 then
    pcall(function()
      for k, v in cert:getSubject():each() do
        if k == "CN" then add(v) end
      end
    end)
  end

  return names
end

-- host_matches(names, host) -> bool
-- RFC 6125 hostname match against a cert's DNS identities: case-insensitive
-- exact match, OR a single left-most "*" wildcard label that covers EXACTLY one
-- host label. So "*.badssl.com" matches "a.badssl.com" but NOT
-- "wrong.host.badssl.com" (two labels under the wildcard) and NOT the bare apex
-- "badssl.com". Pure — the security-critical core, unit-tested in ws_tls_spec.
function M.host_matches(names, host)
  if type(host) ~= "string" or host == "" then return false end
  host = host:lower():gsub("%.$", "")          -- normalise a trailing root dot
  local is_ip = host:match("^%d+%.%d+%.%d+%.%d+$") ~= nil
  for _, raw in ipairs(names or {}) do
    local name = type(raw) == "string" and raw:lower() or ""
    if name ~= "" and name == host then return true end
    if not is_ip and name ~= "" then
      -- "*.rest": the "*" must be the whole left-most label, covering exactly
      -- one host label (no embedded dot), and "rest" must match the remainder.
      local rest = name:match("^%*%.(.+)$")
      if rest then
        local label, host_rest = host:match("^([^.]+)%.(.+)$")
        if label and host_rest == rest then return true end
      end
    end
  end
  return false
end

-- format_starttls_error(code, strerror) -> string
-- Map cqueues' raw numeric starttls error through its error-string helper so
-- `logread` shows a name instead of a bare `-1935895353` (#2153 quality fix).
-- NOTE: cqueues collapses specific OpenSSL reasons (e.g. "certificate verify
-- failed") into a single generic code at its boundary, so strerror resolves
-- this to "Unknown TLS/SSL error" rather than the exact reason — still far more
-- legible than the opaque integer, and the raw code is preserved for grep.
function M.format_starttls_error(code, strerror)
  if type(code) == "number" and strerror then
    local ok, s = pcall(strerror, code)
    if ok and type(s) == "string" and s ~= "" then
      return string.format("%s (%s)", s, tostring(code))
    end
  end
  return tostring(code)
end

return M
