import '@testing-library/jest-dom/vitest'
import { afterEach, vi } from 'vitest'
import { cleanup } from '@testing-library/react'

// #862: jsdom doesn't ship IntersectionObserver; useInfiniteScroll relies on
// it. Stub a no-op so tests render without triggering auto-load.
if (typeof globalThis.IntersectionObserver === 'undefined') {
  // Capture callbacks so tests can synthesize "sentinel scrolled into view"
  // by calling globalThis.__triggerIntersection().
  const callbacks: Array<(entries: { isIntersecting: boolean }[]) => void> = []
  // Constructor mirrors the browser's IntersectionObserver signature so static
  // analysis doesn't flag a "superfluous" second argument when callers pass
  // options like `{ rootMargin: '400px' }`.
  class FakeIntersectionObserver {
    root: Element | null = null
    rootMargin: string
    thresholds: ReadonlyArray<number> = []
    constructor(
      cb: (entries: { isIntersecting: boolean }[]) => void,
      options?: IntersectionObserverInit,
    ) {
      this.rootMargin = options?.rootMargin ?? ''
      callbacks.push(cb)
    }
    observe = vi.fn()
    unobserve = vi.fn()
    disconnect = vi.fn()
    takeRecords = vi.fn(() => [])
  }
  // @ts-expect-error — jsdom shim
  globalThis.IntersectionObserver = FakeIntersectionObserver
  // @ts-expect-error — test helper
  globalThis.__triggerIntersection = () => {
    for (const cb of callbacks) cb([{ isIntersecting: true }])
  }
}

afterEach(() => {
  cleanup()
})
