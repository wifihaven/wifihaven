# Bidirectional API ↔ Router Contract Fixtures (#634)

Two sets of golden JSON files exercising the wire contract between the Scala
API server (`api/`) and the OpenWRT lua agent (`openwrt/`):

| Directory | Producer (source of truth) | Consumer (asserts shape) |
| --- | --- | --- |
| `api-to-router/` | API zio-json codecs (`shared/src/Models.scala`) | `openwrt/test/contract_spec.lua` via `luci.jsonc` + `render.lua` |
| `router-to-api/` | Agent (`openwrt/files/usr/lib/lua/wifihaven/`) | `shared/test/src/contract/ContractGoldenSpec.scala` via the production decoder |

The fixtures themselves are produced by the same Scala generator
(`ContractFixtures` + `ContractGenerate`) for convenience — both halves are
emitted through the production zio-json codecs so they're guaranteed to be
valid at the moment of generation. Once committed, drift in either direction
flips a test:

* If `Models.scala` changes the wire shape (rename, removed field, changed
  default), the API-side test regenerates a different JSON than what's in
  `api-to-router/` — the test fails with a diff. The lua spec, which loads
  the now-stale golden, also fails because the parser sees fields that
  `render.lua` doesn't recognize.
* If the agent's POST shape changes (e.g. renames `destIp` to `dest_ip`,
  drops `eventId`), the corresponding `router-to-api/*.json` must be updated
  to match; `ContractGoldenSpec`'s round-trip assertion fails until the API
  decoder is updated too.

## Regenerating

After an intentional codec change:

```sh
./scripts/regen-contract-fixtures.sh
```

(equivalent to `mill shared.test.runMain wifihaven.shared.contract.ContractGenerate`)

Inspect the diff and update the *consumer* side in the same PR. Never
regenerate without reading the diff — that defeats the contract.

## Why golden files (not schema gen)?

Option 3 in #304 (a single source of truth that compiles to both Scala and
lua) is the long-term right answer but is much bigger. Golden files are
the cheap, durable middle ground that catches the specific bug class behind
#297 / #302 / #456: a codec rename on one side that nobody updates on the
other.

## Coverage

`api-to-router/`:

* `policy_snapshot.json` — `/api/router/policy` response. Exercises devices
  with profile-resolved rules AND per-MAC overrides, every `BlockRules`
  field populated incl. `blockReason` and `blockIpOnly`, multiple
  `failureMode` variants.

`router-to-api/`:

* `router_events_request.json` — `/api/router/events` POST. Includes
  `connection_attempt` (FQDN and v6 IP-literal `host`), `dhcp_lease`,
  `first_seen_mac`, and an `eventId` UUID (#338).
* `usage_report.json` — `/api/router/usage` POST. Includes records with
  and without `ip`, FQDN and IP-literal `host`.
* `register_router_request.json` — `/api/router/register` POST, with the
  optional `platformVersion` / `agentVersion` fields set.

Adding a surface: add a fixture to `ContractFixtures`, append it to
`apiToRouter` or `routerToApi`, add the corresponding test case in
`ContractGoldenSpec` (and a consumer assertion in `contract_spec.lua` for
the API → router direction), and regenerate.
