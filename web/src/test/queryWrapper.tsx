import { type ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

// A fresh QueryClient per test keeps caches isolated. We disable retries +
// background refetches so tests don't see ghost calls from the harness.
export function makeTestQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
        refetchOnWindowFocus: false,
        refetchOnReconnect: false,
        staleTime: 0,
        gcTime: Infinity,
      },
      mutations: { retry: false },
    },
  })
}

export function withQuery(ui: ReactNode, client: QueryClient = makeTestQueryClient()) {
  return <QueryClientProvider client={client}>{ui}</QueryClientProvider>
}
