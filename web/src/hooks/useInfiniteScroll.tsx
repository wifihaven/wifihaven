import { useEffect, useRef } from 'react'

// #862 — IntersectionObserver-based infinite scroll. Attach `sentinelRef` to
// an empty <div/> at the bottom of the table; when it scrolls into view and
// there's no in-flight fetch, `onLoadMore` fires. Callers manage their own
// list state + nextCursor.
export function useInfiniteScroll(opts: {
  sentinelRef: React.RefObject<Element | null>
  hasMore: boolean
  loading: boolean
  onLoadMore: () => void
  // Trigger ~one viewport early so the next page is mostly loaded by the time
  // the user reaches it.
  rootMargin?: string
}) {
  const cbRef = useRef(opts.onLoadMore)
  cbRef.current = opts.onLoadMore

  useEffect(() => {
    const el = opts.sentinelRef.current
    if (!el || !opts.hasMore || opts.loading) return
    const io = new IntersectionObserver(
      entries => {
        for (const e of entries) if (e.isIntersecting) cbRef.current()
      },
      { rootMargin: opts.rootMargin ?? '400px' },
    )
    io.observe(el)
    return () => io.disconnect()
  }, [opts.sentinelRef, opts.hasMore, opts.loading, opts.rootMargin])
}
