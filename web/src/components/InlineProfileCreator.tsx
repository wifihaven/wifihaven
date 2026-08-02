import { useEffect, useRef, useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { api } from '@/api/client'
import { newProfileDefaults } from '@/api/profileDefaults'
import { useInvalidators } from '@/api/queries'

// The `<select>` value that means "I want a new profile", as opposed to any
// real profile id or `''` (no profile). Exported because the option that
// carries it and the onChange branch that interprets it live in the caller —
// a hand-copied literal in either place would silently stop opening the
// creator (`docs/process/single-source-of-truth.md`).
export const NEW_PROFILE_VALUE = '__new__'

// #2367 / #2560 — the shared implementation of "create a profile without
// leaving the device you're assigning". Both device-assignment surfaces on
// /devices mount it: the Add-Device modal and the per-row inline editor
// (`DevicesPage`). #2367 shipped it for the modal only; the row editor grew no
// equivalent, so assigning a device to a *new* profile from the row meant
// leaving the page for /profiles and coming back. It lives here rather than
// being copied a second time.
//
// Not in scope: /profiles' own new-profile row (`ProfilesPage`), which creates
// a profile as an end in itself rather than to assign it to something.
//
// Creation reuses the same endpoint and the same safe-by-default payload the
// Profiles page uses (#978 via `newProfileDefaults`), so an inline-created
// profile is indistinguishable from one made on /profiles.
export function InlineProfileCreator({
  testIdPrefix, isFirstProfile = false, canCancel, autoFocusName = true,
  onCreated, onCancel, onPendingChange,
}: {
  /** Namespaces this instance's data-testids, e.g. `add-device` → `add-device-create-profile`. */
  testIdPrefix: string
  /**
   * True on a zero-profile household, which the Add-Device modal opens straight
   * into. Selects the copy and nothing else — see `canCancel` / `autoFocusName`
   * for why the other decisions get their own props.
   */
  isFirstProfile?: boolean
  /**
   * Whether to focus the name input on mount. True when the operator reached
   * the creator by picking "+ New profile…" (the select they used may have been
   * disabled on the way, dropping focus to `<body>`); false when a container
   * opened into the creator on its own and focus belongs to a field earlier in
   * the form.
   */
  autoFocusName?: boolean
  /**
   * Whether to offer Cancel. Deliberately NOT derived from `isFirstProfile`:
   * callers that freeze their own controls while the creator is open rely on
   * Cancel being the exit, and one boolean meaning both "which copy" and "is
   * there a way out" is how a caller ends up with no exit at all.
   */
  canCancel: boolean
  onCreated: (profileId: number) => void
  onCancel: () => void
  /**
   * Notified while a create is in flight, so a caller that owns controls
   * outside this component can freeze them for the same window. Without it
   * each caller re-derives the guard and they drift.
   */
  onPendingChange?: (pending: boolean) => void
}) {
  const invalidators = useInvalidators()
  const [name,  setName]  = useState('')
  const [error, setError] = useState<string | null>(null)

  const createMutation = useMutation({
    mutationFn: (profileName: string) => api.profiles.create(newProfileDefaults(profileName)),
    onSuccess: async (created) => {
      setError(null)
      // Refresh the profile list before handing the id back so the caller's
      // <select> already carries the new option when it re-renders selected.
      // The name field is left alone until then — clearing it here blanks the
      // input while the button still reads "Creating…".
      await invalidators.profileMutated()
      setName('')
      onCreated(created.id)
    },
    // #2560 — without this a server rejection left the button silently inert:
    // the operator saw no error and concluded the dialog was broken.
    onError: (e: unknown) => {
      setError(e instanceof Error ? e.message : 'Could not create the profile')
    },
  })

  function create() {
    const trimmed = name.trim()
    if (!trimmed) { setError('Name is required'); return }
    setError(null)
    createMutation.mutate(trimmed)
  }

  // Mirrored out so callers freeze the controls they own for exactly the window
  // this component is busy, instead of each re-deriving it (#2560 review).
  // Held in a ref so the unmount reset below can depend on [] — an effect that
  // depends on the callback re-runs its cleanup on every identity change, so a
  // caller passing an inline arrow would emit a spurious `false` each render.
  const pendingCb = useRef(onPendingChange)
  useEffect(() => { pendingCb.current = onPendingChange }, [onPendingChange])

  const pending = createMutation.isPending
  useEffect(() => { pendingCb.current?.(pending) }, [pending])
  // A successful create unmounts this component (the caller leaves the creating
  // branch) while `pending` is still true, because `onCreated` runs inside
  // `onSuccess`, before the mutation dispatches success. The sync effect above
  // therefore never sees the falling edge — without this the caller would stay
  // frozen forever.
  useEffect(() => () => { pendingCb.current?.(false) }, [])

  return (
    <div data-testid={`${testIdPrefix}-new-profile`} className="mt-2 space-y-2">
      <p className="text-xs text-brand-text-muted">
        {isFirstProfile
          ? 'Create your first profile to assign this device.'
          : 'Name the new profile — it will be assigned to this device.'}
      </p>
      <input
        type="text"
        value={name}
        onChange={e => setName(e.target.value)}
        data-testid={`${testIdPrefix}-new-profile-name`}
        placeholder="Profile name"
        autoFocus={autoFocusName}
        className="w-full bg-brand-surface border border-brand-border-strong rounded-xl px-4 py-3 text-brand-ink placeholder-brand-text-muted focus:outline-none focus:border-brand-accent"
      />
      {error && (
        <p data-testid={`${testIdPrefix}-new-profile-error`} className="text-sm text-red-700">{error}</p>
      )}
      <div className="flex gap-2">
        <button
          type="button"
          onClick={create}
          disabled={pending}
          data-testid={`${testIdPrefix}-create-profile`}
          className="flex-1 py-2.5 rounded-xl bg-brand-accent text-white text-sm font-semibold disabled:opacity-60"
        >{pending ? 'Creating…' : 'Create profile'}</button>
        {canCancel && (
          <button
            type="button"
            onClick={onCancel}
            disabled={pending}
            data-testid={`${testIdPrefix}-cancel-profile`}
            className="flex-1 py-2.5 rounded-xl bg-brand-alt text-brand-text text-sm font-medium disabled:opacity-60"
          >Cancel</button>
        )}
      </div>
    </div>
  )
}
