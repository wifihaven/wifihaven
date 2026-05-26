import { useEffect, useRef, useState, useCallback } from 'react'

// Shared in-tab Notification engine. Used by both #711 (new-device alerts)
// and #960 (kid-to-parent access requests). Each call site supplies the
// pending list + a renderer; the engine handles the seen-set diff, the
// initial-load skip, and the focus / permission / support guards.
//
// Out of scope (still): the tab-closed case, which needs Web Push + a
// service worker (#884).

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

export interface NewItemsNotifyOptions<T> {
  /** Current list from the polling query. `undefined` while the first
   *  fetch is in flight. */
  data: T[] | undefined
  /** Stable numeric ID per item. */
  idOf: (item: T) => number
  /** System-toast title — function so callers can vary by item kind. */
  titleOf: (item: T) => string
  /** Body line shown under the title. */
  bodyOf: (item: T) => string
  /** Notification tag — collapses repeats for the same logical entity
   *  while the prior toast is still onscreen. */
  tagOf: (item: T) => string
}

/**
 * Fire a browser Notification for each item whose ID hasn't been seen in
 * a prior fetch. Skips:
 *   - the very first fetch (so a page reload doesn't replay the backlog)
 *   - when `window.Notification` is missing / permission != 'granted'
 *   - when the tab is focused (the in-app banner is authoritative)
 */
export function useNotifyOnNewItems<T>(opts: NewItemsNotifyOptions<T>) {
  const { data, idOf, titleOf, bodyOf, tagOf } = opts
  const seen = useRef<Set<number> | null>(null)

  useEffect(() => {
    if (!data) return
    if (typeof window === 'undefined' || typeof window.Notification === 'undefined') return

    const currentIds = new Set(data.map(idOf))

    if (seen.current === null) {
      seen.current = currentIds
      return
    }

    if (window.Notification.permission !== 'granted') {
      seen.current = currentIds
      return
    }

    if (typeof document !== 'undefined' && document.visibilityState === 'visible' && document.hasFocus()) {
      seen.current = currentIds
      return
    }

    const fresh = data.filter(item => !seen.current!.has(idOf(item)))
    for (const item of fresh) {
      try {
        new window.Notification(titleOf(item), {
          body: bodyOf(item),
          tag: tagOf(item),
        })
      } catch {
        // Non-secure context / permission race / etc. The in-app banner
        // is still authoritative — swallow.
      }
    }
    seen.current = currentIds
  }, [data, idOf, titleOf, bodyOf, tagOf])
}
