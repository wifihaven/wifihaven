import { useEffect, useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { api } from '@/api/client'
import { useInvalidators, useMe, useProfiles } from '@/api/queries'
import { useAuthOptional } from '@/hooks/useAuth'
import { useSharedDevicesLive } from '@/hooks/useWs'
import { Skeleton } from '@/components/Skeleton'
import { formatSince, sharedDeviceErrorMessage, type SharedDeviceAction } from '@/lib/sharedDevices'
import type { SharedDevice } from '@/types/api'

// #2850 (epic #2841, design docs/design/shared-devices.md §10, §15 Q1/Q2/Q4/Q5/Q5a): the
// dashboard's "Shared devices" card, for every role.
//
//   - Each shared device with its holder (profile, who checked it in, since when), or "Checked out".
//   - Check in: a child on a profile they are linked to, an adult on any household profile (Q5a).
//     The profile picker appears only when there is more than one profile to choose from (Q4).
//   - Check out: whoever is linked to the holder profile; an adult may force it on anyone (Q2/Q5).
//   - `?checkin=<mac>` (the block page's Check in action) marks that device as "This device". The
//     MAC only pre-selects; the check-in is the same flow as from anywhere else (Q1).
//
// Loading states (AGENTS.md#loading-states): a skeleton until the GET seed resolves, an error
// affordance on failure, and "Checked out" only as a loaded state. A household with no shared
// devices renders nothing.
export function SharedDevicesCard() {
  const auth = useAuthOptional()
  const isAuthenticated = auth?.isAuthenticated ?? false
  const isWriter = auth?.isWriter ?? false
  const shared = useSharedDevicesLive()
  // The check-in choices. The API already narrows `GET /api/profiles` to a child's linked profiles.
  const profilesQuery = useProfiles({ enabled: isAuthenticated })
  // The caller's own linked profiles: what a child may check out, and what an adult checks out
  // without it being a forced check-out.
  const meQuery = useMe({ enabled: isAuthenticated })
  const [params] = useSearchParams()
  const thisDevice = params.get('checkin')?.toLowerCase() ?? null

  if (shared.isPending) {
    return (
      <CardFrame>
        <div data-testid="shared-devices-loading" className="px-5 py-4 space-y-3">
          <Skeleton className="h-4 w-40" label="Loading shared devices" />
          <Skeleton className="h-4 w-56" label="Loading shared devices" />
        </div>
      </CardFrame>
    )
  }

  if (shared.isError) {
    return (
      <CardFrame>
        <div data-testid="shared-devices-error" className="px-5 py-4 flex items-center gap-3 text-sm">
          <span className="text-red-700 flex-1">Couldn't load shared devices.</span>
          <button
            type="button"
            onClick={() => void shared.refetch()}
            className="text-xs text-brand-text hover:text-brand-ink bg-brand-alt px-3 py-1.5 rounded-lg"
          >Retry</button>
        </div>
      </CardFrame>
    )
  }

  const devices = shared.data
  if (devices.length === 0) return null

  const profiles = profilesQuery.data?.map(p => p.profile) ?? []
  const linked = meQuery.data?.profileIds ?? []
  const actions: RowActions | null =
    profilesQuery.isSuccess && meQuery.isSuccess
      ? {
          isWriter,
          linked,
          // Design §15 Q5a: an adult may check in on any household profile; a child only on one
          // they are linked to (the filter is redundant with the API's, and harmless).
          checkInOptions: isWriter ? profiles : profiles.filter(p => linked.includes(p.id)),
        }
      : null

  return (
    <CardFrame>
      <ul>
        {devices.map(d => (
          <SharedDeviceRow
            key={d.mac}
            device={d}
            actions={actions}
            isThisDevice={thisDevice === d.mac.toLowerCase()}
          />
        ))}
      </ul>
    </CardFrame>
  )
}

function CardFrame({ children }: { children: React.ReactNode }) {
  return (
    <section
      data-testid="shared-devices-card"
      className="bg-white rounded-2xl border border-brand-border overflow-hidden"
    >
      <h2 className="px-5 pt-4 pb-2 text-sm font-semibold text-brand-text uppercase tracking-wider">
        Shared devices
      </h2>
      {children}
    </section>
  )
}

interface RowActions {
  isWriter: boolean
  linked: number[]
  checkInOptions: { id: number; name: string }[]
}

function SharedDeviceRow({
  device,
  actions,
  isThisDevice,
}: {
  device: SharedDevice
  /** null until the caller's profiles and linkage have loaded; the actions wait on them. */
  actions: RowActions | null
  isThisDevice: boolean
}) {
  const { mac, holder } = device
  const invalidators = useInvalidators()
  const [picking, setPicking] = useState(false)
  const [picked, setPicked] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)

  // A push that changes the holder (someone else checked in or out) closes a half-finished pick.
  const holderKey = holder ? `${holder.profileId}@${holder.since}` : 'out'
  useEffect(() => {
    setPicking(false)
    setPicked(null)
  }, [holderKey])

  useEffect(() => {
    if (!isThisDevice) return
    const el = document.querySelector(`[data-testid="shared-device-${mac}"]`) as HTMLElement | null
    el?.scrollIntoView?.({ block: 'center', behavior: 'smooth' })
  }, [isThisDevice, mac])

  const mutation = useMutation({
    mutationFn: (v: { action: SharedDeviceAction; profileId?: number }) =>
      v.action === 'check-in'
        ? api.sharedDevices.checkIn(mac, { profileId: v.profileId as number })
        : api.sharedDevices.checkOut(mac),
    onMutate: () => setError(null),
    onSuccess: () => {
      setPicking(false)
      setPicked(null)
      return invalidators.deviceMutated()
    },
    onError: (e, v) => {
      const profileName =
        v.action === 'check-in'
          ? actions?.checkInOptions.find(p => p.id === v.profileId)?.name
          : holder?.profileName
      setError(sharedDeviceErrorMessage(e, { action: v.action, profileName }))
    },
  })

  const checkIn = (profileId: number) => mutation.mutate({ action: 'check-in', profileId })

  return (
    <li
      data-testid={`shared-device-${mac}`}
      className={`px-5 py-3 border-t border-brand-border ${isThisDevice ? 'ring-2 ring-brand-accent/60 ring-inset' : ''}`}
    >
      <div className="flex items-center gap-3 flex-wrap">
        <div className="flex-1 min-w-[10rem]">
          <p className="font-medium text-brand-ink truncate">
            {device.name}
            {isThisDevice && (
              <span className="ml-2 text-xs font-normal bg-brand-accent/10 text-brand-accent border border-brand-accent/20 px-2 py-0.5 rounded-lg">
                This device
              </span>
            )}
          </p>
          <p data-testid={`shared-device-status-${mac}`} className="text-xs text-brand-text-muted">
            <HolderLine device={device} />
          </p>
        </div>
        {actions && (
          <RowButtons
            device={device}
            actions={actions}
            busy={mutation.isPending}
            onCheckIn={() => {
              const opts = actions.checkInOptions
              if (opts.length === 1) checkIn(opts[0].id)
              else setPicking(true)
            }}
            onCheckOut={() => mutation.mutate({ action: 'check-out' })}
            picking={picking}
          />
        )}
      </div>
      {actions && picking && !holder && (
        <div data-testid={`shared-device-picker-${mac}`} className="mt-3 flex items-center gap-2 flex-wrap">
          <label className="sr-only" htmlFor={`shared-device-profile-${mac}`}>Profile</label>
          <select
            id={`shared-device-profile-${mac}`}
            value={picked ?? ''}
            onChange={e => setPicked(e.target.value ? Number(e.target.value) : null)}
            className="bg-brand-surface border border-brand-border-strong rounded-xl px-3 py-2 text-sm text-brand-ink"
          >
            <option value="" disabled>Choose a profile</option>
            {actions.checkInOptions.map(p => (
              <option key={p.id} value={p.id}>{p.name}</option>
            ))}
          </select>
          <button
            type="button"
            disabled={picked === null || mutation.isPending}
            onClick={() => picked !== null && checkIn(picked)}
            className="text-xs font-semibold text-white bg-brand-accent hover:bg-brand-accent-dark disabled:opacity-50 px-3 py-1.5 rounded-lg"
          >Check in</button>
          <button
            type="button"
            onClick={() => { setPicking(false); setPicked(null) }}
            className="text-xs text-brand-text hover:text-brand-ink bg-brand-alt px-3 py-1.5 rounded-lg"
          >Cancel</button>
        </div>
      )}
      {error && (
        <p role="alert" data-testid={`shared-device-error-${mac}`} className="mt-2 text-xs text-red-700">
          {error}
        </p>
      )}
    </li>
  )
}

