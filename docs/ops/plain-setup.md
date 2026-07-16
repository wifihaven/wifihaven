# Plain support setup runbook (operator)

Step-by-step to provision **Plain** as WifiHaven's household-gated support inbox and
turn the integration on. This is the operator-driven half — you create the workspace
and hold the secrets; the code ships **dark** and lights up only once you set the keys.

- **Integration umbrella:** [#2206](https://github.com/wifihaven/wifihaven/issues/2206)
  (epic [#2197](https://github.com/wifihaven/wifihaven/issues/2197)).
- **What's already in the repo:** the config surface + `PlainClient` + the identified
  widget + the household→customer mapping ([#2199](https://github.com/wifihaven/wifihaven/issues/2199)),
  and the Cloudflare Email Routing DNS ([#2198](https://github.com/wifihaven/wifihaven/issues/2198)).
- **What still needs code once you've provisioned:** the go-live items in
  [#2240](https://github.com/wifihaven/wifihaven/issues/2240) (CSP allowlist, widget
  identity-field reconciliation, entitlement custom fields). Flagged inline below.
- **Separate follow-up:** the Claude responder ([#2200](https://github.com/wifihaven/wifihaven/issues/2200))
  reuses the webhook secret + a Claude API key.

> **Verify-as-you-go.** The env-var names, config gates, and Render service names below
> are authoritative (they come from the committed code). The Plain **dashboard
> navigation labels** are documented as of writing — confirm them against Plain's
> current UI, and where a step says *(verify)* treat it as a thing to double-check
> before relying on it.

---

## 0. Prerequisites

- A Plain workspace. Do this **twice**: a **test** workspace wired to **staging**, and
  your **live** workspace wired to **prod**. Keep their secrets separate.
- Confirm the workspace **region**. The code's `wifihaven.support.apiBase` defaults to
  the **UK** GraphQL endpoint `https://core-api.uk.plain.com/graphql/v1`. If your
  workspace is EU/US, you'll override `apiBase` (see §6).
- Access to the **Render** dashboard for `wifihaven-api-staging` and
  `wifihaven-api-prod` (to enter the secrets).

---

## 1. The four values you'll collect from Plain

Everything below maps to one committed, `sync:false` Render env var. Nothing is
committed to the repo — you enter values out-of-band in Render.

| # | Plain value | Where in Plain *(verify)* | Render env var | Gate it turns on |
|---|---|---|---|---|
| 1 | **Chat App ID** | Settings → Chat → **Create a Chat App** | `WIFIHAVEN_SUPPORT_PLAIN_APP_ID` | widget (with #3) |
| 2 | **Chat identity secret** (HMAC for verified customers) | Settings → Chat → your chat app → authentication / identity verification | `WIFIHAVEN_SUPPORT_PLAIN_IDENTITY_SECRET` | widget (with #1) |
| 3 | **API key** (machine user) | Settings → machine user → **Add API Key** → copy once | `WIFIHAVEN_SUPPORT_PLAIN_API_KEY` | write API (customer upsert; #2200 draft posts) |
| 4 | **Request-signing secret** (workspace-global) | Settings → **Request signing** | `WIFIHAVEN_SUPPORT_PLAIN_WEBHOOK_SECRET` | webhook verify (consumed by #2200) |

Two gates, independent:

- **Widget** renders once **both** `APP_ID` and `IDENTITY_SECRET` are set (an app id
  with no signing secret can't produce a verifiable identity, so a half-config = off).
- **Write API** (household→customer upsert) is live once `API_KEY` is set.

With none set, the widget renders nothing and the write client no-ops — the
self-hosted / unconfigured path is unaffected.

---

## 2. Channels — set up both Email and Chat

Plain surfaces support over **channels**. We use two, one per surface:

- **Email channel** → `support@wifihaven.net` inbound + outbound replies (§3).
- **Chat channel** → the identified in-app widget (§4).

They're independent — you can enable Email now and Chat when you flip the widget on.
Both flow into the same Plain inbox; #2200's Claude responder drafts replies regardless
of which channel a message arrived on.

---

## 3. Email channel — inbound forwarding into Plain

Plain receives email via a Postmark inbound address it shows you under
**Settings → Email → Receiving emails** (e.g.
`…@inbound.postmarkapp.com`). You forward `support@wifihaven.net` to that address with
**Cloudflare Email Routing** (declarative — see [#2198](https://github.com/wifihaven/wifihaven/issues/2198)).

1. **Apply the Email Routing Terraform** (the #2198 PR): enables Email Routing on the
   `wifihaven.net` zone (adds the `route1/2/3.mx.cloudflare.net` MX), registers the
   Postmark address as a destination, and creates the `support@wifihaven.net` → Postmark
   forward rule.
2. **Verify the destination address (manual, one-time).** Cloudflare emails a
   confirmation link to the Postmark address — which feeds your Plain inbox — so the
   confirmation **shows up as a conversation in Plain**. Open it and click Cloudflare's
   confirm link from there. (You can't click it in a normal mailbox because the mailbox
   *is* Plain.)
3. **Back in Plain** (Settings → Email → Receiving emails): tick **"Inbound email
   forwarding is set up"** → **Save and continue**, then complete **Sending emails** and
   **Enable email**.

> **DNS caveat — keep exactly one apex SPF record.** The apex already has
> `v=spf1 -all` (`infra/cloudflare/main.tf` `cloudflare_record.spf`). SPF is a *sending*
> policy; Email Routing *receives* and forwards with its own return-path, so it doesn't
> need the apex SPF. If Cloudflare offers to add an SPF/TXT to the apex, **skip it** — a
> second apex `v=spf1` TXT is an SPF permerror. Only take the MX records. This is handled
> in the #2198 Terraform; don't add SPF by hand in the dashboard.

---

## 4. Chat channel — the identified widget

1. **Create the Chat app** (Settings → Chat → Create a Chat App). Copy the **App ID**
   → value #1.
2. **Enable identity verification / authenticated customers** and copy the **identity
   secret** → value #2. *(verify: exact label + location.)*
3. **Note the install snippet** Plain gives you — specifically the **script URL** and the
   exact `Plain.init` shape for a verified customer. We need these for the go-live code
   items (§7): the CSP host allowlist and confirming the identity-hash field name.

The widget itself is already built (`web/src/components/SupportWidget.tsx`): it's
admin-only, renders inside the authenticated SPA, and fetches a **server-signed**
identity from `GET /api/support/identity` — the HMAC email hash is computed server-side
over the logged-in user's own household, so household A can never obtain household B's
identity. You don't wire any of that; you only provide the App ID + identity secret.

---

## 5. API key — machine user (write API)

1. Settings → create a **machine user**.
2. **Add API Key** and grant the scopes our two code paths need (least privilege — one
   key is shared by both):

   | Scope | Why | For |
   |---|---|---|
   | `customer:create` | `upsertCustomer` (household → Plain customer) | **#2199 (now)** |
   | `customer:edit` | `upsertCustomer` updates an existing customer | **#2199 (now)** |
   | `customer:read` | look up the household by `tenantIdentifier`/`externalId` for draft context | #2200 |
   | `thread:create` | `createThread` — the `PlainClient.writeThread` seam | #2200 |
   | `thread:reply` | post the AI-drafted reply into the thread | #2200 |
   | `threadEvent:create` | post the draft as an AI-labeled note/event (if #2200 uses a note vs an unsent reply) | #2200 |

   **Strict minimum for what's merged today (#2199):** just `customer:create` +
   `customer:edit`. **Recommended:** grant the full set now — the key is shown once and
   #2200 lands next, so this avoids re-issuing. The exact draft-posting scope
   (`thread:reply` vs `threadEvent:create`) is #2200's implementation choice; granting
   both keeps it unblocked. **Do NOT grant** `customer:delete`, `customer:impersonate`,
   `thread:assign`, or `thread:unassign` — nothing we do needs them.
3. **Copy the key immediately** (`plainApiKey_…`) — Plain shows it **once**. → value #3.
   Sent by the code as `Authorization: Bearer …`.

---

## 6. Set the values in Render

All four keys are declared `sync:false` in `render.yaml`, so they appear as
"value required" on each API service. Set them on:

- **`wifihaven-api-staging`** ← your **test** Plain workspace values
- **`wifihaven-api-prod`** ← your **live** workspace values

Render → service → **Environment** → fill each key → save (triggers a redeploy).

**Region override.** If your workspace is **not** UK, the default
`apiBase` (`https://core-api.uk.plain.com/graphql/v1`) is wrong. `apiBase` is not one of
the four env vars today, so overriding it means either editing the `support {}` block in
`docker/entrypoint.sh` to render it from a new env var, or setting it in config. If you
end up on a non-UK region, file a note on #2206 and we'll add a
`WIFIHAVEN_SUPPORT_PLAIN_API_BASE` passthrough.

---

## 7. Go-live code items (tracked in [#2240](https://github.com/wifihaven/wifihaven/issues/2240))

These can't work from keys alone — they need code changes that depend on details you
only get once the chat app exists. Send us the chat install snippet (§4.3) and we'll land
these:

1. **CSP allowlist.** The SPA CSP is `script-src 'self'` (`web/public/_headers` +
   `api/src/SecurityHeaders.scala`), so the browser will **block Plain's widget script**
   even with the keys set. We add Plain's chat **script-src / connect-src / frame-src**
   hosts (from the install snippet) to both CSP definitions.
2. **Widget identity-field reconciliation.** Our widget currently sends
   `customerDetails: { email, emailHash }` where `emailHash = HMAC-SHA256(identitySecret,
   lowercased-email)`. Plain's chat-auth doc is the authority on the exact field name (it
   may be `customerHash`) and hashed value — we reconcile `SupportWidget.tsx` +
   `SupportService` to match.
3. **Entitlement custom fields.** To actually send `plan`/`founding` to Plain, register
   those custom fields in the workspace, then we wire their field ids into the
   `upsertCustomer` mutation (`PlainClient.customerFields`, marked `TODO(#2240)`).

---

## 8. Verify (staging first)

1. Log in as an **admin with an email** on staging → the Plain chat bubble should appear.
   If it doesn't, open the browser console:
   - a **CSP violation** means §7.1 isn't done yet;
   - otherwise check `GET /api/support/identity` returns `configured: true` with an
     `emailHash` + `tenantIdentifier`.
2. In Plain, confirm a **customer** was created with `tenantIdentifier = <household_id>`
   and `externalId` set — that's the household-gating mapping working.
3. Send a test email to `support@wifihaven.net` and a test chat → both land in the Plain
   inbox. (Auto-drafted replies are #2200, separate.)

Once staging looks right, repeat §1–§6 against the live workspace + `wifihaven-api-prod`.

---

## 9. What stays manual (no Terraform)

Plain has **no Terraform provider**, so the workspace, channels, chat app, machine-user
API key, and request-signing secret are dashboard-managed (secrets are shown once, so
inherently out-of-band). The declarative parts already live in the repo:

- **Cloudflare Email Routing DNS/MX** → `infra/cloudflare/main.tf` (#2198).
- **Render env-var declarations** → `render.yaml` (`sync:false`); values entered
  out-of-band, never committed.

This runbook is the reproducible record of the dashboard steps.
