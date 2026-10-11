// #2903 — notice when a newer SPA build is live. A tab (or home-screen app) opened before a
// deploy keeps running the old JavaScript against the new API until it reloads, and an old
// bundle can read existing API fields the old way (pre-#2895 it put a checked-out shared device
// under Unmanaged).
//
// The build identity is the set of hashed `/assets/` URLs index.html references. Vite
// content-hashes every emitted file, so this changes exactly when the bundle changes and not on
// an API-only deploy, and it needs no build step, no extra file and no SHA plumbing: the running
// page reads it from its own DOM, and the check re-fetches `/` from the SPA's own origin. That is
// Cloudflare Pages in cloud and the API's StaticRoutes in self-hosted, both of which serve
// index.html at `/` — not `/api/version`, which in cloud is a different service deployed on a
// different schedule.
//
// No telemetry transport exists for the SPA, so outcomes go to the console: info on a detected
// new build, warn on a failed fetch, error on a check that cannot work.

export type BuildCheckSnapshot =
  | { status: 'current' }
  | { status: 'stale' }
  | { status: 'error'; reason: string }

export type BuildCheckLog = Pick<Console, 'info' | 'warn' | 'error'>

export interface BuildChecker {
  snapshot(): BuildCheckSnapshot
  subscribe(listener: () => void): () => void
  /** Fetch index.html and compare. Throttled to one per `minIntervalMs` unless `force`. */
  check(force?: boolean): Promise<void>
}

/** Consecutive failed fetches before a warning becomes an error. */
const FAILURES_BEFORE_ERROR = 3
const DEFAULT_MIN_INTERVAL_MS = 60_000
const TAG = '[build-check]'

/**
 * The sorted, space-joined `/assets/` URLs (by path) of the page's scripts and stylesheets, or
 * null when it references none — the Vite dev server, or a page that is not our index.html.
 */
export function assetFingerprint(source: string | Document): string | null {
  const doc = typeof source === 'string' ? new DOMParser().parseFromString(source, 'text/html') : source
  const urls = new Set<string>()
  doc.querySelectorAll('script[src], link[href]').forEach(el => {
    if (el.tagName === 'LINK' && !/\b(stylesheet|modulepreload)\b/.test(el.getAttribute('rel') ?? '')) return
    const raw = el.getAttribute(el.tagName === 'SCRIPT' ? 'src' : 'href')
    if (!raw) return
    let path: string
    try {
      path = new URL(raw, 'http://origin.invalid').pathname
    } catch {
      return
    }
    if (path.startsWith('/assets/')) urls.add(path)
  })
  return urls.size === 0 ? null : [...urls].sort().join(' ')
}

export function createBuildChecker(opts: {
  /** The running bundle's fingerprint; null means this page cannot be checked. */
  running: string | null
  fetchIndex: () => Promise<Response>
  log?: BuildCheckLog
  now?: () => number
  minIntervalMs?: number
}): BuildChecker {
  const log = opts.log ?? console
  const now = opts.now ?? Date.now
  const minIntervalMs = opts.minIntervalMs ?? DEFAULT_MIN_INTERVAL_MS
  const listeners = new Set<() => void>()

  let snap: BuildCheckSnapshot = { status: 'current' }
  let lastStartedAt: number | null = null
  let inFlight: Promise<void> | null = null
  let failures = 0

  const set = (next: BuildCheckSnapshot) => {
    snap = next
    listeners.forEach(l => l())
  }

  if (opts.running === null) {
    const reason = 'the running page references no /assets/ files, so new builds cannot be detected'
    log.error(`${TAG} ${reason}`)
    snap = { status: 'error', reason }
  }

  const failed = (detail: string) => {
    failures += 1
    log.warn(`${TAG} could not fetch index.html: ${detail}`)
    if (failures === FAILURES_BEFORE_ERROR) {
      const reason = `index.html fetch failed ${failures} times in a row (last: ${detail})`
      log.error(`${TAG} ${reason}`)
      set({ status: 'error', reason })
    }
  }

  const run = async () => {
    let res: Response
    try {
      res = await opts.fetchIndex()
    } catch (e) {
      failed(e instanceof Error ? e.message : String(e))
      return
    }
    if (!res.ok) {
      failed(`HTTP ${res.status}`)
      return
    }
    failures = 0
    const served = assetFingerprint(await res.text())
    if (served === null) {
      const reason = 'served index.html references no /assets/ files'
      log.error(`${TAG} ${reason}`)
      set({ status: 'error', reason })
    } else if (served !== opts.running) {
      log.info(`${TAG} a newer build is live (running ${opts.running}, served ${served})`)
      set({ status: 'stale' })
    } else if (snap.status !== 'current') {
      set({ status: 'current' })
    }
  }

  return {
    snapshot: () => snap,
    subscribe(listener) {
      listeners.add(listener)
      return () => { listeners.delete(listener) }
    },
    check(force = false) {
      // Stale is terminal until reload; an unreadable running page has nothing to compare.
      if (snap.status === 'stale' || opts.running === null) return Promise.resolve()
      if (inFlight) return inFlight
      const t = now()
      if (!force && lastStartedAt !== null && t - lastStartedAt < minIntervalMs) return Promise.resolve()
      lastStartedAt = t
      inFlight = run().finally(() => { inFlight = null })
      return inFlight
    },
  }
}

// Read when this module is evaluated, which is during the entry chunk's evaluation and before
// any later code could add /assets/ <link>s to the document (Vite's preload helper does that for
// dynamic imports). Those runtime additions are absent from the served index.html, so reading the
// document any later could mark every tab stale.
const runningAtLoad = typeof document === 'undefined' ? null : assetFingerprint(document)

let shared: BuildChecker | null = null
let devNoticeLogged = false

/**
 * The tab's checker, compared against the fingerprint read at module load. Null under the Vite
 * dev server (and vitest), whose index.html has no hashed assets and which hot-reloads anyway;
 * that one case is logged rather than reported as a broken check.
 */
export function getBuildChecker(): BuildChecker | null {
  if (import.meta.env.DEV) {
    if (!devNoticeLogged) console.info(`${TAG} off under the dev server`)
    devNoticeLogged = true
    return null
  }
  if (!shared) {
    shared = createBuildChecker({
      running: runningAtLoad,
      fetchIndex: () => fetch('/', { cache: 'no-store', headers: { Accept: 'text/html' } }),
    })
  }
  return shared
}
