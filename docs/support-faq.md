# Customer FAQ (support-agent answer sheet)

Plainly-worded answers a support agent (human or the Claude support responder,
`deploy/support-agent/agent.yaml`) can quote **directly to a customer**. Each
entry cites the code or migration it is derived from, so it can be re-verified
rather than trusted — see
[`docs/process/verify-and-cite.md`](process/verify-and-cite.md).

Internal-only detail stays out of the quotable text. Anything under
"Where this comes from" is for the agent, not the customer.

---

## Can I add another router?

**Quotable answer.**

> Your plan includes **one router**. WifiHaven itself supports multiple routers
> per household — that part is already built and running (our own household
> uses it) — so this is a limit of the current beta plan, not something the
> product can't do. Multi-router is the first thing we expect to open up as
> paid tiers roll out. There's no self-serve upgrade yet, so if you need a
> second router today, just reply here and we'll raise the limit on your
> account manually.

**If they've already tried and hit the error.** Creating a second router from
the Routers page fails with `403 Forbidden` and the message
*"your plan includes N router(s)"* (N is that household's own limit — the SPA
shows the server's text verbatim). That is the cap, not a bug and not a
problem with their router.

**What to do for them.** There is no self-serve upgrade path. Raising a
household's limit is a manual operator change to that household's
`households.router_cap` value — escalate to the operator rather than promising
a timeline.

### Where this comes from

- The limit is **per household**, read from the `households.router_cap` column
  at request time — never a global constant, so raising one household's cap is
  a pure data change:
  [`api/src/db/EntitlementsRepo.scala:44-51`](../api/src/db/EntitlementsRepo.scala#L44-L51).
- The current plan's value is the column default set in
  [`api/resources/db/migration/V66__beta_requests_billing_entitlements.sql:98`](../api/resources/db/migration/V66__beta_requests_billing_entitlements.sql#L98)
  and applied to every beta-approved household by
  [`BetaService.DefaultRouterCap`](../api/src/beta/BetaService.scala#L260).
  **This doc deliberately does not restate that number outside the quotable
  answer above** — read it from the migration if you need to confirm it, and
  check the specific household's column value before telling a customer their
  own limit (it can be higher; the operator household is flagged past the
  public cap by the same migration).
- Enforcement lives in `POST /api/admin/routers`:
  [`api/src/routes/RouterRoutes.scala:245-249`](../api/src/routes/RouterRoutes.scala#L245-L249)
  counts the household's existing routers and fails with
  `ApiError.Forbidden("your plan includes N router(s)")` — mapped to HTTP 403 by
  [`ErrorMapper.scala:31`](../api/src/routes/ErrorMapper.scala#L31) and surfaced as
  the inline error on the Routers page
  ([`web/src/api/client.ts:223`](../web/src/api/client.ts#L223),
  [`web/src/pages/RoutersPage.tsx:68`](../web/src/pages/RoutersPage.tsx#L68)).
  A created-but-not-yet-enrolled router still consumes a slot.
- Multi-router is a **plan** boundary by design, not a technical ceiling:
  routers bind to a household rather than 1:1
  ([#2104](https://github.com/wifihaven/wifihaven/issues/2104),
  [#2106](https://github.com/wifihaven/wifihaven/issues/2106)), and the named
  first upsell is a multi-home tier that "just raises the cap" — see
  [`docs/design/pricing-analysis.md` §6](design/pricing-analysis.md#6-tier-structure)
  and [`docs/design/multi-tenant-launch.md` §6](design/multi-tenant-launch.md).

> **Keep this current.** Update this entry when either (a) the router-cap
> default changes, or (b) a real multi-router tier or a self-serve upgrade flow
> ships — at which point "reply here and we'll raise it manually" becomes wrong
> and must be replaced with the actual upgrade path. The cap value is stated in
> exactly one place here (the quotable answer); everything else refers to the
> migration, so there is one line to change, not several.

---

## What's the difference between self-hosted and Cloud? / Can I run it myself?

**Quotable answer.**

> Both, honestly — it's the same software either way, and the difference is
> narrower than it sounds.
>
> **Cloud** (what you're on) means we run the API and the dashboard for you at
> `app.wifihaven.net`, so there's nothing to install or upgrade beyond the
> agent on your router.
>
> **Self-hosted** means you run that same API server yourself, on your own
> hardware — a small Linux box at home is plenty. It bundles the dashboard, so
> it's one thing to run, and nothing about that install talks to a WifiHaven
> server. It's free, permanently.
>
> What doesn't change is where the blocking happens. On both, rules are
> enforced right on your own router, in real time — the API is only the piece
> that decides the policy and sends it down; your family's traffic never goes
> through it. So it's really a question of where the dashboard and your account
> data live, not where the filtering happens.

**The direction inverts if you write it from memory — don't.** Self-hosted is
**the customer's** hardware; Cloud is **ours**. We stated it backwards to a
journalist once ([#2718](https://github.com/wifihaven/wifihaven/issues/2718)),
inside an answer that was otherwise correct, which is exactly why it went
unnoticed.

**Who asks this.** Everyone on a support thread is a Cloud customer, on both
intake paths. A self-hosted install has no account with us and no support
widget, so it cannot open a thread; and mail to `support@` only reaches the
agent when the From address matches a registered household admin
([`SupportResponder.scala` `emailIntakeGate` /
`resolveAdminHousehold`](../api/src/support/SupportResponder.scala) — for
anyone else no agent session runs at all; an unregistered *new* thread also
gets a fixed non-agent reject, continuations are skipped silently). So treat
the question as "what would I be giving up / gaining", never as a diagnostic,
and don't open by asking which tier they're on. What the gate does **not**
establish is what the question is about: a Cloud customer asking "could I run
this myself?" is a normal question, not a misread.

**Moving between tiers** is not self-serve. Escalate rather than describing a
migration path.

### Where this comes from

- [`docs/architecture.md` § "Deployment model: self-hosted vs.
  cloud"](architecture.md) is the canonical statement — the tier table, the
  SPA-hosting split behind it, and the enforcement point. Quote from there.
- Pricing and the current beta state are whatever the public pricing section of
  [`web-marketing/site/index.html`](../web-marketing/site/index.html) says.
- Enforcement being router-local on both tiers is the "Architectural model"
  callout in [`AGENTS.md`](../AGENTS.md): the API ships a policy snapshot, the
  router applies it with nftables.

> **Keep this current.** The quotable answer states no price, on purpose — the
> beta/GA state moves, and the pricing page is the one place that tracks it.
> Update this entry if a self-serve tier switch ever ships, or if self-hosted
> stops bundling the SPA into the API image.
