import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { api } from '@/api/client'
import { newProfileDefaults } from '@/api/profileDefaults'
import { useInvalidators } from '@/api/queries'

// #2367 / #2560 — the one implementation of "create a profile without leaving
// the page you're on". Both device-assignment surfaces mount it: the Add-Device
// modal and the per-row inline editor on /devices (`DevicesPage`). #2367 shipped
// this for the modal only and the row editor grew no equivalent, which left a
// zero-profile household unable to assign a device from the row at all — so it
// lives here rather than being copied a second time
// (`docs/process/single-source-of-truth.md`).
//
// Creation reuses the same endpoint and the same safe-by-default payload the
// Profiles page uses (#978 via `newProfileDefaults`), so an inline-created
// profile is indistinguishable from one made on /profiles.
export function InlineProfileCreator({
  testIdPrefix, hasProfiles, onCreated, onCancel,
}: {
  /** Namespaces this instance's data-testids, e.g. `add-device` → `add-device-create-profile`. */
  testIdPrefix: string
  /** False on a zero-profile household — swaps the copy and drops Cancel (there is nothing to go back to). */
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
      setName('')
      setError(null)
      // Refresh the profile list before handing the id back so the caller's
      // <select> already carries the new option when it re-renders selected.
      await invalidators.profileMutated()
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
