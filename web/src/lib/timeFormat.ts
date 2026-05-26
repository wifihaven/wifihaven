// Time formatting + bucket utilities shared across the timeline pages and
// the inline /profiles charts. Extracted from the old TimePage (#722/#794)
// during the #978 cleanup so the helpers survive TimePage's deletion.

import type { ProfileTimeBucket } from '@/types/api'

const WEEKDAY = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']

/**
 * #791: render minute totals compactly. Under 60 → "Xm" (e.g. "13m");
 * 60 and above → "H:MM" (e.g. "3:15", "10:21"). The previous code path
 * emitted "{n}m" everywhere, which combined with the 32px Y-axis width
 * produced visually clipped labels like "00m" for "200m" / "60m" for "260m".
 */
export function formatMins(n: number): string {
  if (!Number.isFinite(n) || n < 0) return '0m'
  const mins = Math.round(n)
  if (mins < 60) return `${mins}m`
  const h = Math.floor(mins / 60)
  const m = mins % 60
  return `${h}:${m.toString().padStart(2, '0')}`
}

/**
 * #794: minute-past-the-UTC-hour where the household's local midnight falls. We ask the server
 * to align its hourly bucket grid to this offset so each returned bucket lives entirely within
 * one local-tz day. Real-world tz offsets are all multiples of 15 minutes; we snap to 0/15/30/45.
 *
 * Example: India (+5:30) — local midnight is at 18:30 prev-UTC-day. getUTCMinutes() returns 30.
 * US Pacific — local midnight is at 07:00 / 08:00 UTC, getUTCMinutes() returns 0.
 * Nepal (+5:45) — local midnight at 18:15 UTC, getUTCMinutes() returns 15.
 */
export function localBucketOffsetMin(now: Date = new Date()): 0 | 15 | 30 | 45 {
  const localMidnight = new Date(
    now.getFullYear(), now.getMonth(), now.getDate(), 0, 0, 0, 0,
  )
  // Snap to the nearest 15-min multiple just in case the host happens to expose a sub-minute
  // offset (shouldn't happen for any real tz, but be defensive).
  const raw = localMidnight.getUTCMinutes()
  const snapped = (Math.round(raw / 15) * 15) % 60
  return (snapped as 0 | 15 | 30 | 45)
}

/**
 * #794: group hourly UTC buckets into local-day buckets ending at `toDate` (inclusive). Each
 * bucket lives fully within one local-tz day if the caller fetched with the right
 * `bucketOffsetMin`, so we can just attribute the whole bucket to whatever local date
 * `bucketStart` falls on. Returns 7 rows ordered chronologically with `date: YYYY-MM-DD` in
 * local time and the weekday label.
 */
export function groupBucketsByLocalDay(
  perBucket: ProfileTimeBucket[],
  toDate: string,
): { date: string; label: string; usedMins: number }[] {
  const byLocalDate = new Map<string, number>()
  for (const b of perBucket) {
    const dt = new Date(b.bucketStart) // parsed as UTC, rendered in local
    const y = dt.getFullYear()
    const m = String(dt.getMonth() + 1).padStart(2, '0')
    const d = String(dt.getDate()).padStart(2, '0')
    const localDate = `${y}-${m}-${d}`
    byLocalDate.set(localDate, (byLocalDate.get(localDate) ?? 0) + b.usedMins)
  }
  // Build seven contiguous local days ending on toDate.
  const end = new Date(`${toDate}T00:00:00`)
  const out: { date: string; label: string; usedMins: number }[] = []
  for (let i = 6; i >= 0; i--) {
    const day = new Date(end)
    day.setDate(end.getDate() - i)
    const y = day.getFullYear()
    const m = String(day.getMonth() + 1).padStart(2, '0')
    const d = String(day.getDate()).padStart(2, '0')
    const localDate = `${y}-${m}-${d}`
    out.push({
      date: localDate,
      label: WEEKDAY[day.getDay()],
      usedMins: byLocalDate.get(localDate) ?? 0,
    })
  }
  return out
}