/** Holder profile, who checked it in, and since when; or the loaded "Checked out" state. */
export function HolderLine({ device }: { device: SharedDevice }) {
  const { holder } = device
  if (!holder) {
    return (
      <span className="inline-block bg-brand-alt text-brand-text border border-brand-border px-2 py-0.5 rounded-lg">
        Checked out
      </span>
    )
  }
  return (
    <>
      <span className="text-brand-ink font-medium">{holder.profileName}</span>
      {holder.checkedInBy && <> · checked in by {holder.checkedInBy}</>}
      {' '}· since {formatSince(holder.since)}
    </>
  )
}

function RowButtons({
  device,
  actions,
  busy,
  picking,
  onCheckIn,
  onCheckOut,
}: {
  device: SharedDevice
  actions: RowActions
  busy: boolean
  picking: boolean
  onCheckIn: () => void
  onCheckOut: () => void
}) {
  const { mac, holder } = device
  const btn = 'text-xs font-semibold px-3 py-1.5 rounded-lg transition-colors disabled:opacity-50 shrink-0'

  if (holder) {
    const own = actions.linked.includes(holder.profileId)
    // A child may only check out their own check-in; an adult may force anyone's (design §15 Q2/Q5).
    if (!own && !actions.isWriter) return null
    return (
      <button
        type="button"
        data-testid={`shared-device-checkout-${mac}`}
        disabled={busy}
        onClick={onCheckOut}
        className={`${btn} text-brand-text bg-brand-alt hover:text-brand-ink`}
      >{own ? 'Check out' : 'Force check out'}</button>
    )
  }

  if (actions.checkInOptions.length === 0) {
    return (
      <p data-testid={`shared-device-no-profile-${mac}`} className="text-xs text-brand-text-muted">
        {actions.isWriter ? 'Add a profile to check this device in.' : 'Ask a parent to link your account to a profile.'}
      </p>
    )
  }

  if (picking) return null
  return (
    <button
      type="button"
      data-testid={`shared-device-checkin-${mac}`}
      disabled={busy}
      onClick={onCheckIn}
      className={`${btn} text-white bg-brand-accent hover:bg-brand-accent-dark`}
    >Check in</button>
  )
}
