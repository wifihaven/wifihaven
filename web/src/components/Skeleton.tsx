// #2166 — shared loading skeleton. A pending query must show a loading state,
// never a placeholder that looks like a real value (`0m`, `0`, an empty count).
// A loading `0` is indistinguishable from a genuine zero and has masked real
// bugs — a slow per-profile query once made `/profiles` show `0m` everywhere
// (#1098), reading as data loss when it was a perf problem. See
// docs/process/loading-states.md.
//
// This consolidates the inline `animate-pulse` blocks the dashboard KPI tiles
// already use (#1837/#2056) so every view reaches for the same themed skeleton
// rather than re-inventing one (or falling back to a bare "0").

export function Skeleton({
  className = '',
  testId,
  label = 'Loading…',
}: {
  /** Tailwind sizing/shape classes for the block, e.g. `h-4 w-16`. */
  className?: string
  testId?: string
  /** Accessible label announced to assistive tech while pending. */
  label?: string
}) {
  return (
    <span
      role="status"
      aria-label={label}
      aria-busy="true"
      data-testid={testId}
      className={`inline-block rounded bg-brand-border/60 animate-pulse ${className}`}
    />
  )
}
