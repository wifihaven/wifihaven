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
   (never agent-authored text), and that prompt is **the only message the customer
   gets for that turn** (#2667 — see below), carrying a signed, short-TTL consent link:
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
5. The grant **closes the loop by itself** (#2460): the server reads the thread,
   takes the customer's last message — the question that made the agent ask for
   permission — and re-dispatches it immediately with a `dataAccess=true` token.
   The customer does nothing else; they come back to an answer. Any later message
   on the thread dispatches with the scope in the usual way while the grant lives.

### Why the grant re-dispatches (#2460)

Until #2460 the grant only wrote a row: consent was consumed by the NEXT inbound
webhook. In practice the customer had already lost the conversation — the consent
link navigates out of the page hosting the chat widget — so clicking Allow led to
a terminal page and silence, the same dead end #2419 was created to fix. The
server now finishes the turn.

Bounds on the resume, all of which a review must preserve:

| Property | Behaviour |
| --- | --- |
| Idempotency | Keyed on the no-live-grant → granted **transition**, reported by the same transaction that writes it (`SupportConsentRepo.grant` returns a Boolean: a `FOR UPDATE` read of the prior state, then the upsert). Re-confirming a live grant meters `resume_skipped` and dispatches nothing, so a page reload — or a second click once the row exists — cannot double-answer. **Residual:** two *simultaneous* FIRST grants have no row to lock yet and can both report a transition; they carry the same question, and the dispatch caps bound the cost. |
| Cost | Draws `dispatchThreadLimiter` then `dispatchGlobalLimiter` through the shared `withDispatchCaps`, exactly like an inbound dispatch (`resume_rate_limited` when capped). The grant still lands — consent is never lost to a cost cap. The caps wrap the re-dispatch branch **only**: they are a draw, not a check, so the fixed-string nudge below never spends one (otherwise a `timeline:read` outage would drain the shared daily AI budget on threads that dispatch nothing). |
| Loop guard | Untouched. The #2403/#2404 guard lives on the inbound webhook path; the agent's eventual reply still arrives as a `thread.chat_sent` that the guard drops. |
| Unreadable thread | Fail-open. If Plain's timeline read yields nothing (a hiccup, or the #2452 `timeline:read` gap) we cannot know what to re-ask, so a FIXED server-authored nudge posts instead (`resume_no_message`). It is never agent-authored and carries no consent URL (#2453). |
| Latency | The resume runs OFF the request fiber (`forkDaemon`), so the consent POST returns as soon as the grant commits. Its two legs are bounded only by their own transport timeouts (`PlainClient.HistoryTimeout`, then the dispatcher's `RequestTimeout`), which together exceed the SPA's request timeout — running it inline would let a *successful* grant abort client-side and be shown to the customer as a broken link. It is also deliberately not wrapped in a ZIO timeout: that would *interrupt* the dispatch and drop its metric sample rather than backgrounding it. The grant commits first, so nothing here can cost the customer's consent. |
| One reply per turn (#2668) | The resume's idempotency covers a *repeated grant*, not a *concurrent session*. In prod (2026-08-09) the customer granted, the resume dispatched, and the customer then went back to the chat and asked again — exactly what the consent page tells them to do — which dispatched a SECOND session on the same thread. Both answered: two bot messages seconds apart. The guard is at the shared callback boundary, not in this flow: every dispatch mints a `ConsentToken` session id, `DispatchTracker` records the latest one per thread, and a `reply` / `request-consent` callback from any other session is dropped (`support_agent_action_total{outcome="superseded"}`). The LATEST dispatch wins because its kickoff carries a superset of the earlier session's context, so nothing is lost — and it resolves the same way whichever of the two lands first. `escalate` is deliberately NOT guarded (a duplicate handoff is noise; a dropped one is a customer who asked for a human and did not get one), and an untracked thread — a restart, an evicted entry, a pre-#2668 token — fails OPEN. |
| One dispatch primitive | The resume does not fork the dispatch assembly — it calls the same `withDispatchCaps` + `dispatchAgentSession` the webhook path calls, so the caps ordering, the #2241 mint audit line, the token shape, and the #2416 config-vs-transient split cannot drift between the two paths. |

**Known gap — the consent link still opens in the same tab.** We post the link as
markdown (`textContent`/`markdownContent` on Plain's `replyToThread`); markdown
has no link-target syntax and Plain documents no HTML passthrough, so the server
cannot make the chat widget open it in a new tab. The resume above is what makes
that survivable — the answer arrives whether or not the customer finds their way
back — and the consent page's granted state now offers an explicit "Back to your
conversation" action.

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
| Granularity of the data | the bounded `HouseholdSummary` — name, plan, counts, profile names + pause state, TODAY's per-profile screen time (used / daily limit / extension / remaining / blocked-right-now + reason), the household-local date those minutes are for, and each device's name + profile name | Consent widens the token's DATA SCOPE only; it never widens what the endpoint returns, nor the household/thread binding. |

**What the read returns, and why that list is what it is (#2665).** The payload
was originally name/plan/counts/profile-names. On the first real prod support
conversation a customer asked how much screen time a device had used that day,
granted a 24-hour data-access consent, and the agent still could not answer —
because no amount of consent would have answered it. That is consent theatre: a
security decision by the customer, a live grant sitting open for a day, and zero
reads against it (`support_agent_action_total{op="household_read"}` had no series
at all). Today's usage is now in the payload, sourced from
`TimeStatusService.dayStateAll` — the SAME primitive the policy snapshot's
TimeLimit decision and `GET /api/blocked` read, so a support answer cannot
disagree with the dashboard the customer is looking at.

Devices carry only their NAME and their PROFILE's name. That pairing is load-
bearing: customers ask by device ("macbook-pro"), minutes are accounted per
profile, and without the join the agent holds the number and cannot connect it to
the question.

Still deliberately absent, and it should stay that way: MACs, IPs, hostnames,
per-host / per-app traffic, query logs, block events, user emails, any history
before today. Those are what would turn a grounded support answer into an
exfiltration payload if a session were hijacked, and none of them is needed for
the questions this desk actually receives. Widen only against a real, recurring
question — and note that everything here is unscrubbable by regex (profile names
are typically children's given names), which is exactly why a `dataAccess=true`
session is structurally refused GitHub issue filing (#2454).

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
- **At the consent moment the customer sees only server-authored text.** That is
  the guarantee, and it rests on three parts, none sufficient alone: the prompt is
  server-authored (#2419), the live link never re-enters the agent's context
  (#2453, below), and no agent-authored message may share the turn with the
  prompt (#2667).
- **The agent cannot post beside the prompt (#2667).** Until this, "the agent
  supplies no text *here*" was true of the consent *message* and false of the
  consent *moment*: nothing stopped the agent calling `reply` in the same turn,
  immediately next to the real signed link and under the same
  `🤖 WifiHaven support assistant` banner. A genuine, correctly-signed link with
  *"sign in with your password to verify your identity"* posted beside it is a
  phishing primitive with every technical control intact — the link being real is
  what makes it work. The two customer-visible agent writes (`reply` and the
  server's consent prompt) are now **mutually exclusive within a session**, in
  either order, claimed atomically at the two sites that write to the thread
  (`DispatchTracker.claimThreadWrite`). The suppressed call returns 200 — the turn
  IS handled, and a 4xx would make the run retry a write it can never land — and
  is loud on `support_consent_total{outcome=reply_after_consent_prompt |
  consent_prompt_after_reply}` plus
  `support_agent_action_total{outcome=consent_exclusive}`, with a WARN naming the
  thread and neither the customer nor the suppressed text. **Expect zero:** the
  repo prompt tells the agent to end its turn after asking, so a non-zero rate
  means the deployed routine has drifted (#2469) or something is trying it. The
  claim is in-memory and per-process, and fails **open** across a restart — the
  same call #2668 makes, since a customer who gets no answer at all is worse.
  Observed prod symptom that prompted it (#2527 §B): two bot messages under one
  banner, one saying the grant expires "after 24 hours" and the other "after a
  while", at the moment the customer was deciding.
- **The consent link never re-enters the agent's own context (#2453).** The prompt
  is posted through the same machine-user write path as every AI reply, so since
  #2430/#2441 it comes back on the timeline as an `ai_assistant` turn — and the
  guarantee above only holds if the agent never *sees* the link: otherwise it can
  re-post the real, valid URL wrapped in a pretext of its own.
  `CloudAgentDispatcher` therefore strips consent links out of **every route by
  which customer- or thread-sourced text reaches the kickoff** — the rendered
  history turns (every role, since a customer quoting the prompt back is the
  obvious way around an AI-turn-only rule), the current `customerMessage`, and
  the email `subject`. The current message is not merely another route but the
  *first* one: a quoted link arrives there on the very dispatch that carries it
  and only ages into history on the next, and #2460's resume lifts the customer's
  last timeline turn straight into `customerMessage`. The redaction is narrow on purpose:
  ordinary URLs in history are left alone, because the agent needs to read the
  links customers actually send. `SupportPrivacy.scrubForIssue` is the opposite
  direction and blanket-redacts *all* URLs, so no capability link can reach a
  public GitHub issue.
- **A consent link is single-use, and cannot outlive a withdrawal (#2453).** See
  the `support_consent_link_use` schema below. A replayed or pre-withdrawal link
  is refused (`link_spent` / `link_stale`) and writes nothing; re-clicking Allow
  while the grant is still live stays an idempotent no-op that neither resumes the
  conversation nor extends the window.
- **The consented read and public-issue filing do not compose (#2454).** An agent
  session whose token carries `dataAccess=true` is refused
  `POST /api/support/agent/issues` outright (403,
  `support_consent_total{outcome="issue_refused_data_session"}`). The scrubber
  could never be the control here: the read returns a household NAME and PROFILE
  names, which by product design are typically children's given names — ordinary
  words that match no PII pattern and never will. The kickoff prompt tells the
  agent, and points it at escalation instead. Nothing legitimate is lost: an agent
  that needed account data to understand a problem can describe the symptom
  without republishing the account.
  The agent must also tell the CUSTOMER that reason plainly (#2671): that it
  cannot open a public bug report from a conversation where it can see their
  account data, that this is deliberate so their household details stay out of a
  public tracker, and that a fresh conversation without a data grant can have it
  filed directly. The first prod occurrence instead said the report had gone to a
  human "since that's the right path for a report like this" — a euphemism that
  hides a privacy protection worth advertising, and teaches a rule that is not
  true. `deploy/support-agent/agent.yaml` step 6b is the standing instruction.

### Deploying #2453 — in-flight links die at the deploy

The consent link's signed payload went from three fields to five (adding the
nonce and the mint time). `ConsentGrant.verify` rejects the old three-field shape
as `Malformed` rather than accepting a nonce-less link — deliberately, since
tolerating one would leave the replay hole open for as long as any old link
survives.

**Operator impact, bounded and self-healing:** any consent link posted before the
deploy and not yet redeemed stops working the moment it lands. The blast radius
is one link TTL (24h) of threads that were mid-ask. The customer sees the SPA's
existing copy — *"That permission link is no longer valid. Ask the assistant in
your support conversation to send a new one."* — and the assistant mints a fresh
one on the next message. No data is lost and no grant is affected: already-recorded
grants live in `support_thread_consent` and are untouched by the token shape.

Nothing to do before or after the deploy. Watch
`support_consent_total{outcome="invalid"}` for a small one-off bump in the first
24h; a *sustained* rise past that is a real problem, not this.

## Schema

`support_thread_consent` (V84):

| Column | Notes |
| --- | --- |
| `household_id` | FK → `households`, `ON DELETE CASCADE` |
| `thread_id` | the Plain thread id (Plain owns threads; we mirror nothing) |
| `granted_by_user_id` | FK → `users`, `ON DELETE SET NULL` — audit trail of which admin granted |
| `granted_at` / `expires_at` / `revoked_at` | the grant window |
| `UNIQUE (household_id, thread_id)` | one record per thread; a re-grant UPSERTs |

`support_consent_link_use` (V85, #2453) — the SINGLE-USE ledger for consent
links:

| Column | Notes |
| --- | --- |
| `nonce` | PRIMARY KEY — the random nonce minted into the link's `g1.…` token. The uniqueness constraint IS the single-use enforcement: a second redemption is a unique violation, not a read-then-write race |
| `household_id` / `thread_id` | audit trail + cascade only; the decision keys on `nonce` alone |
| `consumed_at` | when the link was redeemed (injected Clock) |
| `link_expires_at` | the `exp` the spent link carried — when it would have lapsed anyway |

Why it exists: the consent link is a stateless signed capability, and since
#2430/#2441 the thread transcript re-enters the support agent's own prompt — so a
link that leaked into agent context (or was captured any other way) could be
replayed to RE-GRANT access after the customer withdrew it (`grant` UPSERTs
`revoked_at = NULL`). Consuming the nonce on redemption spends the link.

Two rules the source side (stacked follow-up PR) holds on top of the table:
Two rules the source side holds on top of the table:

- **Only ALLOW consumes.** Withdrawal must never be blockable, so the revoke path
  neither consumes a nonce nor is gated on one — a customer whose link is already
  spent can still revoke.
- **A link minted BEFORE a revocation cannot undo it.** The link carries its mint
  time; a grant whose link predates the row's `revoked_at` is refused. That reads
  V84's existing `revoked_at`, so it needs no column here.

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
