import { QueryClient } from '@tanstack/react-query'
import { isForbiddenError } from './client'

// #803: stale-while-revalidate caching for hot read endpoints.
// Per-endpoint freshness is set on the individual useQuery hooks
// (see queries.ts); these are the conservative app-wide defaults.
export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        // #2069: a 403 is a terminal authorization outcome (e.g. a child role
        // hitting an admin/adult-only or unscoped endpoint) — retrying it just
        // hot-loops the same denial, which is exactly the prod console 403 storm.
        // Skip retry on 403; still retry once on genuine 5xx / network blips.
        retry: (failureCount, error) => !isForbiddenError(error) && failureCount < 1,
        refetchOnWindowFocus: true,
        refetchOnReconnect: true,
      },
      mutations: {
        retry: 0,
      },
    },
  })
}
