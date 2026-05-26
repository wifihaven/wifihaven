import { useAlerts } from '@/api/queries'
import { useNotifyOnNewItems, useNotificationPermission } from './useNotifyOnNewItems'
import type { Alert } from '@/types/api'

// In-tab browser Notifications for pending alerts (#711, now reading from
// the unified `/api/alerts` feed). Today every alert is kind=new_device;
// #960 extends the per-kind title/body builders below for access_request.
//
// Tab-closed delivery is still Web Push (#884) — out of scope here.

export { useNotificationPermission }

const idOf = (a: Alert) => a.id

function titleOf(a: Alert): string {
  switch (a.kind) {
    case 'new_device':     return 'New device on the network'
    case 'access_request': return 'Access request from a household member'
  }
}

function bodyOf(a: Alert): string {
  const who = a.deviceName ?? a.profileName ?? a.mac
  // access_request shape (host/requestKind) is non-null only when #960's
  // POST /api/access-requests is wired; before that this branch is unreached.
  if (a.kind === 'access_request') {
    return `${who} sent a request`
  }
  return `${who} (${a.mac})`
}

function tagOf(a: Alert): string {
  return a.kind === 'new_device'
    ? `wifihaven-new-device-${a.mac}`
    : `wifihaven-access-request-${a.id}`
}

export function useNotifyOnNewAlerts() {
  const { data } = useAlerts()
  useNotifyOnNewItems<Alert>({ data, idOf, titleOf, bodyOf, tagOf })
}

/** Mountable component for `App.tsx` — wires the hook and renders nothing. */
export function AlertsNotifier() {
  useNotifyOnNewAlerts()
  return null
}
