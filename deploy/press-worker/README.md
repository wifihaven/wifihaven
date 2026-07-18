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

## Provisioning (operator, once)

Everything below is dark until done; the API's `press.responderEnabled` flag must ALSO be flipped
(render.yaml PR) after the secrets are set — see [`../press-agent/README.md`](../press-agent/README.md).

1. **Deploy the Worker** (from this directory):
   ```sh
   npm install
   npx wrangler deploy                # prod  → wifihaven-press-worker
   npx wrangler deploy --env staging  # staging → wifihaven-press-worker-staging
   ```
2. **Set the shared secret** — the SAME value as the API's `WIFIHAVEN_PRESS_WEBHOOK_SECRET`
   (generate ≥32 random chars; set it in Render for the API and here):
   ```sh
   npx wrangler secret put PRESS_WEBHOOK_SECRET
   npx wrangler secret put PRESS_WEBHOOK_SECRET --env staging
   ```
3. **Bind the address to the Worker** — Cloudflare dashboard → the `wifihaven.net` zone → Email →
   Email Routing → Routing rules → add `press@wifihaven.net` → action **Send to a Worker** →
   `wifihaven-press-worker`. (Email Routing must be enabled on the zone, which sets the MX records.)
   Equivalent API call: `POST /zones/{zone}/email/routing/rules` with a `worker` action — or add a
   `cloudflare_email_routing_rule` to `infra/cloudflare` once the CF token carries the Email scope.
4. **Flip the API on** — set the press secrets in Render (`WIFIHAVEN_PRESS_*`) and, in a render.yaml
   PR, set `WIFIHAVEN_PRESS_RESPONDER_ENABLED=true` (staging first). Outbound email
   (`WIFIHAVEN_EMAIL_*`) must be configured too — the API refuses to boot with the press responder on
   and email off (#2265).

## Notes

- **`postal-mime`** parses the raw MIME to plain text; `run npm install` before `wrangler deploy`.
- The Worker never bounces the sender and returns nothing on a downstream error (it logs to the CF
  dashboard) — mail is never lost.
- Local dev: `npx wrangler dev` won't receive real email; test the API leg directly by POSTing a
  signed envelope to `/api/press/inbound` (see `PressResponderSpec` for the exact shape).
