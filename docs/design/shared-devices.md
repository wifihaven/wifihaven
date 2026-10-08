# Shared devices: check-in and check-out design

Status: Approved. Product decisions are recorded in §15 (operator, 2026-10-08).

## 1. Goal

Today a computer several children share has to sit on one profile, typically a
profile the children share. One child can spend that profile's whole budget on it
after their own device runs out, and the usage is not attributed to the child who
spent it.

Required behaviour:

1. A **shared** device is **fully blocked by default** (the *checked-out* state).
2. A child logs in to WifiHaven and **checks in** to the shared device **on
   their own profile**.
3. While checked in, **usage on that device counts toward the child's profile**
   and **all of that profile's rules apply** to the device.
4. The child can **check out** in the UI; the device returns to fully blocked.
5. If the child **runs out of time**, every shared device they hold is
   **automatically checked out**.

## 2. Constraints and how this design satisfies them

| Constraint (AGENTS.md) | How it holds here |
|---|---|
| Router is a dumb applier | Check-in state, auto-checkout and "who owns this device now" are resolved in `PolicyService`. The router sees either `profileId = <holder>` or `rules = {blocked, CheckedOut}`, both existing wire shapes (§8). |
| Snapshot is a minimal functional shape | **No new snapshot field.** A checked-out device is `blocked = true` + a `blockReason`; a checked-in device points at its holder's existing `ProfilePolicy`. The only wire-visible change is one new `MacBlockReason` string value (§8.2). |
| Policy is global + profile only (#1452) | "Shared" is a device *attribute*, not a rule set. The rules on a shared device are always the holder's profile rules or the fixed checked-out block. There is no way to author rules for the device (§4.4). |
| Wire is a public contract | One additive enum value, verified opaque on the router (§8.2). |
| Single source of truth | One attribution primitive (`AttributionScope`, §6) replaces every "devices currently on profile P" derivation; minutes are still computed only by `TimeStatusService` / `Presence`. One assignment primitive writes current assignment + history together (§5.3). |
| Multi-tenant | Every new table carries `household_id`; every new read is household-bounded; pushes go out wrapped in `HouseholdScoped`. |
| No dark-by-default | No new required config. The idle timeout is a per-household setting with an explicit default, not an env var. |
| Metrics ship with a dashboard | §12. |
| Loading never looks like data | Check-in state renders a skeleton until loaded; "Checked out" is a real state, never a loading placeholder (§10). |
| Autosave | Check-in / check-out are single actions; the "shared" toggle autosaves. No Save buttons. |
| Schema-only migration PRs first | §14 orders the migrations first. |

## 3. Verified findings (checked against `origin/main` @ 374c3a0)

**F1. Attribution follows the device's *current* profile. CONFIRMED, and it is the whole day.**
Every per-profile usage read selects presence rows by "MACs whose
`devices.profile_id` is P *now*", for the entire day:

- `TimeStatusService.dayStateLive` / `dayStateFromRollupAndTail`:
  `deviceRepo.listForProfile(profileId)` then `listPresenceRows(..., devices.map(_.mac), date)`
  (`api/src/policy/TimeStatusService.scala:229-230,326-328`).
- `dayStateAllLive` / `dayStateAllFromRollupHits`: `devices.groupBy(_.profileId)` then filter
  presence by MAC (`TimeStatusService.scala:281,426`).
- The rollup **recomputes the whole day every tick** from the same grouping
  (`api/src/usage/TimeUsedRollupJob.scala:273-278,302`), so a rolled value does not freeze
  attribution either.
- `AppUsedRollupService` (`api/src/usage/AppUsedRollupService.scala:172`), `AmbientLearnJob`
  (`api/src/usage/AmbientLearnJob.scala:279`), `UsageRoutes` (`:420,:993`), `Routes`
  (`:1203,:1283,:1338`), `DashboardNowRoutes` (`:136,:176`), `SpaPush` (`:587`).
- Connection-event logs and series label and filter by profile through
  `SqlFragments.deviceLabelJoin` -> `LEFT JOIN profiles p ON p.id = d.profile_id`
  (`api/src/db/SqlFragments.scala:84-87`) and `d.profile_id IN (...)`
  (`api/src/db/Repos.scala:3577,3644,3724`). `connection_events` carries no profile column.

So flipping `devices.profile_id` on check-in would move the **whole day's** (and every
past day's) usage on that device to the new holder. This is also an existing, latent bug for
*ordinary* devices: moving a phone from one profile to another today re-attributes all of its
history. §6 fixes both with one mechanism.

**F2. The SPA is reachable from a fully blocked device. CONFIRMED (config + code); hardware check still owed.**
Prod sets `WIFIHAVEN_UI_ALLOWED_HOSTS=api.wifihaven.net,app.wifihaven.net`
(`render.yaml:458`), which `PolicyService` ships in `global.extraAllowed`
(`api/src/policy/PolicyService.scala:385,836`). The router renders it as `@global_allow`, a
carve-out on every per-MAC drop including whole-MAC `blocked` (`render.lua:1352-1356`,
`ga_suffix`). The block page itself redirects to the SPA's `/blocked?mac=&host=&bpt=`
(`openwrt/files/usr/lib/lua/wifihaven/block_page.lua:174-186`). **Still to verify on hardware:**
a blocked macOS browser can load `app.wifihaven.net`, log in, and open the ws. That is a test
in the first UX slice, on the test router, not prod.

**F3. The router tolerates a new block reason. CONFIRMED.**
`render.lua` stores `r.blockReason or "blocked"` as an opaque string (`render.lua:1295,1729`)
and writes it into the nft rule comment `wh_drop:<mac>:<reason>`. `nft_drops.classify_reason`
folds any unrecognised reason into the bounded `whole_mac` bucket
(`openwrt/files/usr/lib/lua/wifihaven/nft_drops.lua:51-66`); the `WHOLE_MAC_REASONS` table at
`:40-47` is documentation only. `conntrack.lua:1225-1226` echoes the string back on
`POST /api/router/events`. **No agent change is needed.** API-side, `MacBlockReason.parse` and
the `BlockReason.fromWire` map (`shared/src/Models.scala:2223-2230,2337-2343`) must learn the
new value in the same API deploy that first emits it.

**F4. Users link to profiles as a many-to-many set. CONFIRMED.**
`UserProfileRepo` (`api/src/db/Repos.scala:406-428`): a user may be linked to several profiles,
and a profile to several users. A child token already sees only its linked profiles
(`SpaWsRegistry.scala:57`, `useDataScope`). Which profile a check-in uses is Q4.

**F5. Precedents.** The unmanaged-MAC path (`PolicyService.scala:789-806`) already emits
explicit per-MAC `rules = {blocked, Unmanaged}` for a profileless device. The checked-out state
reuses exactly that wire shape. `CrossDeviceOverlapMode` is `Sum | Dedup` (default `Sum`,
`shared/src/Models.scala:160`).

**F6. Latency.** Mutations call `PolicyService.invalidate(household)`, which rebuilds and pushes
over ws without waiting for the ticker; the ticker default is 5 s
(`snapshotCacheRefreshSeconds = 5`, `api/src/Config.scala:177`; per-env override not verified).
Router apply latency is measured by `ws_push_apply_latency_seconds`. #2785 and #2796 (tick-loop
stalls) are both closed; #2787 (re-resolve sweep) is still open and is a sweep-cadence issue, not
an apply-path one.

