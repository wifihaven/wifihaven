#!/usr/bin/env bash
# Regenerate golden contract fixtures under shared/contract/ (#634).
#
# Two producers, two directions:
#
#   * api-to-router/*.json  — produced by the API's zio-json codecs via
#     the Scala main `wifihaven.shared.contract.ContractGenerate`. Drift
#     in Models.scala flips the diff; the lua consumer (openwrt/test/
#     contract_spec.lua) then catches stale-schema breakage.
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
mill shared.test.runMain wifihaven.shared.contract.ContractGenerate "$@"

echo "→ router-to-api (lua agent generator)"
(
  cd openwrt
  LUA_PATH="./files/usr/lib/lua/?.lua;./files/usr/lib/lua/wifihaven/?.lua;./test/shim/?.lua;./test/shim/?/init.lua;$(lua -e 'print(package.path)')" \
    lua test/contract_gen.lua
)
