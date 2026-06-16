---
name: incident-investigation
description: Investigate a WifiHaven production incident in the right order — observe before theorize, code LAST. Invoke whenever debugging a live failure — "investigate this incident", "the /api/router/usage endpoint is failing", "errors in prod", "5xx spike", "debug this alert", "why is this route 400ing". Walks the evidence chain dashboards (Grafana) → API logs → router/device → code, classifies the failure from the signal before any code is read, and emits a structured incident record with a fast-mitigation path.
---

# Incident investigation — observe before theorize

This skill encodes how WifiHaven debugs a **live production incident**. The
single rule that makes it work: **gather the signal before forming a code
hypothesis, and read code LAST.** Evidence flows dashboards → logs → device,
and only then code.

Why the order is the whole point: in the #1569 incident the first instinct was
to theorize from the code — "DB contention / pool exhaustion" — *before*
looking at the signal. One log line (intermittent **~13 ms 400s**) falsified
that instantly: 13 ms means no DB round-trip, and a 400 is decode/validation,
not contention. A code-first guess wastes time and aims the fix at the wrong
subsystem. Walking the evidence first makes the investigation both faster and
correct.

This file is the **process**. The **data** lives elsewhere and is read live —
do not duplicate it here:

- Grafana dashboards (checked-in JSON) →
  [`deploy/grafana/dashboards/`](../../../deploy/grafana/dashboards/);
  series are emitted by `AppMetrics` (`api/src/metrics/Metrics.scala`).
