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
