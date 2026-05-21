import { QueryClient } from '@tanstack/react-query'

// #803: stale-while-revalidate caching for hot read endpoints.
// Per-endpoint freshness is set on the individual useQuery hooks
// (see queries.ts); these are the conservative app-wide defaults.
export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: 1,
        refetchOnWindowFocus: true,
        refetchOnReconnect: true,
      },
      mutations: {
        retry: 0,
      },
    },
  })
}
