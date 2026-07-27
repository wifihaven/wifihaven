# Support data-access consent (#2419)

How the Claude support responder gets permission to read the customer's own
household summary — and why it can never grant itself that permission.

Refs: [#2419](https://github.com/wifihaven/wifihaven/issues/2419),
[#2241](https://github.com/wifihaven/wifihaven/issues/2241) (the agent token),
[#2200](https://github.com/wifihaven/wifihaven/issues/2200) (the responder),
[#2199](https://github.com/wifihaven/wifihaven/issues/2199) (the identified
widget), [#2259](https://github.com/wifihaven/wifihaven/issues/2259) (agent-token
revocation).

## The problem

The #2241 token carries a `dataAccess` scope; only with it does
`GET /api/support/agent/household` return the (bounded) account summary. That
scope was sourced from a `dataConsent` flag on the Plain webhook payload which
**nothing ever set** — so it was always false, and the agent's only option on an
account question was to decline:

> "I don't have permission to look up your account details for this thread, so I
> can't pull the exact number for you directly here."

That is a dead end, not an answer.

## The flow

1. The customer asks something that needs account data.
2. The agent, having no data scope, calls
   `POST /api/support/agent/request-consent` with its existing thread-bound
   token. It **requests**; it does not grant.
3. The SERVER posts a fixed, server-authored consent prompt into that thread
   (never agent-authored text), carrying a signed, short-TTL consent link:
   `<appBaseUrl>/support/consent?g=<grant token>`. The grant token is HMAC-signed
   under the agent-token secret with a distinct `g1` version prefix, and the MAC
   is computed over `"<version>.<payload>"` — the version is **bound into the
   signature**, so re-labelling one token family as the other fails the signature
   outright rather than relying on the payloads happening to parse differently.
4. The customer opens the link **signed in to the dashboard** and clicks Allow.
   The SPA posts the grant token to `POST /api/support/consent` with their normal
   session JWT. The server verifies the grant token, requires the JWT's household
   to equal the token's household, and records the grant in
   `support_thread_consent` (V84).
5. The customer's next message dispatches an agent session whose token is minted
   with `dataAccess=true`, and the agent answers the question.

## Why design (a), server-mediated, and not (b), widget-side consent

The issue offered a second option: a consent control in the #2199 identified
widget that stamps `dataConsent` on submission.

- (b) requires consent **before** the question is asked. The customer has no idea
  yet whether their question needs account data, so it is either a permanent
  always-on toggle (over-broad) or a checkbox most people ignore (back to the
  dead end).
- (b) cannot rescue a conversation already in progress — which is exactly the
  reported symptom.
- (b) only works for the widget origin. The #2307 email-intake path has no
  widget, so an email-origin thread could never consent.
- (a) puts the decision at the moment it is needed, in the customer's own words
  ("may I look that up?"), and works identically on both origins.

(b) is not merely unimplemented — the vestigial hook for it is **removed**. The
webhook parser used to read a `dataConsent` boolean off the Plain payload and OR
it into the token's scope; nothing ever set it, and an unverifiable inbound
boolean feeding a security decision is exactly what this issue set out to
eliminate. Data access now has ONE source: the `support_thread_consent` record.
If widget-side consent is ever wanted, it must go through the same server-side
grant, not a payload flag.

## Scope and duration

| Property | Value | Why |
| --- | --- | --- |
| Scope | one `(household_id, thread_id)` pair | Both are in the UNIQUE key and in every lookup, so consent on thread A grants nothing on thread B, and household A's row can never widen a household-B token. |
| Duration | 24 hours from the grant | A support conversation's active window. Long enough for an async back-and-forth, short enough that a forgotten grant lapses on its own. Re-granting is one click. |
| Revocation | `revoked_at` stamped by the customer's "stop allowing" action | Ahead-of-expiry withdrawal, without waiting out the TTL. A live grant is `revoked_at IS NULL AND expires_at > now`. |
| Granularity of the data | unchanged — the bounded `HouseholdSummary` (name, plan, counts, profile names + pause state) | Consent widens the token's DATA SCOPE only; it never widens what the endpoint returns, nor the household/thread binding. |

**Revoking consent takes effect immediately (#2476).** `dataAccess` is stamped
into the token once, at mint — but the household read does not stop there: it
RE-READS the `support_thread_consent` record on every call, and refuses (403,
metered `support_consent_total{outcome="read_withdrawn"}`) when there is no live
grant. Both must hold, so a withdrawal bites on the very next read by an
already-minted, still-unexpired, still-data-scoped token.

That re-read exists because of #2473. The agent-token TTL was 30 minutes and is
now 24 hours (a cloud-agent run can be paused on subscription usage limits and
resumed hours later; a token that dies mid-pause silently throws away the
customer's answer). Had the read kept trusting the mint-time stamp, that change
would have stretched the post-withdrawal residual window from ≤30 minutes to a
full day. Re-reading removes the window entirely instead, independent of the TTL.

What #2473 does still cost is incident response: with a 30-minute window,
waiting out a suspected leaked token was plausible; at 24 hours it is not.
#2259 (an explicit agent-token revocation list) is what closes that, and this
change raises its priority.

## The security boundary

- **Consent is recorded server-side from an explicit CUSTOMER action.** The only
  writer is `POST /api/support/consent`, authenticated by the customer's session
  JWT and scoped to `claims.hh`. Nothing parses the inbound message text for
  agreement: a message saying "I consent" — or a prompt-injection string
  impersonating one — changes nothing.
- **The agent cannot self-grant.** Its token authorises `request-consent` (which
  makes the server post a prompt) and nothing more. Requesting consent and having
  consent are separate privileges, enforced by separate credentials — the agent
  holds no JWT and there is no code path from an agent-token-authenticated
  request to a `support_thread_consent` row.
- **Cross-household is impossible by construction.** The grant endpoint refuses a
  grant token whose household differs from the JWT's; the dispatch lookup keys on
  the household the webhook already resolved. A household-A grant cannot appear
  in a household-B token.
- Every consent request, grant, mismatch, and revocation is audit-logged
  (household + thread, never the token) and metered on
  `support_consent_total{outcome}`. A withdrawal of a grant that was not live
  meters as `revoke_noop`, so the panel counts real withdrawals.

## Schema

`support_thread_consent` (V84):

| Column | Notes |
| --- | --- |
| `household_id` | FK → `households`, `ON DELETE CASCADE` |
| `thread_id` | the Plain thread id (Plain owns threads; we mirror nothing) |
| `granted_by_user_id` | FK → `users`, `ON DELETE SET NULL` — audit trail of which admin granted |
| `granted_at` / `expires_at` / `revoked_at` | the grant window |
| `UNIQUE (household_id, thread_id)` | one record per thread; a re-grant UPSERTs |

## Deploying the MAC change (one-time, #2419)

The signature now covers `"<version>.<payload>"` rather than the payload alone,
for both `ConsentToken` (`v1`) and `ConsentGrant` (`g1`). That is a **breaking
change to already-issued agent tokens**: for up to
`support.agentTokenTtlMinutes` (30 at the time; the default is 1440 since #2473,
so a comparable future rollover would take a day to self-clear, not half an hour)
after the deploy, a cloud-agent
session dispatched by the previous image holds a token the new image will not
verify. Those sessions fail their callback with the uniform 401 and their draft
never reaches the customer; they meter as
`support_agent_action_total{outcome="denied"}`.

So: **a short denied spike immediately after this deploy is expected, not an
attack.** It self-clears within the token TTL — every subsequent inbound message
mints a fresh token. Nothing else is affected (the SPA JWT and the press token
are separate secrets and separate code paths), and no consent links exist yet at
first deploy.

## Operator notes

- The agent prompt lives in `deploy/support-agent/agent.yaml`. The Claude Code
  Cloud routine transport (#2300/#2327) keeps its prompt in the web UI, so after
  any change to that `system:` block the operator must **re-paste the prompt into
  the routine** at claude.ai/code/routines — otherwise the live path still runs
  the old prompt.
- If a customer reports the assistant asking for permission repeatedly, check
  whether their grant is expiring (24h) or whether the consent link is being
  opened while signed out — the grant POST requires an authenticated session.
