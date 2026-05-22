// #846 — shared helpers for the Traffic Usage + Connection Events pages.

export type RangePreset = '1h' | '6h' | '24h' | '7d' | 'custom'

export const PRESET_LABELS: Record<Exclude<RangePreset, 'custom'>, string> = {
  '1h':  'Last 1h',
  '6h':  'Last 6h',
  '24h': 'Last 24h',
  '7d':  'Last 7d',
}

export const PRESET_MS: Record<Exclude<RangePreset, 'custom'>, number> = {
  '1h':  60 * 60 * 1000,
  '6h':  6 * 60 * 60 * 1000,
  '24h': 24 * 60 * 60 * 1000,
  '7d':  7 * 24 * 60 * 60 * 1000,
}

export function presetRange(preset: Exclude<RangePreset, 'custom'>): { from: string; to: string } {
  const now  = Date.now()
  const from = new Date(now - PRESET_MS[preset]).toISOString()
  const to   = new Date(now).toISOString()
  return { from, to }
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

export function fmtDuration(seconds: number): string {
  if (seconds < 60) return `${seconds}s`
  const m = Math.floor(seconds / 60)
  const s = seconds % 60
  if (m < 60) return s > 0 ? `${m}m ${s}s` : `${m}m`
  const h = Math.floor(m / 60)
  const mm = m % 60
  return mm > 0 ? `${h}h ${mm}m` : `${h}h`
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
