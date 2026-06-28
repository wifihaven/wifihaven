# Verify and cite system facts

A standing rule for anyone — human or agent — working in this repo. It is the
process guardrail against the failure that produced the wrong 5-minute `raw`
window ([#2018](https://github.com/wifihaven/wifihaven/issues/2018) /
[#2020](https://github.com/wifihaven/wifihaven/issues/2020)): a constant and
several "how it works" claims were **asserted without being traced to their
authoritative source**, and were wrong.

## Stating facts about the system {#verify-and-cite}

Any statement of a **constant's value, a cadence / interval / window, a schema
fact, or "how subsystem X works"** must be traced to and cited from its
**authoritative source in the repo** — the config file, the code that sets it,
or the migration. Not from a comment. Not from memory. Not from inference. If
you write it down, you must be able to point at the line it came from.

- **Cite the source.** When you assert a value or a behavior, name where it
  lives: `openwrt/files/etc/config/wifihaven` → `usage_report_interval`, the
  migration `V{n}__….sql`, the function that computes it. A claim with no
  traceable source is a guess wearing a fact's clothing.

- **Comments and docstrings are NOT authoritative.** A comment can be the bug —
  it drifts from the code it annotates, or it was wrong when written. Verify the
  claim against the actual config / code before repeating it. The false comment
  *"source rows are at UTC 5-min boundaries already"* is exactly how the #2018
  wrong mental model got cemented and echoed forward.

- **Single-source constants — derive, never re-hardcode.** A value that already
  lives in one authoritative place must never be copied as a literal elsewhere;
  read it or derive it. The usage-report period is single-sourced at the agent
  (`usage_report_interval`, default 60s) and rides every stored row as
  `period_start`/`period_end`; the API must hold no copy. Re-hardcoding such a
  value is a single-source-of-truth violation — see
  [`single-source-of-truth.md`](single-source-of-truth.md#single-source-of-truth).

- **When you can't verify, say "unknown / unverified" and stop.** Do not
  construct a plausible explanation to fill the gap. A confident wrong answer is
  worse than "I don't know — let me check git." If the source isn't traceable in
  the time you have, say so explicitly rather than inventing a backstory for why
  the code is the way it is.

- **Own mistakes by fact.** Attribute by `git blame`, not by vibe. Don't
  distance yourself with "legacy / pre-existing / not me" — it's all our code.
  Find who/what/when actually introduced it and say that.

## Worked cautionary example — the 5-minute `raw` window (#2018)

The dashboard `raw` bandwidth gauge was bucketed on a hardcoded **5-minute**
window (`UsageTraffic.floorTo(Raw) … % 300`, `stepOf(Raw) = ofMinutes(5)`). That
constant was copied from a **dead agent default**: the original
`usage_report_interval` was `300` (5 min,
[#101](https://github.com/wifihaven/wifihaven/issues/101)), but
[#529](https://github.com/wifihaven/wifihaven/issues/529) had already moved the
agent to **60s** before
[#846](https://github.com/wifihaven/wifihaven/issues/846) re-hardcoded the stale
`300` into the API. The full provenance — dates, PRs, the false comment that
cemented it — lives in the
[single-source-of-truth worked example](single-source-of-truth.md) and in the
[#2018](https://github.com/wifihaven/wifihaven/issues/2018) thread; treat those
as the authoritative record and verify against `git` before restating any of it
here.

Every guardrail in this doc would have caught it:

- **Cite the source / single-source it** → the period lives at the agent and on
  each row's `[period_start, period_end)`; the API copy was unsourced.
- **Comments aren't authoritative** → the "5-min boundaries already" comment was
  itself the bug, trusted as fact.
- **Say "unknown," don't confabulate** → when asked *why* it was 5 min, a
  plausible-but-false backstory ("the router reported in 5-min batches back then,
  so it was correct at the time") was manufactured instead of checking git.

The fix derives the window from the row's real `[period_start, period_end)` span
(one shared helper, `UsageTraffic.windowFor`), and a CI guard
(`.github/scripts/check-usage-period-hardcode.sh`) now rejects a newly-added
`% 300` / `ofMinutes(5)` / bare `300` in the usage/traffic paths.

## Enforcement

The PR-review checklist
([`docs/pr-review-checklist.md`](../pr-review-checklist.md)) carries an
**"Unsourced facts & magic constants"** review dimension that flags exactly this
class — magic constants not traced to source, re-hardcoded single-sourced
values, and comments/PR-body claims the diff doesn't substantiate. This doc is
the convention; that dimension is its automated enforcement on every PR.
