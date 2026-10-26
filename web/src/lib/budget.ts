// #2935 (D12): a limit's remaining time, in the one figure a collapsed row has room for.

/** "20m", "1h 10m", "2h". Not `formatMins` ("1:10"), which the usage totals use. */
export function formatLeft(mins: number): string {
  const m = Math.max(0, Math.round(mins))
  if (m < 60) return `${m}m`
  const h = Math.floor(m / 60)
  const r = m % 60
  return r === 0 ? `${h}h` : `${h}h ${r}m`
}

export interface BudgetInput {
  dailyLimit?: number | null
  dailyUsed?: number | null
  weeklyLimit?: number | null
  weekUsed?: number | null
}

/**
 * The tighter of the day and week budgets: the window with less time left. Either window alone
 * when only one limit is set; `null` when neither is. A window with a limit but no usage figure is
 * unknown, not zero, so it is skipped: callers show a skeleton until usage has loaded.
 */
export function tighterLeft(b: BudgetInput): { text: string; exhausted: boolean } | null {
  const windows: { left: number; label: string }[] = []
  if (b.dailyLimit != null && b.dailyUsed != null) windows.push({ left: b.dailyLimit - b.dailyUsed, label: 'today' })
  if (b.weeklyLimit != null && b.weekUsed != null) windows.push({ left: b.weeklyLimit - b.weekUsed, label: 'this week' })
  if (windows.length === 0) return null
  // `<` keeps the day first on a tie.
  const w = windows.reduce((a, c) => (c.left < a.left ? c : a))
  return w.left <= 0
    ? { text: `Used up ${w.label}`, exhausted: true }
    : { text: `${formatLeft(w.left)} left ${w.label}`, exhausted: false }
}
