# Press inbox Email Worker — provisioning (#2203)

`press@wifihaven.net` is handled by a **Cloudflare Email Worker**, not a mailbox or a helpdesk
vendor. Cloudflare Email Routing catches the address and runs [`src/index.ts`](src/index.ts) per
message. The Worker is deliberately thin:

1. parse the inbound email (sender, subject, text),
2. classify it against the auto-reply/DSN markers ([`src/loop-guard.ts`](src/loop-guard.ts), #2442),
3. build a small JSON envelope `{from, subject, text, messageId, loopGuard}`,
4. HMAC-SHA256-sign the raw body under the secret it **shares** with the API
   (`press.webhookSecret`), in the `X-WifiHaven-Signature` header,
5. `POST` it to `${PRESS_API_URL}/api/press/inbound`.

The **API** does everything else: it verifies the signature, dispatches an Anthropic Managed Agents
**press session** (persona in [`../press-agent/`](../press-agent/)), and — when the agent posts its
reply back — **emails it straight to the sender** via Resend. The reply destination is locked into
the per-session token, so a prompt-injected agent can't redirect it. The Worker holds **no** Anthropic
key and makes **no** AI call. See `docs/process/declarative-config.md`.

## Data flow

```
press@wifihaven.net
   │  (Cloudflare Email Routing rule → Worker)
   ▼
wifihaven-press-worker  ──HMAC-signed POST──▶  POST /api/press/inbound   (WifiHaven API)
                                                     │  verify sig → dispatch
                                                     ▼
                                            Anthropic Managed Agents (press persona)
                                                     │  POST /api/press/agent/reply (token)
                                                     ▼
                                            API emails the reply → the original sender (Resend)
```

## Auto-reply / DSN loop guard (#2442)

Prod's press **From and Reply-To are both `press@wifihaven.net`** — the address Email Routing binds
to this Worker — and the prod responder is enabled (#2537). So anything that auto-replies to a reply
we send comes straight back here: an out-of-office, a newsroom ticketing acknowledgement, a
bounce/DSN. Answer it and it answers back.

[`src/loop-guard.ts`](src/loop-guard.ts) classifies each inbound message from its headers and the
SMTP envelope sender, and stamps the verdict on the envelope as `loopGuard`:

| marker | trips on |
|---|---|
| `auto_submitted` | `Auto-Submitted:` with any value other than `no` (RFC 3834) |
| `precedence` | `Precedence: bulk` / `auto_reply` / `junk` |
| `x_auto_response_suppress` | `X-Auto-Response-Suppress:` present |
| `list_id` | `List-Id:` present |
| `null_return_path` | `Return-Path: <>` (or empty), or an empty envelope `MAIL FROM` — the DSN signature |

The **API** enforces it: a non-empty `loopGuard` skips dispatch entirely (`PressResponder`), so no
agent session is created and no reply is sent. It is read *before* the `from`/`text` requirement,
because a bounce has neither — pre-#2442 those landed in `outcome=malformed`, indistinguishable from
a broken Worker.

The skip is **not** silent (#2265/#2266). Every one lands on `press_loop_guard_total{reason}` and on
`press_ai_draft_total{outcome="skipped_auto_submitted"}`, both on the Press Grafana dashboard, plus a
`console.warn` here naming the sender (Workers Logs, on since #2673) — the counter deliberately
carries no address. That log line is how you check whether a rising count is autoresponders or a
journalist we wrongly ignored.

Deliberately **not** in the skip set: `Precedence: list` / `first-class` / `normal`, `Auto-Submitted:
no`, and any of these header names carrying an empty value. Wrongly dropping a reporter is the worse
failure, so the classifier errs toward delivering.

## Reply identity: From ≠ Reply-To (#2407)

The outbound reply carries **two different addresses**, and the split is deliberate:

These are the values `render.yaml` ships — set them verbatim, display name included:

| | staging | prod | why |
|---|---|---|---|
| **From** (`WIFIHAVEN_PRESS_FROM_ADDRESS`) | `WifiHaven Press <press-staging@wifihaven.net>` | `WifiHaven Press <press@wifihaven.net>` | the mailbox must be on a Resend-**verified sending domain** |
| **Reply-To** (`WIFIHAVEN_PRESS_REPLY_TO_ADDRESS`) | `press@staging.wifihaven.net` | `press@wifihaven.net` | must be an address Email Routing actually **delivers to this Worker** |

Resend verifies per-**domain**, and only the apex `wifihaven.net` is verified (the
`resend._domainkey` DKIM + the `send.wifihaven.net` return-path MX in
`infra/cloudflare/main.tf`). `staging.wifihaven.net` is a *separate*, unverified domain to Resend —
adding it is a paid plan add-on — so staging borrows the apex as `press-staging@`. The `staging`
DKIM that does exist belongs to **Postmark**, for Plain's `support@` mail, and does not cover
Resend.

(The apex-with-env-suffix shape follows the shared notification sender, observed in
[#2407](https://github.com/wifihaven/wifihaven/issues/2407) sending as
`alerts-staging@wifihaven.net`. That value is `sync: false` in `render.yaml`, so it is an observed
deployment value rather than something checked in.)

This matters because our DMARC is `p=reject; sp=reject; adkim=s`: a From on the unverified
subdomain is **rejected outright**, not spam-filed. `adkim=s` also requires the DKIM `d=` to
strict-align with the From domain — which the apex satisfies.

Reply-To is exempt from DMARC alignment, so it can safely name `press@staging.wifihaven.net`, the
address the routing rules above actually bind to this Worker. That's what makes a journalist's
human follow-up thread back into the pipeline.

Both keys are **required** when `press.responderEnabled` is true (`PressConfig.missingRequiredKeys`,
#2265 no-dark-by-default) — boot fails loudly rather than silently falling back to the shared
`alerts@` notification sender.

**Caveat:** `press-staging@wifihaven.net` is a *sending-only* identity — no Email Routing rule binds
it, and there is no apex catch-all. Mail addressed to it (a bounce/DSN, an out-of-office, or a
client that ignores `Reply-To`) is rejected at the apex MX rather than reaching the Worker. That is
acceptable because every reply we send sets `Reply-To` to the routed inbox, but it is why the two
keys must not be collapsed back into one.

## Provisioning — declarative (no manual `wrangler deploy`, no dashboard clicks)

Both halves are configured in-repo and applied by CI on merge (`docs/process/declarative-config.md`).
Everything is dark until the repo secrets exist; the API's `press.responderEnabled` flag is the final
switch (render.yaml PR) — see [`../press-agent/README.md`](../press-agent/README.md).

1. **Worker deploy + secret sync — CI.** [`.github/workflows/master-press-worker.yml`](../../.github/workflows/master-press-worker.yml)
   runs `wrangler deploy` (prod → `wifihaven-press-worker`, staging → `wifihaven-press-worker-staging`)
   and pushes `PRESS_WEBHOOK_SECRET` on every merge touching `deploy/press-worker/**` — the same
   `cloudflare/wrangler-action` mechanism the SPA Pages deploy uses. It's dark until the CF token
   secret exists (skips with a notice, mirroring `master-support-agent.yml`).

2. **Address → Worker binding — Terraform.** `infra/cloudflare/main.tf` declares
   `cloudflare_email_routing_rule.press_to_worker` (+ `…_staging_to_worker`) with a `worker` action,
   applied by `master-cloudflare.yml`. No dashboard rule. (Ordering: the Worker must exist before the
   rule applies; the first TF apply before the worker's first deploy fails and succeeds on the next
   run — both are automated on merge.)

**Repo secrets to set (once, in GitHub → Settings → Secrets):**

| Secret | Value |
|---|---|
| `CLOUDFLARE_API_TOKEN` | Cloudflare token with **Workers Scripts: Edit** (+ the Email Routing / Pages scopes the other pipelines need). Gates both the worker deploy and the TF apply. |
| `CLOUDFLARE_ACCOUNT_ID` | the Cloudflare account id (already set for the SPA deploy). |
| `PRESS_WEBHOOK_SECRET_PROD` | **prod** shared HMAC secret — MUST equal the prod API's `WIFIHAVEN_PRESS_WEBHOOK_SECRET`. (This table and the workflow used to say `PRESS_WEBHOOK_SECRET`, but the secret that exists is `…_PROD`; the mismatch is why prod's Worker was never given a secret — #2673.) |
| `PRESS_WEBHOOK_SECRET_STAGING` | **staging** shared HMAC secret — MUST equal the staging API's `WIFIHAVEN_PRESS_WEBHOOK_SECRET`. |

**Operator prerequisites TF/CI can't do:** Email Routing must be enabled on the `wifihaven.net` zone
(and the `staging` subdomain added — the same one-time steps `support`'s rules document in
`infra/cloudflare/main.tf`), and the CF token must carry the Workers scope.

3. **Flip the API on** — set the press secrets in Render (`WIFIHAVEN_PRESS_*`, matching the repo
   secrets above) and, in a render.yaml PR, set `WIFIHAVEN_PRESS_RESPONDER_ENABLED=true` (staging
   first). Outbound email (`WIFIHAVEN_EMAIL_*`) must be configured too — the API refuses to boot with
   the press responder on and email off (#2265).

> First-time bootstrap: a local `npm ci && npx wrangler deploy` from this directory works too
> (identical result), but is not required — CI is the source of truth.

## Notes

- **`postal-mime`** parses the raw MIME to plain text; run `npm ci` before `wrangler deploy` (CI and CD both install from the lockfile, so a local `npm install` can bundle a different toolchain than the one that ships).
- **An unconfigured Worker rejects the message (#2673).** If `PRESS_WEBHOOK_SECRET` or
  `PRESS_API_URL` is unset on a deployment, the Worker `setReject`s the inbound mail (a permanent
  SMTP failure — the sender is told it did not arrive and pointed at `support@wifihaven.net`), and
  logs which binding(s) are missing by name. It does **not** accept-and-discard: that is what it
  used to do, and press@ silently ate journalist mail for weeks in prod because
  `PRESS_WEBHOOK_SECRET` was never set. Both bindings are fixed at deploy time, so this can only
  ever mean misconfiguration — never a transient fault.
- Once configured, the Worker does **not** bounce on a downstream failure: a 4xx/5xx from the API is
  logged to Workers Logs and the message is accepted, so a hiccup at the API does not become a
  delivery failure at the journalist.
- Local dev: `npx wrangler dev --env=""` serves the email handler at
  `POST /cdn-cgi/handler/email?from=…&to=…` (body = a raw `.eml`), which is how the config guard
  above is exercised end-to-end without real mail. Put local values in `.dev.vars` (gitignored) to
  test the configured path; point `PRESS_API_URL` at a local sink, **never** at
  `https://api.wifihaven.net`. The API leg can also be tested directly by POSTing a signed envelope
  to `/api/press/inbound` (see `PressResponderSpec` for the exact shape).
- Tests: `npm test` (vitest) covers the loop-guard classifier and the missing-binding guard;
  `npm run typecheck` covers both `src/` and `test/`. Both run in CI on any `deploy/press-worker/**`
  change (the `Press Worker Tests` job in `.github/workflows/ci.yml`) — `master-press-worker.yml`
  only deploys.
