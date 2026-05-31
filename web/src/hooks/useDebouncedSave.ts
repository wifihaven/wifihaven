import { useEffect, useRef, useState } from 'react'

export type SaveStatus = 'idle' | 'saving' | 'saved' | 'error'

export interface UseDebouncedSave {
  status: SaveStatus
  error: string | null
  // True when the live value differs from the last-saved baseline and no save
  // has completed for it yet — i.e. a change is queued or in flight. Drives the
  // "Unsaved" indicator (#1003).
  dirty: boolean
  flush: () => Promise<void>
  // Re-run the save that last failed (#1003). No-op unless status is 'error'.
  retry: () => Promise<void>
}

// #973: debounce field changes into a single save. Tracks the LATEST baseline
// (the value the caller considers "saved") so re-renders that resync from
// server state don't redundantly fire a save. The `key` lets a caller reset
// the baseline when the underlying record changes (e.g. switching to a
// different profile's editor).
export function useDebouncedSave<T>(
  value: T,
  save: (v: T) => Promise<void>,
  opts: { delayMs?: number; key?: string | number; equals?: (a: T, b: T) => boolean } = {},
): UseDebouncedSave {
  const { delayMs = 500, key, equals } = opts
  const [status, setStatus] = useState<SaveStatus>('idle')
  const [error, setError] = useState<string | null>(null)
  const baselineRef = useRef<T>(value)
  const lastKeyRef  = useRef(key)
  const pendingRef  = useRef<T | null>(null)
  const failedRef   = useRef<T | null>(null)
  const timerRef    = useRef<ReturnType<typeof setTimeout> | null>(null)
  const savedTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const eq = equals ?? Object.is

  // Reset baseline when key changes (different record).
  if (key !== lastKeyRef.current) {
    lastKeyRef.current = key
    baselineRef.current = value
    pendingRef.current = null
    if (timerRef.current) clearTimeout(timerRef.current)
    setStatus('idle')
    setError(null)
  }

  async function commit(v: T) {
    setStatus('saving')
    setError(null)
    try {
      await save(v)
      baselineRef.current = v
      failedRef.current = null
      // If newer changes came in while saving, leave them for the next tick.
      if (pendingRef.current != null && !eq(pendingRef.current, v)) {
        // pendingRef holds the latest value; let the effect re-schedule.
        setStatus('idle')
      } else {
        setStatus('saved')
        if (savedTimerRef.current) clearTimeout(savedTimerRef.current)
        savedTimerRef.current = setTimeout(() => setStatus('idle'), 1500)
      }
    } catch (e) {
      failedRef.current = v
      setStatus('error')
      setError(e instanceof Error ? e.message : 'Save failed')
    }
  }

  useEffect(() => {
    if (eq(value, baselineRef.current)) return
    pendingRef.current = value
    if (timerRef.current) clearTimeout(timerRef.current)
    timerRef.current = setTimeout(() => {
      const v = pendingRef.current
      pendingRef.current = null
      if (v !== null) void commit(v as T)
    }, delayMs)
    return () => {
      if (timerRef.current) clearTimeout(timerRef.current)
    }
  }, [value])

  useEffect(() => () => {
    if (timerRef.current) clearTimeout(timerRef.current)
    if (savedTimerRef.current) clearTimeout(savedTimerRef.current)
  }, [])

  async function flush() {
    if (timerRef.current) {
      clearTimeout(timerRef.current)
      timerRef.current = null
    }
    const v = pendingRef.current
    pendingRef.current = null
    if (v !== null && !eq(v as T, baselineRef.current)) {
      await commit(v as T)
    }
  }

  async function retry() {
    const v = failedRef.current
    if (v !== null) await commit(v as T)
  }

  const dirty = !eq(value, baselineRef.current)

  return { status, error, dirty, flush, retry }
}
