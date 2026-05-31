// #1191 — module-scoped store tracking whether the SPA's API calls are
// currently failing. `client.req()` calls reportFailure/reportSuccess; the
// global <ApiUnreachableBanner /> subscribes via useSyncExternalStore.
//
// Deliberately tiny: no transports, no auto-retry loop, no toast pile-up.
// One bit of state ("unreachable now?") with a reason string for the
// banner copy.

export type ApiHealthReason = '5xx' | 'network' | 'timeout'

export interface ApiHealthSnapshot {
  unreachable: boolean
  reason: ApiHealthReason | null
  /** Wall-clock ms of the most recent failure; used to bust the snapshot
   *  identity so React re-renders even when `unreachable` stays true. */
  failedAt: number | null
}

type Listener = () => void

let snapshot: ApiHealthSnapshot = { unreachable: false, reason: null, failedAt: null }
const listeners = new Set<Listener>()

function emit() {
  for (const l of listeners) l()
}

export const apiHealth = {
  snapshot(): ApiHealthSnapshot {
    return snapshot
  },
  subscribe(listener: Listener): () => void {
    listeners.add(listener)
    return () => { listeners.delete(listener) }
  },
  reportFailure(reason: ApiHealthReason): void {
    snapshot = { unreachable: true, reason, failedAt: Date.now() }
    emit()
  },
  reportSuccess(): void {
    if (!snapshot.unreachable) return
    snapshot = { unreachable: false, reason: null, failedAt: null }
    emit()
  },
  /** Test-only — wipes state between tests. */
  reset(): void {
    snapshot = { unreachable: false, reason: null, failedAt: null }
    emit()
  },
}
