# Design for weekly limits and app groups

Tracking: [#2930](https://github.com/wifihaven/wifihaven/issues/2930).
Product decisions: confirmed by the operator on 2026-10-23 and 2026-10-24, recorded in §15.

## 1. Goal

1. **Weekly limits for apps.** Today an app assignment can carry a daily time
   limit (`app_policy_assignments.daily_minutes`). Add a weekly one.
2. **App groups.** Several apps share one time budget. Their combined usage is
   tracked and capped together ("Games: Roblox + Minecraft + Fortnite, 5 h a
   week"). A group is defined once per household; each profile that uses it
   sets its own limits.
3. **Same choice about the daily total as today.** A group's usage, like a
   per-app limit's, either **counts** or **does not count** toward the
   profile's daily total. This is the existing `exemptFromDaily` flag
   (AGENTS.md#exempt-from-daily). The SPA shows it inverted, as "Counts toward
   daily limit".

## 2. Constraints and how this design meets them

| Constraint | How it holds here |
|---|---|
| DNS is never the enforcement plane | An exhausted weekly or group budget puts the member apps' distinctive hosts in `extraBlocked`, which the router enforces as an nftables drop on resolved IPs. Nothing touches DNS. |
| Router is a dumb applier | All time accounting and every budget decision happen in `TimeStatusService` / `PolicyService`. The router never learns that weeks or groups exist. |
| Snapshot is a minimal functional shape | **No new snapshot field and no new `MacBlockReason`.** Exhaustion collapses into `extraBlocked`, and the under-budget carve-outs into `extraAllowed`, exactly as per-app daily caps do today (§7). |
| #1899: only distinctive hosts are block-eligible | Block side reads each member app's `distinctiveHosts`; allow side keeps the full host-set (§7.2). A shared backend is never dropped because one app or one group ran out. |
| Single source of truth | One budget primitive computes used minutes for a (profile, budget, window). Enforcement, the time-status API, the per-app bars and the weekly display all read it (§6). The weekly display stops having its own aggregation (§6.5). |
| Interval-aware attribution (#2844) | Every presence read goes through `AttributionScope`, one household-local day at a time, so reassignment and shared-device check-in history apply unchanged (§6.3). |
| Policy is global + profile only (#1452) | Limits are **profile** policy: a weekly limit sits on a profile's app assignment, and a group's limits sit on the profile's attachment of it. The household-level group is only a name and a member list, with no limits and no rules, so it adds no policy tier. No device-level authoring. |
| Catalog is repo-authored (#1798) | A group is a **policy** object (a budget over assigned apps), not a catalog object. It never edits app definitions or host lists. |
| Multi-tenant | New tables carry `household_id`; every new read is household-bounded. |
| Schema-only migration PRs first | §13 slice 1 is the migration alone. |
| Metrics ship with a dashboard | §11. |
| Loading never looks like data | Week-to-date figures render a skeleton while pending, never `0m` (§10). |
| No dark-by-default | No new required config. Week start is a household setting with an explicit, logged default (§5.1). |

## 3. Verified findings

Checked against `origin/main` @ `4747e41b`.

1. **Per-app cap groups exist and are the thing to generalize.**
   `ProfileAppDispositions.capGroups` (`api/src/policy/ProfileAppDispositions.scala:54-55`)
   is every `mode = TimeLimited` assignment. Each one becomes an
   `app:<slug>`-labelled host-set (`capGroupLabelDistinctiveHosts`, `:76-77`)
   that `Presence.appSpansForProfile` (`api/src/presence/Presence.scala:750`)
   stitches as **one stream** per device. That primitive already takes a list of
   `(label, hosts)` groups. A group of apps is the same structure with a host-set
   that is the union of several apps' sets.
2. **Every allow-carve would defeat a group or weekly block.**
   The router lets `extraAllowed` beat `extraBlocked` (#421). Today four
   producers put an app's hosts in `extraAllowed`, and none of them knows about
   any budget beyond the app's own daily cap:
   - `exemptUnderCapHosts` (`PolicyService.scala:1669`): exempt and under its
     own daily cap. It beats every whole-MAC block except a hard pause, so an
     exempt app stays reachable through Paused (soft), Schedule and TimeLimit
     (`:1544-1565`; #1418 empties it only under a hard pause).
   - `timeLimitedUnderCapHosts` (`:1701`, #1980): TimeLimited, under its own
     daily cap, profile not blocked.
   - the `AllowedDuring` branch of `ProfileAppDispositions.enforcement`
     (`ProfileAppDispositions.scala:147-149`). Its gate reads the **profile's**
     daily cap (`capExhausted = dailyCapExhausted(state)`,
     `PolicyService.scala:784-786`), not the app's own cap. So today an active
     window keeps an app reachable past its own daily cap.
   - the plain `AppMode.Allowed` branch of the same fold (`:154-155`), which
     carves unconditionally.

   If a group budget is exhausted but the member's own daily cap is not, the
   member's hosts would land in **both** lanes, allow would win, and the group
   limit would do nothing. **So "exhausted" has to become one predicate over
   every budget an app belongs to** (§7.1). This is the main correctness risk
   in the feature.
3. **The weekly display must not be the base for enforcement.**
   `buildProfileTimeStatusWeek` (`api/src/routes/Routes.scala:1693-1765`) is a
   route-local helper. Its own comment says it applies "no exempt-pattern
   filtering (weekly view is informational)" (`:1719-1721`). It totals per
   **profile/device**, not per app or budget, and its window is a **trailing**
   7 days (`to.minusDays(6)`), not a calendar week. It is interval-aware
   (`AttributionScope.forRange`, #2844), so reassignment is not the problem.
   The missing counting rules are. The weekly summary route (`:1229-1294`) has
   the same shape.
4. **Past days are not cached for enforcement.** `time_used_daily` and
   `app_used_daily` are refreshed for **today only** (`TimeUsedRollupJob.scala:27-36`)
   every 15 minutes (`:69`). A past date's row holds whatever the last tick of
   that day saw, up to 15 minutes short of the day's end. A past-date read
   goes all-live (`TimeStatusService.scala:229-230`, `AppUsedRollupService.scala:146-149`).
   Any household-settings save deletes **every date's** rows for the household
   (`api/src/db/Repos.scala:2078-2082`). Seven days of all-live presence reads
   on every snapshot build is not affordable: a single day's read scanned up to
   191k rows on prod (#2652). **Weekly needs settled past-day rows** (§6.4).
5. **Week boundaries do not exist.** The household has `dailyResetTime` and
   `dailyResetTz` (`shared/src/Models.scala:1050-1052`). There is no week-start
   day.
6. **Attribution of a straddling row.** `AttributionScope.profileAt`
   (`api/src/db/AttributionScope.scala:84-93`) credits a bucket that straddles a
   reassignment to whoever held the device at the bucket's **start**. Presence
   rows use the same rule through `AttributionSpan.covers(periodStart)` (`:28-29`).
   Weekly inherits it by reading each day through the same scope.
7. **Extensions are profile-level and daily.** `TimeExtension` is keyed by
   `(profileId | deviceMac, date)` (`shared/src/Models.scala:510-519`). Per-app
   caps take no extensions (`PolicyService.scala:1647-1648`).
8. **The per-app primitive's group list is also an attribution set.**
   `appSpansForProfileWithDropCount` builds its heartbeat-suppression exception
   set from the union of **all** groups passed in (`Presence.scala:784-785`).
   Adding hosts to that list can change other apps' minutes (see #2744). A
   group entry must add nothing beyond its members' hosts (§6.2).
9. **#2818 (open) does not block this.** It concerns `appHostPatterns` using
   `hosts` instead of `distinctiveHosts`, which affects the **profile daily
   total**. Budget minutes are stitched from `distinctiveHosts` only (finding 1),
   so groups are unaffected. The exempt-from-daily path keeps today's behaviour
   (full host-set excluded from the total), which is the same for a group as
   for a single app. #2818 can land before or after.
10. **Block-page reason is already parameterised.** `BlockReason.AppTimeLimit(label)`
    (`shared/src/Models.scala:2327`) carries a free-form label. A group can use
    `group:<id>` as its label. No new reason case is needed (§8).

## 4. Vocabulary

- **Budget.** A set of apps plus optional daily and weekly limits. Used minutes
  are counted once across the whole set. There are two kinds:
  - an **app budget**: one TimeLimited assignment and its own `daily_minutes` /
    `weekly_minutes` (the existing per-app cap, extended with a weekly limit);
  - a **group budget**: a profile's attachment of a household group
    (`profile_app_groups`), covering that profile's assignments of the group's
    member apps.
- **Covered app.** An app that is a member of a group attached to the profile.
  The attachment is that app's policy on the profile: it is not listed as its
  own app row (D6).
- **Overlap.** A covered app that also has its own assignment on the same
  profile. Allowed, but the SPA warns and offers to remove the assignment
  (D14, D15).
- **Window.** `Day` (the household-local date, from `dailyResetTime`) or `Week`
  (seven consecutive household-local dates starting on the household's week-start
  day).
- **Exhausted.** A budget is exhausted when, for any window with a limit set,
  used minutes ≥ limit.
- **Blocked app.** An app is blocked by budget when **any** budget containing it
  is exhausted.

## 5. Data model

### 5.1 Week start

`household_settings.week_start_day SMALLINT NOT NULL DEFAULT 1 CHECK (week_start_day BETWEEN 1 AND 7)`,
ISO numbering (1 = Monday … 7 = Sunday), default Monday (D1).

The week starting on household-local date `W` is the dates `W … W+6`. Its
instant window is `AttributionScope.rangeWindow(W, W+6, settings)`: from
`dailyResetTime` on `W` to `dailyResetTime` on `W+7`, in `dailyResetTz`. A week
that crosses a DST change is 167 or 169 hours long. That is correct: it is seven
household days, and each day is already DST-correct.

`PolicyService.householdWeekStart(instant, settings): LocalDate` is the single
definition: the household-local date of `instant`, moved back to the most recent
`week_start_day`. Changing `week_start_day` mid-week takes effect at the next
read. The current week becomes the one containing today under the new start day.

### 5.2 Weekly limit on an app

`app_policy_assignments.weekly_minutes INT NULL CHECK (weekly_minutes IS NULL OR weekly_minutes > 0)`.
It sits alongside `daily_minutes`. `None` means no weekly limit, as #1627 made
`None` mean "no daily limit". Setting either limit implies `mode = time_limited`
(the SPA already promotes on a daily limit, `ProfilesPage.tsx:2198-2229`).
Clearing both demotes to `allowed`. Either limit running out blocks the app
(D11).

### 5.3 Groups (household) and their use on a profile

A group is defined once per household: a name and a member list. Each profile
that uses it **attaches** it with its own limits and its own "counts toward
daily" flag (§15 D3, D5).

```sql
CREATE TABLE app_groups (
  id           BIGSERIAL PRIMARY KEY,
  household_id BIGINT NOT NULL REFERENCES households(id),   -- no cascade from households (V65, V90)
  name         TEXT   NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (household_id, name),
  UNIQUE (id, household_id)              -- target of the composite FKs below
);

CREATE TABLE app_group_members (
  group_id     BIGINT NOT NULL,
  household_id BIGINT NOT NULL,
  app_id       BIGINT NOT NULL REFERENCES apps(id) ON DELETE CASCADE,
  PRIMARY KEY (group_id, app_id),
  FOREIGN KEY (group_id, household_id) REFERENCES app_groups(id, household_id) ON DELETE CASCADE,
  UNIQUE (household_id, app_id)          -- an app is in at most one group per household (D2)
);

CREATE TABLE profile_app_groups (
  id                BIGSERIAL PRIMARY KEY,
  household_id      BIGINT NOT NULL,
  profile_id        BIGINT NOT NULL,
  group_id          BIGINT NOT NULL,
  daily_minutes     INT CHECK (daily_minutes  IS NULL OR daily_minutes  > 0),
  weekly_minutes    INT CHECK (weekly_minutes IS NULL OR weekly_minutes > 0),
  exempt_from_daily BOOLEAN NOT NULL DEFAULT TRUE,   -- D5: same default as per-app
  created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  FOREIGN KEY (profile_id, household_id) REFERENCES profiles(id, household_id) ON DELETE CASCADE,
  FOREIGN KEY (group_id, household_id)   REFERENCES app_groups(id, household_id) ON DELETE CASCADE,
  CHECK (daily_minutes IS NOT NULL OR weekly_minutes IS NOT NULL),   -- D4
  UNIQUE (profile_id, group_id)
);
CREATE INDEX idx_profile_app_groups_group ON profile_app_groups(group_id);
```

The composite FKs make a cross-household member, attachment or usage row
unrepresentable: each row's household must be both its group's and its
profile's. The profile half needs a new `UNIQUE (id, household_id)` on
`profiles`. Nothing updates `profiles.household_id`, so that FK never blocks an
existing write.

**Coverage comes from the attachment, not from assignment rows (D6).** A
covered app needs no `app_policy_assignments` row on the profile. Attaching a
group writes only the `profile_app_groups` row, and detaching deletes only that
row. Adding or removing a member changes only `app_group_members`. Nothing is
auto-assigned and nothing is demoted, so there is no reconcile step that could
drift.

**Overlap (D14, D15).** If the profile also has its own assignment for a covered
app (it was there before the group was attached, the app was added to the group
later, or the parent added it on purpose), both rows stay. Enforcement applies
both, and the stricter one wins (§7.1). The API flags the assignment with
`coveredBy: {groupId, name}`, and the SPA warns with a one-click remove (§10).

### 5.4 Settled daily usage per budget

`app_used_daily` (V53) already holds per-(profile, app, date) engaged seconds.
Group budgets get a sibling table, keyed by profile because each profile counts
its own usage of a household group:

```sql
CREATE TABLE app_group_used_daily (
  household_id    BIGINT      NOT NULL,
  profile_id      BIGINT      NOT NULL,
  group_id        BIGINT      NOT NULL,
  date            DATE        NOT NULL,
  engaged_seconds BIGINT      NOT NULL,
  rolled_through  TIMESTAMPTZ NOT NULL,
  rolled_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (profile_id, group_id, date),
  FOREIGN KEY (profile_id, household_id) REFERENCES profiles(id, household_id)   ON DELETE CASCADE,
  FOREIGN KEY (group_id, household_id)   REFERENCES app_groups(id, household_id) ON DELETE CASCADE
);
CREATE INDEX idx_app_group_used_daily_household_date ON app_group_used_daily(household_id, date DESC);
```

A row is **settled** when `rolled_through >= ` the end instant of its date
(`AttributionScope.dayWindow(date)._2`). Only settled rows are read for past
days (§6.4). Today's row keeps the existing rolled-plus-live-tail semantics.

`app_used_daily` needs no schema change: settling is a write-side rule
(§6.4).

### 5.5 Prod volume

None of these tables is a growth table in the
`docs/process/migrations.md#migrations-prod-data-volume` sense.
`app_group_used_daily` grows with groups × days, the same order as
`app_used_daily`. Both `ALTER TABLE … ADD COLUMN` statements use a constant
default or NULL, which is metadata-only on PG 11+. The migration slice still
records the measured prod row counts of `household_settings` and
`app_policy_assignments` in the PR.

## 6. The budget primitive (TimeStatusService)

### 6.1 One type for both budget kinds

```scala
enum BudgetKey { case App(id: AppId); case Group(id: AppGroupId) }

final case class CapBudget(
    key: BudgetKey,
    label: String,                  // "app:<slug>" | "group:<id>": the Presence group key
    members: List[AppDisposition],  // one for an app budget, N for a group
    countHosts: List[String],       // union of members' distinctiveHosts (stitched)
    dailyLimit: Option[Int],
    weeklyLimit: Option[Int],
    exemptFromDaily: Boolean,       // the attachment's flag, or the app's own
)
```

`ProfileAppDispositions.budgets` is the single fold that produces them. It
replaces `capGroups` as the cap source. Its inputs are the profile's assignment
rows plus its attachments with their members' hosts:

- Every TimeLimited assignment yields an app budget, as today.
- Every attachment yields a group budget.
- A covered app with **no** assignment gets a synthesized `AppDisposition` with
  mode `TimeLimited`, no own limits, and the attachment's exempt flag. It has no
  budget of its own; it exists so the app's hosts are in the per-app list
  (§6.2) and in the allow/block projections.
- A covered app **with** an assignment keeps its row. Its effective mode is
  `Blocked` if the row is Blocked, otherwise `TimeLimited`. An Allowed row on a
  covered app does **not** carve its hosts into `extraAllowed`
  unconditionally, because that would defeat the group (finding 2). Its own
  limits, if any, stay as an app budget.

`AppDisposition` gains `weeklyMinutes` and `groupId: Option[AppGroupId]`.

**Effective exempt flag (D5).** A member's effective `exemptFromDaily` is the
flag on its profile's attachment of the group. `exemptPatterns` and every carve read the effective flag, so a
group cannot be half counted and half exempt.

### 6.2 Counting a budget's minutes

A budget's minutes for one household-local day are
`Presence.appSecondsForProfile(rows, budgets.map(b => b.label -> b.countHosts), …)`,
which is the existing call with more entries in the list:

- A host shared by two members counts once. `countHosts` is a union, and
  each row is matched once per group (`Presence.scala:791-792`). Two members
  used back to back with an idle gap under `N` bridge into one session. That
  is the right meaning for "one shared budget".
- Shared hosts never count. `countHosts` is built from `distinctiveHosts`
  (#1897), so the #2818 leak does not apply.
- A group entry adds no host of its own. The primitive's suppression-exception
  set is the union of every group's hosts (finding 8). Every covered app is
  already in the per-app list (a real or synthesized `TimeLimited`
  disposition, §6.1), so the group entry adds nothing beyond its members.
  Attaching a group whose members are **new** to the profile does grow the set,
  exactly as assigning those apps one by one would, and that can move other
  apps' minutes in the same way (finding 8). Test 7 (§12) pins both halves:
  attaching a group whose members are already assigned leaves every other
  app's minutes unchanged, and attaching one with new members gives the same
  minutes as assigning those members individually.

### 6.3 Attribution

A window's usage is the **sum of its days**. Each day is read exactly as the
daily path reads it today: `AttributionScope.forDay(day)` →
`trafficRepo.listPresenceRows(scope.spansFor(profile), day)` → gate →
stitch. Consequences:

- A device moved from profile A to B mid-week: A's week contains A's spans, and
  B's week contains B's spans. This is the same mechanism and the same days the
  daily figure uses.
- A shared device checked in by A Tuesday 10:00–11:00 and by B Tuesday 12:00–13:00:
  each profile's Tuesday has only its own interval.
- A row straddling a reassignment is credited by its `period_start`
  (`AttributionScope.scala:28-29`, `:84-93`).
- **Weekly minutes = Σ daily seconds, floored once.** A session that crosses the
  daily reset counts in both days, as it does today. The weekly figure is
  `floor(Σ seconds / 60)`, so it can be one minute more than the sum of the
  seven floored daily bars. The SPA shows the API's weekly number and never sums
  bars itself.

### 6.4 Settled past days

The week-to-date read returns seconds per budget for the current week. (As
built in #2932 it is not a separate `BudgetUsageService`: every
`TimeStatusService` day-state read adds the week to the states it returns, so
the snapshot and `/api/time/status` get it from the same call. Settled rows are
read and written through `SettledUsageRepo`, and the invalidation is
`SettledUsage` in `api/src/db/SettledUsage.scala`.)

```
Σ_{d in week, d < today} settledSeconds(budget, d)  +  todaySeconds(budget)
```

- `todaySeconds` is the existing rolled-plus-tail read
  (`AppUsedRollupService.aggregate`), extended to group labels.
- A day `d` is **settleable** once `now >= end(d) + SettleDelay`. Before that,
  `d` (in practice, yesterday during the first part of today) is read the way
  today is: its rolled row plus a live tail past `rolled_through`. Nothing
  marks it settled early, so a report that arrives during the delay is
  counted.
- `settledSeconds(budget, d)` for a settleable day reads `app_used_daily` /
  `app_group_used_daily`. When the row is missing or unsettled, it computes day
  `d` live through the same per-day function and upserts the row with
  `rolled_through = end(d)`. This is write-through, single-flighted per
  (household, date) in the same way as `TimeStatusCache` (#2012), so a cold
  week costs one live read per missing day, once.
- **The rollup job settles.** On each tick it settles every settleable day of
  the current week that is not yet settled. That covers `time_used_daily`,
  `app_used_daily` and `app_group_used_daily`; the first needs no schema change
  because it already carries `rolled_through`. In the steady state the read path
  hits settled rows, and a week costs one indexed range read per household per
  snapshot.
- `SettleDelay` is a named constant, initially two rollup intervals (30 min,
  from `TimeUsedRollupJob.Interval`, `:69`). It does not estimate the true
  report lag, which is unverified (below). The late-row metric (§11) tells us
  whether to raise it.
- **Invalidation.** A settled row is only valid for the dispositions it was
  computed under. Every write that changes a profile's dispositions also changes
  the attribution set (finding 8), and so can change any app's or group's
  minutes on that profile. **Any write that changes a
  profile's dispositions deletes that profile's settled `app_used_daily` /
  `app_group_used_daily` / `time_used_daily` rows for the current week**, in
  the same transaction. Those writes are an assignment create, delete or mode
  change; an attachment create or delete; and a membership edit, which applies
  to every profile the group is attached to. An app-template sync that changes
  an app's hosts counts too, for every profile that uses the app. Settings save
  keeps its existing all-dates DELETE (`Repos.scala:2078-2082`), and
  `app_group_used_daily` joins it. An exempt-flag edit (on an assignment or an
  attachment) changes which traffic the profile daily total counts, so it
  deletes that profile's current-week settled `time_used_daily` rows
  (`TimeUsedRollupJob.scala:333` rolls through the exempt-filtered
  `usedSecondsForProfile`). It changes no app or group minutes. Limit edits
  change no usage at all, so they invalidate nothing. The next read recomputes at most six
  days per affected profile, once, under the new definition (D7,
  retroactive).
- **Late reports.** A report that arrives after its day was settled is not
  counted toward the weekly budget. How late a report can arrive is
  **unverified** (it depends on agent buffering while the API is unreachable).
  A metric (§11) counts presence rows ingested for already-settled days, so
  the loss is measured rather than assumed.

### 6.5 Display reads the same primitive

- `ProfileDayState` gains `perGroup: List[BudgetState]`. `AppDayState` (and the
  new `BudgetState`) gain `weeklyLimitMinutes`, `weekUsedMinutes`, `weekStart`,
  `weekResetsAt`. These are additive JSON fields on `/api/time/status/...`.
- The per-app and per-group week figures the SPA shows come from that state. It
  is the same object `PolicyService` enforces from, as #1104 already does for
  the daily figures.
- The existing weekly views (`/api/time/status/week`,
  `/api/time/status/summary/week`) move to the **calendar week** (D10). Their
  window becomes the week containing `?to=` (default today), and their total
  becomes `Σ_d usedSecondsForProfile(d)`, exempt-filtered per day. Past days
  come from settled `time_used_daily` rows (§6.4), and today comes from the
  existing rolled-plus-tail read, so the total costs one small range read. The
  hourly bars, per-device summary and top hosts still need presence rows. They
  keep today's single 7-day range read. On `/api/time/status/week` that read
  is behind `TimeStatusCache`; `/api/time/status/summary/week` has no cache
  today. Either way the view costs what it costs now plus that one query. That read passes the
  profile's exempt patterns to the `Presence` calls, which already take an
  exclusion list (`Routes.scala:1722-1735` passes `Nil` today), so the bars add
  up to the total. The total stops running its own unfiltered aggregation. Days
  after today in the current week are absent from the response, and the SPA
  renders them as "not yet",
  never as `0m` (AGENTS.md#loading-states). Profiles with exempt apps will see
  lower weekly totals than before, because the old totals counted exempt apps.
  The PR says so.
- A reconciliation test pins displayed = enforced for the same profile, budget
  and week (§12).

## 7. PolicyService enforcement

### 7.1 One exhaustion predicate

```scala
def budgetExhausted(b: BudgetState): Boolean =
  b.dailyLimit.exists(b.usedToday >= _) || b.weeklyLimit.exists(b.usedWeek >= _)

/** Apps blocked by ANY budget that contains them. */
def appsOverBudget(state: ProfileDayState): Set[AppId]
```

Every reader that currently asks "is this app over its own cap" asks
`appsOverBudget` instead:

| Today | Becomes |
|---|---|
| `appCapExhaustedHosts` (`:1650`) | distinctive hosts of every app in `appsOverBudget` |
| `exemptUnderCapHosts` (`:1669`) | effective-exempt apps **not** in `appsOverBudget`. Its reach is unchanged: it still beats soft Pause, Schedule and TimeLimit (D16) |
| `timeLimitedUnderCapHosts` (`:1701`) | TimeLimited apps **not** in `appsOverBudget` |
| `enforcement`'s AllowedDuring carve (`ProfileAppDispositions.scala:147-149`) | keeps its existing gate (profile daily cap not exhausted unless exempt, and the #1679 schedule toggle) **and** is also withheld when the app is in `appsOverBudget`. This changes today's behaviour: a window no longer keeps an app reachable past its own daily cap (D17) |
| `enforcement`'s plain Allowed carve (`:154-155`) | unchanged for an app in no budget. A covered app's Allowed row gets no carve (§6.1) |
| `timeLimitBlockFromState` (`:1236`) | first exhausted budget containing the host; `resetAt` is the end of the exhausted window |

`appsOverBudget` lives in one place and is shared by the snapshot and
`decideDetailed`, as #1532 requires.

**Extensions** (D8): a profile extension keeps raising the
profile daily total only. Budgets take no extension, as per-app caps take none
today.

### 7.2 Hosts

- Block side: each blocked member's `distinctiveHosts` → `extraBlocked`
  (#1899).
- Allow side: full `hosts`, unchanged.
- A host that is distinctive on a blocked app and also on an allowed, unrelated
  app ends up in both lanes, and allow wins. That is today's per-app behaviour
  (#763) and is not changed here.

### 7.3 Precedence table

Evaluated per (MAC, app, host) at `now`. "Whole-MAC block" means Paused /
Schedule / TimeLimit (profile daily) / Manual. "Over" means the app is in
`appsOverBudget`. Router outcome is after #421 allow-beats-block.

| # | Whole-MAC block | App budget (own daily / own weekly) | Group budget (daily / weekly) | Effective exempt | App hosts land in | **Outcome** |
|---|---|---|---|---|---|---|
| 1 | none | under / under | under / under | any | `extraAllowed` (#1980 carve) | Reachable |
| 2 | none | under / **over** | — | any | `extraBlocked` (distinctive) | **Blocked**, weekly; resets at week start |
| 3 | none | under / under | under / **over** | any | `extraBlocked` | **Blocked** by group, weekly. The #1980 carve is withheld, so it cannot override (finding 2) |
| 4 | none | **over** (daily) / under | under / under | any | `extraBlocked` | Blocked, daily (today's behaviour) |
| 5 | none | under / under | **over** / under | any | `extraBlocked` | Blocked by group, daily |
| 6 | TimeLimit | under / under | under / under | yes | `extraAllowed` (exempt carve) | Reachable: exempt app outlives the daily total |
| 7 | TimeLimit | under / under | under / **over** | yes | `extraBlocked` | **Blocked**: exempt carve withheld because the group is over |
| 8 | TimeLimit | under / under | under / under | no | — | Blocked by the whole-MAC drop (today's behaviour) |
| 9a | Schedule | under / under | under / under | yes | `extraAllowed` (exempt carve) | **Reachable at bedtime**: an exempt member under every budget outlives Schedule, as an exempt app does today (D16) |
| 9b | Schedule | under / under | under / under | no | — unless an `allowed_during` window carves | Blocked, unless per-app-schedules §5 row 1 applies |
| 9c | Schedule | any | any **over** (any budget) | any | — | **Blocked**: no carve for an app over any budget, exempt or windowed (D16, D17) |
| 9d | Schedule, `allowed_during` active | under / under | under / under | no | `extraAllowed` if the existing gate passes: profile daily cap not exhausted unless exempt, and the #1679 schedule toggle | Rows 1 and 9a–9c of per-app-schedules §5, unchanged; this design only **adds** the over-budget condition of row 9c above |
| 9e | Paused (soft) | under / under | under / under | yes | `extraAllowed` (exempt carve) | Reachable, as today (D16) |
| 9f | Paused (soft) | any | any **over** | any | — | Blocked |
| 10 | Paused (hard) | any | any | any | — | Blocked (#1418 empties per-profile carves) |
| 11 | none | Allowed-mode app (no limits, not in a group) | — | any | `extraAllowed` | Reachable (unchanged) |
| 12 | default-deny profile | under / under | under / under | any | `extraAllowed` | Reachable within budget; blocked by the baseline once over |
| 13 | none | overlap: own row **Blocked** | under / under | any | `extraBlocked` | **Blocked** (stricter wins, D14) |
| 14 | none | overlap: own row **Allowed** | under / **over** | any | `extraBlocked` | **Blocked**: the Allowed row's carve is withheld for a covered app |
| 15 | none | overlap: own row daily **over** | under / under | any | `extraBlocked` | **Blocked** by its own limit |

Notes:
- Rows 3, 7, 9c and 14 are the cases finding 2 exists for. Each gets a feature test
  that fails if the carves still read only the app's own cap.
- During an overlap (D14), the stricter setting wins. An individually Blocked
  covered app stays blocked. One with its own limit is blocked when either its
  own limit or the group's runs out. An Allowed row adds no carve (§6.1).
  Rows 13–15 above pin this.
- Exhausting a budget never sets `blocked` for the MAC. It does not trigger the
  shared-device auto-checkout, which keys off the whole-profile block
  (shared-devices §7.3).

### 7.4 Lowering a limit mid-week

A limit lowered below what has already been used takes effect at the next
snapshot (D9). That is how lowering a daily limit behaves today.

### 7.5 Freshness

Snapshots are already rebuilt on poll and on push. A weekly boundary is a daily
reset instant, which already changes the ETag. Budget exhaustion is detected at
the same cadence as per-app daily exhaustion. Nothing new is needed.

## 8. Block page and reason

- `timeLimitBlockFromState` returns `AppTimeLimit("group:<id>")` for a group
  block and `AppTimeLimit("app:<slug>")` for an app block. The wire string
  `app_time_limit:<label>` is unchanged in form, and routers echo labels back
  opaquely (`Models.scala:2458-2460`). The decision's `resetAt` is the end of the
  exhausted window: the next daily reset, or the next week start.
- `GET /api/blocked` gains an additive field
  `budget: {kind: "app"|"group", name, window: "day"|"week"}`.
- The block page names the budget and the window, with no reset time (D12).
  Examples: "Games has used up its weekly time." and "Roblox has used up its
  daily time."
- The block page stops offering "Ask for more time" for an `app_time_limit`
  block (`BlockedPage.tsx:184` offers it today). A granted extension raises
  only the profile daily total (D8), so it can never lift an app or group
  budget, and the button is a dead control for today's per-app daily cap too.
  "Ask to unblock this site" stays.

## 9. API

All changes are additive. Each route follows `docs/process/api-route-checklist.md`.

- `UpsertAppAssignmentRequest.weeklyMinutes: Option[Int] = None`.
- Household groups: `GET/POST /api/app-groups`,
  `PATCH/DELETE /api/app-groups/{id}` (name, `memberAppIds`). Admin-only writes,
  scoped through `HouseholdScoped`.
- Attachments: `GET /api/profiles/{id}/app-groups`,
  `PUT/DELETE /api/profiles/{id}/app-groups/{groupId}` (daily, weekly,
  `exemptFromDaily`).
- Every membership and attachment write invalidates the policy snapshot.
- Assignment responses gain `coveredBy: {groupId, name} | null`, which is set
  when the app is also covered by a group attached to that profile (D14).
- `PATCH /api/settings` accepts `weekStartDay`.
- `/api/time/status/...` responses gain the §6.5 fields.

Validation:
- an attachment needs at least one of daily or weekly;
- an app already in another group of the household is rejected with a message
  that names that group (D2);
- daily > weekly is accepted, and the SPA shows a hint (D11).

## 10. SPA

The #2764 layout (compact allowed/blocked lists with expanders) stays. No row
grows taller when collapsed.

- App row, collapsed: the existing limit chip shows the tighter remaining
  figure, e.g. `20m left today` or `1h 10m left this week` (D12).
- App limit drawer: the `min/day` input (unchanged) plus a `min/week` input,
  with a usage bar for each limit set.
- Covered apps are not listed as app rows (D6). They appear only inside
  their group's row.
- Overlap warning (D14): an app row whose assignment has `coveredBy` shows an
  amber chip, "Also in *Games*", in the collapsed row's chip slot, so the row
  does not grow taller. Its drawer explains that both apply and the stricter
  wins, and offers **Remove from this profile**, which deletes the assignment
  and leaves the group coverage in place.
- Picker (D15): "+ Allow app" / "+ Block app" mark covered apps "In
  *Games*". Picking one still works and lands with the overlap warning.
- Groups on a profile: a compact "Groups" sub-list at the top of the allowed
  list. Each attached group is one collapsed row: name, member icons, remaining
  time. Its expander holds the profile's daily and weekly inputs and the
  "Counts toward daily limit" checkbox, with a hint under it while unchecked:
  "Not counted: while under its limits, this group stays reachable during
  bedtime and a soft pause." That is D16, and because unchecked is the default
  (D5), the hint is what keeps it from being a surprise. The label is inverted
  from the field, as
  today (AGENTS.md#exempt-from-daily). "+ Add group" lists the household's
  groups and a "New group…" entry.
- Editing members happens in the group's expander. Because the group is
  household-wide, the expander says which other profiles use it ("Also used by:
  Sam"). Adding a member that a profile already lists on its own shows the
  overlap warning on that profile.
- Loading: week-to-date bars use the same three states as #2764 (pending
  skeleton, error, value). The `UsageStatus` prop is required and threaded the
  same way.
- Settings: a "Week starts on" select next to the daily reset time.
- Weekly view: calendar week (D10). Future days in the current week render
  as empty slots, never `0m`.
- Not in v1: a per-day breakdown chart per budget, and remaining weekly
  time on the kid-facing view (D12).
- Autosave everywhere, with no Save buttons (#995).
- Copy for an Allowed-mode app claims only the budget effect (#2747).

## 11. Observability

All metrics go through `AppMetrics` / `MetricGuard` with bounded labels and no
per-profile, per-app or per-group label.

- `app_budget_exhausted{kind="app"|"group", window="day"|"week"}`: gauge of
  the count of exhausted budgets across all households. It is set once per
  reevaluate tick from that tick's per-household snapshot results, summed. A
  per-household snapshot build never writes it directly, because builds run
  per household and each one would overwrite the others' value.
- `budget_usage_settle_total{outcome="hit"|"refill"|"error"}`: counter on
  `settledSeconds`. A sustained `refill` rate means the settle tick is not
  keeping up.
- `budget_late_presence_rows_total`: counter of presence rows ingested for a
  date already settled (§6.4).
- `budget_week_read_seconds`: read-latency histogram.
- Each gets a panel under `deploy/grafana/dashboards/` in the PR that emits it
  (`grafana-terraform` gate).

## 12. Tests

Feature tests run on embedded Postgres through the full stack with
`Clock.TestClock`. No `*Repo` is mocked. Every absence assertion has a liveness
anchor: the same rig shows non-zero minutes for a control profile, budget or
day, so a rig with no traffic cannot pass.

Attribution (weekly budget):
1. Shared device: A checks in Tuesday, checks out, and B checks in Thursday.
   Each profile's weekly budget equals only its own interval's minutes. Anchor:
   both are > 0.
2. Non-shared device reassigned A → B Wednesday 12:00. A's week has Monday to
   Wednesday 12:00, and B's has the rest.
3. A presence row whose `period_start` is before the reassignment and whose end
   is after it counts toward A only.
4. **Displayed weekly minutes = enforced weekly minutes** for the same profile,
   budget and week, via `/api/time/status` and the snapshot's `extraBlocked`
   threshold crossing at exactly that minute.

Budget semantics:
5. §7.3 rows 3 and 7: group over, member's own cap under. The member's hosts are
   in `extraBlocked` and **not** in `extraAllowed`. Fails on `main`.
6. A host on two members counts once. A `shared` host counts zero.
7. Attaching a group whose members are already assigned changes no other app's
   minutes. Attaching one with new members gives the same minutes as assigning
   them individually (§6.2).
8. Effective exempt: an attachment with "counts" on excludes nothing from the
   daily total even if a member's own flag was exempt, and the reverse.
9. An extension raises the profile total only and does not lift a budget.
9a. §7.3 table rows 9a–9f: an exempt member under budget is reachable during Schedule
    and a soft pause (D16), and blocked once over any budget. An `allowed_during`
    window no longer carves an app past its own daily cap (D17). Today's
    per-app-schedules rows 9a–9c still pass unchanged.
10. One household group attached to two profiles with different limits: each
    profile is blocked at its own limit on its own usage.

Membership (§5.3):
11. A covered app with no assignment is enforced by the group alone, and the
    profile's assignment list does not contain it.
12. Overlap: §7.3 rows 13–15, each with a non-overlapping control member that
    behaves normally. `coveredBy` is set on the overlapping assignment only, and
    removing that assignment leaves the group coverage enforced.
13. A member cannot be put in a second group. A cross-household member or
    attachment is rejected (composite FK and repo check).

Time:
14. Week boundary at `dailyResetTime` on `week_start_day`, one minute either
    side.
15. DST spring-forward and fall-back weeks (a US and an EU zone): 167 h and
    169 h weeks, with budget usage straddling the transition night.
16. Changing `week_start_day` mid-week recomputes the current week.

Settling:
17. A cold week with no rows refills via write-through once. A second read is
    all hits (metric asserted).
18. A settings save deletes, then refills with identical totals.
18a. A report for yesterday that arrives inside `SettleDelay` is counted. One
     that arrives after settling increments the late-row counter.
18b. An assignment mode change and an attachment create or delete each delete
     the profile's current-week settled rows, and the week recomputes to the
     same value a cold read gives. An exempt-flag edit deletes only the
     current-week `time_used_daily` rows, and the weekly view total then
     matches a cold read.
19. A membership edit recomputes the week under the new membership for every
    attached profile.

## 13. Rollout (foundation first)

Each step is one PR and one sub-issue under the epic.

1. **Schema only.** The next free Flyway version (V92 at the time of writing,
   after V91 from #2928 merged; check `origin/main` for a newer one before
   opening the PR): `household_settings.week_start_day`, the
   `UNIQUE (id, household_id)` on `profiles` that the composite FKs need,
   `app_policy_assignments.weekly_minutes`, `app_groups`, `app_group_members`,
   `profile_app_groups`, `app_group_used_daily`. SQL and docs only. It waits
   for the operator to merge.
2. **Budget primitive.** `CapBudget` / `ProfileAppDispositions.budgets`,
   repos for the new tables, `BudgetUsageService.weekToDate`, settle-on-read
   plus the rollup job's settle-yesterday pass, `ProfileDayState.perGroup` and
   the week fields, and moving the weekly views onto the calendar week and
   `usedSecondsForProfile` per day. Budgets are computed and exposed but **not
   enforced**. Ships with the settle metrics and panels.
3. **Enforcement.** `appsOverBudget` across the snapshot and `decideDetailed`,
   the carve-withholding from finding 2, the block-reason labels, and the
   `app_budget_exhausted` gauge and panel.
4. **API.** Group and attachment CRUD, `coveredBy` on assignments,
   `weeklyMinutes` on assignments, `weekStartDay`, the `/api/blocked` budget
   field, and the TS client.
5. **SPA and block page.** §10 and §8.
6. **Observability.** Anything not already shipped with slices 2 and 3, plus an
   alert on a sustained `refill` rate.

Slices 2 and 3 are separate so the counting can be checked on prod, by reading
`/api/time/status`, before anything is enforced from it.

## 14. Alternatives considered

- **Build weekly on `buildProfileTimeStatusWeek`.** Rejected (finding 3): no
  exempt filtering, a per-device total, a trailing window, and it lives in a
  route.
- **Weekly = one stitch across the whole 7-day presence read.** Rejected. It
  needs a 7-day presence read per snapshot, and it disagrees with the daily
  figures when a session crosses the reset, which would put display and
  enforcement out of step.
- **Per-app weekly as a singleton group.** Rejected for the data model, because
  it would leave two places to set a per-app limit. Accepted inside the code:
  both are `CapBudget`.
- **Group minutes = Σ members' minutes.** Rejected. It double-counts a shared
  host and simultaneous use, so two apps used together would run out twice as
  fast.
- **Per-profile groups.** Considered and declined by the operator (D3). They
  are simpler, but the same group has to be set up again for each child.
- **Auto-assign members on attach.** Rejected (D6): it listed a covered app as
  a separate row, and it needed a reconcile step to promote and demote
  assignments on every membership change.
- **Forbid overlap.** Deferred (D14, D15): the operator could not see a reason
  to list a covered app separately, but chose to warn rather than block until
  one turns up.
- **Limits on the household group.** Rejected: that would make the group a
  policy tier above the profile. Limits live on the per-profile attachment.
- **A new `MacBlockReason` or snapshot field for weekly.** Rejected:
  architecture rule 2.

## 15. Decisions (operator, 2026-10-23)

- **D1 Week start.** A household setting, "Week starts on", default Monday. The
  week resets at `dailyResetTime` in `dailyResetTz`.
- **D2 Membership.** An app is in at most one group per household. Being in a
  group gives it no limits of its own. Own limits exist only on an overlapping
  assignment (D14).
- **D3 Scope.** Groups are defined per household (name and members). Each
  profile attaches a group with its own limits.
- **D4 Group limits.** Daily and weekly are both optional on an attachment, and
  at least one is required.
- **D5 Counts toward daily.** The flag lives on the attachment and overrides
  members' own flags while they are in the group. The default is not counted
  (exempt), matching the per-app default.
- **D6 A group covers its members.** On a profile with the group attached, a
  member is covered by the group and is not listed as its own app. Listing it
  separately is treated as a likely mistake. *(Revised 2026-10-23; this
  replaces auto-assigning members.)*
- **D7 Retroactive.** Usage counts from the start of the week, including when a
  limit or group is created, or its membership changes, mid-week.
- **D8 Extensions.** They apply to the profile daily total only.
- **D9 Lowering below used.** It blocks immediately.
- **D10 Weekly view.** It moves to the calendar week and becomes
  exempt-filtered, matching the budgets and the daily headline.
- **D11 Daily and weekly on one app.** Either running out blocks. Setting either
  implies time-limited. Daily > weekly is accepted with a hint.
- **D12 Visibility in v1.** App and group rows show week-to-date usage and what
  is left. The block page names the budget and the window, with no reset time.
  There is no per-day breakdown chart and no kid-facing remaining time in v1.
- **D13 Profile weekly total.** Out of scope for this epic. The budget model
  leaves room for it.
- **D14 Overlap is warned, not blocked.** A covered app that also has its own
  assignment keeps both. Both apply and the stricter wins. The SPA warns and
  offers a one-click remove of the assignment.
- **D15 New overlaps are allowed with a warning.** The app picker marks covered
  apps, and picking one lands with the same warning.
- **D16 Exempt keeps its reach (2026-10-24).** A "not counted" group or app
  that is under every budget stays reachable through bedtime (Schedule), a soft
  pause and the daily total, which is the exempt meaning per-app limits already
  have. Only a hard pause stops it. The SPA says so beside the checkbox (§10).
- **D17 Budgets bite inside windows (2026-10-24).** An active `allowed_during`
  window decides when an app may be used. Its budgets decide how much: once any
  of them runs out, the app is blocked, window or not. This changes today's
  behaviour, where a window keeps an app past its own daily cap.

## 16. Still open

- Late presence reports. How late a report can arrive after its day is settled
  is unverified (§6.4). `budget_late_presence_rows_total` measures it, and the
  settle delay is tunable. S6 reviews it after a few weeks on prod.
- Overlap policy. D14 and D15 warn rather than forbid. If overlaps turn out to
  be mistakes in practice, forbidding them is the alternative recorded in §14.
