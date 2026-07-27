# No dark-by-default — fail loud on missing required config

This was added to AGENTS.md §"No dark-by-default — required config fails loud";
see AGENTS.md for the TOC entry.

## No dark-by-default {#no-dark-by-default}

**A feature that *requires* config MUST fail loud when that config is missing or
invalid — never silently turn itself off.** "Fail loud" means one of:

- a **typed startup validation error** (`zio-config`) that crashes boot with a
  clear, actionable message naming the missing key, or
- for a runtime dependency that can only be checked live, a **loud, alerting
  error** (logged at error, metered, dashboarded) — not a swallowed no-op.

**Absence of a secret is a bug, not a "disable" switch.** When a missing or
blank env var / secret makes a feature quietly no-op, two failure modes follow,
and both look "fine" from the outside:

- If the var is **never set**, the feature silently never runs.
- If the var is **lost later** — config drift, a deploy that drops a secret, a
  scope/permission change — the feature silently stops, with **no signal**.

This has been an expensive, recurring failure class for us:

- **[#1972](https://github.com/wifihaven/wifihaven/issues/1972)** — Loki
  0-ingest. The Grafana Cloud token lacked the `logs:write` scope, so logs were
  silently dropped; a logback `<if>` condition also silently rejected its config.
  Nothing surfaced that log export had stopped.
- The **category-enforcement path was a silent no-op on prod** until
  **[#1334](https://github.com/wifihaven/wifihaven/issues/1334)** fixed the
  agent's blocklist-fetch call — devices went unblocked and nothing said so.
- The **Notifier / Resend email seam**
  ([#2195](https://github.com/wifihaven/wifihaven/issues/2195)) and the
  **Stripe client** ([#2135](https://github.com/wifihaven/wifihaven/issues/2135))
  are the same shape today: a client that degrades to a no-op when its key is
  absent. `EmailConfig.enabled` is `apiKeyTrimmed.nonEmpty && fromTrimmed.nonEmpty`
  and `StripeConfig.enabled` is `secretKey.trim.nonEmpty` (`api/src/Config.scala`)
  — an `enabled` flag *derived from secret presence*, i.e. absence ⇒ disabled.
  That is precisely the anti-pattern below. (These are self-hosted-optional by
  design, so the fix is rule 3, not "always fail": make the off-state an explicit
  named flag, not the absence of a key. The audit that converts them is tracked
  separately.)

The root cause in each is identical: **absence of config was treated as
"disabled" instead of as an error.**

## The five rules

### 1. Required config fails loud

A feature that requires config validates that config at the boundary and
**crashes or alerts** when it's missing/invalid. The model is the JWT-secret
guard in `api/src/Config.scala`
([#2084](https://github.com/wifihaven/wifihaven/issues/2084)): a `require`
in the `JwtConfig` case class that fails boot with
`"wifihaven.jwt.secret must be at least 32 characters …"` rather than starting
with a weak secret. Required means: if it's absent, the feature cannot do its
job, so the process should not come up pretending it can.

### 2. Config-before-code

**Set the env var / secret in ALL target environments *before* (or atomically
with) the code that depends on it lands.** Sequence the config rollout ahead of
the code. Dependent code **assumes the config is present** — it does **not**
defensively degrade to off "just in case the secret isn't there yet." That
defensive branch is exactly what turns a one-deploy gap into a permanent silent
outage. If the config genuinely can't be set ahead of time in every
environment, the feature is optional — see rule 3, and say so explicitly.

### 3. Required vs. genuinely-optional is explicit

If a feature is *legitimately* optional-off (e.g. a paid seam a self-hosted
install never uses), that disabled state must be **named, deliberate, and
observable**:

- chosen by an **explicit flag** — a real `enabled: Boolean` config key set to
  `false` — **not** by the *absence* of a secret,
- **logged at startup** so an operator reading boot logs sees "X: disabled",
  and
- **surfaced in a health/config endpoint** so the running state is inspectable
  without grepping logs.

An unlabeled silent branch is banned **even for optional features**. "Disabled
because nobody set the key" and "disabled because the operator chose to" must be
distinguishable — the first is a bug, the second is a decision.

### 4. Startup validation reports ALL missing keys at once

When required config is missing, report **every** missing/invalid required key
in one pass, not just the first one that fails. A misconfigured deploy should be
diagnosable from a single boot attempt — an operator shouldn't fix one key,
redeploy, discover the next, and repeat. Accumulate the errors and fail with the
full list.

### 5. Best-effort side-writes — split by cause: skip flakes, but a bad credential means broken

Runtime best-effort / enrichment side-writes (an external call made *alongside* a
primary operation, not on its critical path) are the one place a non-fatal
failure is legitimate — but only for the right *cause*. It is tempting to make
these uniformly non-fatal ("don't let the enrichment crash the request") and call
a logged/metered failure good enough. That is wrong for a whole class of failure.
**Sort side-write failures by cause, because the cause dictates whether you may
skip or must fail:**

- **Transient / genuinely-optional failure** — a network blip, a timeout, an
  upstream 5xx, a truly best-effort enrichment the request does not need. **Skip
  it**: keep the failure non-fatal so the primary request still succeeds. Even
  then it MUST be **logged** and **metered with an attributable `{outcome}`** — a
  bounded counter, never a bare `catch` / `.ignore` — so a path that has quietly
  stopped producing anything is visible on a dashboard, not inferred only from
  user reports.
- **Broken credential / misconfiguration** — a wrong, expired, or under-scoped
  API key (a 401/403), a missing permission, an unprovisioned dependency, an
  unregistered schema. **This is not optional degradation — the integration is
  simply broken, and we should be broken too.** It is the same class as the
  missing-secret bug above, so it must **fail loud**: fail the request, or —
  better, when the credential/permission/schema is checkable ahead of time —
  **fail at provisioning/startup** so it never reaches a live request. Metering a
  403 and continuing is exactly the silent-degradation anti-pattern; a bad
  credential must not be swallowed into a log line or a dashboard blip.

The dividing line is **recoverable-by-config**: if setting a key, granting a
permission, or registering a schema would fix it, it is a config bug and belongs
in the fail-loud path — not behind a metric. Flakiness cannot be fixed by config,
so it may be skipped (and metered).

**Worked example — the Plain tenant-entitlement write
([#2410](https://github.com/wifihaven/wifihaven/issues/2410)).**
`PlainClient.upsertCustomer` runs `upsertTenantEntitlement` as a best-effort
step alongside the customer upsert (`api/src/support/PlainClient.scala`): the
household's `plan` / `founding` tenant fields are written whether or not the
customer half succeeds, and never flip the customer outcome. (Since
[#2435](https://github.com/wifihaven/wifihaven/issues/2435) it runs *first* — the
email-collision reconcile joins the customer to the household's Plain tenant, so
that tenant has to exist by then.) Today,
if the machine-user key lacks `tenantField:create` / `tenantField:update`, or the
`plan` (String) / `founding` (Boolean) tenant-field schemas
(`docs/ops/plain-setup.md` §7.3) aren't registered, each field write **fails**
(a 403 for the missing permission, a field error for an unregistered schema),
the path records `outcome=error` on `support_tenant_upsert_total` (via
`AppMetrics.supportTenantUpsert`), and the customer upsert still returns success
while the entitlement fields silently never appear. The metric makes that
*observable* — but a 403 from an under-permissioned key is a **broken
credential**, not a flake, so metering-and-continuing is the wrong handling:
**[#2410](https://github.com/wifihaven/wifihaven/issues/2410) makes that path
fail** (surfaced at provisioning, where the permission/schema gap is knowable,
rather than degrading a live write into a metered no-op). A genuine transient
tenant-write hiccup, by contrast, is fine to skip-and-meter.

## Anti-patterns — grep for these

These shapes are the tell. Every one of them means "absence of config ⇒ feature
off, silently":

- **`sys.env.get(...).getOrElse(<disabled>)`** — reading a secret and defaulting
  to an off/empty/no-op value when it's unset. The `getOrElse` *is* the dark
  switch.
- **`Option[...]` config that gates whether a feature runs** — an `Option`
  secret pattern-matched `case None => <no-op>`. If `None` silently disables a
  required feature, it should be a required key that fails validation instead.
- **An `enabled` flag *derived from* secret presence** —
  `val enabled = apiKey.nonEmpty`. It reads like an explicit toggle but is really
  absence-⇒-off in disguise (rule 3 wants a *standalone* flag).
- **Feature flags that default off on absence** — a flag whose unset state is
  `false`, so forgetting to set it anywhere ships the feature dark with no
  error.
- **A client/layer that returns a no-op instance when unconfigured** (e.g. a
  `Disabled`/`noop` transport selected by missing keys) *without* an explicit
  `enabled=false` decision, a startup log line, and a health-endpoint signal.
- **A best-effort side-write that treats a broken credential like a flake** —
  `foo().catchAll(e => ZIO.logWarning(...))`, or even `... *> meter("error")`, on
  an enrichment call that 401/403s because a key is wrong or under-permissioned.
  A metric makes it visible but does **not** make it acceptable: a
  config-recoverable failure belongs in the fail-loud path (fail the request, or
  fail at provisioning), not behind a log line or a counter (rule 5).
- **A best-effort side-write whose failure path is a bare log-and-continue with
  no metric** — even for a legitimately-skippable transient failure, swallowing
  it with no `{outcome}` counter means a path that has gone dark is invisible.
  Add the bounded counter (rule 5).

When you find one on a **required** feature, convert it to fail-loud (rule 1).
When the feature is genuinely optional, convert it to an explicit, logged,
observable flag (rule 3). When it's a best-effort side-write, split by cause
(rule 5): a broken-credential / permission / provisioning failure fails loud (the
request, or better at startup); only a genuinely-transient / optional failure is
skipped — and even then it is logged + metered. Either way, never leave the
silent branch.