## 4. Model

### 4.1 Vocabulary

- **Shared device**: a device with `shared = true`. It is never permanently assigned to a
  profile.
- **Check-in**: a time interval during which a shared device is held by one profile. Opened by
  a user, closed by check-out or auto-checkout.
- **Holder**: the profile of the open check-in, if any.
- **Checked out**: a shared device with no open check-in.

### 4.2 Effective state of a device at instant *t*

| Device | Open check-in at *t*? | Effective rules | `blockReason` |
|---|---|---|---|
| not shared, `profile_id = P` | n/a | P's `BlockRules` | P's (Paused / Schedule / TimeLimit / DefaultDeny / none) |
| not shared, no profile | n/a | unmanaged policy | `Unmanaged` or none |
| shared | yes, holder P | P's `BlockRules`, unchanged | P's |
| shared | no | `blocked = true`, empty `extraAllowed` | `CheckedOut` |

A checked-in shared device is **indistinguishable from any other device on P**: same
schedules, daily limit, app modes, blocklists, `blockIpOnly`, pause and `failureMode`. That is
how requirement 3 holds without a parallel rules path.

A checked-out device's only reachable hosts are `global.extraAllowed` (the WifiHaven UI and
block page, plus the #1307 infra allowlist), so the child can reach the SPA to check in (F2).

