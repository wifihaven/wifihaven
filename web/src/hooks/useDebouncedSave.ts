import { useEffect, useRef, useState } from 'react'

// 'dirty' covers the debounce window (a change is pending but not yet sent);
// 'saving' is the in-flight request. The UI groups both under an "Unsaved"
// affordance (#995). 'saved' is transient and decays back to 'idle'.
export type SaveStatus = 'idle' | 'dirty' | 'saving' | 'saved' | 'error'

export interface UseDebouncedSave {
  status: SaveStatus
  error: string | null
  // True when the live value differs from the last-saved baseline and no save
  // has completed for it yet — i.e. a change is queued or in flight. Drives the
  // "Unsaved" indicator (#1003).
  dirty: boolean
  flush: () => Promise<void>
  // Re-run the save that last failed (#1003 / #995) without losing the dirty
  // form value — the form keeps the value in its own state. No-op unless
  // status is 'error'.
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
        setStatus('dirty')
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
    if (eq(value, baselineRef.current)) {
      // Reverted back to the saved value before the debounce fired — drop the
      // pending "Unsaved" state. The cleanup below already cleared the timer.
      setStatus(s => (s === 'dirty' ? 'idle' : s))
      return
    }
    pendingRef.current = value
    setStatus('dirty')
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

// #995: aggregate several field-level save states into one section-level
// indicator (e.g. an Admin card with three independently-saving fields).
// Precedence: any in-flight → 'saving'; else any pending → 'dirty'; else any
// failed → 'error'; else any recently-saved → 'saved'; else 'idle'. `retry`
// re-attempts every errored field; `error` surfaces the first failure.
export function mergeSaveStatus(
  parts: ReadonlyArray<{ status: SaveStatus; error: string | null; retry: () => Promise<void> }>,
): { status: SaveStatus; error: string | null; retry: () => Promise<void> } {
  const has = (s: SaveStatus) => parts.some(p => p.status === s)
  const status: SaveStatus = has('saving')
    ? 'saving'
    : has('dirty')
      ? 'dirty'
      : has('error')
        ? 'error'
        : has('saved')
          ? 'saved'
          : 'idle'
  const error = parts.find(p => p.status === 'error')?.error ?? null
  const retry = async () => {
    await Promise.all(parts.filter(p => p.status === 'error').map(p => p.retry()))
  }
  return { status, error, retry }
}
