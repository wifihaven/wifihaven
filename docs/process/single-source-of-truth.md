# Single source of truth

This was originally in AGENTS.md §"Single source of truth — never duplicate a decision or computation"; see AGENTS.md for the TOC.

## Single source of truth — never duplicate a decision or computation {#single-source-of-truth}

**The same logical quantity or decision must be computed in exactly ONE
place; every other consumer calls it.** "Minutes used today", "is this MAC
blocked", "the block reason", "engaged seconds for an app", a wire reason
string — each of these is one function, not a pattern re-derived at each
call site. Duplicated logic drifts: one copy gets updated, the others don't,
and the surfaces silently disagree. Every recent prod incident in this class
was a divergence — [#1531](https://github.com/wifihaven/wifihaven/issues/1531)
(displayed daily total didn't subtract exempt-from-daily app time while the
cap-evaluation path did) and
[#1539](https://github.com/wifihaven/wifihaven/issues/1539) (the SPA schedule
chip read a dead legacy table while enforcement read named schedules) were
both display-vs-enforcement drift.

This rule has **two** failure modes, not one:

1. **Re-derivation** — the same quantity or decision *computed* a second time
   (the #1531 / #1539 shape above).
2. **Drift-by-omission** — the same entity *written* by several parallel paths,
   one of which silently skips a required step (a seed, an invariant row, a
   cleanup) that its siblings perform. There is no duplicated *computation* to
   spot here — just a fork whose two arms have quietly diverged in what they
   write. [#2355](https://github.com/wifihaven/wifihaven/issues/2355) is the
   worked example: a second household-creation path
   (`HouseholdRepoLive.create`) seeded `households` + the global-sentinel
   profile but NOT the `household_billing` row that the canonical
   `approveAndProvision` path seeds, and shipped as a prod/staging "no billing
   record for this household" error. **Every household-creation path must seed
   `households` + `household_billing` + the global-sentinel profile together;
   there should be exactly one creation primitive.** The resolution is the same
   as for re-derivation — COLLAPSE the forks to one shared primitive both
   callers invoke (or TYPE-ENFORCE), never patch the missing step into the fork
   and leave two hand-maintained arms.

- **Reuse the existing primitive — don't re-derive.** Before adding a new
  path that computes such a value, search for the one that already does it
  and call it. The canonical ones present today include
  `TimeStatusService`'s day-state / `usedSecondsForProfile` /
  `usedSecondsByMac`, `Presence.appSecondsForProfile`, and
  `BlockReason.asWire` / `BlockReason.fromWire` for wire reason strings.
- **Resolve unavoidable proximity two ways.** **COLLAPSE** — extract a shared
  function, or have one path call the other. Or **TYPE-ENFORCE** — a shared
  sealed type, or a wire round-trip the compiler keeps exhaustive (e.g.
  `BlockReason.asWire`/`fromWire`). Treat any `must mirror X` / `same branch
  as Y` / `keep in sync` comment as a defect to remove, not a pattern to
  copy.
- **If two paths genuinely must stay separate, ACCEPT + TEST-PIN.** Add a
  test that fails the moment they diverge, so the coupling is enforced by CI
  rather than by a comment.
- **A "keep in sync" comment is never a valid resolution — least of all as a
  way to "address" a review finding.** Writing `// KEEP IN SYNC with …` or
  `// must stay byte-identical to …` does not fix duplication; it *is* the
  anti-pattern, and it silently re-fails the moment someone edits one copy.
  When a duplication finding is raised, the only acceptable responses are
  COLLAPSE, TYPE-ENFORCE, or ACCEPT + TEST-PIN — the review is not addressed
  until one of those lands. This applies to **cross-language / cross-file**
  string duplication (a shell comment, a Markdown doc, and a UI constant all
  holding the same literal), not only to Scala logic: those can't `import` each
  other, so the resolution is a TEST-PIN that reads the files and asserts they
  match. **Worked example:** the OpenWRT install one-liner appears in the
  `openwrt/install.sh` header, `docs/install-openwrt.md` §2, and the SPA
  `ROUTER_INSTALL_COMMAND`; their equality is pinned in
  `openwrt/test/install_spec.sh` (it extracts the canonical line from
  `install.sh` and asserts the other two contain it verbatim), so drift fails
  CI — no "keep in sync" comment anywhere.
- **Carve-out — intentional wire-shape redundancy is NOT a violation.** Where
  the architecture *mandates* duplication on the wire — the infra allowlist
  copied into every profile's `extraAllowed`
  ([#1311](https://github.com/wifihaven/wifihaven/issues/1311)), or the
  `profiles` map as a wire dedup aid — that is the correct shape, not a
  divergence to collapse. See the **"Redundancy and wire-shape are separate
  concerns"** bullet under the Architectural model in AGENTS.md; this rule and
  that one are consistent — wire-shape duplication is data, not a second copy
  of a decision.

The worked example and outstanding backlog is the audit umbrella
[#1532](https://github.com/wifihaven/wifihaven/issues/1532), which already
drove several collapses:
[#1544](https://github.com/wifihaven/wifihaven/issues/1544) (`decide()`
hand-mirrored the whole snapshot fold),
[#1545](https://github.com/wifihaven/wifihaven/issues/1545) (block-reason
strings in three hand-written copies, fixing a live mislabel), and
[#1546](https://github.com/wifihaven/wifihaven/issues/1546) (per-device totals
recomputed off the canonical total, fixing a live over-count).

### Worked example — the usage-report period lives at the agent, never in the API

The usage-report **period** — how often a router posts a `traffic_reports`
batch — is single-sourced at the agent:
`openwrt/files/etc/config/wifihaven` → `usage_report_interval` (default
**60s**, configurable; heading sub-minute as #1023 streams usage). The real
per-report window then rides **every** stored row as
`period_start`/`period_end`. **The API must never hold its own copy of that
period.**

It once did, and it drifted exactly as this rule predicts. The original agent
default was `300` (5 min, #101); #529 changed it to 60s; then
[#846](https://github.com/wifihaven/wifihaven/issues/846) hardcoded a
**5-minute** `raw` window into the API
(`UsageTraffic.floorTo(Raw) … % 300`, `stepOf(Raw) = ofMinutes(5)`) — five
days **after** the agent had moved off 300s. A false comment ("source rows are
at UTC 5-min boundaries already") cemented the wrong mental model. The API copy
silently disagreed with the real cadence, and the dashboard `raw` bandwidth
gauge read ~5× smoothed and divorced from `1m`
([#2018](https://github.com/wifihaven/wifihaven/issues/2018)).

The fix is the rule: derive the window from the row's real
`[period_start, period_end)` span — one helper, `UsageTraffic.windowFor`,
shared (COLLAPSE) by the `GET /api/usage/traffic` aggregate path and the
websocket live-edge push so they cannot disagree; `stepOf(Raw)` returns `None`
(raw has no fixed width) so callers *must* use the row span. A pinning test
(TEST-PIN) seeds two different report periods (37s and 90s) and asserts the
streamed/queried `raw` window equals each, failing the moment a fixed window is
re-hardcoded. A narrow CI guard
(`.github/scripts/check-usage-period-hardcode.sh`) rejects a newly-added
`% 300` / `ofMinutes(5)` / bare `300` literal in the usage/traffic bucket +
rollup paths, pointing back here
([#2020](https://github.com/wifihaven/wifihaven/issues/2020)). Same shape as a
display bucket (10m/1h/…) is a legitimate *display* constant — only the
*source-period* must never be copied.
