---
name: Wire schema versioning deferred to pre-v1.0
description: Issue #376 (snapshotVersion + capability negotiation) is intentionally deferred; until it lands, wire changes must be additive/backwards-compatible (no breaking changes)
type: project
---
Wire-contract schema-evolution policy (issue [#376](https://github.com/wifihaven/wifihaven/issues/376) — snapshotVersion, serverCapabilities/agentCapabilities negotiation, capability registry) is **deferred until we approach v1.0 release**. Decided 2026-05-14.

**Why:** Building and maintaining a full capability-negotiation framework now costs more than it buys at the current install base. As of the prod deploy the API and the OpenWRT agent deploy **independently** (no tandem deploy), so the wire is a public contract — but for an install base of ~one the cheap discipline of additive-only, ignore-unknown-on-input changes is enough to keep an older deployed agent and a newer API compatible without a versioning handshake.

**How to apply:**
- Do NOT proactively add `snapshotVersion`, `serverCapabilities`, `agentCapabilities`, capability headers, or registry tables to the wire contract.
- Wire shape changes must follow the backwards-compatibility policy in `AGENTS.md` ("Backwards compatibility"): **additive fields only, ignore unknown on input, deprecation windows for removals.** Breaking/non-additive wire changes are off the table until #376 lands — they are no longer free, because there is no tandem deploy to coordinate both sides at once.
- The historical shape-change issues (e.g. #385 `failure-mode-three`, #386 `failover-threshold-in-snapshot`, #374 `unmanaged-mac-policy`, #383 `https-block-page`, #391 `hostid-tagged-union`, #392 `ipv6-ipsets`) that predate the deploy could land as plain breaking changes; anything still in flight must now be made additive instead.
- If a future task asks for the capability framework, surface this memory and confirm v1.0 timing before starting. The draft design (rules + registry) is in the #376 thread / chat history if it needs to be revived.
