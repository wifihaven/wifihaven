# Where do I add this test?

A one-page map of the test suites in this repo and which one a given
behavior belongs in. Background: three-gate plan (#652), Gate 1 (#653),
Gate 2 (#654 / #686), Gate 3a + 3b (#655). The old monolithic live-mode
`e2e-vm.yml` track was retired in #656 — VM e2e is now Gate 2 (fake API)
plus the two Gate 3 halves.

## 30-second decision tree

Pick the lowest layer that can fail the way you care about.

1. **Pure logic in one process?** → unit tests. `api/test/` (Scala),
   `web/src/**/*.test.tsx` (Vitest), `openwrt/test/` (busted/Lua).
2. **API contract — admin or router-API field shapes, status codes,
   etag round-trip, 4xx/401?** → **Gate 1** (`scripts/e2e-tests.sh` +
   `scripts/e2e-router.sh`, run as `api-smoke-staging` in
   `master-api-ui.yml`).
3. **Router enforcement — does blocked / allowed / scheduled / paused /
   extra-allowed actually do the right thing on the wire?** → **Gate 2**
   (`scripts/e2e/scenarios_fake/`, run by `e2e-vm-fake.yml`).
4. **The seam between a real router build and a real API deploy?** →
   **Gate 3** (`scripts/e2e/gate3/`, run by `e2e-vm-gate3a.yml` and
   `e2e-vm-gate3b.yml`). Almost never the right answer for new tests —
   see "Gate 3" below.

Worked example — adding a new `failureMode` enum value:

- New admin-API field shape / accepted values → **Gate 1**.
- Router rendering the new mode at enforcement time → **Gate 2**.
- Gate 3 → **no change**. Gate 3 doesn't enumerate modes; it only checks
  that the wire still flows.

## Suite scope reference

### Unit tests

`api/test/` · `web/src/**/*.test.tsx` · `openwrt/test/`. Run from `ci.yml`
on every PR via `mill __.test`, `npm test`, and the Lua test job.

- **Add here:** anything that can be exercised in one process with no
  router VM, no live API, no network.
- **Don't add here:** anything whose failure mode is "the two sides
  disagree about the wire" — that's what the gates exist for.

### Gate 1 — API contract smoke against staging

Workflow: `master-api-ui.yml` job `api-smoke-staging`. Driver:
`scripts/e2e-tests.sh` + `scripts/e2e-router.sh`. Hits a freshly-deployed
staging API; the "router" side is the fake driver in those scripts.

- **Add here:** new admin-API endpoints or fields, router-API contract
  changes, status-code expectations, etag / If-None-Match behavior,
  auth / 401 / 4xx surface area.
- **Don't add here:** anything that needs a real OpenWRT image to
  observe — that's Gate 2. Don't add deep enumeration of enforcement
  modes; the goal here is contract surface, not depth.
- **Template tests:** `scripts/e2e-tests.sh` covers the admin path;
  `scripts/e2e-router.sh` covers the router-API path.
- **Multi-tenant isolation (#2151):** `scripts/e2e-isolation.py` provisions
  **two real households** (A = default; B via the beta pipeline) and asserts
  the absence of cross-tenant leakage across the user API, the router
  snapshot, ingest, beta provisioning, and the router cap (design
  `docs/design/multi-tenant-isolation.md` §7). It runs in the compose `e2e`
  job **only**, not `api-smoke-staging`: it creates a persistent second
  household + routers the staging backend can't clean between runs, and the
  disposable compose DB (`down -v` each run) is what two-household seeding
  needs. Add tenant-scoping assertions here.

### Gate 2 — router enforcement against fake API

Workflow: `e2e-vm-fake.yml` (gates `publish-openwrt` in
`master-router.yml`). Boots qemu OpenWRT + Alpine client on the
self-hosted KVM runner, points the agent at the in-process fake API in
`scripts/e2e/fake-api/` serving snapshot goldens.

- **Add here:** every enforcement mode and combination —
  blocked / allowed / scheduled / paused / extra-allowed,
  daily limits, time limits, unknown-device handling, reassignment,
  block-page rendering, etc. **Depth is Gate 2's job.**
- **Don't add here:** API contract assertions — the fake API is by
  construction whatever the snapshot says, so you can't catch a real
  contract drift here. Use Gate 1.
- **Template tests:** `scripts/e2e/scenarios_fake/test_03_blocked_domain.py`,
  `test_extra_blocked.py`, `test_schedule.py`, `test_pause.py`.

### Gate 3 — real router vs real API (two halves)

Both halves share the same code under `scripts/e2e/gate3/`. The only
difference is which side is "fresh" and which is "current released":

- **Gate 3a** (`e2e-vm-gate3a.yml`, gates `publish-openwrt`): freshly-built
  router against current staging API. "Can this router still talk to
  prod's API?"
- **Gate 3b** (`e2e-vm-gate3b.yml`, gates `publish-api`):
  last-published router against freshly-deployed staging API. "Does this
  API still work for routers customers are running?"

Gate 3 is deliberately a thin smoke. Its purpose is to catch wire-protocol
regressions across an actual published-version skew — not to retest
behaviors either side already covers.

- **Add here:** almost nothing. A new wire-level field that needs cross-
  version compatibility evidence might qualify; talk to whoever opened
  #655 first.
- **Don't add here:** new enforcement modes (Gate 2). New admin-API
  fields (Gate 1). UI behavior (web unit tests + Gate 1). Anything that
  could be expressed without two real artifacts.

## Cheat sheet

| If the failure is… | Add to |
|---|---|
| "the API would accept/return the wrong JSON" | Gate 1 |
| "the router would block/allow the wrong packet" | Gate 2 |
| "an old router talking to a new API would break" | Gate 3b |
| "a new router talking to the current API would break" | Gate 3a |
| "this function returns the wrong value" | unit test |

## Out of scope here

- Runner capacity — tracked in #657.
- Wire-schema versioning framework — tracked in #376 / #597.
