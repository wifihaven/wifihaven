import { useEffect, useRef, useState, useCallback } from 'react'
import { useDeviceAlerts } from '@/api/queries'

// #711 follow-up: browser Notifications for new-device alerts.
//
// This is the in-tab layer — fires `new Notification(...)` when a freshly-raised
// alert shows up in a polling response. It does NOT cover the "tab closed" case
// (#884 will, via Web Push + a service worker). For the in-tab case it works
// regardless of which page is mounted, because the hook is wired in once at the
// Layout level.

type NotificationPermissionState = 'default' | 'granted' | 'denied' | 'unsupported'

function detectPermission(): NotificationPermissionState {
  if (typeof window === 'undefined' || typeof window.Notification === 'undefined') {
    return 'unsupported'
  }
  return window.Notification.permission as NotificationPermissionState
}

/**
 * Imperative permission-state hook. Returns the current state plus a `request`
 * function that prompts the browser. Updates when the user grants/denies so
 * callers can re-render their CTA.
 */
export function useNotificationPermission() {
  const [state, setState] = useState<NotificationPermissionState>(() => detectPermission())

  const request = useCallback(async () => {
    if (typeof window === 'undefined' || typeof window.Notification === 'undefined') {
      setState('unsupported')
      return 'unsupported' as const
    }
    const result = await window.Notification.requestPermission()
    setState(result as NotificationPermissionState)
    return result
  }, [])

  return { state, request }
}

/**
 * Side-effect hook: when `useDeviceAlerts` returns an ID the user hasn't been
 * notified about yet, fire a browser Notification. The first batch after mount
 * is treated as "already seen" — we do not want a notification flood when the
 * page first loads. Suppresses notifications when the tab has focus (the SPA
 * banner is already visible — a system toast on top would be redundant).
 */
export function useNotifyOnNewAlerts() {
  const { data } = useDeviceAlerts()
  const seen = useRef<Set<number> | null>(null)

  useEffect(() => {
    if (!data) return
    if (typeof window === 'undefined' || typeof window.Notification === 'undefined') return

    const currentIds = new Set(data.map(a => a.id))

    if (seen.current === null) {
      // First successful fetch — seed the seen set and skip notifications,
      // otherwise every page load would re-announce every pending alert.
      seen.current = currentIds
      return
    }

    if (window.Notification.permission !== 'granted') {
      seen.current = currentIds
      return
    }

    // Skip when the tab has focus — the banner is already onscreen.
    if (typeof document !== 'undefined' && document.visibilityState === 'visible' && document.hasFocus()) {
      seen.current = currentIds
      return
    }

    const fresh = data.filter(a => !seen.current!.has(a.id))
    for (const a of fresh) {
      try {
        new window.Notification('New device on the network', {
          body: `${a.deviceName} (${a.mac})`,
          // `tag` collapses repeated notifications for the same MAC if the user
          // hasn't dismissed the prior one.
          tag: `wifihaven-new-device-${a.mac}`,
        })
      } catch {
        // Some browsers throw when called from a non-secure context or when the
        // permission state races with a revoke. Swallow — the in-app banner is
        // still authoritative.
      }
    }
    seen.current = currentIds
  }, [data])
}

/**
 * Mountable component for `App.tsx` — wires the side-effect hook and renders
 * nothing. Kept as a component (not just a hook call in Layout) so existing
 * Layout tests don't need to mock the alerts query.
 */
export function DeviceAlertNotifier() {
  useNotifyOnNewAlerts()
  return null
}
