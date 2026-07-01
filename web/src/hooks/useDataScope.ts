// #2069 — one place that answers "how must this session's data requests be
// scoped?" so the dashboard / usage / events pages stop firing endpoints a
// child role isn't authorized for (the prod 403 storm).
//
// The API's own role rules (see `UsageRoutes`, `Routes`) are the contract this
// mirrors client-side:
//   - `/api/stats`                    → admin only
//   - `/api/connection-events/series` → admin OR adult (aggregate view)
//   - `/api/usage/traffic` unscoped   → admin OR adult; a child MUST pass its
//                                       own `profileId`/`mac` or the server 403s
//   - `/api/logs` (raw)               → any role; server post-filters to the
//                                       caller's visible profiles
// So the only role that needs client-side scoping/gating is CHILD. Admin and
// adult keep their existing unscoped behaviour untouched.
import { useAuthOptional } from '@/hooks/useAuth'
import { useMe } from '@/api/queries'

export interface DataScope {
  /** admin — may call `/api/stats` and every unscoped/aggregate view. */
  isAdmin: boolean
  /** admin OR adult — may call the aggregate `/connection-events/series` and unscoped `/api/usage/traffic`. */
  isAdult: boolean
  /** child — must scope every data request to `childProfileIds`. */
  isChild: boolean
  /**
   * The profile ids a CHILD session must scope its requests to:
   *   - `null`      → admin/adult: no client scoping needed (server serves them unscoped).
   *   - `undefined` → child, but `/api/me` hasn't resolved yet: hold the request.
   *   - `number[]`  → the child's linked profiles (may be `[]` → nothing to show; surface
   *                   a needs-linking empty state rather than firing an unscoped 403).
   */
  childProfileIds: number[] | null | undefined
  /** True while a child's `/api/me` is still loading (so callers can defer, not fire unscoped). */
  scopeLoading: boolean
}

export function useDataScope(): DataScope {
  // Non-throwing read: page-level unit tests mount pages without an AuthProvider.
  // With no provider we default to the pre-#2069 unscoped behaviour (treat as
  // admin) — the real app always mounts AuthProvider (App.tsx), and the server
  // enforces authorization regardless, so this default is never a security gap.
  const auth = useAuthOptional()
  const isAdmin = auth?.isAdmin ?? true
  const isAdult = auth?.isAdult ?? true
  const isChild = auth?.isChild ?? false
  const isAuthenticated = auth?.isAuthenticated ?? false
  // Only children need their linked-profile list; admins/adults are served
  // unscoped, so don't spend a round-trip on `/api/me` for them.
  const me = useMe({ enabled: isAuthenticated && isChild })
  const childProfileIds = !isChild ? null : me.data?.profileIds
  return {
    isAdmin,
    isAdult,
    isChild,
    childProfileIds,
    scopeLoading: isChild && childProfileIds === undefined,
  }
}
