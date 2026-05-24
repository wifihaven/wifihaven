# Profiles + Screen Time merge — design notes

Tracks the breakdown of [#965](https://github.com/wifihaven/wifihaven/issues/965).

## Decisions

### Q1 — Surviving route: `/profiles`
Keep `/profiles`; drop `/time` (today's Screen Time route) and remove its nav entry. The collapsed-card summary row carries the at-a-glance time view that `/time` exists to provide, so the framing is preserved. "Profiles" is the more inclusive name and is already the discoverability anchor in the top nav.

### Q2 — Save model: debounced autosave (no Save buttons)
Operator preference: autosave is the project-wide default for edit forms going forward; Profile is the first place we land it. **Hard prereqs:** [#423](https://github.com/wifihaven/wifihaven/issues/423) (PATCH /api/profiles/:id) and [#581](https://github.com/wifihaven/wifihaven/issues/581) (symmetric Profile read/write shapes). Without those, autosave would have to PUT the whole profile per keystroke — not acceptable.

Shape:
- Each field is debounced (~600ms after last edit) and fires a scoped PATCH for just that field/section.
- Per-subsection "Saved just now" indicator (transient) + a persistent "Unsaved" indicator while the debounce timer is pending or a request is in flight.
- Errors surface inline on the subsection with a Retry; the dirty value stays in the form.
- No Save buttons anywhere on the page.

Ordering: #423 and #581 must merge before any of #965 sub-2..sub-6. The shell (#965 sub-1) does not edit and can land in parallel with #423/#581.

### Q3 — Expanded-card layout: inline collapsible accordion subsections
Subsections (name/icon/color · devices · schedule · time limits · rules · users · cross-device overlap) are each independently collapsible inside the expanded card. The issue body already calls this out for mobile; tabs hide structure on a settings-like surface and flat always-open is too tall.

Default open state when a card is first expanded: name/icon/color section open, the rest collapsed. Subsection open/close state is local to the card (not persisted across reloads).

### Q4 — "Edit users" UX: multi-select of existing household users
Sub-section is a multi-select picker of household login users assigned to this profile. Reuses the inverse of today's `EditProfileIds` modal on [`UsersPage.tsx`](web/src/pages/UsersPage.tsx). Full user CRUD (create/delete/rename) stays on `/users` — the Users admin page is not absorbed.

### Q5 — Interaction with #767 (apps replace FQDN inputs): strategy B, apps first
1. [#767](https://github.com/wifihaven/wifihaven/issues/767) ships into today's modal `ProfileEditor`, replacing the `extraAllowed` / `extraBlocked` textareas at [`ProfilesPage.tsx:688–705`](web/src/pages/ProfilesPage.tsx) with the app-picker rows. This produces an `<AppPolicyEditor>`-shaped component used inside the existing modal.
2. #965 sub-issues 1, 2, 3, 4, 6 proceed in parallel — none of them touch the rules section, so no file-touch collision with #767.
3. #965 sub-issue 5 (inline rules subsection) blocks on #767 merging. It then lifts the `<AppPolicyEditor>` component from the modal into the expanded card; the component itself does not need to change.
4. #965 sub-issue 7 (delete modal + drop `/time` nav entry) runs last and is the only one that deletes `ProfileEditor`.

This ordering means #767 and #965 do not clobber each other: #767 owns the textarea→app-picker swap inside the modal, #965 owns the modal→inline-card lift.

## Sub-issue sequence

```
#423 (PATCH) ──┐
#581 (sym shapes) ──┤
                    ├──> #965 sub-2 (name/icon/color + devices)
#965 sub-1 ─────────┤    ├── #965 sub-3 (schedule)
(collapse shell)    │    ├── #965 sub-4 (time limits + overlap)
                    │    ├── #965 sub-6 (users)
                    │    └── #965 sub-5 (rules; ALSO blocks on #767)
                    │             └── #965 sub-7 (delete modal + drop /time nav)
```

- #965 sub-1 (shell) is the only edit-free sub-issue and can run in parallel with #423 / #581.
- Every other sub-issue (sub-2..sub-7) blocks on #423 + #581 + sub-1.
- Sub-5 additionally blocks on #767.
- Sub-7 is the cleanup gate.

## Mobile

- Collapse-by-default is already the pattern from [#832](https://github.com/wifihaven/wifihaven/issues/832) — keep it.
- Expanded card's accordion subsections each render full-width on phone. No Save button anywhere — the "Saved just now" / "Unsaved" indicator sits in the subsection header.
- The +Time button stays in the collapsed summary row, not buried in the expanded view — same affordance as today's screen-time header per [#855](https://github.com/wifihaven/wifihaven/issues/855).

## Out of scope (here)

- #946 (+Time UI update bug) — separate fix; the merged shell must not re-introduce the bug but doesn't need to fix it.
- (#423 / #581 are now hard prereqs, not out-of-scope — see Q2.)
- #622, #578, #137 — unrelated surfaces.
