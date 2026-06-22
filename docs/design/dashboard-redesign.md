# /dashboard redesign — design note

Status: **proposed — implementation PAUSED, gated on websocket #1023** (design phase of [#1148](https://github.com/wifihaven/wifihaven/issues/1148)). No implementation in this PR. See §7.5 for the sequencing decision.

> **Revision 2 (2026-06-22).** §§1–6 below are the original design pass (PR
> [#1158](https://github.com/wifihaven/wifihaven/pull/1158), 2026-05-29) and are kept
> verbatim for the live-inspection grounding. Since then three things changed that the
> original pass could not see, and the implementation sub-issues it called for were never
> filed. **§7 is the authoritative revision**: it reconciles the now-shipped "Most Recently
> Blocked" feed ([#1338](https://github.com/wifihaven/wifihaven/issues/1338)) into the IA,
> resolves the §6 open questions into concrete decisions, and locks the implementation into
> filed, dependency-ordered sub-issues. Where §7 and §§1–6 disagree, **§7 wins.**

This note resolves the eight piecemeal ux-audit tweaks folded into #1148 into a single
coherent layout, grounded in live inspection of the staging dashboard rather than code
alone. It produces a target layout + information architecture, dispositions each input
issue, and breaks the work into PR-sized chunks for follow-up.

## Method / live inspection

Inspected the rendered SPA on **wifihaven.net (production)** (`/dashboard`,
`/usage/events`, `/profiles`) via the browser extension on 2026-05-29, logged in as admin.
Full-page text and screenshots were captured at 1080p. (An earlier pass against
`staging.wifihaven.net` showed the same structural problems but on thin synthetic e2e data
— one active device, all bare-IP events, zero blocks — so this note is grounded in the
realistic prod household instead.)

Prod data density (the realistic case the input issues describe):

- **7 profiles**: 4 active (`Kids`, `Sameer`, `Family`, `Rachel`) + 3 idle (`Quintus`, `Prima`, `Octavius`).
- **18 devices** across the household, from MacBooks and phones to a wall of IoT (Plex Server, 2× Sonos, Lutron Bridge, Lennox Thermostat, Garage Door Opener, B-Hyve Controller, NAS, printers).
- **16,755 connection events today, 3 blocked**; 62 events in the last hour, 0 blocked.

The mobile reflow could not be captured reliably — the extension's screenshot viewport
stayed at desktop width regardless of window resize — so reflow behaviour below is read
from the responsive Tailwind classes in `web/src/pages/DashboardPage.tsx`, not from a live
narrow render. **The operator may want to re-check mobile on a real device.**

## 1. Current state

`web/src/pages/DashboardPage.tsx` renders, top to bottom:

1. `<h1>Dashboard`
2. `NewDevicesHint` banner — e.g. "1 new device on the network — review on the Devices page →" (observed on staging).
3. `AccessRequestsBanner` — kid extension / access requests (empty on staging).
4. **NOW** section (`NowSection`, polls every 10s via `useDashboardNow`) — a 2-col grid of per-profile cards; each active card lists every active device with a per-row "Xs ago" label, a "watching <host> · Nm" line, and a top-hosts list. Idle profiles render as full-size dimmed cards reading "No activity in the last 5 minutes".
5. **KPI strip** — 4 `StatCard`s: Queries today / Blocked today / Queries (1h) / Blocked (1h). Renders **after** NOW.
6. **Top Blocked (24h)** + **Per Device (24h)** — side-by-side panels.
7. **Recent Queries** — full `LogTable` (30 rows) fetched via `api.logs.query({ limit: 30 })`.

### What live inspection (prod) showed — and what's wrong with it

- **One profile card dominates the entire page.** The `Family` card lists **~11 active devices** — Plex Server, both Sonos units, Lutron Bridge, Lennox Thermostat, Garage Door Opener, B-Hyve Controller, NAS, MacBook, `device-45219d`, `tb8786p1-…` — each with a 3-host sublist. It is taller than the rest of NOW combined, and almost all of it is IoT chatter no human is "using". The high-value cards (`Kids` watching Khan Academy, `Sameer`, `Rachel`) are buried below it. This is exactly #819, at worse-than-described scale (11 rows, not 9).
- **Idle profiles render as full empty cards.** `Quintus`, `Prima`, `Octavius` each render a full-size "No activity in the last 5 minutes" box at the bottom of NOW — three dead cards taking three card-heights. → #820.
- **KPI strip is below the fold.** The 4 headline numbers render only after the entire (very tall) NOW grid; on prod they're several screens down. → #821.
- **"Per Device (24h)" is a wall of zeros.** Prod shows **18 device rows, 17 of which read "0 blocked"** (only `Kid Mac` shows 3). It's a per-device *traffic-volume* leaderboard — `Plex Server · 8115 queries`, `Sameer Mac · 2472` — wearing a security-panel hat. Volume belongs on /devices and /profiles. → #822.
- **"Top Blocked (24h)" is a single noise row.** The whole panel is one line: `captive.apple.com · 3` — an Apple captive-portal connectivity probe, i.e. effectively noise, not a meaningful parental block. So on a healthy household the two blocking panels are *one real row between them*, yet they occupy a full two-column section: Top-Blocked (1 row) next to Per-Device (18 rows) is a wildly asymmetric, mostly-empty band. → #822 (merge + honest empty state).
- **"Blocked today: 3" is the only KPI that matters, and it's a captive probe.** "Queries today: 16,755" is a big number that answers nothing for the glance question. The signal is in *blocked*, not *volume*. → motivates the KPI restate.
- **"Recent Queries" is a log firehose, not a dashboard widget.** Prod's table opens with **8 consecutive `Plex Server` bare-IP rows** (peer/streaming traffic), then `Sameer Mac → api.github.com` **four times in a row** (a polling loop — precisely the case #823 calls out), all `ok`/`allow`. Real browser hostnames do resolve (`assets-proxy.anthropic.com`, `graph.instagram.com`, `inquisition.goguardian.com`), but the table still duplicates `/usage/events` and adds a second network call on load. → #823.
- **Terminology is stale and inconsistent across surfaces.** The dashboard says "Queries today", "Recent Queries", "N queries". The richer surface at `/usage/events` is **already titled "Connection Events"**. The project has already adopted the correct term — only the dashboard lags. → #299.
- **Per-row "Xs ago" labels carry no signal.** Every NOW device row shows "1m ago" off the same snapshot. → #825.
- **The "watching <host>" line is often a bare IP.** `Sameer Mac · watching 104.16.185.241`, `Plex Server · watching 185.238.231.174`, `Sameer Desk · watching 162.159.197.2 · 29m` — the headline activity line frequently shows an unresolved IP, which is meaningless to a parent. Relevant to whatever ranking/labeling the redesigned NOW card uses (and to #747/#783).
- **No throughput anywhere.** No live bytes-in/out on the dashboard today. → #747.
- **Perceived latency, confirmed on prod.** NOW stayed "Loading live activity…" for ~7–8s before painting (near-instant on staging's thin data). The page also gates the whole render on `Promise.all([logs.stats(), logs.query()])` behind one `PageLoader`, and the 24h panels scan raw `connection_events` rather than the rollup tables. → #1098 / #1099 / #809.

### What's genuinely useful today

- The **NOW** active cards — "who is online and what are they watching right now" — are the single most valuable thing on the page, when not buried under the Family/IoT card. The `Kids · watching www.khanacademy.org · 2m` line is exactly the glanceable signal a parent wants.
- The **"Blocked today / Blocked (1h)"** numbers answer "is anything being blocked" at a glance — once they're above the fold and not drowned by the volume tiles.
- The **banners** (new device, access requests) are correctly placed action prompts.

## 2. Goal / non-goals

**The dashboard answers one question: "Is everything OK in the house right now?"** —
who's online, who's blocked or paused, and is anything wrong (new device, pending request,
a profile out of time). It is a *glanceable status board*, not an analytics surface.

Non-goals (these now have richer dedicated homes):

- **Per-query / per-event history** → `/usage/events` ("Connection Events"), which already has granularity routing, column aggregation, and filtering.
- **Per-device and per-profile traffic volume / usage-today** → `/profiles` (per-profile usage + `+Time`, observed live) and `/devices`.
- **Deep blocklist / schedule / time-limit editing** → `/profiles`.

Design principles:

1. Everything that answers "OK right now?" is above the fold on a 1080p load.
2. Signal over completeness — zero-rows, empty cards, and duplicated log content are removed, not dimmed.
3. One freshness indicator per live section, not per row.
4. The IA must be mirror-able on a 375px phone (per #982: Dashboard NOW is a named v1 iOS surface, read-only).

## 3. Target layout + IA

Section order, top to bottom:

```
┌─────────────────────────────────────────────────────────────┐
│ Dashboard                                                     │
│                                                               │
│ [ ⚠ 1 new device → Devices ]   [ ⚠ 2 access requests → ]      │  ← banners (only when present)
│                                                               │
│ ┌──────────┬──────────┬──────────┬──────────┐                │
│ │ Online   │ Blocked  │ Events   │ Blocked  │   ← KPI strip   │  ABOVE the fold
│ │ now      │ now      │ (1h)     │ (1h)     │     (4 tiles)   │
│ │   3      │   1      │   18     │   0      │                 │
│ └──────────┴──────────┴──────────┴──────────┘                │
│                                                               │
│ NOW                              updated 12s ago · ↻ in 8s    │  ← one freshness pill
│ ┌─────────────────────────┐ ┌─────────────────────────┐      │
│ │ Sameer          ▲2.4 ▼18 │ │ Kids                     │      │  ← active profile cards
│ │ Sameer Mac · app.warp.dev│ │ Kid Mac · watching       │      │     (throughput on card
│ │ Sameer iPhone· plex.tv   │ │   khanacademy.org · 2m   │      │      header, top-N devices)
│ │ Sameer Desk · (active)   │ │                          │      │
│ │ ─ show 1 more ─          │ │                          │      │
│ └─────────────────────────┘ └─────────────────────────┘      │
│ ┌─────────────────────────┐ ┌─────────────────────────┐      │
│ │ Family          ▲0.3 ▼1  │ │ Rachel                   │      │
│ │ MacBook · 108.32.93.57   │ │ Rachel Mac · grok.x.com  │      │
│ │ Plex Server · (active)   │ │ Rachel iPhone· (active)  │      │
│ │ NAS · (active)           │ │                          │      │
│ │ ─ show 8 more ─          │ │                          │      │  ← #819: 11-device card capped
│ └─────────────────────────┘ └─────────────────────────┘      │
│                                                               │
│ Idle (3): Quintus · Prima · Octavius ▸                        │  ← collapsed idle row (1 line)
│                                                               │
│ ── below the fold ──                                          │
│                                                               │
│ Blocking activity (24h)               1 host · 3 blocked      │  ← merged panel
│ ┌─────────────────────────────────────────────────────┐     │
│ │ captive.apple.com                             3  ▸    │     │     (or one-line empty state
│ └─────────────────────────────────────────────────────┘     │      when 24h had zero blocks)
│                                                               │
│ Recent connection events                    View all → /…     │  ← link card, NO table
└─────────────────────────────────────────────────────────────┘
```

### Section-by-section

**Banners** (unchanged placement, top). Only render when there's something to act on. These are the "anything wrong?" channel.

**KPI strip — moved above NOW** (#821). Reworked from "queries"-centric to status-centric:

- **Online now** — count of devices active in the last 5 min (derivable from the NOW snapshot; no new query).
- **Blocked now** — count of profiles currently `blocked` (paused / out-of-schedule / over-limit), from the same snapshot.
- **Events (1h)** — connection events in the last hour (was "Queries (1h)"; renamed per #299).
- **Blocked (1h)** — blocked events in the last hour.

  Rationale: "Online now / Blocked now" answers the glance question directly; the two 1h tiles give recent-trend context. "Queries today / Blocked today" (cumulative) is analytics — it moves to /usage/events headers. Exact tile set is an open question (§6).

**NOW — the heart of the page.** Owns the most vertical space but capped:

- **Active profile cards** render first, in the existing 2-col grid (1-col on mobile).
- Each card caps at **top-3 devices** ranked by most-recent activity, with an in-place "show N more" expander (#819). Expander state is per-session (`sessionStorage`), reset on reload.
- **Idle profiles collapse into a single row** below the active grid: "Idle (N): name · name · name ▸", expandable in place to full (dimmed) cards. Paused-and-idle profiles live here too, tagged paused when expanded (#820).
- **One freshness pill** in the section header — "updated 12s ago · refresh in 8s" — using TanStack Query's `dataUpdatedAt`, not a self-timer. Per-row "Xs ago" labels are dropped; the inline label is kept only as an exception for a device whose `lastSeenSeconds` is materially older than the snapshot (#825).
- **Throughput** (#747): show live in/out on the **profile card header** as a compact "▲2.4M ▼18M" (or "—" when unknown), aggregated per profile. Deferred from v1 unless the data source is cheap (see §6 + disposition). Per-device/per-host throughput stays off the dashboard.

**Blocking activity (24h) — single merged panel** (#822), below the fold. Replaces the
Top Blocked + Per Device pair:

- Header: "Blocking activity (24h)" + subtitle "N hosts · M blocked events".
- Body: ranked blocked-host list (existing `topBlocked` shape), each row optionally expandable to show which devices triggered it (needs a small contract addition — see §6; ship without expansion for v1 if unavailable).
- No "0 blocked" rows ever. If there were zero blocks in 24h, render a **one-line** "No blocked connection events in the last 24h" — not two empty cards.
- Backed by the rollup tables (#809), not a raw `connection_events` scan (#1099).

**Recent connection events — link, not table** (#823 + #299). Drop the inline `LogTable`
and the `api.logs.query({ limit: 30 })` call entirely. Replace with a single link card
"Recent connection events — View all →" pointing at `/usage/events`. No inline preview;
the full surface is one click away and already superior.

### Mobile reflow (#982)

- KPI strip: `grid-cols-2` (2×2) on mobile, `md:grid-cols-4` on desktop (matches existing pattern).
- NOW: single-column card stack on mobile; idle row stays one line.
- Blocking activity: single column; the events link card is full-width.
- The section *order* (banners → KPI → NOW → blocking → link) is identical on mobile and is exactly the read-only v1 iOS surface set — the iOS app can mirror NOW + KPI and link out for the rest.

## 4. Disposition of input issues

| Issue | Disposition | Where / rationale |
|-------|-------------|-------------------|
| **#819** NOW: cap card height, top-N + expander | **Fold in** | NOW section; top-3 devices/card + per-session expander. Critical: prod's `Family` card is 11 devices of mostly-IoT chatter — ranking must surface human-relevant devices, not Sonos/Lutron noise (see §6 Q6). |
| **#820** NOW: collapse idle profiles | **Fold in** | NOW section; single "Idle (N): …" row below active grid, expandable. |
| **#821** Move KPI strip above NOW | **Fold in (modified)** | KPI moves above NOW, *and* tile contents change from cumulative "queries" to status-first ("Online now / Blocked now") — supersedes the pure-reorder scope of #821. |
| **#822** Merge Top Blocked + Per Device | **Fold in** | New "Blocking activity (24h)" panel; Per-Device volume dropped (belongs on /devices). |
| **#823** Drop Recent Queries table | **Fold in** | Replaced by a link card to `/usage/events`; removes the second load-time fetch. |
| **#825** Single freshness timestamp | **Fold in** | One pill in NOW header via `dataUpdatedAt`; per-row labels dropped (inline kept as stale-row exception only). |
| **#299** "queries" → "connection events" | **Fold in** | Applies everywhere on the dashboard (KPI labels, panel/link copy). Note: `/usage/events` already uses "Connection Events" — this aligns the dashboard to the existing term. |
| **#747** Show active bytes in/out | **Fold in as deferred sub-task** | Per-profile throughput on NOW card headers *if* a cheap source exists; otherwise defer to its own PR. Per-device/host throughput stays off the dashboard (→ /devices, /profiles). |

All eight are folded; none are superseded-and-dropped or deferred-out-of-scope, except
#747's *implementation* which is gated on a data source. Once this design is signed off,
#819/#820/#821/#822/#823/#825/#299 close as superseded by the implementation PRs below,
and #747 stays open as the throughput sub-task.

## 5. Implementation breakdown (PR-sized chunks)

Ordered so each PR is independently shippable and the page improves monotonically. TDD per
`CLAUDE.md`: update `DashboardPage` feature/component tests first.

1. **Terminology + drop Recent Queries** (#299, #823). Rename dashboard copy "queries" → "connection events"; remove the `LogTable` section and the `api.logs.query` call; add the "View all → /usage/events" link card. Pure frontend; removes one network call. Lowest risk, do first.
2. **KPI strip: move + restate** (#821, #299). Move the strip above NOW; swap to Online now / Blocked now / Events (1h) / Blocked (1h), derived from the NOW snapshot + existing stats. Pure frontend.
3. **NOW density: top-N + idle collapse + single freshness** (#819, #820, #825). Card cap with per-session expander; idle-profile collapse row (reuse `EmptyState`); freshness pill from `dataUpdatedAt`; drop per-row labels. Frontend; no API change (`useDashboardNow` already returns the full list).
4. **Blocking activity (24h) merged panel** (#822). Replace the two panels with one ranked-host panel + one-line empty state; drop per-device volume. Frontend, with a **backend follow-up** if "devices per blocked host" expansion is wanted (file separately, ship v1 without expansion).
5. **Rollup-backed 24h panels + perceived-latency fix** (#1098, #1099, #809). Point the 24h/blocking aggregations at `traffic_hourly` / `traffic_daily` (now that #809 is merged) instead of raw `connection_events`; batch per-profile usage into one round-trip; replace the single blocking `PageLoader` with per-section skeletons so NOW paints independently of the 24h panels. This is the latency PR — backend + frontend.
6. **Per-profile throughput on NOW cards** (#747). Only after confirming a cheap source (the NOW snapshot's active buckets, or a rollup-derived rate). Frontend if the snapshot already carries bytes; otherwise a small additive API field first. Lowest priority.

Data-source note: every 24h/aggregate panel must read the rollup tables (#809:
`traffic_hourly` for the current-hour tail, `traffic_daily` for older windows), never a
raw `connection_events` scan — this is the #1099 win. The live NOW snapshot stays as-is
(`useDashboardNow`); it is small and already fast.

## 6. Open questions for the operator

1. **KPI tile set.** Proposed: Online now / Blocked now / Events (1h) / Blocked (1h). Do you still want a cumulative "today" number on the dashboard, or is daily volume firmly /usage/events' job now? (Could also surface "profiles over time-limit today" as a tile.)
2. **Throughput (#747) on the dashboard at all?** It's the one item that's arguably analytics. Keep per-profile live in/out on NOW cards, or drop it from the dashboard entirely and put throughput only on /devices & /profiles?
3. **Blocking-host → devices expansion.** Worth a backend contract addition to show "which devices hit this blocked host", or is the ranked host list enough for v1?
4. **Idle-collapse threshold.** Idle = "no activity in last 5 min" (current NOW definition). Keep 5 min, or a different window for what counts as "online now" in the KPI tile?
5. **Mobile.** Live narrow render couldn't be captured via the extension — please sanity-check the reflow on a real phone before the iOS IA is locked.
6. **Device ranking inside a card (#819).** Prod's `Family` profile has ~11 devices, almost all IoT (Plex, Sonos, Lutron, Lennox, garage door, thermostat, NAS) generating constant bare-IP chatter. "Top-3 by most-recent activity" would surface a Sonos before a MacBook. Options: (a) rank by active-seconds/session length rather than recency; (b) de-prioritise devices whose only activity is heartbeat/bare-IP; (c) let IoT-heavy "infrastructure" profiles collapse more aggressively than human profiles. Which ranking do you want?
7. **IoT noise generally.** Should infra/IoT devices (Sonos, thermostat, printers) be visually de-emphasised or grouped on the dashboard, or treated like any other device? This also affects the "Online now" KPI count — 18 devices are "online" but most are appliances.
8. **Aside (not in scope here):** the `/usage/events` subtitle reads "Per-query DNS / blocking decisions", which is slightly off the architecture model (DNS is never the enforcement plane). Worth a tiny copy fix on that surface in a separate issue.

---

## 7. Revision 2 (2026-06-22) — reconcile shipped state, decisions, locked plan

§§1–6 were written against prod on 2026-05-29. Three things changed since, and the
implementation sub-issues §5 called for were never filed. This section is authoritative.

### 7.1 What shipped since revision 1 (and how the design reconciles it)

- **"Most Recently Blocked" feed shipped** ([#1338](https://github.com/wifihaven/wifihaven/issues/1338)).
  `DashboardPage` now renders a `RecentlyBlockedSection` — a newest-first, **blocked-only**,
  trailing-hour feed (`useRecentBlocked` → `api.logs.query({ blocked: true, hours: 1, limit: N })`,
  polled every 10s), with a "View all → /usage/events" link. **This is signal, not the firehose
  #823 targets.** The original §3 IA had only a "Recent connection events — link, not table"
  footer; rev 2 folds the shipped feed into the IA as the **recency complement** to the 24h
  blocking panel. It is *kept*. The thing #823 drops is the separate **unfiltered** "Recent
  Queries" `LogTable` (still present at `DashboardPage.tsx:87-94`) — that one goes.
- **#809 rollup tables merged** and **#1099 (per-profile usage opt) closed.** The §5 chunk-5
  caveat "once #809 lands" is now unblocked: the 24h aggregations should ride the rollups.
- **No throughput source was added.** `DashboardNowDevice` still carries no bytes field, so #747
  remains backend-gated (see decision Q2).

### 7.2 Updated section order (supersedes the §3 wireframe)

```
Dashboard (h1)
  Banners            — NewDevicesHint, AccessRequestsBanner (only when present)
  KPI strip          — Online now · Blocked now · Events (1h) · Blocked (1h)      [above the fold]
  NOW                — freshness pill; active cards (top-3 devices + expander); idle-collapse row
  Most Recently Blocked  — blocked-only trailing-hour feed (#1338), View all → /usage/events
  Blocking activity (24h) — one merged ranked-host panel; one-line empty state
  (no inline log table)
```

The two recency/blocking elements sit adjacent and are intentionally distinct: **Most Recently
Blocked** answers "what just got dropped?" (live, un-aggregated, 1h); **Blocking activity (24h)**
answers "what's been getting blocked today?" (aggregated, ranked). Neither duplicates the other,
and neither is the firehose.

### 7.3 Decisions on the §6 open questions

| # | Question | Decision (rev 2) |
|---|----------|------------------|
| Q1 | KPI tile set | **Online now / Blocked now / Events (1h) / Blocked (1h).** Cumulative "today" volume is dropped from the dashboard — it is analytics and lives on `/usage/events`. (chunk 2) |
| Q2 | Throughput (#747) on the dashboard | **De-scoped from the redesign arc.** The NOW snapshot carries no bytes today; surfacing it needs an additive API field + a per-profile rate aggregation, and §1 showed NOW activity lines are already often bare IPs — throughput on noisy IoT cards adds density without answering "is everything OK?". #747 stays open as its **own** backend-gated follow-up, **not** part of chunks 1–5. |
| Q3 | Blocking-host → devices expansion | **Ship v1 without expansion** (ranked host list only). `stats.topBlocked` has no per-device linkage; adding it is an additive-API change. File separately only if wanted; do not block chunk 4. |
| Q4 | Idle / "online now" threshold | **Keep 5 min** (the current NOW activity window). "Online now" = devices active in the last 5 min. |
| Q5 | Mobile reflow | Reflow is derived from Tailwind classes, not a live narrow render. **Operator should sanity-check on a real phone before the iOS IA (#982) is locked.** Not a code blocker. |
| Q6 | Device ranking inside a card (#819) | **Rank by active-seconds over the NOW top-hosts window, not recency** — recency would surface a Sonos heartbeat above an actively-streaming MacBook. The top-3 cap + expander contains the IoT chatter visually. (chunk 3) |
| Q7 | IoT-noise classification | **Out of scope for v1.** No infra/IoT device taxonomy exists; building one is larger than this redesign. The active-seconds ranking + top-3 cap (Q6) is the v1 answer. "Online now" counts all active devices including appliances for v1; refining it depends on the same future classification work. File separately if pursued. |
| Q8 | `/usage/events` subtitle copy ("Per-query DNS / blocking decisions") | Out of scope here — minor copy fix on a different surface. Tracked as a standalone good-first-issue, not part of this arc. |

### 7.4 Locked implementation plan — filed sub-issues

Dependency-ordered; each independently shippable and sized so it does not re-touch the others.
TDD per `CLAUDE.md` (component/feature test first). Filed under umbrella #1148, Epic "Dashboard & UX".

| # | Sub-issue | Inputs closed | Layer | Notes |
|---|-----------|---------------|-------|-------|
| 1 | [#1833](https://github.com/wifihaven/wifihaven/issues/1833) — drop Recent Queries firehose + rename queries→connection events | #299, #823 | FE | Lowest risk; removes one load-time fetch. Keeps the #1338 feed. |
| 2 | [#1834](https://github.com/wifihaven/wifihaven/issues/1834) — KPI strip above NOW + status-first restate | #821, #299 | FE | Online now / Blocked now derive from the NOW snapshot. |
| 3 | [#1835](https://github.com/wifihaven/wifihaven/issues/1835) — NOW density: top-N devices + idle collapse + single freshness | #819, #820, #825 | FE | Highest-value chunk. Active-seconds ranking; per-session expander; `dataUpdatedAt` pill. |
| 4 | [#1836](https://github.com/wifihaven/wifihaven/issues/1836) — merge Top Blocked + Per Device → one Blocking activity (24h) panel | #822 | FE | One-line empty state; drops per-device volume. |
| 5 | [#1837](https://github.com/wifihaven/wifihaven/issues/1837) — rollup-back 24h stats + per-section skeletons | (#1098) | BE+FE | Lands last; rides #809 rollups; NOW paints independently of stats. |

Chunks 1–4 are pure frontend and could land in any order; the table order minimizes copy-merge
conflicts (terminology first) and ships the biggest win (chunk 3) once the page is already tidy.
Chunk 5 lands last because it touches the data path the earlier chunks render.

**Inputs not closed by the arc** (stay open as their own follow-ups): **#747** (throughput,
backend-gated — Q2), plus any optional spin-offs from Q3 (host→device expansion) / Q7 (device
classification) / Q8 (copy fix) if the operator chooses to pursue them.

Once this revision is signed off, #299/#819/#820/#821/#822/#823/#825 close as **superseded** by
the chunk PRs above (do not also implement them piecemeal).

### 7.5 Sequencing decision (2026-06-22) — paused, gated on websocket #1023

**The implementation is on hold until the websocket streaming transport
([#1023](https://github.com/wifihaven/wifihaven/issues/1023), priority #1) lands.** Operator
decision: the dashboard is the household's live status surface, so its live sections must be
**sourced from the streaming push**, not the current 10s `refetchInterval` poll. Building the
redesigned NOW / KPI / Most-Recently-Blocked sections on the polling path first would be
throwaway — the data plane changes underneath them when #1023 ships.

Concretely, this revises the original real-time framing (which said the websocket "could later
push these updates, but do not depend on it"): the redesign now **does** depend on it.

- **`useDashboardNow` (NOW snapshot), `useRecentBlocked` (Most Recently Blocked), and the
  derived "Online now / Blocked now" KPIs** become consumers of the streaming transport rather
  than independent 10s pollers. The single-freshness-pill decision (#825) still holds, now
  driven by last-push time instead of `dataUpdatedAt`.
- **The 24h aggregate panel (chunk 5 / #1837)** stays request/response on the rollup tables —
  it is not real-time and does not need the stream.
- **Layout/structure chunks (1–4: #1833–#1836)** are mostly transport-agnostic, but they are
  paused too rather than landed piecemeal: shipping the NOW density / KPI restate against the
  poller and then re-sourcing them against the stream is double work, and a half-migrated
  dashboard is worse than the current one.

**Status of the sub-issues:** #1833–#1837 are moved out of *In Progress* → **Blocked**
(`blocked` + `blocked-on-#1023`). They are kept (the plan is still correct); they resume once
#1023 provides the stream. When work restarts, fold the streaming-source wiring into chunks 2
and 3 (the live sections) as their first step, before the layout changes.

This note records the decision; no further dashboard PRs until #1023 lands.
