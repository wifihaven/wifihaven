import type { TrafficUsageBucket } from '@/types/api'

// #846 — shared helpers for the Traffic Usage + Connection Events pages.

// Bucket → wall-clock window we anchor at "to". Picked so each bucket level
// shows a useful but bounded number of rows without paging (proper paging
// lands in #862). Raw is unique — it's bounded by row-count, not window-width.
export const BUCKET_WINDOW_MS: Record<Exclude<TrafficUsageBucket, 'raw'>, number> = {
  '1m':  6 * 60 * 60 * 1000,         // 6 hours → 360 rows
  '10m': 24 * 60 * 60 * 1000,        // 1 day
  '1h':  7 * 24 * 60 * 60 * 1000,    // 1 week
  '12h': 14 * 24 * 60 * 60 * 1000,   // 2 weeks
  '1d':  30 * 24 * 60 * 60 * 1000,   // 1 month
  '1w':  180 * 24 * 60 * 60 * 1000,  // 6 months
}

// Raw view is row-count bounded; API enforces a `limit` (default 100). We
// anchor the window at `to` and walk back a generous distance — the row cap
// handles the truncation.
export const RAW_WINDOW_MS = 7 * 24 * 60 * 60 * 1000  // 1 week look-back ceiling

export function windowFromTo(bucket: TrafficUsageBucket, to: string): { from: string; to: string } {
  const t = new Date(to).getTime()
  const width = bucket === 'raw' ? RAW_WINDOW_MS : BUCKET_WINDOW_MS[bucket]
  return { from: new Date(t - width).toISOString(), to: new Date(t).toISOString() }
}

// <input type="datetime-local"> needs "YYYY-MM-DDTHH:mm" in local time, not UTC ISO.
export function isoToLocalInput(iso: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  const pad = (n: number) => String(n).padStart(2, '0')
  return (
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}` +
    `T${pad(d.getHours())}:${pad(d.getMinutes())}`
  )
}

export function localInputToIso(local: string): string {
  // "2026-05-22T10:30" — interpreted as local, converted to UTC ISO.
  const d = new Date(local)
  return Number.isNaN(d.getTime()) ? '' : d.toISOString()
}

export function fmtBytes(n: number): string {
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  if (n < 1024 * 1024 * 1024) return `${(n / (1024 * 1024)).toFixed(1)} MB`
  return `${(n / (1024 * 1024 * 1024)).toFixed(2)} GB`
}

// H:MM with round-half-up on the minute. Special case: any active period
// `0 < seconds < 60` rounds UP to `0:01` so an active cell never reads `0:00`.
export function fmtDuration(seconds: number): string {
  const totalMinutes = seconds > 0 && seconds < 60 ? 1 : Math.round(seconds / 60)
  const h = Math.floor(totalMinutes / 60)
  const mm = totalMinutes % 60
  return `${h}:${String(mm).padStart(2, '0')}`
}

export function localTime(iso: string): string {
  // Always use browser-local TZ — no per-page TZ chip, per #846 audit decision.
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return d.toLocaleString()
}

export function browserTz(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'
}
