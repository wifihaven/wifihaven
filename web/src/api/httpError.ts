// #2824 — the HTTP status behind a rejected API call, as a leaf module.
//
// A leaf module on purpose (same reasoning as `api/queryKeys.ts`): it imports
// nothing, so a component that needs to branch on a status does not pull in
// `api/client.ts` — which most page tests mock wholesale, and a mocked module
// cannot supply a helper the component under test calls.
//
// Why a status at all: #2824's category-coverage view answers a safety question
// ("does this profile block nothing?"), and the household-global layer is half of
// the answer. A 404 from `GET /api/profiles/global` means the household has no
// global sentinel row — true for households provisioned between V65 and V73, see
// V73__profiles_is_global_per_household.sql — i.e. an EMPTY global layer that
// coverage can still be computed against. Any other failure means coverage is
// unknowable, and an unknown must render as an explicit unknown, never as
// "blocks nothing" (AGENTS.md#loading-states). Telling those apart without a
// status would mean string-matching a response body.

export class HttpError extends Error {
  constructor(readonly status: number, message: string) {
    super(message)
    this.name = 'HttpError'
  }
}

/** The HTTP status behind a rejected API call, or `undefined` if it carried none. */
export function httpStatusOf(e: unknown): number | undefined {
  // Covers HttpError and the pre-existing typed 403s (ForbiddenError and its
  // PasswordChangeRequiredError subclass), which carry their own `status`.
  // A plain object/Error with a numeric `status` is tolerated too, so rejections
  // that did not come from `req` (a test's mocked client, say) stay usable.
  if (e && typeof e === 'object' && 'status' in e) {
    const s = (e as { status?: unknown }).status
    if (typeof s === 'number') return s
  }
  return undefined
}
