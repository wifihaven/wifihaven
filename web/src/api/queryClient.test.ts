import { describe, it, expect } from 'vitest'
import { createQueryClient } from './queryClient'
import { ForbiddenError } from './client'

// #2069 — a 403 is a terminal authorization outcome (a child role hitting an
// admin/adult-only or unscoped endpoint). It must NOT be hot-retried — that is
// exactly the prod console 403 storm. Genuine 5xx / network blips still retry
// once. The retry policy lives on the app-wide QueryClient default.
describe('queryClient retry policy (#2069)', () => {
  const retry = createQueryClient().getDefaultOptions().queries?.retry as
    (failureCount: number, error: Error) => boolean

  it('never retries a 403 (ForbiddenError)', () => {
    expect(retry(0, new ForbiddenError('mac or profileId required for non-admin'))).toBe(false)
    expect(retry(5, new ForbiddenError('Admin required'))).toBe(false)
  })

  it('retries other errors exactly once', () => {
    const err = new Error('HTTP 500')
    expect(retry(0, err)).toBe(true)
    expect(retry(1, err)).toBe(false)
  })
})