### 4.3 Why not a "Shared" profile with a special flag

Putting a shared device on a pseudo-profile would either make the router reason about it (wrong)
or require the profile's rules to change per holder, which is per-device policy by another name.
A device attribute plus an assignment interval keeps rules strictly profile-sourced.

### 4.4 #1452 guardrail

`shared` gates **who may hold the device and when**, never **what the device may reach**. No
column, route or SPA control on a device may contribute a host, category, schedule, limit or mode.
A review checklist line for every PR in this epic: *"Does any new device-level field influence a
`BlockRules` field other than through the holder profile or the fixed checked-out block?"* If yes,
it is a #1452 violation.

## 5. Data model

### 5.1 One assignment-history table for all devices (Q7)

```sql
-- V90 (schema-only PR)
CREATE TABLE device_profile_assignments (
  id             BIGSERIAL PRIMARY KEY,
  household_id   BIGINT      NOT NULL REFERENCES households(id),
  device_id      BIGINT      NOT NULL REFERENCES devices(id)  ON DELETE CASCADE,
  profile_id     BIGINT      NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  started_at     TIMESTAMPTZ NOT NULL,          -- '-infinity' for backfilled rows
  ended_at       TIMESTAMPTZ NULL,              -- NULL = open
  kind           TEXT        NOT NULL CHECK (kind IN ('assigned','check_in')),
  started_by     BIGINT      NULL REFERENCES users(id) ON DELETE SET NULL,
  ended_by       BIGINT      NULL REFERENCES users(id) ON DELETE SET NULL,
  end_cause      TEXT        NULL CHECK (end_cause IN
                   ('reassigned','unassigned','check_out','forced','time_limit',
                    'schedule','paused','idle','day_reset','unshared')),
  CHECK (ended_at IS NULL OR ended_at > started_at)
);
-- One open assignment per device = one holder per shared device, by construction.
CREATE UNIQUE INDEX uq_dpa_device_open ON device_profile_assignments(device_id)
  WHERE ended_at IS NULL;
CREATE INDEX idx_dpa_household_profile ON device_profile_assignments(household_id, profile_id, started_at);
CREATE INDEX idx_dpa_device_started    ON device_profile_assignments(device_id, started_at);

ALTER TABLE devices ADD COLUMN shared BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE household_settings
  ADD COLUMN shared_device_idle_minutes INT NOT NULL DEFAULT 15
  CHECK (shared_device_idle_minutes BETWEEN 1 AND 1440);

-- Backfill: every currently-assigned device gets one open interval from -infinity, which
-- reproduces today's attribution EXACTLY (current profile owns all history). Zero behaviour
-- change at migration time.
INSERT INTO device_profile_assignments(household_id, device_id, profile_id, started_at, kind)
SELECT household_id, id, profile_id, '-infinity', 'assigned'
FROM devices WHERE profile_id IS NOT NULL;
```

