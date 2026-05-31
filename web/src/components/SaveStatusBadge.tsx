import type { SaveStatus } from '@/hooks/useDebouncedSave'

// #995: shared autosave indicator. 'dirty' (debounce pending) and 'saving'
// (in-flight) both communicate "not yet saved"; 'saved' is transient; 'error'
// optionally renders an inline Retry. The dirty value lives in the form's own
// state, so Retry just re-commits it.
export function SaveStatusBadge({
  status, error, testId, onRetry,
}: {
  status: SaveStatus
  error: string | null
  testId: string
  onRetry?: () => void
}) {
  if (status === 'dirty') {
    return <span data-testid={testId} data-status="dirty" className="text-xs text-brand-text-muted">Unsaved</span>
  }
  if (status === 'saving') {
    return <span data-testid={testId} data-status="saving" className="text-xs text-brand-text-muted">Saving…</span>
  }
  if (status === 'saved') {
    return <span data-testid={testId} data-status="saved" className="text-xs text-brand-accent">Saved just now</span>
  }
  if (status === 'error') {
    return (
      <span
        data-testid={testId}
        data-status="error"
        className="inline-flex items-center gap-1.5 text-xs text-red-700"
        title={error ?? ''}
      >
        Save failed
        {onRetry && (
          <button
            type="button"
            data-testid={`${testId}-retry`}
            onClick={onRetry}
            className="underline hover:no-underline font-medium"
          >
            Retry
          </button>
        )}
      </span>
    )
  }
  return <span data-testid={testId} data-status="idle" className="text-xs text-transparent select-none">·</span>
}
