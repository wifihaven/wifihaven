# e2e gate coverage audit — 2026-05

Audit of the three-gate e2e plan (#652) against major functionality and major
bug fixes that have landed since the gates went live. Closes #916.

## Gate inventory

### Gate 1 — API contract smoke against staging (#653)

Wired by [#671](https://github.com/wifihaven/wifihaven/pull/671) in
[.github/workflows/master-api-ui.yml](../../.github/workflows/master-api-ui.yml)
(`api-smoke-staging` job, `needs: [deploy-staging]`). Runs two bash scripts:

**[scripts/e2e-tests.sh](../../scripts/e2e-tests.sh) — admin plane**

| # | Step                                          | Asserts                                                                 |
|---|-----------------------------------------------|-------------------------------------------------------------------------|
| 1 | API reachable                                 | health probe                                                            |
| 2 | `POST /api/auth/login`                        | token + admin role                                                      |
| 3 | `GET /api/profiles` requires auth             | 401 without token, 200 with                                             |
| 4 | `POST /api/profiles`                          | creates profile, returns id                                             |
| 5 | `GET /api/profiles/{id}`                      | fetch matches create                                                    |
| 6 | `GET /api/devices`                            | 2xx JSON array                                                          |
| 7 | `GET /api/logs`, `GET /api/stats`             | 2xx                                                                     |
| 8 | `GET /api/blocklists`                         | 2xx                                                                     |
| 9 | `GET /api/time/status`                        | 2xx JSON array                                                          |
| 10 | fake-router steady-state log scan            | no events/usage errors                                                  |

**[scripts/e2e-router.sh](../../scripts/e2e-router.sh) — router plane**

| # | Step                                          | Asserts                                                                 |
|---|-----------------------------------------------|-------------------------------------------------------------------------|
| 1 | Admin login                                   | token                                                                   |
| 2 | Create test profile (`dailyMinutes=1`)        | profile id                                                              |
| 3 | Router enrollment                             | token, router id                                                        |
| 4 | `GET /api/router/policy` ETag round-trip      | 200 → 304 with `If-None-Match`                                          |
| 5 | `POST /api/router/usage` (90s activity)       | snapshot shows `blocked=true reason=TimeLimit`; `lastSeenIp` updates    |
| 6 | `POST /api/router/events` (dhcp + 3 conn-attempt shapes) | 2xx; HostId tagged-union shapes round-trip                    |
| 7 | Pause profile                                 | snapshot `reason=Paused`                                                |
| 8 | Add always-on schedule                        | snapshot `reason=Schedule`                                              |
| 9 | `GET /blocked` on SPA host                    | 200 with each block reason                                              |
| 10 | Negative: missing/bogus auth, malformed JSON  | 4xx                                                                    |

### Gate 2 — qemu OpenWRT + fake API (#654)

Wired by [#770](https://github.com/wifihaven/wifihaven/pull/770) in
[.github/workflows/e2e-vm-fake.yml](../../.github/workflows/e2e-vm-fake.yml).
Gates `publish-openwrt` in `master-router.yml`. Runs the suite under
[scripts/e2e/scenarios_fake/](../../scripts/e2e/scenarios_fake/).

| Test                                                 | Asserts                                                                                       |
|------------------------------------------------------|-----------------------------------------------------------------------------------------------|
| `test_02_allowed_browsing`                           | allowed FQDN reaches origin; event captured by fake                                           |
| `test_03_blocked_domain`                             | blocked FQDN dropped at DNS                                                                   |
| `test_05_usage_in_api`                               | fake captures usage report for client MAC; **keys `bytesIn`/`bytesOut` present (no >0 check)** |
| `test_06_blocked_page`                               | blocked HTTP request lands on block-page                                                      |
| `test_extra_blocked` × 3                             | per-MAC ExtraBlocked drops; per-profile scope                                                 |
| `test_extra_blocked::test_extra_allowed_beats_blocked` | admin allow overrides @blocked_macs (#421)                                                  |
| `test_pause` × 3                                     | pause blocks; add device while paused blocks; reassign-off-paused unblocks                    |
| `test_reassignment::test_reassign_to_paused_blocks_and_back_restores` | reassign-onto-paused blocks; reassign-back restores                          |
| `test_schedule` × 3                                  | in-window blocks, out-of-window allows, smoke transition                                      |
| `test_time_limit::test_time_limit_exhausted_blocks`  | exhausted quota blocks                                                                        |
| `test_port_alloc` × 3                                | unit-level harness fixture                                                                    |
| `test_snapshot_builder` × N                          | golden-shape unit tests                                                                       |

### Gate 3 — thin qemu integration vs real staging (#655)

**As audited (2026-05), not wired yet** ([#655](https://github.com/wifihaven/wifihaven/issues/655)).
The legacy monolithic live-mode suite (`scripts/e2e/scenarios/` under
`.github/workflows/e2e-vm.yml`) was the closest cousin (full docker-compose
API + qemu router + Alpine client) but gated nothing; it was retired in
[#656](https://github.com/wifihaven/wifihaven/issues/656) (folding in
[#882](https://github.com/wifihaven/wifihaven/issues/882)). The narrower
Gate 3a/3b smoke (`scripts/e2e/gate3/`, run by `e2e-vm-gate3a.yml` /
`e2e-vm-gate3b.yml`) replaced it per #655.

For audit purposes, Gate 3's "covered scenarios" column is empty everywhere;
flagging that here once instead of repeating it per row below.

## Coverage gaps filed

Per-gate; each row is a separate gap-issue.

### Gate 1 — admin/router API contract surface (against staging)

| Gap                                                                                                                | Issue | Source                          | Difficulty |
|--------------------------------------------------------------------------------------------------------------------|-------|---------------------------------|------------|
| Proportional per-FQDN time vs presence not asserted                                                                | [#924](https://github.com/wifihaven/wifihaven/issues/924) | #715/#843                       | S          |
| `/api/sessions` must 404 (catch resurrection)                                                                      | [#925](https://github.com/wifihaven/wifihaven/issues/925) | #845/#851                       | S          |
| `PUT /api/devices` with `profileId=null` (insert + update paths)                                                   | [#926](https://github.com/wifihaven/wifihaven/issues/926) | #708/#841                       | S          |
| Week-chart TZ near midnight household-local                                                                        | [#927](https://github.com/wifihaven/wifihaven/issues/927) | #794/#867/#797                  | M          |
| Cross-device overlap `sum` vs `dedup` modes                                                                        | [#928](https://github.com/wifihaven/wifihaven/issues/928) | #751/#872                       | S          |
| New-device alert on unseen MAC + dismiss                                                                           | [#929](https://github.com/wifihaven/wifihaven/issues/929) | #711/#875                       | S          |
| Heartbeat filter defaults + FQDN allowlist pinned                                                                  | [#930](https://github.com/wifihaven/wifihaven/issues/930) | #714/#740/#788/#789/#799/#800   | M          |
| `/api/connection-events/series` with raw + aggregated buckets                                                      | [#931](https://github.com/wifihaven/wifihaven/issues/931) | #847/#850                       | S-M        |
| `/api/usage/traffic` + `/api/usage/series` with bucket/groupBy params                                              | [#932](https://github.com/wifihaven/wifihaven/issues/932) | #721/#722/#745/#749/#846/#853   | M          |
| Remaining admin endpoints (`/api/dashboard/now`, `/api/household/settings`, `/api/time/extend`, `/api/auth/change-password`, `/api/me`) | [#933](https://github.com/wifihaven/wifihaven/issues/933) | #266/#442/#449/#506/#599        | M (group)  |

### Gate 2 — qemu router + fake API

| Gap                                                                              | Issue | Source                                   | Difficulty |
|----------------------------------------------------------------------------------|-------|------------------------------------------|------------|
| Assert `bytesOut/bytesIn > 0` in usage flow (not just key-presence)              | [#920](https://github.com/wifihaven/wifihaven/issues/920) | #717/#833/#858/#866/#905/#913 | S          |
| Assert agent version on router after install + auto-update                       | [#921](https://github.com/wifihaven/wifihaven/issues/921) | #871/#876/#896/#899/#909/#771 | S-M        |
| Assert `wifihaven-update` cron entry present after install                       | [#922](https://github.com/wifihaven/wifihaven/issues/922) | #869/#873/#896/#899/#898/#904 | S          |
| Assert per-direction nft set tx vs rx separation                                 | [#923](https://github.com/wifihaven/wifihaven/issues/923) | #879/#881/#897/#900/#905/#913 | M          |

### Gate 3

No additional gaps filed beyond the umbrella issue ([#655](https://github.com/wifihaven/wifihaven/issues/655)).
Standing up Gate 3a + 3b is itself the gap; the operator-named scenarios
(blocked + allowed end-to-end against real staging) are exactly the thin
shape #655 already specifies. Several Gate 1/2 gaps above will naturally get
a "covered in Gate 3 too" lift once Gate 3 lands.

## Recommended priority

In rough merge order, biggest bang-for-buck first:

1. **#920** (bytes > 0) — single-line tightening of an existing fake-mode test;
   catches the entire bytes_out cascade root.
2. **#922** (cron-entry post-install) — single SSH check; catches the
   auto-update self-heal root.
3. **#925** (sessions 404) — single curl; protects against accidental
   resurrection of a removed surface.
4. **#926** (profileId=null) — three curls; covers a new optional path
   that the SPA actively exercises.
5. **#921** (agent version on router) — coupled to #771 landing; the
   meta-infra gap that lets bad publishes hit prod.
6. **#923** (per-direction nft sets) — directional sanity, distinct from
   #920; the harder failure mode that #905 fixed.
7. Remaining Gate 1 read-side coverage (#931, #932, #930, #927) — each
   is straightforward but slightly more setup than the items above.
8. Gate 3 standup (#655).

## Out of scope (noted, not filed)

- **Apps schema (#761/#868)** — scaffolding only; nothing user-visible to gate yet.
- **API caching / SWR (#802/#803/#815/#816)** — functional but not regression-prone in a way a gate would usefully catch; covered by load tests and staging soak instead.
- **CORS preflight (#612/#626/#627)** — already covered by the SPA `smoke-staging` job in master-api-ui.yml.
- **Render infra / DNS / domain config (#587/#590/#603/#605/#606/#613/#615)** — gated by deploy steps, not e2e tests.
- **Path filters and CI plumbing (#465/#618/#619/#624/#642/#690/#702/#880/#890)** — meta-CI, not product behavior.
- **Renames (familydns → wifihaven, packages, paths)** — pure renames, no behavior delta.

## Method note

This audit reads only the test infrastructure and PR titles/bodies; no new
tests were written. Each gap is its own follow-up to be spawned individually,
per the umbrella plan in #916.
