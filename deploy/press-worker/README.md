# Press inbox Email Worker — provisioning (#2203)

`press@wifihaven.net` is handled by a **Cloudflare Email Worker**, not a mailbox or a helpdesk
vendor. Cloudflare Email Routing catches the address and runs [`src/index.ts`](src/index.ts) per
message. The Worker is deliberately thin:

1. parse the inbound email (sender, subject, text),
2. build a small JSON envelope `{from, subject, text, messageId}`,
3. HMAC-SHA256-sign the raw body under the secret it **shares** with the API
   (`press.webhookSecret`), in the `X-WifiHaven-Signature` header,
4. `POST` it to `${PRESS_API_URL}/api/press/inbound`.

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

## Reply identity: From ≠ Reply-To (#2407)

The outbound reply carries **two different addresses**, and the split is deliberate:

| | staging | prod | why |
|---|---|---|---|
| **From** (`WIFIHAVEN_PRESS_FROM_ADDRESS`) | `press-staging@wifihaven.net` | `press@wifihaven.net` | must be on a Resend-**verified sending domain** |
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
| `PRESS_WEBHOOK_SECRET` | **prod** shared HMAC secret — MUST equal the prod API's `WIFIHAVEN_PRESS_WEBHOOK_SECRET`. |
| `PRESS_WEBHOOK_SECRET_STAGING` | **staging** shared HMAC secret — MUST equal the staging API's `WIFIHAVEN_PRESS_WEBHOOK_SECRET`. |

**Operator prerequisites TF/CI can't do:** Email Routing must be enabled on the `wifihaven.net` zone
(and the `staging` subdomain added — the same one-time steps `support`'s rules document in
`infra/cloudflare/main.tf`), and the CF token must carry the Workers scope.

3. **Flip the API on** — set the press secrets in Render (`WIFIHAVEN_PRESS_*`, matching the repo
   secrets above) and, in a render.yaml PR, set `WIFIHAVEN_PRESS_RESPONDER_ENABLED=true` (staging
   first). Outbound email (`WIFIHAVEN_EMAIL_*`) must be configured too — the API refuses to boot with
   the press responder on and email off (#2265).

> First-time bootstrap: a local `npm install && npx wrangler deploy` from this directory works too
> (identical result), but is not required — CI is the source of truth.

## Notes

- **`postal-mime`** parses the raw MIME to plain text; `run npm install` before `wrangler deploy`.
- The Worker never bounces the sender and returns nothing on a downstream error (it logs to the CF
  dashboard) — mail is never lost.
- Local dev: `npx wrangler dev` won't receive real email; test the API leg directly by POSTing a
  signed envelope to `/api/press/inbound` (see `PressResponderSpec` for the exact shape).
