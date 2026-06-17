# Project board — every new issue gets an Epic

This was originally in AGENTS.md §"Project board"; see AGENTS.md for the TOC.

## The board

- **Title:** `WifiHaven`
- **Owner:** `wifihaven` (org-level Project v2)
- **Number:** 1
- **URL:** https://github.com/orgs/wifihaven/projects/1

The board is **managed directly in the GitHub UI**. The live `Epic` taxonomy
and the field/option IDs are **not** mirrored in this repo — they drift the
moment anything is added or renamed. Fetch them live:

```sh
gh project field-list 1 --owner wifihaven --format json
```

That command returns every field (`Status`, `Epic`, …) with its current
option list and IDs, which is what every script and `gh project item-edit`
call needs anyway.

## `Status` field meanings

| Status | Meaning |
|--------|---------|
| `Todo` | Not started. The default for any issue without an `in-progress`/`blocked` signal. |
| `In Progress` | Actively being worked. Mirrors the `in-progress` label. |
| `Blocked` | Waiting on something else. Mirrors the `blocked` label or any `blocked-on-#NNN` label. |
| `Done` | Closed/shipped. |

**Convention:** keep `Status` in agreement with the issue labels — when you
move an issue to `In Progress` or `Blocked` on the board, set the matching
`in-progress` / `blocked` label (and vice versa) so the two read surfaces
don't drift.

## Umbrellas are native sub-issue parents

Umbrella issues are wired as real **parents** via GitHub's sub-issue feature
(not just prose lists in the body), so the child issues nest under them on
the board and on the issue page. An umbrella is the canonical "this set of
issues gates on each other" mechanism — children inherit the parent's
relationship for free.

To add a child to an umbrella, use the sub-issue control on the issue page
(or the GraphQL `addSubIssue` mutation). When an umbrella has no open parent
issue, group its children via the `Epic` field instead until one exists.

## Maintaining the board

New repo issues are **auto-added** to the board (Status defaults to `Todo`)
by the project's built-in *Auto-add to project* workflow, so they land on
the board even when no agent is in the loop. The `Epic` is **not** set
automatically — set it on triage.

**When you file or triage a new issue, make sure it has the right `Epic`.**
Don't leave a new issue sitting in the `Other` epic when a real thread fits
(and if it somehow isn't on the board, add it). Steps:

1. Add it if missing:
   `gh project item-add 1 --owner wifihaven --url <issue-url>`.
2. Set `Epic` to the matching thread from the live taxonomy
   (`gh project field-list 1 --owner wifihaven --format json`) — judge from
   the title, labels, and body.
3. Set `Status`: `In Progress` if it carries the `in-progress` label,
   `Blocked` if `blocked` / `blocked-on-#NNN`, otherwise `Todo`.

**Starting a large new thread? Create a new `Epic` option for it** instead
of forcing it into `Other`. Only do this for a thread big enough to deserve
its own swimlane — a new umbrella, or a body of work that will span several
issues; a one-off still goes in `Other`. Add the option in the Project UI
(Epic field → add option) or via GraphQL `updateProjectV2Field` (pass the
full option list, each `{name,color,description}`).

Field IDs and option IDs are discoverable with
`gh project field-list 1 --owner wifihaven --format json`; set a field with
`gh project item-edit --id <itemId> --project-id <projectId> --field-id <fid>
--single-select-option-id <oid>`.

## Deciding what to pull next

The repeatable "what do we work on next / what to spawn" process — reconcile
the board, apply the standing priority stack, order foundation-first by
dependency — is encoded as a skill:
[`.claude/skills/epic-prioritization/SKILL.md`](../../.claude/skills/epic-prioritization/SKILL.md).
