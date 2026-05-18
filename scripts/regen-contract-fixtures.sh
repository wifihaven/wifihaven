#!/usr/bin/env bash
# Regenerate golden contract fixtures under shared/contract/ (#634).
#
# Both halves are written from the in-process Scala values defined in
# `shared/test/src/contract/ContractFixtures.scala`, serialized through the
# production zio-json codecs. After running, inspect the diff: every change
# should correspond to an intentional wire-contract update, and the
# consumer code on the other side must be updated in the same PR.
set -euo pipefail
cd "$(dirname "$0")/.."
exec mill shared.test.runMain wifihaven.shared.contract.ContractGenerate "$@"
