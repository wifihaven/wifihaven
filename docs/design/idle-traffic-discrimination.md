# Idle-traffic discrimination — ambient-host baseline + engagement-anchor gate (#2077)

**Status:** accepted design, 2026-07-05. Evidence below is from prod
(`wifihaven-pg-prod`, READ-ONLY), 2026-06-21 → 2026-07-05, the three kid iPads
(Octavius `a6:05:9a:63:83:af`, Prima `04:72:ef:d6:e4:5a`, Quintus
`26:74:fc:f9:4e:9e`).

## Problem

Devices sitting idle (iPad on a shelf, overnight) emit constant background
traffic — push, iCloud sync/photo upload, OS-update metadata, telemetry, widget
feed polls — and some of it still credits as kid screen-time. Prior passes
(#714 byte-floor, #1503/#1525 InfraHosts, #1666 anchor bytes, #1897 shared
hosts, #2025/#2068 activity windows) each removed a class of inflation; this
design addresses the residual structurally instead of by another list append.

## Method

Exported all 13,540 `traffic_reports` rows for the three kid iPads over 14
days and replayed them through an offline mirror of the exact
`Presence.scala` pipeline (spanOf with the #2068 degenerate-envelope
fallback, `effectiveGap` 2×R clamp, per-(mac,host) stitch, device union,
`InfraHosts` suppression, the prod 10 KB byte floor, #1506
attribution-beats-suppression, exempt-from-daily patterns from the profiles'
real app assignments). The replay reconciles with the server's own
`time_used_daily` rollups almost exactly (Octavius 06-26: 46 = 46, 06-27:
51 = 51, 06-28: 59 = 59; Quintus 07-01: 103 = 103), so its numbers can be
read as "what prod credits".

Labels: 01:00–05:00 America/Denver = certain-idle; known engagement bursts
(LEGO 06-21 ≈ 16:50 on all three iPads, gimkit 06-27 for Octavius) and
diverse high-byte daytime windows = active.

> Caveat honored: `active_start`/`active_end` on these prod rows are mostly
> degenerate (pre-#2068-agent-fix fleet: e.g. Quintus 07-05: 296 of 414 rows
> degenerate), so the replay's `spanOf` falls back to the flush window
> exactly as the shipped server code does. Envelope values were never
> treated as signal.

## What the data shows

### 1. The current pipeline still credits idle time, and it is not a threshold problem

Overnight (01–05 local), across 14 nights × 3 devices, **37 distinct hosts
survive** the enabled 10 KB heartbeat byte floor — every one of them Apple OS
background (`gsp-ssl.ls.apple.com`, `ocsp2.apple.com` at up to 67 MB,
`mesu.apple.com`, `iosapps.itunes.apple.com` at 34 MB, …). Many are already
on `InfraHosts` and get suppressed; the replayed *residual* that credits as
screen time is **~50 phantom minutes / 3 devices / 14 nights**, all from
hosts *not yet* on the list:

| leaking host (overnight, survives 10 KB floor, not on InfraHosts) | nights seen |
|---|---|
| `valid.apple.com`, `cl2/cl3/cl4.apple.com` (iCloud upload lanes) | recurring |
| `cds.apple.com`, `cstat.cdn-apple.com`, `ipcdn.apple.com` | recurring |
| `app-site-association.cdn-apple.com`, `device-config.pcms.apple.com` | recurring |
| `weatherkit.apple.com`, `help.apple.com`, `known-issues.apple.com`, `updates.cdn-apple.com`, `www.google-analytics.com` | occasional |

This is the **fourth** curation iteration of exactly this shape
(#1629 → #1669/#1672 → #1694 → this list). Static host-class curation
(direction 4) is a treadmill: Apple mints background hostnames faster than we
enumerate them.

Daytime is worse: **~26 % of all currently-credited kid screen-time minutes
(~800 of ~1,860 min over the window) sit in spans of ≤ 2 distinct hosts with
no app-attributed row** — the same background class, just during the day
(photo-sync uploads to `edge-033.usden6.icloud-content.com`,
`token.safebrowsing.apple`, `c.apple.news` widget polls, `signaler-pa` /
`oauthaccountmanager.googleapis.com`, …). ~11 min/device/day of "kid used the
internet" that never happened.

### 2. Idle and active traffic are separable by co-presence topology, not by bytes

Per (device, 10-minute window), idle vs active distributions on the real
samples:

| feature (per 10-min window) | idle p50 | idle p90 | idle max | active p50 | active p90 |
|---|---|---|---|---|---|
| rows | 1 | 3 | 11 | 37 | 79 |
| distinct hosts | 1 | 3 | 8 | 24 | 55 |
| distinct non-infra hosts | **0** | **1** | 4 | **12** | 25 |
| non-infra hosts ≥ 10 KB | 0 | 1 | 3 | 8 | 19 |
| bytes | 10 KB | 184 KB | **67 MB** | 15.5 MB | 126 MB |

Bytes do NOT separate (`ocsp2.apple.com` moved 67 MB overnight; iCloud photo
upload moves 64–78 MB while the kid does nothing). **Host-diversity
co-presence separates almost perfectly**: real use lights up dozens of hosts
at once; idle traffic arrives as *temporally isolated one-or-two-host
bursts*. That is the discriminating structure.

But a naive span-level diversity gate ("drop spans with ≤ K distinct hosts,
no app row") has real-use casualties in its tail, which is why we don't stop
there:

| gate | overnight phantom dropped (of 50.4 min) | daytime dropped | real-use casualties observed |
|---|---|---|---|
| K=1 | 38.3 | 519 | none observed |
| K=2 | **50.4 (100 %)** | 800 | a 17-min 2-host LEGO tail session (`imageresizer.prod.dbix.i.lego.com` + `scout.services.lego.com`, 9.8 MB); a 21-min 77 MB 2-host span shaped like a FaceTime call (single IP-literal peer + oauth) |

A kid deep in one app with cached assets, or a FaceTime call (one IP-literal
peer), legitimately touches 1–2 hosts. Diversity alone can't tell that from
`valid.apple.com` polling — but *history* can: LEGO hosts appear in isolation
once; `valid.apple.com` appears in isolation day after day.

### 3. Isolation-learned ambient baseline + anchor gate: clean separation

**Learning rule:** a host is *ambient* for the household when it appears in
**isolated** device-presence spans (≤ 2 distinct hosts in the span, no
app-attributed row) on **≥ 3 distinct days** inside a trailing 14-day window.
"Habitually alone ⇒ background": real usage essentially always co-occurs with
other traffic, so real-use hosts don't accumulate isolated days.

On the prod sample this learns 40 hosts — all verifiably background: the
whole leaking Apple tail above, plus daytime-only background that clock-based
(quiet-hours) profiling would miss: `edge-033.usden6.icloud-content.com` /
`gateway-asset.icloud-content.com` (photo sync), `token.safebrowsing.apple`,
`c.apple.news`, `stocks-data-service.apple.com`,
`excess-ga.duolingo.com` / `ios-api-cf.duolingo.com` (Duolingo's background
telemetry — learned while real Duolingo sessions stay counted, see gate
below), `signaler-pa.googleapis.com`, `photosdata-pa.googleapis.com`,
`one.one.one.one`, and — correctly — `api.wifihaven.net` itself.

**Gate rule:** a device-level merged presence span **counts toward screen
time iff it contains at least one anchor row**, where anchor = app-attributed
(the existing #1506 seam) **or** non-ambient host. Unanchored spans are
dropped whole; anchored spans count **in full** — ambient rows inside an
anchored span still contribute their seconds, so a real session's total is
never shaved (no re-opening of the #1446/#2068 undercount class).

Results on the 14-day sample (in-sample):

| surface | before | after gate | casualties |
|---|---|---|---|
| overnight (01–05) phantom | 50.4 min | **1.7 min** (one `known-issues.apple.com` span, seen only 1 day — below the learning threshold; more history learns it) | — |
| daytime background spans | 492 min credited | dropped | every dropped span ≥ 3 min manually verified: 100 % Apple/Google background or iCloud photo-sync |
| LEGO sessions (41.5 / 32.9 / 27.7 min, incl. the 17-min 2-host tail) | counted | **counted** | none |
| gimkit 29.6-min session (app-attributed) | counted | **counted** (structurally cannot be dropped) | none |
| FaceTime-shaped 77 MB span | counted | **counted** (IP-literal peer is never learned ambient) | none |

**Causal validation** (learn on 06-21→06-29 only, evaluate 06-30→07-05):

| `ambientMinIsolatedDays` | learned hosts | holdout phantom dropped (day + night) | holdout kept | casualty check |
|---|---|---|---|---|
| 2 | 41 | 165 + 17 min | 283 + 1 min | clean |
| **3 (default)** | 23 | 139 + 17 min | 309 + 1 min | clean (all Apple/Google background) |
| 4 | 14 | 92 + 7 min | 356 + **12 min phantom survives** | too conservative |

## Directions evaluated (from #2077)

1. **Traffic-shape classification** — *subsumed*. The learned-isolation rule
   *is* a traffic-shape classifier, operating on the one feature the data
   shows separates (co-presence topology over time). Explicit
   cadence-regularity / byte-variance features add tuning surface and
   opacity without adding separation (bytes provably don't separate, §2).
2. **Interaction-gated presence** — *adopted*, as the anchor gate. The pure
   per-window form (N hosts AND byte envelope) has the K=2 casualties above;
   anchoring on "app-attributed OR non-ambient" fixes its tail.
3. **Idle-envelope baselining** — *adopted*, as the learning rule. Chosen
   *structural* (isolation) rather than *clock-based* (overnight profiling):
   clock-based learning misses daytime-only background (photo sync, safe-
   browsing token) which was 4× the overnight leak, and clock-based learning
   is poisonable by night-sneaking (a kid on LEGO at 2 AM would teach the
   system LEGO is ambient; under isolation-learning that session is diverse,
   so nothing is learned — and it still counts as screen time, which is the
   correct parental outcome).
4. **Host-class weighting** — *rejected as primary* (kept as tier 0: the
   static `InfraHosts` list stays and short-circuits the common cases). Four
   consecutive curation iterations still leak 37 overnight hosts; the class
   system's marginal value over learned-ambient is negative (two lists to
   drift instead of one list + one learner). **Amended by #2177** (see
   [§2177-residual](#2177-residual)): adopted in a deliberately narrow form —
   an anchor-eligibility-only class (`InfraHosts.cloudBackground`) for the
   burst families the isolation learner *structurally cannot* learn. Still
   not primary: the learner remains the general mechanism; the class covers
   exactly its blind spot.
5. **Screen-on proxy signals** (DNS query rate/diversity, QUIC churn, flow
   concurrency) — *deferred*. Requires new agent-side signals on the wire;
   co-presence diversity extracted server-side from data we already collect
   achieves the needed discrimination. If a future pass needs sub-minute
   screen-on fidelity, this is the direction to revisit (additive wire
   fields, per §wire-contract).

## Design

### Mechanism (server-side only; no wire or agent change)

Two pieces, both in the API server, composing with the existing pipeline:

1. **Ambient learner** (new daily job alongside the existing rollup jobs):
   for the previous day — one day *label*, derived in UTC and shared by every
   household so their contributions to a `(host, day)` row land in the same
   write, matched against each household's own local `traffic_reports.date`
   (#2553) — per device, compute counted presence rows and
   merged device spans exactly as the daily total does (same
   `Presence` primitives, same app-attribution inputs), find *isolated*
   spans (distinct hosts ≤ `ambientIsolationMaxHosts`, no app-attributed
   row), and upsert one row per (host, day) into `ambient_host_days`.
   The *ambient set* at read time = hosts with ≥ `ambientMinIsolatedDays`
   distinct days within the trailing `ambientLearningWindowDays`. Days
   outside the window are pruned by the job. Incremental (one day per run),
   partition-pruned, no unbounded scan.
2. **Anchor gate** (one new `Presence` entry point, single-source-of-truth):
   given counted rows + the ambient set + app host patterns, compute the
   device-level merged spans, keep rows overlapping an *anchored* span
   (anchor = app-attributed row or non-ambient-host row), drop the rest.
   Applied at the presence-row boundary **before** every counting surface
   (daily total, per-app, per-site, per-host breakdowns, rollups), so
   counting and ranking cannot diverge (the #1532 lesson). Gate disabled ⇒
   identity function.

Invariants preserved (guard tests in the code PR):

- **minutes ≤ wall-clock** — the gate only removes spans; every surface is
  monotonically ≤ its pre-gate value.
- **app ⊆ profile** — any span containing app-attributed rows is anchored by
  definition, so per-app time can never exceed the gated profile total.
- **a real session credits ~real minutes** — anchored spans count in full;
  ambient rows inside them still contribute (LEGO/gimkit fixtures).
- **idle-overnight ≈ 0** — fixture from the prod overnight shape.
- **fail-open** — an empty/missing ambient table (fresh install, job not yet
  run) means fewer hosts are ambient ⇒ behavior degrades toward today's,
  never toward over-suppression.

### Tuning knobs (presence_tuning_settings style, V52 precedent)

`household_settings` columns, operator-adjustable via the existing settings
API (additive request fields, defaults preserve current behavior):

| column | default | meaning |
|---|---|---|
| `ambient_gate_enabled` | `false` | master switch for the anchor gate (learning always runs so the operator can inspect what *would* be ambient before enabling) |
| `ambient_isolation_max_hosts` | `2` | span diversity ≤ this ⇒ "isolated" for learning |
| `ambient_min_isolated_days` | `3` | distinct isolated days before a host becomes ambient |
| `ambient_learning_window_days` | `14` | trailing window for day counts |

Defaults are the causally-validated point above. Shipping default-off gives
the operator an inspection period on prod (the learned set is visible via the
explain surface) before flipping the gate.

### Observability (§instrument-new-functionality)

- `presence_ambient_hosts` gauge — size of the current learned set.
- `presence_ambient_spans_dropped_total` counter — spans dropped by the gate
  (bounded: no per-host/mac labels), the over-suppression watchdog: a
  sustained rise with flat screen-time means the learner is eating real
  sessions.
- `presence_ambient_learn_runs_total{outcome}` counter, outcome ∈ {ok, error}.
- Grafana panel additions under `deploy/grafana/dashboards/` in the same PR.
- Explain surface: `GET /api/presence/ambient-hosts` (admin) — the learned
  set with day counts and last-isolated dates, mirroring
  `heartbeat-explain`'s tune-before-enable workflow.

### What happens when the #2068 agent fix fully deploys

Nothing changes in this design's math: tighter activity envelopes shrink the
spans the gate evaluates but not the isolation topology (a background host
alone in a window is alone regardless of span width). The gate composes with
`spanOf` rather than replacing it.

### Poisoning / failure modes considered

- *Solo-use real host learned ambient* (e.g. `www.mathplayground.com`, seen
  isolated on 2 days in the sample — below threshold): requires ≥ 3 days of
  the kid using **only** that host with **zero** co-traffic, which the data
  shows essentially doesn't happen (real use lights up co-hosts). If it ever
  does: the host is visible in the explain surface, and the canonical remedy
  is authoring an app template for it — app attribution beats ambient
  structurally (#1506).
- *Fleet-wide agent wedge* (the #2068 class): a wedged agent emitting
  single-host reports could teach false ambient entries. The learner keys on
  *distinct days*; a transient wedge contributes ≤ 1–2 days, below
  threshold, and entries age out of the window.
- *New device joins*: its background hosts take ~3 days to learn; until then
  behavior is today's (fail-open, slight over-count, never under).

## Rollout

1. **PR A (this doc + schema-only migration, §migrations-back-compat):**
   V63 — the four `household_settings` columns + `ambient_host_days` table.
   Inert: nothing reads them until PR B.
2. **PR B (code):** `Presence.anchorGate` + learner job + settings plumbing +
   explain endpoint + metrics + Grafana + guard tests (TDD; failing tests as
   their own first commit). Feature tests on embedded Postgres, injected
   Clock, no repo mocks.
3. Operator inspects `GET /api/presence/ambient-hosts` on prod after ~3 days
   of learning, then flips `ambient_gate_enabled`.

## #2177 — residual phantom after rollout: the co-occurring-burst gap {#2177-residual}

Post-rollout observation (gate enabled on prod 2026-07-12; learner healthy —
57 learned hosts across the household, all verified background) showed the
gate working as designed yet still crediting a residual phantom on the kid
iPads. Replay of prod `traffic_reports` for 2026-07-06→13 (offline mirror of
the `Presence` pipeline, scratchpad `replay.py`/`classtest.py`):

| device | raw (gate off) | #2077 gate | residual phantom shape |
|---|---|---|---|
| Octavius | 53 min | 46 min | morning wakeup bursts, zero interaction |
| Prima | 80 min | 78 min | (mostly real use — Math Academy) |
| Quintus | 261 min | 114 min | wakeup bursts + sync lanes, kid asleep/at school |

**Root cause is structural, not tuning.** The isolation learner classifies a
host ambient only when it appears in *isolated* spans (≤ 2 distinct hosts) on
≥ 3 distinct days. The residual anchors — App Store background polls
(`p46-buy.apps.apple.com`), iCloud sync lanes (`*.icloud-content.com`),
Google private APIs (`signaler-pa.googleapis.com` and the rest of the
`-pa.googleapis.com` family), OAuth token refresh, Apple CDN
(`cstat.cdn-apple.com`), and Apple-infra IP literals (`17.253.x.x`) — fire in
**dense co-occurring bursts** (device wakeup, ~06:30–08:00): five to fifteen
hosts inside one merged span. No member of the burst ever appears isolated,
so no isolated day ever accrues, and per-host learning can never catch them —
at any threshold. Lowering `ambient_min_isolated_days` cannot close this gap;
it only erodes the safety margin on genuinely-isolated real use.

**Fix (#2177): two class-level anchor tiers, composed with the learner.**

1. `InfraHosts.cloudBackground` — apex/suffix FAMILIES of first-party-cloud
   background endpoints (App Store/media API, iCloud content lanes,
   `-pa.googleapis.com`, OAuth/token control plane, Firebase Analytics,
   telemetry beacons). A host on the class cannot be the *sole anchor* of a
   span. Class-level naming is what beats the curation treadmill: one
   `apps.apple.com` entry covers every present and future `pNN-buy` shard.
2. IP-literal / label rows anchor only when the span's non-FQDN rows
   together move > `Presence.IpAnchorSpanBytes` (5 MB) — FQDN co-traffic is
   excluded from the sum, so heavy class-host volume cannot launder an
   anchor onto a low-byte IP peer. Real IP-literal sessions are heavy
   (the FaceTime span: 77 MB / 21 min); the phantom bursts' Apple-infra IP
   rows carried ≤ 0.5 MB. 5 MB sits an order of magnitude clear of both.

Both tiers are **anchor-eligibility only** — never row suppression. A class
row inside a genuinely-anchored span still counts in full, app attribution
(#1506) still beats the class, and everything rides the existing
`ambient_gate_enabled` kill-switch (off ⇒ identity). The gate remains
only-ever-removes, so the #1446/#2068 undercount class stays closed.

Replay of the shipped rule set on the same window:

| device | #2077 gate | + #2177 class tiers | verified survivors |
|---|---|---|---|
| Octavius | 46 min | **37 min** | gimkit + Duolingo sessions intact (incl. a 199 MB / 18.5 min Duolingo span) |
| Prima | 78 min | **76 min** | all Math Academy sessions intact to the minute |
| Quintus | 114 min | **80 min** | Sweetwater/YouTube/1Password sessions intact |

Accepted residual (deliberate exclusions, real-use over aggressiveness):
`ios-api-cf.duolingo.com` (Duolingo's real lesson API — its solo spans are
plausibly genuine practice; an app template attributes them properly),
`lh3.googleusercontent.com` (user photos), `accounts.google.com`,
`www.apple.com`. These keep anchoring by design; the remaining path for them
is app templates, not suppression.

Boundary notes: the class deliberately excludes every user-facing surface,
`InfraHostsSpec` pins both membership and the exclusions, and
`AmbientGateSpec` pins the wakeup-burst/zero-learning, FaceTime-survives,
attribution-beats-class, and kill-switch behaviors. No schema change: the
class list is code-curated (same lifecycle as `InfraHosts.canonical`), and
the existing `presence_ambient_spans_dropped_total` counter + Grafana panels
observe the extended gate unchanged.

## #2274 — idle MacBook: background-sync tail + the single-sample inflation {#2274}

Operator report 2026-07-17: the Kids profile showed 31 min "usage today"
while its only device (a MacBook Pro, `ca:ef:a1:72:6a:a3`) sat lid-closed in a
cabinet all day. Read-only prod investigation (router DHCP lease renewed but L2
`STALE`, 0 live conntrack; `time/heartbeat-explain`): 46 macOS **Power-Nap**
wakes at a near-perfect ~15-min cadence, 06:06 → 00:23 next-day (incl.
overnight), all real bytes but zero interaction — background sync/telemetry
(Google Drive Desktop, iCloud, Serato/Brave/Adobe updaters). An **ACCRUAL**
over-count (correct MAC, real bytes, no engagement), NOT attribution. The
offline replay (`scratchpad/replay*.py`) reproduces prod exactly: current model
= 29–30 / 66 / 24 min for 07-17 idle / 07-11 real / 07-14 mixed (prod 31 / 66 /
27).

Two contributing mechanisms, established by replay:

1. **The dual-use core is already handled.** `docs.google.com`,
   `drive.google.com`, `ssl.gstatic.com`, `lh3.googleusercontent.com` are all
   learned-ambient — the #2091 learner works; they do not anchor. The earlier
   worry that host-class couldn't touch a dual-use core was an analysis error
   (the learned-ambient set had not been applied).

2. **A background-sync tail the class missed** — Serato/Brave/Adobe telemetry
   & updaters, Apple OS-config (`experiments.`/`sylvan.`/`tether.edge`/
   `device-config.pcms.`), Google `update.googleapis.com`. Like the #2177
   families these fire only in dense co-occurring wakeup bursts, so the learner
   structurally cannot learn them. **This PR extends `cloudBackground` with
   them** (idle 30 → 16 min; real-use day 66 → 66, untouched — zero real-use
   casualty). Same anchor-eligibility-only semantics and boundary discipline as
   #2177; `InfraHostsSpec` pins membership and the preserved boundary.

**The single-sample inflation (the deeper lever, deferred to #2287).** The
deployed agent is current (v0.3.23; #2024 + #2025 both live). But 91% of the
idle day's counted rows are **single-sample** (`activeSeconds = 10`) → a
degenerate `activeStart == activeEnd` envelope → `Presence.spanOf` #2068 falls
back to the full flush window (~90–148 s vs ~10 s of real activity), an 8.6×
per-row inflation. On the real day only 47% are single-sample, so
single-sample cleanly marks background wakes. But the obvious fix — shrinking
degenerate rows in `spanOf` — is **replay-proven unsafe**: it crushes the real
day 66 → 16 min, because real multi-host sessions rely on the wide flush windows
*overlapping* to bridge into one continuous session; shrinking rows shatters
that bridge and re-opens the #2016/#2068 undercount. The structurally-correct
fix is an **isolated-span** rule in the ambient gate (drop a single-report-
period, sub-wedge-width, non-app-attributed, non-IP-heavy merged span), which
needs a design doc + replay harness + broad real-use validation + operator
sign-off on the residual undercount — tracked as **#2287**.
