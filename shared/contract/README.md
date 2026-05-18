# Bidirectional API ↔ Router Contract Fixtures (#634)

Two sets of golden JSON files exercising the wire contract between the Scala
API server (`api/`) and the OpenWRT lua agent (`openwrt/`). Each fixture is
written by the **authoritative producer for that direction** and consumed by
the other side's production parser:

| Directory | Producer (authoritative) | Consumer (asserts shape) |
| --- | --- | --- |
| `api-to-router/` | API zio-json codecs (`shared/src/Models.scala`) via `ContractGenerate.scala` | `openwrt/test/contract_spec.lua` via `luci.jsonc` + `render.lua` |
| `router-to-api/` | OpenWRT agent's production POST-body builders (`conntrack.build_event`, `conntrack.build_first_seen_mac_event`, `conntrack.build_dhcp_lease_event`, `usage.build_report`) via `openwrt/test/contract_gen.lua` | `shared/test/src/contract/ContractGoldenSpec.scala` via the production decoder |

The two producers are deliberately split: an API-generated router→api fixture
would round-trip cleanly through the API's own codec even if the live agent
silently changed what it sends. Writing each direction's fixtures from the
side that actually produces the bytes means producer drift on either side
flips a diff (and then trips the consumer test on the other side).

* If `Models.scala` changes the wire shape (rename, removed field, changed
  default), the API-side generator emits different JSON than what's in
  `api-to-router/` — the lua spec, which loads the now-stale golden,
  fails because the parser sees fields that `render.lua` doesn't recognize
  (or vice versa).
* If the agent's POST shape changes (e.g. renames `destIp` to `dest_ip`,
  drops `eventId`), the corresponding `router-to-api/*.json` regenerates
  with a visible diff AND `ContractGoldenSpec`'s round-trip assertion
  fails: the API decoder either rejects the new shape outright, or accepts
  it but re-encodes to a different shape (the classic "agent quietly
  stopped sending eventId" regression).

A dedicated CI job (`Contract Tests (API ↔ Router)` in
`.github/workflows/ci.yml`) runs both halves on every PR that touches the
contract surface, and verifies the committed fixtures match what the
producers would generate today — so you can't sneak a producer change in
without the fixture diff showing up in the PR.

## Regenerating

After an intentional codec change on either side:

```sh
./scripts/regen-contract-fixtures.sh
```

That runs both generators:

* `mill shared.test.runMain wifihaven.shared.contract.ContractGenerate` —
  writes `api-to-router/*.json` from Scala values.
* `lua openwrt/test/contract_gen.lua` — writes `router-to-api/*.json` by
  calling the agent's production POST-body builders directly.

Inspect the diff and update the *consumer* side in the same PR. Never
regenerate without reading the diff — that defeats the contract.

## One documented exception: `register_router_request.json`

The actual producer for `POST /api/router/register` is the shell `printf`
in `openwrt/install.sh`, not lua — enrollment runs before the agent boots,
so it's a one-shot shell script invocation. There is no lua function to
call here, so `contract_gen.lua` mirrors the printf format string directly
and labels the section accordingly. If `install.sh`'s `printf` format
changes, update both `install.sh` and `contract_gen.lua` in the same PR
(the CI regen-diff guard will catch you if you forget the latter).

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

* `router_events_request.json` — `/api/router/events` POST. Built by
  `conntrack.build_event` (connection_attempt, FQDN + IPv6 variants),
  `conntrack.build_dhcp_lease_event`, and
  `conntrack.build_first_seen_mac_event`. Includes an `eventId` UUID
  (#338) — the generator pins these to deterministic values so the
  fixture is stable across regens.
* `usage_report.json` — `/api/router/usage` POST. Built by
  `usage.build_report` with a pinned `lookup_hostname` (FQDN for one
  record, miss for the other so it falls back to an IPv4-tagged HostId)
  and a pinned tracker (so `activeSeconds` is deterministic).
* `register_router_request.json` — `/api/router/register` POST. Mirrors
  `openwrt/install.sh`'s printf format string (see exception above).

Adding a surface: add a builder call to `openwrt/test/contract_gen.lua`
(or, for an API→router surface, a fixture to `ContractFixtures.scala` and
an entry in `apiToRouter`), add the corresponding test case in
`ContractGoldenSpec` (and a consumer assertion in `contract_spec.lua` for
the API → router direction), and regenerate.