`devices` is a small table (one row per device per household); the backfill is metadata-scale,
not a growth-table rewrite (#migrations-prod-data-volume does not apply). Row count to be
confirmed against prod before merge.

**Why one table for all devices, not a shared-only `device_checkins` table:** with a shared-only
table, every attribution read needs two branches ("shared -> intervals, otherwise -> current
profile"). That is the fork the single-source-of-truth rule forbids, and the non-shared branch
keeps the latent re-attribution bug. With one table there is **one** attribution rule for every
device: *a presence row belongs to the profile whose assignment interval contains it.* A check-in
is just an assignment of `kind = 'check_in'`. The trade-off is a visible behaviour change:
reassigning an ordinary device stops moving its past usage (Q7).

### 5.2 `devices.profile_id` stays the "current" read

Keeping `devices.profile_id` as the current-assignment cache means every **now**-shaped read (the
snapshot, `decide`, the device list, the Devices page) keeps working unchanged. For a shared device
it is the holder while checked in and `NULL` while checked out.

### 5.3 One write primitive

`DeviceAssignment.assign(household, deviceId, newProfile: Option[ProfileId], at, kind, by, cause)`
is the **only** code that writes `devices.profile_id`. In one transaction it closes the open
interval (`ended_at = at`, `end_cause`), opens the new one, and updates `devices.profile_id`.
The existing writers (`Repos.scala:2274,2320` upserts) are routed through it; a CI guard rejects
a new `UPDATE devices SET ... profile_id` / `INSERT INTO devices(... profile_id ...)` outside it, and a
TEST-PIN asserts `devices.profile_id` equals the open interval's profile for every device after
each feature test. Profile deletion cascades both (`ON DELETE SET NULL` on `devices`,
`ON DELETE CASCADE` on the history), so the invariant survives the one writer we do not control.

Deploy gap: between the schema PR and the code PR, old code can reassign a device without writing
history. The code PR's boot step reconciles any device whose open interval disagrees with
`profile_id` (close at boot, open at boot), logged and metered. It is a one-shot, filed for deletion
under #1608.

## 6. Interval-aware attribution

Usage is attributed at **read time**, by joining assignment intervals on each row's `period_start`.
§16 explains why a running per-profile total and ingest-time profile stamping were rejected.

### 6.1 One primitive: `AttributionScope`

```scala
final case class AttributionSpan(mac: MacAddress, deviceId: DeviceId, from: Instant, until: Option[Instant])
final case class AttributionScope(household: HouseholdId, byProfile: Map[ProfileId, List[AttributionSpan]])
```

Built by one repo read over `[dayStart, dayEnd)` (or any window), clipped to the window. Then:

- **Presence reads take spans, not MACs.** `TrafficReportRepo.listPresenceRows` /
  `listPresenceRowsSince` gain a span-filtered form (`period_start >= from AND period_start < until`
  per mac). The bare-MAC-list forms are removed from attribution call sites so a caller *cannot*
  fetch per-profile presence without going through a scope (TYPE-ENFORCE, the same move as
  `MacScope` in #2708).
- **The device list a profile folds over** (Sum mode, per-device summaries,
  `usedSecondsByMac`) is the set of devices with any span in the window, so a shared device appears
  under every profile that held it that day, credited only its in-interval presence.
- `MacScope.Only` grows span bounds, so the traffic/usage-series routes inherit the same rule.

Membership is decided by `period_start` (report periods are the agent's `usage_report_interval`,
default 60 s), so a check-in boundary is attributed to within one report period. Accepted and
stated, not hidden.

### 6.2 Call-site inventory (each becomes a scope consumer)

| Surface | File | Window |
|---|---|---|
| Profile day state (live / rollup+tail / batched) | `TimeStatusService.scala:229,281,326,426` | day |
| Daily rollup writer | `TimeUsedRollupJob.scala:273-302` | day |
| Per-app rollup | `AppUsedRollupService.scala:172` | day |
| Ambient learn | `AmbientLearnJob.scala:279` | day |
| Usage routes (usage-by-app, weekly, series) | `UsageRoutes.scala:420,993` | request window |
| Time status / rollup routes | `Routes.scala:1203,1283,1338` | day / range |
| Dashboard "now" | `DashboardNowRoutes.scala:136,176` | now (current read; already right via §5.2) |
| SPA ws time-status push | `SpaPush.scala:587` | day |
| Logs + series profile label/filter | `SqlFragments.deviceLabelJoin`, `Repos.scala:3547,3577,3644,3724` | per event `ts` |

The inventory is a starting list, not proof of completeness (grep cannot prove a sweep). The
structural guard is §6.1's type change: once presence reads require spans, any missed caller fails
to compile.

### 6.3 SQL side (connection-event logs and series)

`deviceLabelJoin` becomes interval-aware:

```sql
LEFT JOIN devices d ON d.mac = ce.mac AND d.household_id = r.household_id
LEFT JOIN device_profile_assignments dpa
       ON dpa.device_id = d.id AND ce.ts >= dpa.started_at
      AND (dpa.ended_at IS NULL OR ce.ts < dpa.ended_at)
LEFT JOIN profiles p ON p.id = dpa.profile_id
```

and the profile filter becomes `dpa.profile_id IN (...)`. `connection_events` is a growth table, so
this PR carries `EXPLAIN (ANALYZE, BUFFERS)` against prod-shaped data (#query-explain-before-merge).
The join target is tiny and indexed on `(device_id, started_at)`.

The hourly / daily `connection_events_*` rollups are keyed by `(mac, bucket)`. A bucket that
straddles a check-in boundary is attributed by **bucket start**: off by at most one bucket on the
series chart, never in the daily-limit math (that uses presence, §6.1).

### 6.4 What does NOT change

`traffic_reports`, `connection_events` and `block_events` gain no column; no growth table is
rewritten. `time_used_daily` / `app_used_daily` stay keyed by `(profile_id, date)`; they are now
fed scoped presence.

## 7. PolicyService

### 7.1 Snapshot assembly

`buildEnforcingSnapshot` already maps each device to a `DevicePolicy`
(`PolicyService.scala:803-806`). It becomes:

```scala
val rules =
  if (d.shared && d.profileId.isEmpty) Some(checkedOutRules)    // blocked, CheckedOut
  else if (d.profileId.isEmpty) unmanagedRules
  else None                                                     // holder's ProfilePolicy
```

`checkedOutRules` = `BlockRules(blocked = true, blockReason = Some(CheckedOut), extraAllowed = Nil, ...)`,
independent of the household's unmanaged policy, so a shared device is blocked when checked out
even in an `allow` household. `decideDetailed` (the block page's `GET /api/blocked`) reads the same
device row and returns `CheckedOut`. Both paths share one `effectiveDeviceRules` function so they
cannot drift (#1544).

### 7.2 Precedence for a shared device

| # | State | Router outcome | `blockReason` |
|---|---|---|---|
| 1 | Checked out | Blocked except `global.extraAllowed` | `CheckedOut` |
| 2 | Checked in, holder unrestricted | Holder's rules | none |
| 3 | Checked in, holder paused | Blocked **immediately** via the holder's rules; auto-checkout then releases it | `Paused`, then `CheckedOut` |
| 4 | Checked in, holder enters schedule block | Same | `Schedule`, then `CheckedOut` |
| 5 | Checked in, holder out of daily time | Same | `TimeLimit`, then `CheckedOut` |
| 6 | Checked in, no engaged presence for the idle threshold | Holder's rules until released | none, then `CheckedOut` |
| 7 | Checked in at the household's daily reset | Released at the reset instant | `CheckedOut` |
| 8 | Checked in, holder is default-deny | Holder's allow-list only (not a trigger) | `DefaultDeny` |

Per §15 Q3, rows 3–7 all auto-check-out. A carve-out the holder has (an `allowed_during` app,
an exempt app under its cap) is reachable during the brief held-but-blocked window and gone once
the device is released; that is intended, since the released device belongs to nobody.

### 7.3 Auto-checkout

**Enforcement never depends on auto-checkout.** The instant the holder's profile is TimeLimit-blocked,
the shared device is blocked too, because it resolves to the holder's rules. Auto-checkout only
*releases* the device so another child can check in.

Mechanism: `SharedDeviceCheckoutJob`, run on the existing per-household reevaluate tick. It reads the
day states the snapshot build already computes (`timeStatusService.dayStateAll`), and for each open
`check_in` whose holder matches an enabled trigger (Q3), it calls the §5.3 primitive with the
matching `end_cause` and then `invalidate(household)`. Writes stay out of the snapshot build itself.

- `time_limit` / `schedule` / `paused`: the holder's `ProfileDayState.blockReason`. Precedence when
  several hold at once follows the existing `Paused > Schedule > TimeLimit` order.
- `idle`: the latest engaged presence row inside the open interval (or the interval start if none) is
  older than `shared_device_idle_minutes`.
- `day_reset`: the interval started before the household's most recent daily reset
  (`PolicyService.nextDailyResetAfter` / `householdLocalDate`, the same reset everything else uses).
  The `ended_at` is stamped at the reset instant, not the tick instant, so no post-reset presence is
  attributed to yesterday's holder.

A parent granting a time extension after auto-checkout does **not** re-check the child in; the child
checks in again.

### 7.4 Overlap with the child's own device

A child on their own phone and a shared device at once is one profile with two devices, folded by the
profile's existing `crossDeviceOverlapMode` (`Sum` counts both; `Dedup` counts wall-clock once).
No new mode (Q6).

## 8. Wire and router

### 8.1 Snapshot

No new fields. Checked in -> `DevicePolicy(profileId = Some(holder), rules = None)`; checked out ->
`DevicePolicy(profileId = None, rules = Some(checkedOutRules))`, the same shape the unmanaged path
already ships and the agent contract fixture already covers.

### 8.2 New `MacBlockReason.CheckedOut`

Additive enum value (`"CheckedOut"`). The router treats it as opaque (F3). API-side `parse`,
`asString`, the `fromWire` map, the SPA's reason union and block-page copy all learn it in the same
change. The SPA ships separately on Cloudflare Pages, so the SPA must render an unknown reason
generically before the API starts emitting it (ordering in §14). A router busted spec pins that an
unrecognised reason renders and classifies as `whole_mac`; it runs on the real Lua 5.1 target.

### 8.3 Failure mode

A checked-in device uses the holder's `failureMode`. A checked-out device has no profile; under API
loss the router keeps its last snapshot, so it stays blocked. Auto-checkout cannot run while the API
is unreachable; the device stays bound to the holder's last rules until the link returns.

## 9. API

| Route | Who | Effect |
|---|---|---|
| `GET /api/shared-devices` | any authed user in household | Shared devices, current holder (profile + user display name), since-when. A child sees all shared devices but only check-in actions for profiles they are linked to. |
| `POST /api/shared-devices/{deviceId}/check-in` `{profileId}` | child: linked profiles; adult/admin: any household profile (Q5a) | Opens a `check_in` interval. 409 `held` (Q2). 403 `not_linked`. 409 `profile_blocked` if the profile is currently `Paused` / `Schedule` / `TimeLimit` (Q3). |
| `POST /api/shared-devices/{deviceId}/check-out` | any user linked to the holder profile, or adult/admin | Closes with `check_out` (holder side) or `forced` (adult acting on someone else's check-in). |
| `PATCH /api/devices/{id}` `{shared}` | writer (adult) | Toggles shared. Turning it on closes any `assigned` interval (`end_cause = unshared`) and leaves the device checked out. |

Every route is household-scoped from `claims.hh`; a device id from another household is a 404. Each
mutation calls `invalidate(household)` and emits an SPA ws event so every open dashboard updates.

Device identity for "check in on *this* device" is Q1. The block-page path already carries a
router-issued `mac` and `bpt`; the SPA can pass them through, and the API checks `bpt`'s household
and that the MAC is a shared device in it.

## 10. UX

- **Child, on the shared device:** opening the browser lands on the block page if the request is
  HTTP, but most sites are HTTPS and will show a certificate warning (architecture.md §7.6: 443 is
  DNATed to a self-signed cert). The reliable path is the child going to `app.wifihaven.net`
  directly, which is reachable (F2). The page shows the shared device as checked out with a
  **Check in** action for the child's profile.
- **Child, anywhere:** a "Shared devices" card on the child dashboard shows each shared device, who
  holds it, and Check in / Check out.
- **Block page:** for `CheckedOut`, copy says the device is shared and offers Check in (log in first
  if needed).
- **Parents:** Devices page shows a "Shared" badge plus the current holder; the dashboard shows who
  holds each shared device; adults can force a check-out. Profile usage includes shared-device time,
  and per-device summaries list the shared device under each child who held it that day.
- **States:** loading shows a skeleton; "Checked out" is a loaded state with its own styling; errors
  show an error affordance.

## 11. Latency budget

Check-in or check-out: DB write -> `invalidate` -> rebuild -> ws push -> router apply. The apply time is
`ws_push_apply_latency_seconds`. Target: device unblocked within **5 s p95** of the click. The UX
slice measures this on the test router before calling the slice done; if it misses, the fix belongs
in the agent apply path, not in a shared-device special case.

Auto-checkout on time-out: enforcement is immediate through the holder's rules. The release lands
within one usage-report interval plus one reevaluate tick.

## 12. Observability

- `shared_device_checkin_total{action=check_in|check_out, cause}` with `cause` drawn from the
  `end_cause` enum plus `user`; bounded, no mac/profile/device label.
- `shared_device_checkins_open` gauge (household count, not per device).
- `shared_device_checkin_rejected_total{reason=held|not_linked|profile_blocked|not_shared|bad_bpt}`.
- `device_assignment_reconciled_total` for the §5.3 boot reconciler.
- Grafana panels for all of the above under `deploy/grafana/dashboards/` in the same PRs.

## 13. Making an existing device shared

There is no per-household or per-device migration. The V90 backfill is uniform: every device that
has a profile gets one open `assigned` interval from `-infinity`, which reproduces today's
attribution exactly.

Making a device shared is an ordinary operation, the same for a brand-new device and for one that
has been on a profile for months:

1. An adult turns on **Shared** for the device (`PATCH /api/devices/{id}` `{shared: true}`).
2. The assignment primitive closes the device's open `assigned` interval at that instant
   (`end_cause = unshared`) and clears `devices.profile_id`. The device is now checked out.
3. Usage before that instant stays with the profile the device was on; usage after it is attributed
   only to whoever has it checked in.

Turning **Shared** off closes any open check-in (`end_cause = unshared`) and leaves the device
unassigned; the adult assigns it to a profile as usual. Neither direction creates, edits or deletes
a profile (§15 Q8).

## 14. Rollout order (foundation first)

1. **Schema PR:** V90 `device_profile_assignments` + `devices.shared` + backfill. Migration only.
2. **Assignment primitive:** single writer, boot reconciler, CI guard, invariant TEST-PIN.
3. **AttributionScope:** span-scoped presence reads; migrate every §6.2 call site; type guard.
4. **Logs/series:** interval-aware `deviceLabelJoin`, with EXPLAIN.
5. **SPA tolerance:** render an unknown `MacBlockReason` generically (ships before 6).
6. **PolicyService:** `CheckedOut` reason, shared-device resolution, `decide` parity, router busted spec.
7. **Check-in API** + metrics + dashboard.
8. **Auto-checkout job** + metrics + dashboard.
9. **SPA:** shared toggle, holder display, child check-in card, block-page Check in, force check-out.
10. **Hardware validation** on the test router: reachability (F2), latency (§11), end-to-end check-in,
    auto-checkout.

Steps 1–4 change nothing user-visible for households with no shared devices except the Q7 behaviour
change (reassigning a device no longer moves its history).

## 15. Decisions (operator, 2026-10-08)

| # | Question | Decision | Consequence in this design |
|---|---|---|---|
| Q1 | Where can a child check in? | From any device, by picking the shared device from a list. No proof of physical presence. | `deviceId` in the route is enough; `mac`/`bpt` from the block page only pre-selects the device. Trust model: a remote check-in only spends the child's own time. |
| Q2 | Device already held? | Refused (409) until the holder checks out or is auto-checked-out; an adult can force a check-out. | Enforced by `uq_dpa_device_open`; the route maps the unique violation to 409 `held`. |
| Q3 | Auto-checkout triggers | Daily time exhausted, schedule/bedtime block, profile paused, idle timeout, daily reset (all five). | `end_cause` ∈ `time_limit`, `schedule`, `paused`, `idle`, `day_reset`. Check-in is also refused while the profile is blocked for `Paused`, `Schedule` or `TimeLimit` (`DefaultDeny` is a baseline, not a block-for-now, so it may check in). §7.2 rows 3–5 all end in `CheckedOut`. |
| Q3a | Idle threshold | 15 min default, per household. | New `household_settings.shared_device_idle_minutes INT NOT NULL DEFAULT 15` in the schema PR, editable on Settings (autosave). "Idle" means no engaged presence on the device (the same heartbeat/ambient-filtered definition that drives screen time), so background OS traffic does not keep a check-in alive and there is no second definition of activity. |
| Q4 | Child linked to several profiles | Automatic if linked to one; picker if several. | Check-in takes `profileId`; the SPA omits the picker when there is one choice. |
| Q5 | Adults | Adults can force check-out, check a child in remotely, and hold the device on an adult profile. | Adult/admin may check in on any household profile (see Q5a). |
| Q5a | Adult with no linked profile | May choose any household profile. | Authz: child -> linked profiles only; adult/admin -> any non-global profile in the household. |
| Q6 | Own device + shared device at once | Use the profile's existing `crossDeviceOverlapMode`. | No new knob (§7.4). |
| Q7 | History for all devices or shared only | All devices. | §5.1 as written. Release note: moving a device to another profile no longer moves its past usage. |
| Q8 | The profile a device was on before it became shared | Untouched. | Making a device shared never creates, edits or deletes a profile (§13); an adult tidies up profiles by hand. |
| Q9 | Who may check in | Any profile the user is linked to (plus Q5a for adults). | No per-device eligibility list; there is nothing device-level to author (§4.4). |
| — | Child accounts | Every child already has a login linked to their own profile. | No account-setup prerequisite in the rollout. |

Out of scope for v1 (file separately if wanted): parent notifications on check-in / auto-checkout,
a PIN-style child login, scheduled or time-boxed check-ins.

## 16. Alternatives considered

### A. Keep a running per-profile usage total, credited to whichever profile the device is on when usage arrives

Rejected, because screen time is not additive per report. `TimeStatusService` / `Presence` rebuild a
day's minutes from the raw presence rows, and each report's contribution depends on its neighbours:

- **Session stitching** bridges the gaps between reports (`presenceContinuationSeconds`), so a single
  report has no fixed number of seconds on its own.
- **The ambient gate** keeps or drops a span depending on whether an anchor row exists, and a *later*
  row can change that. This is why the rollup re-gates the whole day on every tick
  (`api/src/usage/TimeUsedRollupJob.scala:273-278`) instead of incrementing.
- **`Dedup` overlap mode** takes the union across a profile's devices, and per-app limits take the
  union across an app's host-set.

A counter bumped at arrival would therefore disagree with what enforcement computes. That is the
display-vs-enforcement divergence the single-source-of-truth rule exists to prevent (#1531).

### B. Stamp `profile_id` onto each usage / event row at ingest

This is the workable form of A: keep recomputing from rows, but filter by a stamped column instead
of joining assignment history. Rejected for three reasons.

1. **Reports arrive late.** The agent queues failed usage posts and retries with backoff capped at
   15 minutes, indefinitely while the API is unreachable
   (`openwrt/files/usr/lib/lua/wifihaven/usage.lua:451-527`). Stamping "the profile at arrival"
   misattributes every late report that crosses a check-in or check-out: a child checks out at
   15:00, the router was offline, and the 14:40–15:00 reports land at 15:10 under the next holder.
   Stamping "the profile at `period_start`" fixes that, but only by looking it up in assignment
   history, which is this design's table anyway.
2. **It changes the growth tables.** `traffic_reports` (and `connection_events`, for the logs
   profile filter) would need a new column and a per-profile index on partitioned high-volume
   tables. Existing rows would then need either a backfill (a minutes-long migration on prod, the
   #1197 class; see #migrations-prod-data-volume) or a permanent "no stamp -> current profile"
   fallback, which is a second attribution path.
3. **Stamps cannot be corrected.** If an adult fixes a wrong assignment or a mistaken check-in,
   interval rows can be amended and every read follows. Stamped usage rows cannot sensibly be
   rewritten.

The assignment-history table is required regardless of how usage is attributed: it carries the
one-holder rule (`uq_dpa_device_open`), who checked in and when, the idle check inside a check-in,
the daily-reset cutoff, and the "who held this device" display. So the real choice was only whether
usage reads *join* the history or *copy* it onto every row. Joining leaves the growth tables
untouched and attributes late reports correctly; its cost is the read-side refactor in §6.

### C. A shared-devices-only check-in table, with ordinary devices still attributed by current profile

Rejected in §5.1 (and §15 Q7): it forks every attribution read into two branches and leaves the
existing bug where reassigning a device moves its past usage.
