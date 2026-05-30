import type { ReactNode } from 'react'

// Shared empty / idle-state placeholder (#828). Use instead of ad-hoc
// `<p>No X yet</p>` strings so "nothing here" surfaces read and look the same
// everywhere.
//
//  - variant="page"   — for an empty list card (centered, roomy).
//  - variant="inline" — for an empty sub-section inside a populated card
//    (left-aligned, compact).

type EmptyStateVariant = 'page' | 'inline'

interface EmptyStateProps {
  title: string
  hint?: ReactNode
  action?: ReactNode
  icon?: ReactNode
  variant?: EmptyStateVariant
  'data-testid'?: string
}

export function EmptyState({
  title,
  hint,
  action,
  icon,
  variant = 'page',
  'data-testid': testId,
}: EmptyStateProps) {
  const isPage = variant === 'page'
  return (
    <div
      role="status"
      data-testid={testId}
      className={
        isPage
          ? 'flex flex-col items-center justify-center gap-2 px-6 py-12 text-center'
          : 'flex flex-col items-start gap-1 px-1 py-2'
      }
    >
      {icon && (
        <div className={isPage ? 'text-brand-text-muted text-2xl' : 'text-brand-text-muted text-base'} aria-hidden="true">
          {icon}
        </div>
      )}
      <p className="text-brand-text-muted text-sm">{title}</p>
      {hint && <p className="text-brand-text-muted text-xs">{hint}</p>}
      {action && <div className="mt-3">{action}</div>}
    </div>
  )
}
