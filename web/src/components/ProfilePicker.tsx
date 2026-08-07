import { useEffect, useRef, useState } from 'react'
import { InlineProfileCreator, NEW_PROFILE_VALUE } from './InlineProfileCreator'
import { Skeleton } from './Skeleton'
import type { ProfileDetail } from '@/types/api'

/**
 * The one label for "assign no profile". Surfaces 2 and 3 of #2607 had drifted
 * to `No profile` and `— No profile —` respectively — the tell that this control
 * existed three times. The plain form wins because it is also what the device
 * row's own profile pill reads.
 */
export const NO_PROFILE_LABEL = 'No profile'

// #2607 — the single "pick a profile for this device" control. Three surfaces on
// /devices each hand-rolled it (the Add-Device modal, the device row editor, the
// new-device alert editor), and only the first could create a profile, so on a
// zero-profile household — the state every new customer starts in — the other
// two were dead ends. Fixing them by copying the creator across would have made
// four copies; see docs/process/single-source-of-truth.md.
//
// Decisions settled here rather than per-caller, because "which copy happened to
// win" is what produced the drift in the first place:
//
//  - The null option always reads `NO_PROFILE_LABEL`. Whether it is OFFERED is
//    the caller's call (`allowNone`) — the Add-Device modal genuinely has no
//    unassigned state to express, the other two do.
//  - An empty household ALWAYS opens straight into the creator. An empty select
//    is a dead end wherever it appears, so there is no prop for this.
//  - Auto-opening never steals focus; picking "+ New profile…" always does. The
//    operator who picked it asked to be there (and the select they used goes
//    disabled on the way, dropping focus to <body>); the operator who was put
//    there still has the surrounding form's earlier fields to fill.
//  - The select is frozen for as long as the creator is open, not just while a
//    request is in flight. Its displayed value is the sentinel either way, and
//    the row editor autosaves — a pick made between "Create profile" and the
//    response would be silently overwritten when the created profile lands.
//    Cancel is the exit.
//
// Out of scope: /profiles' own new-profile row (`ProfilesPage`), which creates a
// profile as an end in itself rather than to assign it to a device — it has no
// select, no null option, and no host form to hand an id back to.
export function ProfilePicker({
  profiles, isLoading = false, isError = false, value, onChange, allowNone,
  selectTestId, testIdPrefix, dense = false,
  onCommitBlockedChange, onPendingChange,
}: {
  profiles: ProfileDetail[]
  /**
   * True while the profile list query is still pending. Kept separate from
   * `profiles.length === 0` on purpose: a loading list must not be treated as an
   * empty household, or every operator would be shoved into profile creation on
   * load (docs/process/loading-states.md).
   *
   * No caller can reach this today — `DevicesPage` gates the whole page on
   * `profilesQuery.isPending` before any picker mounts. It is part of the
   * contract rather than load-bearing: a caller that mounts a picker without
   * that gate must have somewhere honest to put "still loading".
   */
  isLoading?: boolean
  /**
   * True when the profile list query FAILED. The third state
   * (docs/process/loading-states.md), and the one that bites hardest here: a
   * failed query leaves an empty array behind, which without this reads as
   * "zero-profile household" and auto-opens the creator.
   */
  isError?: boolean
  value: number | null
  onChange: (profileId: number | null) => void
  /** Whether "no profile" is a state this caller can express at all. */
  allowNone: boolean
  /** `data-testid` for the `<select>`. Callers own it; their tests predate this component. */
  selectTestId: string
  /** Namespaces the creator's testids, e.g. `add-device` → `add-device-create-profile`. */
  testIdPrefix: string
  /** Tighter padding for the inline row editor, which sits in a table-ish row. */
  dense?: boolean
  /**
   * Notified when the caller must freeze the commit affordance it owns (the
   * modal's Save, the row's Done, the alert editor's Save). Deliberately NOT the
   * same thing as "the creator is open": an auto-opened creator only blocks a
   * caller that has nothing valid to commit yet, and `allowNone` is exactly that
   * declaration. The alert editor CAN save with no profile — that denies the
   * alert — so being dropped into the creator must not take that away.
   *
   * Computing it here rather than leaving each caller to re-derive it is the
   * point; three hand-derived guards is how #2607 happened.
   */
  onCommitBlockedChange?: (blocked: boolean) => void
  /** Notified while a create request is IN FLIGHT — a strictly narrower window. */
  onPendingChange?: (pending: boolean) => void
}) {
  const [creating, setCreating] = useState(false)
  // Distinguishes "the operator picked + New profile…" from "we opened into the
  // creator for them", which is the only thing focus behaviour turns on.
  const [pickedNew, setPickedNew] = useState(false)
  const autoOpened = useRef(false)

  // Fires once, and only once the list has actually loaded successfully.
  useEffect(() => {
    if (autoOpened.current || isLoading || isError || profiles.length > 0) return
    autoOpened.current = true
    setCreating(true)
  }, [isLoading, isError, profiles.length])

  // #2366 — the select must never paint a selection the caller's state does not
  // hold. Without `allowNone` there is no `''` option, so a null value has
  // nothing to match and the browser falls back to painting the first profile.
  // Reconciling it here keeps the invariant with the component that renders the
  // options rather than with each caller's form-seeding code, which is a
  // one-shot snapshot and goes stale when the list arrives late.
  const changeCb = useRef(onChange)
  useEffect(() => { changeCb.current = onChange }, [onChange])
  useEffect(() => {
    if (allowNone || value !== null || profiles.length === 0) return
    changeCb.current(profiles[0].profile.id)
  }, [allowNone, value, profiles])

  // Held in a ref for the same reason `InlineProfileCreator` holds
  // `onPendingChange`: callers pass inline arrows, and an effect keyed on the
  // callback re-runs its cleanup on every identity change, emitting a spurious
  // `false` per render. (`onPendingChange` is forwarded raw below because
  // `InlineProfileCreator` already performs this same dance on it internally —
  // doing it twice would buy nothing.)
  //
  // A failed fetch blocks committing too: we cannot show the operator what they
  // would be committing, so we do not let them commit it blind.
  const commitBlocked = isLoading || isError || (creating && (pickedNew || !allowNone))
  const blockedCb = useRef(onCommitBlockedChange)
  useEffect(() => { blockedCb.current = onCommitBlockedChange }, [onCommitBlockedChange])
  useEffect(() => { blockedCb.current?.(commitBlocked) }, [commitBlocked])
  // Unmounting while blocked (the caller closed its dialog) must release the
  // caller's freeze — nothing else reports the falling edge.
  useEffect(() => () => { blockedCb.current?.(false) }, [])

  const labelMargin = dense ? 'mb-1' : 'mb-2'
  const selectPadding = dense ? 'py-2.5' : 'py-3'

  // Nothing to select from and nothing to select: the creator stands alone, and
  // there is no "back" for it to offer.
  const showSelect = profiles.length > 0 || allowNone

  return (
    <div>
      <label className={`block text-xs font-semibold text-brand-text-muted uppercase tracking-wider ${labelMargin}`}>
        Profile
      </label>
      {isLoading ? (
        <Skeleton
          className={`w-full ${dense ? 'h-10' : 'h-12'}`}
          testId={`${testIdPrefix}-profile-loading`}
          label="Loading profiles…"
        />
      ) : isError ? (
        // Neither a select nor a creator: we do not know what profiles exist, so
        // every option we could draw would be a guess presented as fact.
        <p data-testid={`${testIdPrefix}-profile-error`} className="text-sm text-red-700">
          Couldn&rsquo;t load profiles. Reload to try again.
        </p>
      ) : (
        <>
          {showSelect && (
            <select
              value={creating ? NEW_PROFILE_VALUE : value ?? ''}
              onChange={e => {
                if (e.target.value === NEW_PROFILE_VALUE) {
                  setPickedNew(true)
                  setCreating(true)
                } else {
                  setCreating(false)
                  onChange(e.target.value === '' ? null : Number(e.target.value))
                }
              }}
              disabled={creating}
              data-testid={selectTestId}
              className={`w-full bg-brand-surface border border-brand-border-strong rounded-xl px-4 ${selectPadding} text-brand-ink disabled:opacity-60`}
            >
              {/* #2366 — an explicit null option so an unassigned device's
                  displayed selection matches state (no phantom first profile),
                  assigning is a genuine onChange, and a profile can be removed. */}
              {allowNone && <option value="">{NO_PROFILE_LABEL}</option>}
              {profiles.map(p => (
                <option key={p.profile.id} value={p.profile.id}>{p.profile.name}</option>
              ))}
              <option value={NEW_PROFILE_VALUE}>+ New profile…</option>
            </select>
          )}
          {creating && (
            <InlineProfileCreator
              testIdPrefix={testIdPrefix}
              isFirstProfile={profiles.length === 0}
              canCancel={showSelect}
              autoFocusName={pickedNew}
              onPendingChange={onPendingChange}
              onCreated={id => { setPickedNew(false); setCreating(false); onChange(id) }}
              onCancel={() => { setPickedNew(false); setCreating(false) }}
            />
          )}
        </>
      )}
    </div>
  )
}