- Status meanings, board maintenance →
  [`docs/process/project-board.md`](../../../docs/process/project-board.md).
  Live `Epic` taxonomy + field/option IDs: `gh project field-list 1 --owner wifihaven --format json`
  (board at https://github.com/orgs/wifihaven/projects/1).
- Prod-access + cred-masking conventions, router (`router.lan`) access →
  [`AGENTS.md`](../../../AGENTS.md) (§ *Safety rules for running EXPLAIN
  against prod*, § *Validate query performance before merge*) and
  [`docs/operations.md`](../../../docs/operations.md).

When this skill and those docs disagree, **those docs win** — they are the
source of truth; update this skill if the process itself changed.

---

## Step 1 — Dashboards first (Grafana). Characterize the failure from the signal.

Grafana lives at **wifihaven.grafana.net**; the dashboards are checked in under
[`deploy/grafana/dashboards/`](../../../deploy/grafana/dashboards/)
(`api-health.json`, `api-self-metrics.json`, `data-quality-ingest.json`,
`db-health.json`, `enforcement.json`, `rollup-health.json`,
`router-fleet.json`). Use them to **form** the hypothesis — never to confirm a
code guess you already made.

Before reading any code, pin down four things from the signal:

1. **Which endpoint/metric is alarming**, and its **status-code breakdown** —
   4xx vs 5xx vs timeouts.
2. **Latency** on that route. Status-code + response-time together disambiguate
   the **failure class**, which is the most important early decision:

   | Signal | Likely class |
   |--------|--------------|
   | Fast **4xx** (low ms) | **validation / decode** — bad request shape, not the server. *(The #1569 tell.)* |
   | **5xx** + slow | **defect / contention** — exception, lock, or downstream stall. |
   | Rising latency / **timeouts**, climbing | **contention / pool** — DB, HikariCP exhaustion, slow query. |
   | 4xx/5xx **rate** rising at a step | a **deploy** flipped behaviour — go to onset. |

3. **Error rate** — is it all traffic on that route, or a fraction (intermittent
   ⇒ data-dependent, e.g. one bad record in a batch)?
4. **Onset time**, correlated with the **deploy timeline**. Pull recent merges
   and line them up against the rate's step-change:

   ```bash
   gh pr list --repo wifihaven/wifihaven --state merged --limit 40 \
     --json number,title,mergedAt,mergeCommit
   ```

   A clean onset-vs-merge correlation frequently **names the culprit PR by
   itself** — and gives you the fast-mitigation lever (Step 5).

Output of this step: failure class + alarming route + onset + suspected deploy.
Do **not** open the source yet.

---

## Step 2 — API logs (read-only on prod). Find the actual error.

Pull the API logs for the **failing route around the onset window** and read
the real error / stack / message. Match what you find against the failure class
from Step 1 — if they disagree, trust the log and re-classify.

**This step is service-agnostic by design.** The log *backend* will change; the
investigation step does not:

- **Today: Render.** Logs come from the Render service. The Render root key is
  out-of-band **in memory** — load it into a shell var, **never echo, print, or
  commit it**, and mask it in any captured output. Read-only on prod always
  (see `AGENTS.md` → *Safety rules for running EXPLAIN against prod* for the
  same cred-masking / read-only discipline).
- **Future: structured-log indexing service.** When centralized structured
  logging lands, this step becomes "query the log-indexing service for the
  route + window" instead of tailing Render. **This is a dependency to update
  when that service ships** — revise this section then.

**If the error is NOT in the logs, that is itself a finding.** A failure the
operator cannot see in logs is an observability gap. Capture it and **file an
observability-gap follow-up**, citing the centralized-error-handler work
([#1570](https://github.com/wifihaven/wifihaven/issues/1570)) — consistent
logging/status/body/metrics across endpoints is exactly what closes this class
of gap. (This was the #1569 trap: usage-decode errors appeared only in the 400
response body, never in the logs.)

---

## Step 3 — Router / device side. Capture the raw request the agent saw.

If the failing surface is the router↔API wire (policy, usage, events) or
enforcement, get the device-side view. **Per convention the operator / a
spawned session runs router commands** (`docs/operations.md`); they SSH the
gateway **read-only** (`root@router.lan`) and capture:

- `logread` and the wifihaven agent log.
- The **actual request/response the agent saw** — e.g. the body of the 400 the
  API returned. The failing field is usually right there, before any code is
  read.
- If enforcement-related: `nftables` rules, `conntrack`, `dnsmasq` state /
  ipset population (per the architectural model — DNS attributes, nftables
  enforces).

Capture the **raw error / failing field verbatim** before theorizing about its
cause.

---

## Step 4 — ONLY THEN, the code — with full evidence in hand.

Now open the source. You arrive with the exact error, the failing field, the
metric class, and the onset↔deploy correlation — and **that evidence drives the
code read**, not the reverse. Go straight to the subsystem the signal points
at, find the line that produces the captured error, and confirm the root cause.

Common payoffs once the evidence is in hand:

- A **decode/validation 400** → find the decoder; check whether it logs before
  rejecting and whether one bad record fails a whole batch (the #1569 shape).
- A **contention/latency** signal → the offending query / lock; this feeds the
  **EXPLAIN-before-merge** rule (`AGENTS.md` →
  *Validate query performance before merge* `{#query-explain-before-merge}`).

---

## Cross-cutting principles

- **Observe before theorize.** Never let a code hypothesis precede the signal.
- **Disambiguate failure CLASS first** (defect vs contention vs validation vs
  latency) from status-code + response-time — before touching code.
- **Capture the actual error body / stack BEFORE proposing a cause.**
- **Read-only on prod; mask creds; never echo secrets.** Same discipline for
  Render logs as for prod `EXPLAIN` (`AGENTS.md`).
- **Fast mitigation beats root-cause speed.** If onset correlates with a deploy,
  **revert the correlated PR** (CD redeploys) to stop the bleeding *while* you
  root-cause. Mitigation and diagnosis are parallel tracks, not sequential.
- **A missing log is a finding**, not a dead end → observability-gap follow-up
  (#1570).
- **Single source of truth.** A fix that adds a second decision/computation path
  is a new bug surface; collapse, don't duplicate (`AGENTS.md`).

---

## Worked example — #1569 (the canonical walk)

| Step | What the evidence showed |
|------|--------------------------|
| **1 — Dashboards** | The usage-ingest route showed **fast 400s**, not 5xx or rising latency. Class ⇒ **validation/decode** — immediately falsifies the "DB contention / pool exhaustion" guess (13 ms = no DB; 400 = decode). Intermittent rate ⇒ data-dependent. |
| **2 — API logs** | The decode error was **NOT in the API logs** — only in the 400 response body. → **observability gap**, filed against the centralized error handler (#1570). |
| **3 — Router** | The agent's **400 response body** carried the failing field — the malformed record in the usage batch, captured verbatim. |
| **4 — Code** | Read of `RouterIngestRoutes` confirmed: the batch decoder **rejects without logging** and **fails the whole batch** on one bad record. |
| **Mitigation/fix** | **Log** the decode error + **tolerate** bad records (drop-and-continue instead of whole-batch reject). |

The throughline: the signal (fast 400) classified the failure correctly in
seconds; the code-first guess (contention) would have sent the investigation
down the wrong subsystem entirely.

---

## Output — structured incident record

Emit a single glanceable record, in evidence order:

```
INCIDENT: <route/metric> — <one-line symptom>
SIGNAL:        <status-code breakdown + latency + rate + onset; failure CLASS>
               <onset vs deploy correlation — suspected PR #NNN>
EVIDENCE:
  dashboard:   <which panel/series, what it showed>
  logs:        <actual error/stack — or "NOT LOGGED → observability gap">
  device:      <agent log / 400 body / nft-conntrack — raw failing field>
HYPOTHESIS:    <root cause the evidence points to>
CONFIRMATION:  <the code line / query plan that proves it>
FIX:           <the change>
MITIGATION:    <revert PR #NNN now / feature-flag / tolerate-and-log>
```

Then **file the incident issue** and set the board (per
[`docs/process/project-board.md`](../../../docs/process/project-board.md)):

- **Epic** = `Observability/Metrics` (resolve the exact option name + ID via
  `gh project field-list 1 --owner wifihaven --format json`);
  **Status** = `In Progress` while active.
- File any **observability-gap follow-ups** surfaced in Step 2 (cite #1570),
  and route **query-plan/contention** findings into the EXPLAIN-before-merge
  rule.
- **Fast-mitigation** if onset correlated with a deploy: revert the correlated
  PR (CD redeploys) and note it on the incident issue while root-causing.

Keep it a record, not a narrative — signal → evidence → hypothesis →
confirmation → fix/mitigation.
