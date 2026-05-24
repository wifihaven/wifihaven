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
    // Fallback for environments where IntersectionObserver is throttled
    // (background tabs, some embedded contexts): also listen to scroll and
    // trigger when we're within rootMargin of the bottom.
    const onScroll = () => {
      const rect = el.getBoundingClientRect()
      const margin = parseInt(opts.rootMargin ?? '400px', 10) || 400
      if (rect.top - margin <= window.innerHeight) cbRef.current()
    }
    window.addEventListener('scroll', onScroll, { passive: true })
    return () => {
      io.disconnect()
      window.removeEventListener('scroll', onScroll)
    }
  }, [opts.sentinelRef, opts.hasMore, opts.loading, opts.rootMargin])
}
