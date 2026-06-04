#!/usr/bin/env bash
# Regenerate golden contract fixtures under shared/contract/ (#634).
#
# Two producers, two directions:
#
#   * api-to-router/*.json  — produced by the API's zio-json codecs via
#     `ContractGoldenSpec` run in regenerate mode
#     (`WIFIHAVEN_REGEN_CONTRACT=1`). Drift in Models.scala flips the
#     diff; the lua consumer (openwrt/test/contract_spec.lua) then
#     catches stale-schema breakage. (We use the test runner rather
#     than a `runMain` main because mill's runMain forked-JVM exits
#     non-zero on Linux CI after `main` returns — #674. The test
#     runner exits cleanly on the same classpath.)
#
#   * router-to-api/*.json  — produced by the OpenWRT agent's OWN
#     production POST-body builders (conntrack.build_event,
#     conntrack.build_first_seen_mac_event, conntrack.build_dhcp_lease_event,
#     usage.build_report) via openwrt/test/contract_gen.lua. Drift in the
#     lua agent flips the diff; the Scala consumer (ContractGoldenSpec)
#     then catches decoder/round-trip breakage. (register_router_request.json
#     is a documented exception — see shared/contract/README.md.)
#
# After running, inspect the diff: every change should correspond to an
# intentional wire-contract update, and the consumer code on the other side
# must be updated in the same PR.
set -euo pipefail
cd "$(dirname "$0")/.."

echo "→ api-to-router (Scala codec generator)"
WIFIHAVEN_REGEN_CONTRACT=1 \
  mill shared.test.testOnly wifihaven.shared.contract.ContractGoldenSpec "$@"

echo "→ router-to-api (lua agent generator)"
(
  cd openwrt
  base_path="./files/usr/lib/lua/?.lua;./files/usr/lib/lua/wifihaven/?.lua"
  if [ -n "${WIFIHAVEN_LUCI_JSONC_CPATH:-}" ]; then
    # CI: serialize via the REAL luci-lib-jsonc C module (#1367). Keep the shim
    # OFF LUA_PATH so `require("luci.jsonc")` falls through to the .so on
    # LUA_CPATH — the agent's actual production encoder, not the lua-cjson shim.
    echo "  (using real luci.jsonc: $WIFIHAVEN_LUCI_JSONC_CPATH)"
    LUA_CPATH="${WIFIHAVEN_LUCI_JSONC_CPATH};;" \
    LUA_PATH="${base_path};$(lua -e 'print(package.path)')" \
      lua test/contract_gen.lua
  else
    # Local default: the verified-byte-equivalent shim (test/shim/luci/jsonc.lua),
    # so contributors don't need a C toolchain to regenerate. CI re-verifies the
    # bytes against the real module, so shim drift can't slip through.
    LUA_PATH="${base_path};./test/shim/?.lua;./test/shim/?/init.lua;$(lua -e 'print(package.path)')" \
      lua test/contract_gen.lua
  fi
)
