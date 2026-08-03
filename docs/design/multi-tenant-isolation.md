# Design — multi-tenant isolation (`household_id` tenancy key)

Status: **proposed** (design only — no code in this PR). Kickoff for the
multi-tenant EPIC [#622](https://github.com/wifihaven/wifihaven/issues/622),
filed from the [#369](https://github.com/wifihaven/wifihaven/issues/369)
security audit finding [#2085](https://github.com/wifihaven/wifihaven/issues/2085).

> **Scope of this doc.** This is the umbrella design for making the cloud
> installation serve more than one household. It (a) fixes the tenancy key and
> where it is rooted, (b) enumerates every data read, router-wire flow, and
> edge/config surface that must become household-scoped — mapped to the five
> gaps the audit found, with `file:line` citations, and (c) lays out the schema
> migration, wire back-compat story, test-pinned isolation invariant, and a
> phased, independently-shippable rollout. It does **not** implement
> `household_id` anywhere. The implementation sub-issues filed against this doc
> are listed in [§10](#10-sub-issue-decomposition).
>
> **Out of scope entirely (tracked elsewhere):** tenant-enrollment UI, billing,
> and Stripe integration (named in #622) are product surfaces that sit *on top
> of* the isolation substrate this doc defines. They cannot be built safely
> until the isolation invariant below holds, so they are deliberately deferred
> to their own issues. See [§9](#9-non-goals--phasing).

---

## 0. Why, and the one load-bearing invariant

WifiHaven runs today as a **single-household** deployment: one set of profiles,
devices, users, and (usually) one router, all sharing global tables with no
tenancy dimension. Nothing here is a bug *today* — with one household, "all
rows" and "this household's rows" are the same set. But every place that
assumes that equivalence is a place where, the moment a second household
enrolls, household A could read or write household B's data.

The whole design serves one invariant:

> **A household-A principal (user JWT or router token) must never read or write
> a household-B row.**

Everything below — the tenancy key, the per-query predicate, the router→household
binding, the constructively-scoped ingest writes, the test pins — exists to make
that invariant hold by construction and keep it held by CI.

### 0.1 The tenancy key: `household_id`, rooted in a `households` table

We introduce one new root table:

```sql
CREATE TABLE households (
  id         BIGSERIAL PRIMARY KEY,
  name       TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

`household_id BIGINT NOT NULL REFERENCES households(id)` becomes a column on the
**four tenant-root entities**:

| Table | Why it roots tenancy |
| --- | --- |
| `users` ([`V1__init.sql:4`](../../api/resources/db/migration/V1__init.sql)) | A user's JWT carries its household; every authorized read filters by it. |
| `routers` ([`V2__openwrt.sql:12`](../../api/resources/db/migration/V2__openwrt.sql)) | The router token resolves to a household; the snapshot and ingest scope to it. |
| `profiles` ([`V1__init.sql:12`](../../api/resources/db/migration/V1__init.sql)) | Policy authoring unit; all schedule/limit/app-assignment child tables hang off it. |
| `devices` ([`V1__init.sql:53`](../../api/resources/db/migration/V1__init.sql)) | The MAC↔household binding; the ingest write-path validates payload MACs against it. |

> **Correction to #622's scope.** The epic issue says *"a tenant table that each
> router, device, and profile links to … I don't think any other tables need to
> link to them, because all other tables link to a router, device, or profile."*
> That is *almost* right but misses **`users`** and **`household_settings`**:
> - `users` links to none of those three (a user relates to profiles only
>   through the `user_profiles` mapping, and admin/adult users may relate to
>   *no* profile), so a user JWT has no path to a household without a direct
>   `users.household_id`. This is the source of the household claim in §4.
> - `household_settings` ([`V16__time_handling.sql:21`](../../api/resources/db/migration/V16__time_handling.sql))
>   is a **single-row** table today; it must become one-row-per-household.

Everything else stays scoped **transitively**: `schedules`, `time_limits`,
`site_time_limits`, `app_policy_assignments`, `app_time_limits`,
`named_schedules` etc. carry `profile_id` (FK to a now-scoped `profiles`);
`traffic_reports`, `connection_events` carry `router_id` (FK to a now-scoped
`routers`). Reads through those parents inherit the household predicate via the
join — we do **not** denormalize `household_id` onto every leaf table.

The MAC-keyed screen-time tables are the one subtlety — see [§3.4](#34-the-mac-keyed-screen-time-tables).

> **Two global uniqueness constraints must be relaxed to per-household**, or the
> tenancy key is defeated at the schema level:
> - `devices.mac TEXT NOT NULL UNIQUE`
>   ([`V1__init.sql:55`](../../api/resources/db/migration/V1__init.sql)) →
>   `UNIQUE(household_id, mac)`. Two households can legitimately present the same
>   MAC (randomized-MAC clients, guest devices), and the §3.4 "join through
>   `devices`" isolation only holds if each household has its *own* device row for
>   that MAC. Under the global constraint, household B's device would collide with
>   A's row and one tenant would silently attach to the other's screen-time.
> - `users.username TEXT NOT NULL UNIQUE`
>   ([`V1__init.sql:6`](../../api/resources/db/migration/V1__init.sql)) →
>   `UNIQUE(household_id, username)`, so two households can each have an `admin`
>   login. (Login then resolves username *within* a household — see [§4.1](#41-user-jwt-household-claim).)

### 0.2 What stays global (deliberately not tenant-scoped)

- **Category blocklists** (`blocklist_domains`,
  [`V1__init.sql:66`](../../api/resources/db/migration/V1__init.sql), and the
  YAML-sourced curated lists under `api/resources/blocklists/`). These are
  **curated shared resources**, not tenant data — every household draws from the
  same catalog. `renderBlocklist` serves a blocklist by id; scoping it is about
  *authorization* (does this router's household subscribe to this list?), not
  about per-tenant *content*. See [§3.2](#32-the-router-wire).
- **App templates** (`api/resources/app_templates/`). Template-authored,
  shared, read-only from the tenant's perspective (per
  `memory/feedback_apps_template_authored_only.md`).
- **Edge/config globals** (CORS, allowed hosts, WS origin gate) —
  per-*deployment*, not per-household, until custom domains land (a non-goal,
  [§9](#9-non-goals--phasing)); the surfaces are enumerated in
  [`custom-domain-edge-config.md`](custom-domain-edge-config.md).

> **Staying global is a statement about the DATA, not about the GATE**
> (#2535/#2567). The `apps` / `app_hosts` / `blocklist_domains` catalogs are
> correctly un-scoped — but the routes that *mutate* them were not: six sat
> behind `requireWriter` / `requireAdmin`, so an adult or admin in any household
> could rewrite state every other household's enforcement reads (worst:
> `POST /api/admin/apps/reconcile-templates`, whose `mergeAppInto` deletes an
> `apps` row and repoints `app_policy_assignments` + the app rollup tables across
> every tenant). The fix is not a tenancy column — per-household copies of a
> curated shared catalog would be wrong — it is that these are **operator
> maintenance verbs**, so all six now sit behind `requireOperator`
> ([§9](#9-non-goals--phasing)'s narrow admin-AND-household-1 exception):
> `POST /api/blocklists/:category/clear`, `POST /api/blocklists/:id/refresh`,
> `DELETE /api/apps/:id`, `POST /api/apps/seed-from-templates`,
> `POST /api/apps/:id/reset-to-template`, `POST /api/admin/apps/reconcile-templates`.
> None was reachable from the SPA. The READ side (`GET /api/blocklists`,
> `GET /api/blocklists/:id/hosts`) deliberately **stays** `requireWriter`: bundled
> public data with no confidentiality dimension, on live SPA surfaces. Pinned in
> `api/test/src/feature/CatalogOperatorGateSpec.scala`.

---

## 1. Invariants this design must not break

From `AGENTS.md` and `docs/architecture.md` — load-bearing, and multi-tenancy
must not weaken them:

1. **DNS is never the enforcement plane.** Tenancy adds no DNS-layer mechanism;
   blocking stays nftables forward-drop on resolved IPs.
2. **The router is a dumb applier.** The router still receives a pre-resolved
   `Map[MacAddress, BlockRules]`. Tenancy changes *which rows* the server reads
   to build that map (scoped to the token's household), **not** the wire shape
   the router applies. The router never learns the word "household."
3. **The snapshot is a minimal functional shape.** `household_id` is a
   server-side data-scoping key; it does **not** appear on the wire. There is no
   `householdId` field on `PolicySnapshot`, `DevicePolicy`, or any ingest
   payload. (A router already implicitly *is* one household by virtue of its
   token — see [§4.2](#42-router--household-binding).)
4. **Additive-only wire contract** (`docs/process/wire-contract.md`). API and
   agents deploy independently; the router-visible shapes cannot change
   incompatibly. See [§6](#6-wire-back-compat).

---

## 2. The five gaps (from #2085), verified against current code

Each audit gap, re-verified at current `HEAD` with fresh `file:line` citations.

### Gap 1 — the policy snapshot is household-global, not per-router

`PolicyService.snapshot: Task[PolicySnapshot]`
([`PolicyService.scala:235`](../../api/src/policy/PolicyService.scala)) takes
**no router/household argument**. Its builder `buildSnapshot`
([`PolicyService.scala:305`](../../api/src/policy/PolicyService.scala)) reads
the whole install globally:

- `householdSettingsRepo.get` (single row) — [`:307`](../../api/src/policy/PolicyService.scala)
- `profileRepo.listAllIncludingGlobal` — [`:316`](../../api/src/policy/PolicyService.scala)
- `deviceRepo.listAll` — [`:319`](../../api/src/policy/PolicyService.scala)
- `blocklistRepo.listCategories` — [`:320`](../../api/src/policy/PolicyService.scala)

`GET /api/router/policy` therefore returns **every** household's
devices/profiles/blocklists to **any** enrolled router token. Same for
`renderBlocklist(id)` ([`PolicyService.scala:551`](../../api/src/policy/PolicyService.scala)),
which resolves a category for *any* caller, and the `/api/router/decision`
lookup path that does `deviceRepo.listAll.map(_.find(_.mac == mac))`
([`PolicyService.scala:585`](../../api/src/policy/PolicyService.scala)).

**Fix:** `snapshot` becomes `snapshot(household: HouseholdId)` (resolved from
the router token, §4.2); every read inside `buildSnapshot` gains a
`household_id` predicate. See sub-issue **D** ([§10](#10-sub-issue-decomposition)).

### Gap 2 — router token is not bound to a device/household scope

`RouterAuthLive.authenticate`
([`RouterAuth.scala:41`](../../api/src/routes/RouterAuth.scala)) resolves a
bearer token to a `Router` record via `findByTokenHash`, and ingest correctly
rejects a payload whose `router_id` ≠ the authed router
([`RouterIngestService.scala:59`](../../api/src/routes/RouterIngestService.scala)
and [`:122`](../../api/src/routes/RouterIngestService.scala)). So a router
cannot forge *another router's* `router_id`.

**But the `mac` inside the payload is trusted unconditionally.** `ingestUsage` /
`ingestEvents` write `(router_id, mac, …)` rows
([`RouterIngestService.scala:87`](../../api/src/routes/RouterIngestService.scala),
[`:149`](../../api/src/routes/RouterIngestService.scala)) with no check that
`mac` names a device in the router's household. Since `routers` has no
`household_id` today, there is nothing to check against. A malicious/compromised
router could report usage or connection events for **another household's device
MAC**, corrupting that device's screen-time and connection history (which are
MAC-keyed — see [§3.4](#34-the-mac-keyed-screen-time-tables)).

**Fix:** bind `routers.household_id`, then make every MAC-keyed ingest write
**constructively scoped** to `(router.householdId, mac)` — not a
lookup-and-reject. See [§3.2.2](#32-the-router-wire) for why rejection is the
wrong mechanism (it would break new-device discovery), and sub-issues **C** + **E**.

### Gap 3 — `admin`/`adult` means "see ALL rows"

The visibility helpers in `Routes.scala` treat admin/adult as *global*
visibility over `listAll`:

- `visibleProfiles` — [`Routes.scala:2202`](../../api/src/routes/Routes.scala):
  `if role == admin || adult then ZIO.succeed(all)`.
- `filterDevices` — [`Routes.scala:2214`](../../api/src/routes/Routes.scala).
- `filterLogs` — [`Routes.scala:2226`](../../api/src/routes/Routes.scala).
- `requireProfileReadAccess` — [`Routes.scala:2240`](../../api/src/routes/Routes.scala).
- `requireProfileAccess` (write) — [`Routes.scala:2267`](../../api/src/routes/Routes.scala).

The `all` these receive is already the *global* `profileRepo.listAll` /
`deviceRepo.listAll` result. In multi-tenant, "all" must mean "all **in the
caller's household**." The cleanest fix is to scope at the source (the repo
read, gap 4) so the list handed to these helpers is *already* household-bounded;
the helpers then keep doing role-based narrowing **within** the household
unchanged.

### Gap 4 — all repo reads are global-table

Every `*Repo` list/get read is a singleton with no household dimension. Full
inventory (trait declarations in [`Repos.scala`](../../api/src/db/Repos.scala)):

| Repo read | Decl | Callers to scope |
| --- | --- | --- |
| `UserRepo.listAll` | [`:87`](../../api/src/db/Repos.scala) | user admin routes ([`Routes.scala:113`](../../api/src/routes/Routes.scala),[`:635`](../../api/src/routes/Routes.scala)) |
| `UserProfileRepo.listAllMappings` | [`:95`](../../api/src/db/Repos.scala) | user↔profile admin |
| `ProfileRepo.listAll` | [`:111`](../../api/src/db/Repos.scala) | profile list, snapshot, time-status |
| `ProfileRepo.listAllIncludingGlobal` | [`:114`](../../api/src/db/Repos.scala) | snapshot builder ([`PolicyService.scala:316`](../../api/src/policy/PolicyService.scala)) |
| `NamedScheduleRepo.listAll` | [`:148`](../../api/src/db/Repos.scala) | schedule admin |
| ~~`HouseholdSettingsRepo.get` (single row)~~ | — | **CLOSED (#2533, #2553).** `get` and the unscoped `update(s)` are deleted; `getForHousehold(household)` / `update(household, s)` are the only accessors. #2553 removed the two all-tenant-batch `HouseholdId.Default` reads: `TimeUsedRollupJob` / `AmbientLearnJob` now read each household's OWN settings inside their per-household loop (own day key, own heartbeat/ambient knobs), and `update`'s rollup-cache invalidation is scoped to the writing household's profiles. One explicit `Default` read remains, separately justified in place: [`BlockedRoutes.scala:113`](../../api/src/routes/BlockedRoutes.scala) (the block-page redirect carries no household). |
| `TimeLimitRepo.listAll` | [`:217`](../../api/src/db/Repos.scala) | time-status ([`Routes.scala:904`](../../api/src/routes/Routes.scala)) |
| `AppTimeLimitRepo.listAll` | [`:236`](../../api/src/db/Repos.scala) | time-status ([`Routes.scala:905`](../../api/src/routes/Routes.scala)) |
| `DeviceRepo.listAll` | [`:240`](../../api/src/db/Repos.scala) | devices list, snapshot, decision lookup |
| `AlertRepo.list(includeAll)` | [`:321`](../../api/src/db/Repos.scala) | alerts routes — scope by household |
| `RouterRepo.listAll` | [`:507`](../../api/src/db/Repos.scala) | routers admin list |
| `AppRepo.listAll` / `listAllHostMappings` | [`:2966`](../../api/src/db/Repos.scala),[`:3032`](../../api/src/db/Repos.scala) | apps are **template-global**; scope only *assignments*, not the catalog |

Plus the global-sentinel profile (`isGlobal`,
[`V59__profiles_is_global_drop_globals.sql`](../../api/resources/db/migration/V59__profiles_is_global_drop_globals.sql)):
multi-tenant needs **one sentinel per household** (the global-policy layer is
per-household policy, not fleet-wide). Delivered by #2286:
[`V73__profiles_is_global_per_household.sql`](../../api/resources/db/migration/V73__profiles_is_global_per_household.sql)
widens the `is_global` partial-unique index from installation-wide to
`(household_id, is_global)`, and provisioning seeds each new household's `Global`
sentinel at household-create time (see the #2286 code follow-up) — so
`getGlobalForHousehold` / `GET /api/profiles/global` resolve for every household,
not just the default install.

**Fix:** each read above becomes household-scoped — either a new
`…ByHousehold(hh)` method or a `household_id` predicate added to the existing
query. The `SqlFragments` helper ([`api/src/db/SqlFragments.scala`](../../api/src/db/SqlFragments.scala))
is the natural home for a shared `AND household_id = $hh` fragment. See
sub-issue **E**.

### Gap 5 — public/edge globals are per-deployment

CORS `allowedOrigins` ([`CorsConfig`, Config.scala:119](../../api/src/Config.scala),
consumed in [`Cors.scala:12`](../../api/src/Cors.scala)), `WIFIHAVEN_UI_ALLOWED_HOSTS`
([`PolicyConfig.uiAllowedHosts`, Config.scala:135](../../api/src/Config.scala)),
and `WIFIHAVEN_WS_ALLOWED_ORIGINS` ([`WsConfig.allowedOrigins`, Config.scala:184](../../api/src/Config.scala))
are single per-deployment config values. For the v1
multi-tenant model (**shared apex domain**, e.g. all tenants under
`app.wifihaven.net`), these stay per-deployment and need **no change** — every
tenant shares the same origin. They only become per-household when we offer
**custom per-household domains**, which is an explicit non-goal ([§9](#9-non-goals--phasing)).
The concrete pointer — all three surfaces, verified citations, env vars, and
what a custom-domain epic must change — lives in
[`custom-domain-edge-config.md`](custom-domain-edge-config.md) (sub-issue **F**,
[#2109](https://github.com/wifihaven/wifihaven/issues/2109)); no v1 work item.

---

## 3. Enforcement points, grouped by plane

Three distinct planes, each with its own mechanism.

### 3.1 Server-side data reads (user-facing API)

The `household_id` predicate is applied **at the repo read**, so every consumer
(the gap-3 visibility helpers, the time-status endpoints, the admin lists)
receives an already-scoped list and needs no per-call awareness. The user's
household comes from the JWT claim (§4.1). This is the bulk of the work
(sub-issue **E**) and is the plane most directly guarded by the isolation test
pins (§7).

### 3.2 The router wire

Two sub-mechanisms, **neither of which changes the wire shape**:

1. **Snapshot + blocklist scoping (read).** `snapshot`/`renderBlocklist`/the
   decision-lookup path take the household resolved from the router token and
   read only that household's rows (gap 1). A router receives a snapshot
   containing only its own household's devices/profiles; for `renderBlocklist`,
   the household is checked to *authorize* access to a shared category list, but
   the list **content** stays global (§0.2).
2. **Token binding + constructively-scoped ingest writes.** `routers.household_id`
   (gap 2) closes the cross-household screen-time-poisoning hole — but the
   mechanism is **constructive scoping, not lookup-and-reject**: every MAC-keyed
   ingest write is keyed `(router.householdId, mac)`, so a router *cannot
   address* another household's rows by construction. The `router_id`-mismatch
   guard already exists
   ([`RouterIngestService.scala:59`](../../api/src/routes/RouterIngestService.scala));
   the household scoping is the additive change.

   **Why not reject unknown MACs?** Because a first-seen MAC is not an error —
   it is how **new-device discovery** works. There is no device-enrollment step:
   a device joins a household by appearing behind its gateway. Ingest
   auto-creates the row via `deviceRepo.upsertUnknown`
   ([`RouterIngestService.scala:459`](../../api/src/routes/RouterIngestService.scala)
   → [`Repos.scala:1212`](../../api/src/db/Repos.scala), an
   `INSERT … ON CONFLICT(mac) DO UPDATE` with `profile_id = NULL`), and the
   device sits **unmanaged** — governed by the household's unmanaged-MAC policy
   (allow, or block with the `Unmanaged` reason) — until an admin assigns it to
   a profile. A reject-unknown-MACs guard would break that flow on every first
   sighting.

   Under multi-tenancy this flow is unchanged in shape: household membership of
   a device is not *detected*, it is *defined* by which gateway it appeared
   behind. `upsertUnknown` becomes
   `INSERT … (household_id = router.householdId, mac, …) ON CONFLICT
   (household_id, mac) DO UPDATE` — a new MAC reported by household-A's router
   is created **in A**, unmanaged, exactly as today. If the same MAC later
   appears behind household-B's router, that is a **different row** under
   `UNIQUE(household_id, mac)` (§0.1) — no collision, no ambiguity, and neither
   household can see or affect the other's row. Once the global `UNIQUE(mac)` is
   relaxed, "does this MAC belong to another household?" is a question ingest
   can't ask and doesn't need to: all device reads/writes inside ingest
   (`upsertUnknown`, `touchLastSeenBatch`, `findByMac`, and the MAC-keyed
   `time_usage`/`time_extensions` writes via the §3.4 join) carry the router's
   household key.

The router agent, UCI keys, and CLI flags are **not** part of this — they carry
no household concept and need no change. Household scoping is entirely
server-side; the token *is* the household handle.

### 3.3 Edge/config

Per §2 gap 5 — no v1 work; pointer only. The three per-deployment surfaces
(CORS, UI allowed hosts, WS origin gate) and what a future custom-domain epic
must make per-household are recorded in
[`custom-domain-edge-config.md`](custom-domain-edge-config.md) (sub-issue **F**).

### 3.4 The MAC-keyed screen-time tables

`time_usage` ([`V1__init.sql:76`](../../api/resources/db/migration/V1__init.sql))
and `time_extensions` ([`V1__init.sql:89`](../../api/resources/db/migration/V1__init.sql))
are keyed by bare `device_mac` with **no** `router_id` or `household_id`.
`traffic_reports`/`connection_events`/`block_events` are keyed with `router_id`
(→ household transitively once `routers.household_id` lands), but MAC collisions
across households (two tenants with a device at the same randomized MAC — not
impossible with MAC randomization) would still let one household's usage read
join to another's rows.

**Decision, split by how the table is keyed:**

- **`router_id`-keyed tables** (`traffic_reports`, `connection_events`) need
  **no new column**: `router_id` → `routers.household_id` scopes them
  transitively, and reads already filter by router or join through a
  household-scoped `devices` row. Writes are constructively scoped because the
  `router_id` comes from the authed token, never the payload
  ([`RouterIngestService.scala:59`](../../api/src/routes/RouterIngestService.scala)).
- **Bare-MAC-keyed tables** (`time_usage` — `UNIQUE(device_mac, domain, date)`;
  `time_extensions`; `block_events.mac`) **must gain `household_id` in the key**,
  becoming e.g. `UNIQUE(household_id, device_mac, domain, date)`. Join-through-
  `devices` is *not* sufficient here: under a cross-household MAC collision, two
  households' routers would increment the **same** `time_usage` row (a *write*
  collision, not a read bypass), and then each household's `devices` join would
  faithfully read the other tenant's polluted minutes — corrupting daily-limit
  enforcement in both households. The ingest write path stamps
  `router.householdId` into these increments; reads add the household predicate.

The isolation guarantee then rests on: (1) reads join through a
household-scoped `devices` row (a foreign MAC has no row to join), and (2) all
MAC-keyed writes carry the router's household key, with `upsertUnknown` creating
the household's own device row on first sighting (§3.2.2). This is a deliberate,
narrow denormalization: `household_id` goes only onto tables whose *unique key*
is a bare MAC, not onto every leaf (`docs/process/single-source-of-truth.md`).
The `time_usage` backfill must be estimated against prod row counts per
`docs/process/migrations.md#migrations-prod-data-volume`.

---

## 4. Auth — where the household comes from

### 4.1 User JWT household claim

`JwtClaims` ([`AuthService.scala:17`](../../api/src/auth/AuthService.scala))
carries `sub`, `role`, `iat`, `exp` — **no household**. We add `hh:
HouseholdId`, minted at login from `users.household_id`
([`AuthService.scala:79`](../../api/src/auth/AuthService.scala) is where the
`JwtClaim` content is built) and read back in `verify`
([`AuthService.scala:116`](../../api/src/auth/AuthService.scala), which
reconstructs `JwtClaims` ~`:125`). Every
authorized route then passes `claims.hh` into the (now household-scoped) repo
read.

This composes cleanly with the just-landed `token_version` hardening
([`V64__auth_hardening.sql`](../../api/resources/db/migration/V64__auth_hardening.sql),
#2080): both are additive JWT claims read in `verify`. The household claim is
**not** security-critical to forge-protect beyond the existing HMAC signature —
the JWT is already signed, so a client cannot rewrite `hh` any more than it can
rewrite `role`.

### 4.2 Router → household binding

The router token already resolves to a `Router` record
([`RouterAuth.scala:41`](../../api/src/routes/RouterAuth.scala)). Once `routers`
has `household_id`, `authenticate` returns a `Router` that carries its
household, and every `/api/router/*` handler scopes to `router.householdId` with
zero wire change. The binding is established at **enrollment**: the enrollment
row (`enrollment_token_hash`,
[`V2__openwrt.sql:15`](../../api/resources/db/migration/V2__openwrt.sql)) is
created by an admin *who is already in a household* (their JWT `hh`), so the new
router row inherits `household_id` from the creating admin's claim. No new wire
field on `POST /api/router/register`.

---

## 5. Schema, migration, and backfill

Per `docs/process/migrations.md`, the schema change ships as its **own
schema-only migration PR** (migration SQL + docs only, no source), so the
existing feature-test suite acts as the back-compat gate. The source that reads
the new columns lands in follow-up PRs (sub-issues C–E).

### 5.1 The migration (sub-issue A)

```sql
-- VN__households.sql
CREATE TABLE households (
  id         BIGSERIAL PRIMARY KEY,
  name       TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Backfill the single existing household as id = 1.
INSERT INTO households (name) VALUES ('Default household');

-- Add household_id to the four roots + household_settings, backfilled to the
-- default household, then made NOT NULL. Two-step (nullable add → backfill →
-- set NOT NULL) so the ALTER doesn't rewrite-lock on a non-empty table.
ALTER TABLE users            ADD COLUMN household_id BIGINT REFERENCES households(id);
ALTER TABLE routers          ADD COLUMN household_id BIGINT REFERENCES households(id);
ALTER TABLE profiles         ADD COLUMN household_id BIGINT REFERENCES households(id);
ALTER TABLE devices          ADD COLUMN household_id BIGINT REFERENCES households(id);
UPDATE users            SET household_id = 1;
UPDATE routers          SET household_id = 1;
UPDATE profiles         SET household_id = 1;
UPDATE devices          SET household_id = 1;
ALTER TABLE users    ALTER COLUMN household_id SET NOT NULL;
ALTER TABLE routers  ALTER COLUMN household_id SET NOT NULL;
ALTER TABLE profiles ALTER COLUMN household_id SET NOT NULL;
ALTER TABLE devices  ALTER COLUMN household_id SET NOT NULL;

-- Relax the two GLOBAL uniqueness constraints to per-household (§0.1). Without
-- this, cross-household MAC/username collisions defeat the tenancy key.
ALTER TABLE devices DROP CONSTRAINT devices_mac_key;        -- global UNIQUE(mac)
ALTER TABLE devices ADD CONSTRAINT uq_devices_household_mac UNIQUE (household_id, mac);
ALTER TABLE users   DROP CONSTRAINT users_username_key;     -- global UNIQUE(username)
ALTER TABLE users   ADD CONSTRAINT uq_users_household_username UNIQUE (household_id, username);

-- household_settings: single-row (CHECK (id = 1), V16) → one-row-per-household.
-- Drop the hard singleton CHECK and re-key by household_id.
ALTER TABLE household_settings DROP CONSTRAINT household_settings_id_check;
ALTER TABLE household_settings ADD COLUMN household_id BIGINT REFERENCES households(id);
UPDATE household_settings SET household_id = 1;
ALTER TABLE household_settings ALTER COLUMN household_id SET NOT NULL;
ALTER TABLE household_settings ADD CONSTRAINT uq_household_settings_household UNIQUE (household_id);

-- Partial indexes to keep the per-household predicate cheap.
CREATE INDEX idx_profiles_household ON profiles(household_id);
CREATE INDEX idx_devices_household  ON devices(household_id);
CREATE INDEX idx_routers_household  ON routers(household_id);
CREATE INDEX idx_users_household    ON users(household_id);
```

The bare-MAC-keyed screen-time tables also need the key change from §3.4
(shown separately because of the volume note below):

```sql
ALTER TABLE time_usage      ADD COLUMN household_id BIGINT REFERENCES households(id);
ALTER TABLE time_extensions ADD COLUMN household_id BIGINT REFERENCES households(id);
UPDATE time_usage      SET household_id = 1;
UPDATE time_extensions SET household_id = 1;
ALTER TABLE time_usage      ALTER COLUMN household_id SET NOT NULL;
ALTER TABLE time_extensions ALTER COLUMN household_id SET NOT NULL;
-- UNIQUE(device_mac, domain, date) → UNIQUE(household_id, device_mac, domain, date)
-- (drop/re-add; exact constraint names verified via \d at implementation time)
```

> **Prod data-volume note** (`docs/process/migrations.md#migrations-prod-data-volume`):
> the four root tables (`users`/`routers`/`profiles`/`devices`) are **small,
> bounded** tables and this migration does *not* touch the unbounded-growth
> tables (`traffic_reports`, `connection_events`, rollups). But `time_usage`
> grows with devices × domains × days (bounded by the retention sweep, #2086/
> #2089): its `UPDATE … SET household_id = 1` backfill and unique-index rebuild
> **must be estimated against prod row counts, not test fixtures**, before the
> migration PR merges — split it into its own migration if it approaches the
> 15-minute Render port-scan window.

### 5.2 Backfill correctness

The single existing install is `household_id = 1` uniformly. Because every
current row belongs to the one household, the backfill is a blanket `= 1` — no
per-row logic, no ambiguity. New households get fresh ids from the sequence.

### 5.3 Every new query proves its plan

Per `docs/process/query-perf.md`, each sub-issue that adds a `household_id`
predicate to a hot read (the snapshot builder, the time-status batch, device/
profile lists) must run `EXPLAIN (ANALYZE, BUFFERS)` at prod shape and confirm
the new partial indexes above are used, adding indexes in the **same** PR if a
plan regresses.

---

## 6. Wire back-compat

Per `docs/process/wire-contract.md` — API and agents deploy independently, so
router-visible shapes are a public contract.

**This design is wire-invisible, therefore trivially back-compat:**

- `household_id` is a **server-side data key**. It appears on no snapshot,
  ingest, or decision payload (invariant 3, §1). An older agent and a newer API
  exchange byte-identical wire shapes before and after.
- The snapshot an older agent receives is now *scoped* to its household, but a
  single-household install's scoped snapshot ≡ its old global snapshot, so
  rollout is a no-op for the existing fleet.
- The constructively-scoped ingest path (§3.2.2) is **behavior-preserving for
  the existing fleet**: nothing is rejected — a first-seen MAC is still
  auto-created via `upsertUnknown` (new-device discovery is unchanged), just
  stamped with the router's household. With one household, the scoped writes
  are byte-for-byte the same rows as today's.

No capability negotiation (#376) is required — there is no non-additive wire
change. (Contrast the websocket transport, which needed #376 analysis;
multi-tenancy needs none.)

---

## 7. Test strategy — pinning the isolation invariant

Per `docs/process/testing.md`: **feature tests through the full stack on
embedded Postgres, no repo mocks.** The load-bearing invariant (§0) gets
dedicated cross-household isolation pins, seeded with **two** households.

New seed helper (extends `TestLayers`): `seedTwoHouseholds` → returns
`(hhA, hhB)` each with its own admin user, one profile, one device, one enrolled
router token.

Then a `MultiTenantIsolationSpec` with these shapes (each is a *negative* test —
the point is the **absence** of leakage):

1. **User read isolation.** `GET /api/profiles` (and `/devices`, `/logs`,
   `/alerts`, `/time/status`) with household-A's admin JWT returns **only**
   household-A rows — never household-B's, even though A's role is `admin`
   (guards gaps 3+4). Assert B's profile id / device MAC are absent.
2. **User write isolation.** Household-A's admin `PATCH`/`PUT` against a
   household-B `profileId` / device MAC → `403`/`404`, never a successful write
   (guards `requireProfileAccess`, [`Routes.scala:2267`](../../api/src/routes/Routes.scala)).
3. **Snapshot scoping.** `GET /api/router/policy` with household-A's router
   token returns a `PolicySnapshot` whose `devices`/`profiles` maps contain
   **only** household-A MACs/profiles (guards gap 1).
4. **Ingest MAC isolation + new-device discovery.** Two halves:
   a. Household-A's router `POST /api/router/usage` with a `mac` that already
      exists as a household-B device → the write lands **only** under
      household-A's own `(hhA, mac)` rows (creating A's device row via
      `upsertUnknown` if first sighting); household-B's device row and
      `time_usage` are **byte-identical before/after** (guards gap 2 + §3.4's
      write-collision case).
   b. Household-A's router reports a **never-seen** MAC → a new unmanaged
      device row appears in household A (`profile_id = NULL`,
      `household_id = hhA`) and in **no other** household — pinning that
      constructive scoping did not break new-device discovery.
5. **Blocklist authorization.** `renderBlocklist` for a category the router's
   household is entitled to succeeds; content is identical across households
   (confirms §0.2 — shared catalog, scoped *authorization*).

These specs are **merge-gating** for every sub-issue that touches a scoped read
or the wire. A regression that reintroduces a global read fails pin 1 or 3
immediately. Because the seed uses two real households in embedded PG (not
mocks), the SQL predicate itself is what's under test — exactly the
`docs/process/testing.md` philosophy.

### 7.1 Every scoped read gets BOTH an isolation pin and a sees-own-data pin (#2176)

The isolation pins above are all *negative* — they prove household-A never sees
household-B's rows. That is necessary but **not sufficient**: a predicate that
resolves to the WRONG or an empty household passes every negative pin while
silently returning nothing. That is exactly the admin-0m usage regression
([#2167](https://github.com/wifihaven/wifihaven/issues/2167)) — scoped, isolated,
and broken (its proximate cause was read-amplification, but the *class* of bug an
isolation-only test can't catch is real).

So the standing rule for any household-scoped read: pair each negative pin with a
**positive** one that SEEDS the caller's household with real data and asserts it
reads back **non-empty and correct**, with the cross-household read still empty.
`MultiTenantSeesOwnDataSpec` is the home for these positive pins (mirroring the
`GET /api/time/status` usedMins/cap/appUsage shape the 0m bug hid in). And
`MultiTenantScopedReadGuardSpec` fails the build if a NEW unscoped route read
(a `…listAll` without a `…ForHousehold` sibling call) appears outside the tracked
allowlist — encoding the lesson rather than re-fixing instances. The still-unscoped
usage/analytics/push reads and `named_schedules` are tracked by
[#2126](https://github.com/wifihaven/wifihaven/issues/2126) /
[#2120](https://github.com/wifihaven/wifihaven/issues/2120).

**Index/backfill audit (2026-07-14, #2176):** every E-scoped read is index-backed —
the four root tables via V65's `idx_{profiles,devices,routers,users}_household`
(and the leading column of the composite uniques); the transitive `→ profiles`
joins (`time_limits`, `app_policy_assignments`) via the existing
`time_limits.profile_id UNIQUE` / `idx_app_policy_assignments_profile`; `alerts`
via `alerts(mac, …)`; the `connection_events → routers` logs join filters on the
tiny already-joined `routers` row and leaves `ts`-driven partition pruning intact.
No new index was needed. V65 added every `household_id` column `NOT NULL DEFAULT 1`
with an FK, so a NULL or dangling tenancy key is structurally impossible — pinned
non-vacuously by the backfill-integrity test.

---

## 8. Rollout order (foundation-first)

Dependencies force this order:

```
A (schema + backfill, schema-only PR)
        │
        ├──► B (JWT household claim)        ─┐
        ├──► C (router→household binding)    ├─► E (per-repo predicates + isolation pins)
        └──► D (snapshot/blocklist scoping) ─┘
                                              │
                                     F (edge/config — pointer only, no v1 work)
```

A merges first (it is the back-compat gate). B/C/D can proceed in parallel once
A lands. E depends on B (needs the claim) and is where the isolation pins (§7)
become green. F is documentation-only for v1.

---

## 9. Non-goals / phasing

Explicitly **out of scope for the isolation substrate (v1)**:

- **Tenant-enrollment UI, billing page, Stripe integration** (named in #622).
  These are product surfaces layered on top of a *correct* isolation substrate;
  building them first would bake in leaks. Deferred to their own issues once the
  §7 invariant holds.
- **Custom per-household domains.** v1 is a single shared apex
  (`app.wifihaven.net`); the edge globals (§2 gap 5) stay per-deployment. Custom
  domains reopen CORS / UI-allowed-hosts / WS-origin-gate as per-household
  config — a separate epic. The three surfaces and what that epic must change
  are enumerated in [`custom-domain-edge-config.md`](custom-domain-edge-config.md)
  (sub-issue **F**, [#2109](https://github.com/wifihaven/wifihaven/issues/2109)).
- **Cross-household admin / super-admin.** No principal reads across households
  in v1. A future ops console is a separate, deliberately-privileged surface.
- **Per-household blocklist *content* / custom category authoring.** The
  category catalog stays global and shared (§0.2). Per-household *subscriptions*
  to shared lists are in scope (they route through the scoped profile); authoring
  a household's own private category list is not.
- **Multi-instance connection fan-out** (#1952) — orthogonal transport-scaling
  concern, tracked separately.

---

## 10. Sub-issue decomposition

Filed against this doc; each references #2085 and #622. Ordered per §8.
Filed: **A** = [#2104](https://github.com/wifihaven/wifihaven/issues/2104),
**B** = [#2105](https://github.com/wifihaven/wifihaven/issues/2105),
**C** = [#2106](https://github.com/wifihaven/wifihaven/issues/2106),
**D** = [#2107](https://github.com/wifihaven/wifihaven/issues/2107),
**E** = [#2108](https://github.com/wifihaven/wifihaven/issues/2108),
**F** = [#2109](https://github.com/wifihaven/wifihaven/issues/2109).

- **A — schema + backfill (`households` table + `household_id` on the four roots).**
  Schema-only migration PR (migration SQL + this doc's §5 only, no source), per
  `docs/process/migrations.md`. Creates `households`, backfills the single
  existing install as `household_id = 1`, adds the column (nullable → backfill →
  NOT NULL) to `users`/`routers`/`profiles`/`devices` + `household_settings`
  (with a per-household unique constraint), and the partial indexes. Also adds
  `household_id` to the bare-MAC-keyed screen-time tables (`time_usage`,
  `time_extensions`) with the widened unique keys per §3.4/§5.1 — the
  `time_usage` backfill sized against prod row counts (split into its own
  migration if it approaches the port-scan window). The existing feature suite
  is the back-compat gate.
- **B — JWT household claim.** Add `hh: HouseholdId` to `JwtClaims`, mint it at
  login from `users.household_id`, read it back in `verify`. Additive claim
  alongside the #2080 `token_version` work. Thread `claims.hh` to the call sites
  that will consume it in E. Feature test: a minted token carries the right
  household; cross-household token rejected where household is checked.
- **C — router → household binding.** Add `household_id` to the `Router` model +
  `RouterRepo`, populate it at enrollment from the creating admin's JWT
  household, and surface it on the `Router` returned by `RouterAuth.authenticate`.
  No wire change. Feature test: an enrolled router resolves to its creator's
  household.
- **D — snapshot + blocklist scoping.** Change `PolicyService.snapshot` /
  `renderBlocklist` / the decision-lookup path to take the household from the
  router token and read only that household's rows. Wire shape unchanged
  (invariant 3). Feature test: pins 3 + 5 from §7.
- **E — per-repo household predicates + scoped ingest writes + isolation pins.**
  Add the `household_id` predicate to every global read enumerated in §2 gap 4
  (via `…ByHousehold(hh)` methods or a shared `SqlFragments` predicate), keep
  the gap-3 visibility helpers narrowing *within* the household, and make every
  MAC-keyed ingest write constructively scoped to `(router.householdId, mac)` —
  including `upsertUnknown`, which keeps auto-creating first-seen MACs as
  unmanaged devices *in the router's household* (new-device discovery unchanged;
  §3.2.2, never lookup-and-reject). Land the full `MultiTenantIsolationSpec`
  (§7, including the pin-4b discovery test) as the merge gate. Prove each hot
  query's plan (§5.3).
- **F — edge/config custom-domain pointer.** Documentation-only for v1: record
  in [`custom-domain-edge-config.md`](custom-domain-edge-config.md)
  that CORS `allowedOrigins` / `WIFIHAVEN_UI_ALLOWED_HOSTS` / `WIFIHAVEN_WS_ALLOWED_ORIGINS`
  become per-household only under custom domains, and link the future
  custom-domain epic. No code.

---

## 11. References

- Kickoff finding: [#2085](https://github.com/wifihaven/wifihaven/issues/2085)
- Epic: [#622](https://github.com/wifihaven/wifihaven/issues/622)
- Source audit: [#369](https://github.com/wifihaven/wifihaven/issues/369)
- Wire contract: `docs/process/wire-contract.md`
- Migrations process: `docs/process/migrations.md`
- Testing philosophy: `docs/process/testing.md`
- Query-perf gate: `docs/process/query-perf.md`
- Single-source-of-truth: `docs/process/single-source-of-truth.md`
