# Multi-tenant isolation audit — July 2026

**Umbrella:** [#2563](https://github.com/wifihaven/wifihaven/issues/2563) ·
**Scope:** the whole API surface — schema, repos, routes, background jobs, the router/agent path,
support/press agent data access, and every unauthenticated endpoint ·
**Method:** read the SQL. Nothing below is asserted from a comment, a docstring, or an issue
summary; every verdict names the file and line it was read from
(AGENTS.md [§verify-and-cite](../process/verify-and-cite.md)).

**Commit audited:** `c72961df` (`main`, 2026-08-01).

---

## 0. Why this audit exists, and what it found

Seven open issues — [#2532](https://github.com/wifihaven/wifihaven/issues/2532),
[#2553](https://github.com/wifihaven/wifihaven/issues/2553),
[#2535](https://github.com/wifihaven/wifihaven/issues/2535),
[#2283](https://github.com/wifihaven/wifihaven/issues/2283),
[#2264](https://github.com/wifihaven/wifihaven/issues/2264),
[#2142](https://github.com/wifihaven/wifihaven/issues/2142),
[#2322](https://github.com/wifihaven/wifihaven/issues/2322) — are all the same defect class, and
every one was found by **accident**: a review of an unrelated PR, a crash, a follow-up to a
different fix. None came from a sweep. The tenancy pass was applied route-by-route with no
structural guard, so coverage was unknown.

It is now known. The sweep found **nine new findings**, one of them CRITICAL:

| # | Severity | Finding | Issue |
|---|---|---|---|
| F1 | **CRITICAL** | `POST /api/alerts/{id}/approve\|deny` have no household check — cross-household alert disclosure **and** the ability to unpause another household's child profile | [#2564](https://github.com/wifihaven/wifihaven/issues/2564) |
| F2 | **HIGH** | `DELETE /api/profiles/{id}` has no `requireProfileAccess` — any household's adult deletes another household's profile | [#2565](https://github.com/wifihaven/wifihaven/issues/2565) |
| F3 | **HIGH** | Unauthenticated `POST /api/access-requests` debounce returns another household's alert JSON | [#2566](https://github.com/wifihaven/wifihaven/issues/2566) |
| F4 | **HIGH** | Four more install-wide catalog mutators reachable by any household (beyond #2535's two) | [#2567](https://github.com/wifihaven/wifihaven/issues/2567) |
| F5 | **HIGH** | `listTrafficRollupRows` has no household predicate — `/api/dashboard/now` leaks another household's hostnames on a shared MAC | [#2568](https://github.com/wifihaven/wifihaven/issues/2568) |
| F6 | **MEDIUM** | Unauthenticated `GET /api/blocked` hardcodes `HouseholdId.Default` — discloses household 1's profile name + screen-time | [#2569](https://github.com/wifihaven/wifihaven/issues/2569) |
| F7 | **MEDIUM** | `HouseholdSettingsRepo.update` wipes **every** household's rollup cache | [#2570](https://github.com/wifihaven/wifihaven/issues/2570) |
| F8 | **LOW** | ~20 unscoped dead repo methods — the loaded-gun residue | [#2571](https://github.com/wifihaven/wifihaven/issues/2571) |
| F9 | **MEDIUM** | Schema: `named_schedules` global name unique + `DEFAULT 1`; `block_events` has no tenancy key at all | [#2572](https://github.com/wifihaven/wifihaven/issues/2572) |

The structural guard that stops the next one is
[`api/test/src/feature/MultiTenantRouteCensusSpec.scala`](../../api/test/src/feature/MultiTenantRouteCensusSpec.scala)
— see [§7](#7-the-structural-guard).

**The shape of what was missed.** The read-scoping waves (#2107 / #2108 / #2251 / #2257 / #2282 /
#2313 / #2314) were thorough and they held: not one list-read leaked. Every finding above is on a
surface those waves did not have in their frame:

- **per-id write paths** (F1, F2) — `GET /api/alerts` is scoped; `POST /api/alerts/{id}/approve` is
  not. `PUT /api/profiles/{id}` is scoped; `DELETE` is not. The waves scoped *what you can list*,
  and the gap is *what you can name*.
- **unauthenticated surfaces** (F3, F6) — no principal, so no `claims.hh` to thread, so they were
  skipped rather than solved.
- **install-wide catalogs** (F4) — correctly identified as having no tenancy dimension, which was
  then treated as also meaning no authorization question.
- **one straggler read** (F5) among six correctly-scoped siblings.

---

## 1. Schema inventory — all 41 live tables

48 tables have ever been created; 7 were dropped (`device_alerts`, `global_allow`,
`global_blocklists`, `global_blocks`, `query_logs`, `schedules`, `site_time_limits`), leaving **41**
live. The three subsections below hold **14 + 18 + 9 = 41** — every live table is accounted for, and
the arithmetic is stated so a reader can check it without re-deriving the set. (Counts traced to
`api/resources/db/migration/*.sql`: `CREATE TABLE` distinct names = 48, `DROP TABLE` distinct names
= 7.) Only 14 carry a `household_id` column. The rest are either transitively scoped through a FK or
genuinely install-wide — the audit's job was to prove which, per table, rather than assume.

### 1.1 Directly household-scoped (14)

| Table | `household_id` | `DEFAULT 1`? | Uniques | Verdict |
|---|---|---|---|---|
| `households` | *(is the key)* | — | `slug` UNIQUE (V66); `slug` still **nullable** (#2142) | ✅ root |
| `users` | NOT NULL, no default (V66) | no | `(household_id, username)` V65; global username unique dropped V68; `email` **globally** unique V67 (deliberate — it is the login identifier); V86 partial unique = one admin per household | ✅ |
| `routers` | NOT NULL, no default (V66) | no | — | ✅ |
| `devices` | NOT NULL, no default (V66) | no | `(household_id, mac)` V65; global `devices_mac_key` dropped V74 | ✅ |
| `profiles` | NOT NULL | ⚠️ **yes** | partial unique `(household_id, is_global)` V73 | ⚠️ #2142 |
| `household_settings` | NOT NULL | ⚠️ **yes** | `UNIQUE(household_id)` V65; `id` identity V82 | ⚠️ #2142 |
| `time_usage` | NOT NULL | ⚠️ **yes** | `(household_id, device_mac, host_type, host_value, date)` V65; global unique dropped V75 | ⚠️ #2142 |
| `time_extensions` | NOT NULL, no default (V66) | no | — | ✅ |
| `named_schedules` | NOT NULL (V72) | ⚠️ **yes** | ⚠️ `name` still **globally** unique (V50) | ⚠️ **F9 / #2572** |
| `alerts` | NOT NULL, default dropped V79 | no | composite FK `(household_id, mac) → devices` V79 | ✅ |
| `beta_requests` | **nullable** — by design | n/a | `email` unique | ✅ (row belongs to no household until approval) |
| `household_billing` | PRIMARY KEY | n/a | — | ✅ |
| `support_thread_consent` | NOT NULL (V84) | no | `(household_id, thread_id)` | ✅ |
| `support_consent_link_use` | NOT NULL (V85) | no | `nonce` PK | ✅ |

**`DEFAULT 1` remaining: four tables, not three.** #2142 tracks `profiles`, `household_settings`,
`time_usage`. `named_schedules` (V72, post-dates #2142) is a fourth, tracked by the new #2572. Each
is the dark-by-default shape #2265/#2266 banned: a missing scope silently resolves to household 1
instead of failing loudly. Both #2386 (settings read fell back to `id=1`) and #2533 (SPA wrote
household 1's row) were this mechanism.

### 1.2 Transitively scoped through a FK (18)

Design §0.1 permits scoping through a join rather than a denormalized column. Each of these was
checked to confirm the join actually exists and that live reads compose it.

| Table | Scoped via | Verdict |
|---|---|---|
| `time_limits` | `profile_id → profiles` | ✅ `listAllForHousehold` joins `p.household_id` (`Repos.scala:1848`) |
| `app_policy_assignments` | `profile_id → profiles` | ✅ (`Repos.scala:1932`) |
| `app_policy_schedule_rules` | `assignment_id → app_policy_assignments` | ✅ |
| `profile_schedule_rules` | `profile_id → profiles` | ✅ |
| `schedule_windows` | `schedule_id → named_schedules` | ✅ |
| `user_profiles` | `user_id`/`profile_id` | ⚠️ **reads unscoped — #2532** |
| `time_used_daily` | `profile_id` | ⚠️ `getDayMap(date)` is all-tenant — see §4 |
| `app_used_daily` | `profile_id` | ✅ per-profile reads only |
| `traffic_reports` | `router_id → routers` | ⚠️ one straggler — **F5 / #2568** |
| `connection_events` | `router_id → routers` | ✅ `query`/`querySeries`/`stats` all compose it |
| `connection_events_hourly` | `router_id` | ✅ via `hhRouterScope` (`Repos.scala:3956`) |
| `connection_events_daily` | `router_id` | ✅ |
| `traffic_hourly` / `traffic_daily` | `router_id` | ✅ read only through scoped callers |
| `traffic_hourly_apps` / `traffic_daily_apps` | `router_id` | ✅ |
| `password_reset_tokens` | `user_id → users` | ✅ token is single-use + TTL'd; household comes from the user row |
| `block_events` | ❌ **nothing** | ⚠️ **F9 / #2572** — no `household_id` AND no `router_id`; only `mac`, which V74 made ambiguous |

### 1.3 Install-wide by design (9)

| Table | Why | Note |
|---|---|---|
| `apps`, `app_hosts`, `app_hosts_version` | template-authored catalog (#1798) | mutators reachable by any household — F4 / #2567 |
| `blocklists`, `blocklist_domains` | bundled category catalog | mutators reachable by any household — #2535 / #2567 |
| `beta_cohort` | one-row install-wide flip clock | ✅ |
| `rollup_runs` | job telemetry, no household rows | ✅ |
| `press_messages` | press has no household dimension | ✅ operator-gated |
| `ambient_host_days` | global ambient-host baseline | ⚠️ see §4 — built from *every* household's traffic |

---

## 2. Repos — every `*RepoLive` method

All 24 `*RepoLive` classes were read method-by-method (`Repos.scala` 4,626 lines, plus the nine
standalone repo files). Findings:

**Live and unscoped:**

- `AlertRepo.findById` / `.decide` / `.findRecentAccessRequest` (`Repos.scala:2259`, `:2290`,
  `:2243`) → **F1 / #2564**, **F3 / #2566**.
- `ProfileRepo.delete` (`:1602`) — unscoped by design (the route is the choke point), but the route
  forgot → **F2 / #2565**.
- `UserProfileRepo.*` — every method (`:1432`–`:1475`) → **#2532**, confirmed live, see §6.
- `TrafficReportRepo.listTrafficRollupRows` (`:3109`) → **F5 / #2568**.
- `BlocklistRepo.clearCategory` (`:2312`), `AppRepo.delete` / `.setHosts` / `.mergeAppInto` → **#2535
  / F4 / #2567**.
- `HouseholdSettingsRepo.update`'s two unqualified `DELETE`s (`:1798`, `:1802`) → **F7 / #2570**.
- `TimeUsedRollupRepo.getDayMap(date)` (`TimeUsedRollupRepo.scala:85`) — all-tenant, read inside the
  household-scoped `dayStateAllFromRollup`. See §6 (#2264 scope note).

**Dead and unscoped** (~20 methods, **F8 / #2571**) — **PARTIALLY CLOSED (#2571).**

DELETED (no caller in `api/src`; `mill api.compile` is the proof): `TimeUsageRepo.getSecondsUsed`;
`TimeExtensionRepo.getTotalExtension` / `listForDevice`; `BlockEventRepo.listForMac`;
`ConnectionEventRepo.listForMac`; `TrafficReportRepo.earliestPeriodStart`;
`ProfileRepo.listAllIncludingGlobal` / `getGlobal`; `UserRepo.listAll`; `RouterRepo.listAll`;
`TimeLimitRepo.listAll`; `AlertRepo.list`. Each retained caller (all in `api/test`) moved to the
`…ForHousehold` twin. `NamedScheduleRepo.findByName` was handled separately: it was SCOPED, not deleted — it now takes
a `household` (`Repos.scala:529`), landed by PRs #2582/#2586 under the still-open #2572.

RETAINED, tracked by **#2702**: `TimeUsageRepo.getProportionalSeconds` / `getSecondsAndBytes` /
`listForDevice` / `listForDeviceMacs`; `BlockEventRepo.recent`; `ConnectionEventRepo.listForRouter`;
`TrafficReportRepo.listForRouter`. These have no production caller either, but they ARE the only
read-back path a live feature test has for the write path it exercises (router ingest, the
block-event emit path, the #730 IP→FQDN promotion join). None has a `…ForHousehold` twin to move to,
so deleting them would delete real coverage and adding one is a scoping change, not a deletion —
out of scope for this PR by construction.

**Clean — verified scoped:** `UserRepo` (`findByUsername`, `emailForUser`, `listAllForHousehold`,
`findAdminForHousehold`), `HouseholdRepo`, `ProfileRepo` (`listAllForHousehold`,
`listAllIncludingGlobalForHousehold`, `getGlobalForHousehold`, `householdOf`, `distinctHouseholds`),
`DeviceRepo` (every method takes a `household`; `upsert`/`upsertUnknown` are constructively keyed by
`ON CONFLICT (household_id, mac)`), `TimeUsageRepo.incrementSecondsAndBytesBatch`,
`TimeExtensionRepo.grant`/`grantForProfile`, `RouterRepo.listAllForHousehold`,
`TrafficReportRepo` (six of seven reads), `ConnectionEventRepo.query`/`querySeries`/
`querySeriesRollup`/`stats`/`lastSeenByMacSince`, `AlertRepo.listForHousehold`/`raiseNewDevice`,
`NamedScheduleRepo.listAllForHousehold`/`householdOf`, `HouseholdSettingsRepo.getForHousehold`/
`enforcementDisabled`/`setEnforcementDisabled`, `SupportConsentRepo` (every method keys on
`(household_id, thread_id)`), `EntitlementsRepo`, `HouseholdBillingRepo`, `BetaCohortRepo`,
`PasswordResetTokenRepo`.

`SqlFragments.householdEq` / `householdRouterScope` / `householdFilter`
([`SqlFragments.scala`](../../api/src/db/SqlFragments.scala)) are the single source of the tenancy
predicate and correctly parameterize only `hh`, splicing `column` via `Fragment.const` from
compile-time literals only — **no SQL-injection vector**; every call site passes a constant.

---

## 3. Routes — all 123

Every endpoint in `api/src/routes` was enumerated and given a tenancy verdict; the full census is
now machine-checked in
[`MultiTenantRouteCensusSpec`](../../api/test/src/feature/MultiTenantRouteCensusSpec.scala).

**Household derivation.** No route derives its household from a client-supplied parameter. Every
authenticated route reads `claims.hh` from the verified JWT; the router plane reads
`router.householdId` from the token-resolved row; the support/press agent planes read it from the
signed token. **There is no `?householdId=` anywhere in the API** — the HIGH finding class the audit
brief asked about does not exist here.

**Authorization gates.** Per #2522, `requireAdmin` is the ACCOUNT gate and `requireWriter` the
EDITING gate. Spot-checked every route: the split is applied correctly — account surfaces
(`POST|PATCH|DELETE /api/users`, `/api/billing`, `PUT /api/household/enforcement`,
`/api/support/consent`, `/api/support/identity`) are `requireAdmin`; editing surfaces (profiles,
devices, schedules, app policy, time extensions, household settings) are `requireWriter`. **No route
uses the wrong one.** The one nuance: the install-wide catalog mutators in F4 are `requireWriter` /
`requireAdmin` when they are arguably `requireOperator` — that is a tenancy question, not a
role-split error, and it is what #2567 asks.

**`requireOperator`** (admin AND household 1) is used on exactly the surfaces documented as its
narrow exception: beta-request review/approval, `free_forever` grant/revoke, the press console. No
drift.

**Findings:** F1 (#2564), F2 (#2565), F3 (#2566), F4 (#2567), F5 (#2568), F6 (#2569).

**Clean — explicitly verified:** every profile route except `DELETE`; every device route
(`findByMacInHousehold` + `requireProfileAccess` throughout); every schedule route
(`requireScheduleInHousehold`); every user route (`ownUser`, including the body-borne `userIds` on
`PUT /api/profiles/{id}/users`); `GET /api/alerts`; `GET /api/logs`;
`GET /api/connection-events/series`; `GET /api/stats`; `GET /api/dashboard/now` (except F5's read);
all six `/api/time/status*` routes; `/api/presence/ambient-hosts`; `/api/time/extend`;
`/api/time/extensions/{id}`; all seven `/api/usage/*` routes; `/api/household/settings` (all three
verbs, post-#2533); `/api/household/enforcement`; `/api/billing`; `/api/admin/routers` (all three);
`PUT|DELETE /api/apps/{id}/policy/{profileId}`; `/api/support/consent`; `/api/support/identity`;
`GET /api/ws`.

---

## 4. Background jobs, pollers, rollups, schedulers

The least-audited surface, and the one #2553 hid in: a job has no request context, so it has no
principal to derive a household from.

| Job | Verdict |
|---|---|
| `TimeUsedRollupJob` | ⚠️ per-household loop over `distinctHouseholds` is correct (#2257), but the `settings` read outside it applies **household 1's** reset tz + heartbeat filter to every household — **#2553**, named in-code as `TODO(#2553)` (`TimeUsedRollupJob.scala:182`) |
| `AmbientLearnJob` | ⚠️ same `settings` gap (`AmbientLearnJob.scala:110`) — **#2553**. Separately, it merges every household's per-host counts into ONE global `ambient_host_days` baseline (`:159`). That is documented as by-design, but it means household A's traffic shapes household B's screen-time attribution. #2553's fix shape says to "decide deliberately what `upsertDay`/`pruneBefore` key on" — that decision is still open, and it is a real cross-household coupling, not just a knob. |
| `RollupJobs` (traffic hourly/daily) | ✅ `router_id`-keyed throughout; rollup rows never cross a router |
| `AppUsedRollupService` | ✅ every method takes a `household` and threads it into the presence reads |
| `RetentionSweepJob` | ✅ time-based sweep, no household dimension (v1 = UTC, documented) |
| `PartitionMaintenanceJob` | ✅ DDL only |
| `FlipService` (beta cohort) | ✅ iterates `betaHouseholdIds` and acts per household |
| `PolicySnapshotPublisher` | ✅ publishes per-router snapshots |
| `Metrics` router-count poller | ✅ install-wide gauge, no household rows |

**Cross-cutting:** `HouseholdSettingsRepo.update`'s wholesale rollup-cache wipe (**F7 / #2570**) is a
hidden dependency of #2553 — narrowing it is only safe after #2553 lands, and #2553's fix shape does
not currently mention it.

---

## 5. Router path, policy snapshot, support/press, unauthenticated surfaces

### Router / agent plane — CLEAN

`RouterAuthLive.authenticate` resolves the bearer token to a `routers` row
([`RouterAuth.scala:41`](../../api/src/routes/RouterAuth.scala)), and `router.householdId` is
threaded into **every** downstream write: `handleUsage`, `handleEvents`, `applyDelta`,
`applyDhcpOrFirstSeen`, `touchLastSeenBatch`, `incrementSecondsAndBytesBatch`, `upsertUnknown`,
`renameIfAutoGenerated`, `raiseNewDevice`
([`RouterIngestService.scala`](../../api/src/routes/RouterIngestService.scala) `:86`–`:485`).
Device discovery is **constructively** keyed (`ON CONFLICT (household_id, mac)`) rather than
lookup-and-reject, so a first-seen MAC creates *this* household's row and can never address
another's. `GET /api/router/policy` and `POST /api/router/decision` scope on
`snapshot(router.householdId)` / `decide(router.householdId, …)`.

**Every MAC lookup on this path is household-scoped** — verified individually, which matters because
V74/V75 made MACs legitimately non-unique across households. `MultiTenantIsolationSpec` pins 4a–4f
already cover the same-MAC cases behaviourally.

### Support agent — CLEAN

The `ConsentToken` is thread- **and** household-bound; every `/api/support/agent/*` route derives its
household from the verified token and nothing else. `GET /api/support/agent/household` is
consent-gated (403 `NoConsent` distinct from 401 token failure). `SupportConsentRepo` keys every
read and write on `(household_id, thread_id)`. `POST /api/support/consent` writes `claims.hh` from
the customer's own session JWT — never from the request body (#2419), so the agent cannot widen its
own scope. **A consented session for household A cannot reach household B's data.** Cross-checked
against #2419 / #2453 / #2454; no drift.

### Press — CLEAN

Press has no household dimension by design (`press_messages` is install-wide). All console surfaces
are `requireOperator` (admin AND household 1); the agent callbacks are `PressToken`-signed.

### Unauthenticated surfaces

| Route | Household derivation | Verdict |
|---|---|---|
| `POST /api/auth/login` | identifier form — email (global-unique row), `slug/username`, or bare→default | ✅ derives; uniform `InvalidCredentials` + bcrypt timing equalization, no enumeration |
| `POST /api/auth/forgot-password` | globally-unique `users.email` row | ✅ derives |
| `POST /api/auth/reset-password` | the token's user row | ✅ derives |
| `POST /api/beta/request` | none needed — no household exists yet | ✅ |
| `POST /api/beta/accept` | invite token provisions and binds its OWN household | ✅ |
| `POST /api/router/register` | single-use enrollment token; household stamped at create (#2106) | ✅ |
| `POST /api/billing/webhook` | Stripe signature is the auth; household from `stripe_customer_id` | ✅ |
| `POST /api/support/webhook` | Plain HMAC is the auth; household from the thread's customer | ✅ |
| `POST /api/press/inbound` | CF Email Worker shared secret; no household dimension | ✅ |
| `POST /api/access-requests` | ⚠️ derived in-SQL from the device row, arbitrary on a shared MAC (#2322); debounce read unscoped | ⚠️ **F3 / #2566** |
| `GET /api/blocked` | ⚠️ hardcoded `HouseholdId.Default` | ⚠️ **F6 / #2569** |

**Seven of nine derive correctly or need no household. The two that do not are both the block
page** — and they are the same page, which is why #2322, F3 and F6 should be fixed together.

`DebugRoutes` (7 endpoints) is loopback-only **and** `WIFIHAVEN_DEBUG=1`-gated and is an explicit
all-tenant triage dump — acceptable by design. One doc nit: the comment at
[`DebugRoutes.scala:133`](../../api/src/routes/DebugRoutes.scala) claims "this endpoint's guard
already restricts to admins"; the guard is loopback, not admin. Harmless, but wrong.

---

## 6. Status of the seven known issues

Asked to confirm whether each one's scope is complete or wider than described:

| Issue | Verdict |
|---|---|
| **#2532** `UserProfileRepo` | **Confirmed, scope accurate.** All five methods are unscoped as described; every consumer is independently household-scoped, so it is latent not exploitable — verified by re-reading `visibleProfiles` / `filterDevices` / `filterLogs` / `requireProfile*Access` / `SpaPush`. The issue's own analysis holds. |
| **#2553** rollup + ambient jobs | **Confirmed; scope slightly wider.** Both `settings` reads are as described. Add: the global `ambient_host_days` merge (`AmbientLearnJob.scala:159`) means one household's traffic shapes another's ambient baseline — the issue mentions deciding what to key on, but does not name it as a cross-household coupling. Also add the dependency on **F7 / #2570** (the rollup-cache wipe cannot be narrowed until #2553 lands). |
| **#2535** catalog mutation | **Confirmed; scope materially wider.** The issue names 2 mutators; there are **6** (`+ reset-to-template`, `seed-from-templates`, `reconcile-templates`, `blocklists/{id}/refresh`) — filed as **#2567**. `reconcile-templates` is the worst: `mergeAppInto` deletes app rows and repoints assignments across every household. |
| **#2283** `alerts.household_id` | **Already done.** V78 added the column, V79 added the composite FK and dropped the `DEFAULT 1`, and `AlertRepoLive.baseSelect` (`:2186`) now joins `d.mac = a.mac AND d.household_id = a.household_id` with `listForHousehold` scoping on `a.household_id`. The issue's three work items are all shipped — **it appears stale and closeable.** The remaining gap it does *not* cover is the per-id write path, which is **F1 / #2564**. |
| **#2264** `dayStateAll` residual reads | **Confirmed; scope wider.** `windowsForAllProfiles` and `snapshotAllByProfile` are as described. Add a **third**: `rollupRepo.getDayMap(date)` (`TimeStatusService.scala:372`) is an equally all-tenant read in `dayStateAllFromRollup`, and the issue does not name it. Same non-leaking analysis applies (results are `ProfileId`-keyed and only the scoped household's profiles are iterated), but the fix should close all three. |
| **#2142** remaining `DEFAULT 1` | **Confirmed; scope wider.** The three named tables still carry it. A **fourth**, `named_schedules` (V72, which post-dates the issue), does too — filed as **#2572**. `households.slug` is still nullable as described. |
| **#2322** block-page household | **Confirmed; scope wider.** The write-path analysis is exact. It does not cover (a) the unscoped debounce **read** on the same route (**F3 / #2566**) or (b) `GET /api/blocked`, the other half of the same page (**F6 / #2569**). All three want the same fix — an authoritative household for the block page — and should land together. |

---

## 7. The structural guard

[`api/test/src/feature/MultiTenantRouteCensusSpec.scala`](../../api/test/src/feature/MultiTenantRouteCensusSpec.scala)
— six tests, CI-enforced via the existing `mill __.test`.

**Layer 1 — the census.** Every route in `api/src/routes` must appear in a `Census` map with an
explicit `Tenancy` verdict (`Scoped`, `InstallWide(why)`, `Operator(why)`, `RouterToken(why)`,
`AgentToken(why)`, `Unauthenticated(why)`, `NoTenancy(why)`). Adding, renaming, re-pathing or
removing a route fails the build until someone writes the verdict down. The verdict is a **value the
next test consumes**, not a comment or a checklist item.

**Layer 2 — the choke-point invariant.** Every route declared `Scoped` whose path lets the caller
*name* a row (`long("id")` / `string("mac")`) must textually compose one of the known tenancy choke
points: `requireProfileInHousehold`, `requireProfile{,Read}Access`, `requireScheduleInHousehold`,
`ownUser`, `findByMacInHousehold`, `householdOf`, `requireOperator`, or the inline
`householdId == claims.hh`.

**A bare `claims.hh` is deliberately not a checker**, and test 5 pins that. Reading the caller's
household to *stamp* a write is not the same as *checking* the target — and conflating the two is
exactly how F1 shipped: `POST /api/alerts/{id}/approve` mentions `claims.hh` on the line that stamps
the extension grant, while the alert id it acts on is never checked at all.

**Why this shape.** Best coverage-per-fragility of the options considered. A two-household fixture
run against every feature spec is more precise but needs per-route wiring that rots; a type-level
encoding is a large refactor. This is a source scan: it cannot prove the checker is composed on the
*right* id, but it covers **100% of routes in every route file with zero per-route wiring** and
would have caught both F1 and F2 on the day they were written. The behavioural half already exists
and stays where it is — `MultiTenantIsolationSpec` pins the real cross-household HTTP responses.

**Non-vacuity.** A static guard's characteristic failure is going *vacuously* green — the scan
stops matching, reports zero offenders, and that reads as "all clear". Three tests close that off:

- **Test 4** asserts the scan really does see checkers on the 28 of 38 entity-parameterized routes
  that have them, **and** that every one of the nine `Checkers` strings still occurs somewhere in
  `api/src/routes`. `Checkers` mirrors function names by string, so a rename — or a reflow of the
  whitespace-sensitive `householdId == claims.hh`, which has exactly one occurrence — would
  otherwise silently blind the invariant to every route that used it.
- **Test 2** iterates *every* route declaration, not the deduped one-per-key set. Duplicate keys
  exist (125 declarations dedupe to 123), and checking only the deduped head would let an unguarded
  declaration hide behind a guarded one in a file that sorts earlier.
- **Test 6** requires every non-`Scoped` verdict to carry a substantive `reason`. Those verdicts are
  wholly exempt from the invariant, so the reason is the entire audit trail for the exemption — an
  empty or placeholder one would let a future author silence a real finding with
  `InstallWide("shared")` and keep CI green.

**The tracked-broken allowlist.** F1's and F2's routes are declared `ScopedTracked(2564)` /
`ScopedTracked(2565)` — the audit does not fix findings (each gets its own chip), so the guard would
be red on arrival. Test 3 pins that every tracked entry names a real issue **and** that it is still
genuinely unguarded: land the fix and forget to delete the entry, and CI tells you to. The bound is
shrink-only (`size <= 3`) rather than non-empty, so the last fix — the one that empties the list —
does not read as a red build. A new unguarded route cannot be waved through without filing an issue
for it first.

**Red → green** is visible in this PR's history: commit 1 declares all three routes plain `Scoped`
and test 2 fails naming them; commit 2 moves them to the allowlist.

---

## 8. Explicitly UNVERIFIED

Stated rather than guessed:

- **Whether any household currently shares a MAC with another in production.** F5's and F3's
  severity is conditioned on it. `devices_mac_key` was dropped in V74 so it is *representable*, and
  #2125/#2313/#2314 all treated the condition as real; the audit treats prod as read-only and did
  not query for actual collisions.
- **`traffic_hourly` / `traffic_daily` / `*_apps` read paths.** Verified `router_id`-keyed and
  reached only through household-scoped callers (`RollupRepo.listHourlyInRange` /
  `listDailyInRange` / `aggregateByApp*` are called from `UsageTraffic` behind `claims.hh`-scoped
  handlers). Not traced through every branch of `UsageTrafficQuery`'s tier selection — no gap
  observed, but coverage is by-caller rather than by-SQL-predicate for these four.
- **The SPA.** Out of scope; the audit covers the API. `SpaPush` / `SpaWsRegistry` were audited
  (clean, per-household grouping post-#2257) because they are server-side.
- **The OpenWRT agent's local state.** Out of scope. The snapshot the agent receives is
  household-scoped at the source (`snapshot(router.householdId)`), which is the boundary that
  matters.
