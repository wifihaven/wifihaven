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
--      X509_VERIFY_PARAM_set1_host automatically — verified on-device:
--      wrong.host.badssl.com (valid chain, wrong name) was ACCEPTED with just
--      the store loaded. That is a MITM hole: any CA-valid cert for ANY host
--      would pass. We bind verification to the connect host explicitly via
--      openssl.x509.verify_param:setHost() + context:setParam(); the same
--      on-device test then correctly REJECTS the mismatched cert.
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
