---
name: Wire schema versioning deferred to pre-v1.0
description: Issue #376 (snapshotVersion + capability negotiation) is intentionally deferred; API and agent ship in tandem until then
type: project
---
Wire-contract schema-evolution policy (issue [#376](https://github.com/wifihaven/wifihaven/issues/376) — snapshotVersion, serverCapabilities/agentCapabilities negotiation, capability registry) is **deferred until we approach v1.0 release**. Decided 2026-05-14.

**Why:** Pre-v1.0, API and OpenWRT agent are deployed in tandem from the same repo by a single operator (sameer); the cost of a tandem-deploy constraint is lower than the cost of building and maintaining a capability-negotiation framework now. Auto-update mismatch windows are tolerable for the current install base of one.

**How to apply:**
- Do NOT proactively add `snapshotVersion`, `serverCapabilities`, `agentCapabilities`, capability headers, or registry tables to the wire contract.
- In-flight shape-change issues (e.g. #385 `failure-mode-three`, #386 `failover-threshold-in-snapshot`, #374 `unmanaged-mac-policy`, #383 `https-block-page`, #391 `hostid-tagged-union`, #392 `ipv6-ipsets`) ship as plain breaking shape changes coordinated by tandem deploy — they do NOT need to name a capability or gate emission.
- If a future task asks for the capability framework, surface this memory and confirm v1.0 timing before starting. The draft design (rules + registry) is in the #376 thread / chat history if it needs to be revived.
