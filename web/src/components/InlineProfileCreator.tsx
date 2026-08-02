import { useState } from 'react'
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
  testIdPrefix, hasProfiles, onCreated, onCancel,
}: {
  /** Namespaces this instance's data-testids, e.g. `add-device` → `add-device-create-profile`. */
  testIdPrefix: string
  /**
   * False only on a zero-profile household, which the Add-Device modal opens
   * straight into: swaps the copy and drops Cancel, because there is no
   * previous selection to go back to.
   */
  hasProfiles: boolean
  onCreated: (profileId: number) => void
  onCancel: () => void
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

  return (
    <div data-testid={`${testIdPrefix}-new-profile`} className="mt-2 space-y-2">
      <p className="text-xs text-brand-text-muted">
        {hasProfiles
          ? 'Name the new profile — it will be assigned to this device.'
          : 'Create your first profile to assign this device.'}
      </p>
      <input
        type="text"
        value={name}
        onChange={e => setName(e.target.value)}
        data-testid={`${testIdPrefix}-new-profile-name`}
        placeholder="Profile name"
        className="w-full bg-brand-surface border border-brand-border-strong rounded-xl px-4 py-3 text-brand-ink placeholder-brand-text-muted focus:outline-none focus:border-brand-accent"
      />
      {error && (
        <p data-testid={`${testIdPrefix}-new-profile-error`} className="text-sm text-red-700">{error}</p>
      )}
      <div className="flex gap-2">
        <button
          type="button"
          onClick={create}
          disabled={createMutation.isPending}
          data-testid={`${testIdPrefix}-create-profile`}
          className="flex-1 py-2.5 rounded-xl bg-brand-accent text-white text-sm font-semibold disabled:opacity-60"
        >{createMutation.isPending ? 'Creating…' : 'Create profile'}</button>
        {hasProfiles && (
          <button
            type="button"
            onClick={onCancel}
            className="flex-1 py-2.5 rounded-xl bg-brand-alt text-brand-text text-sm font-medium"
          >Cancel</button>
        )}
      </div>
    </div>
  )
}
