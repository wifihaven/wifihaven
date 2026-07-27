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
| 3 | **API key** (machine user) | Settings → machine user → **Add API Key** → copy once, **then grant its `permissions` array via GraphQL** (§5.1 — no UI toggle; fixes `403 Forbidden`) | `WIFIHAVEN_SUPPORT_PLAIN_API_KEY` | write API (customer upsert; #2200 draft posts) |
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
   forwarding is set up"** → **Save and continue**. Inbound now works — but the workspace
   still **cannot send**. Finish §3.1 below or every reply is dropped.

> **DNS caveat — keep exactly one apex SPF record.** The apex already has
> `v=spf1 -all` (`infra/cloudflare/main.tf` `cloudflare_record.spf`). SPF is a *sending*
> policy; Email Routing *receives* and forwards with its own return-path, so it doesn't
> need the apex SPF. If Cloudflare offers to add an SPF/TXT to the apex, **skip it** — a
> second apex `v=spf1` TXT is an SPF permerror. Only take the MX records. This is handled
> in the #2198 Terraform; don't add SPF by hand in the dashboard.

### 3.1 Email SENDING — a required go-live gate, per workspace

> **This is a hard gate, and skipping it fails silently.** Inbound intake, the
> registered/unregistered decision, and the #2307 static reject can all be perfectly
> correct while **every** outbound message is dropped by Plain with:
>
> ```
> Emails are not enabled for this workspace. Enable them in your workspace
> email settings to send emails.
> ```
>
> That is exactly what happened on **staging** ([#2471](https://github.com/wifihaven/wifihaven/issues/2471),
> found 2026-07-26 during [#2335](https://github.com/wifihaven/wifihaven/issues/2335)
> validation): the DNS had shipped months earlier, but nobody completed the Plain-side
> steps, so no customer ever received a reply. Do this **once per workspace** — staging
> and prod are separate Plain workspaces and enabling one does nothing for the other.

**The DNS is already in the repo — you do not add records by hand.**
`infra/cloudflare/main.tf` carries both sending domains
([#2247](https://github.com/wifihaven/wifihaven/issues/2247)); each is a Postmark-issued
per-domain DKIM selector plus a custom Return-Path, so DKIM `d=` strict-aligns with the
`From` domain under our `adkim=s` DMARC:

| Workspace | From address | DKIM TXT (`cloudflare_record`) | Return-Path CNAME |
|---|---|---|---|
| prod | `support@wifihaven.net` | `plain_dkim_prod` — `20260716020234pm._domainkey` | `plain_bounces_prod` — `plain-bounces` → `pm.mtasv.net` |
| staging | `support@staging.wifihaven.net` | `plain_dkim_staging` — `20260716163303pm._domainkey.staging` | `plain_bounces_staging` — `plain-bounces.staging` → `pm.mtasv.net` |

Confirm they resolve before touching the dashboard — Plain checks them live:

```sh
dig +short TXT   20260716163303pm._domainkey.staging.wifihaven.net   # expect k=rsa; p=…
dig +short CNAME plain-bounces.staging.wifihaven.net                 # expect pm.mtasv.net.
```

Then, in the Plain workspace for that environment:

1. **Settings → Channels → Email**, section **3. Sending emails**. It lists the same TXT
   and CNAME. **Diff them against what `dig` returned** — if the selector Plain shows
   differs from the one in `main.tf`, Plain reissued it and the Terraform needs updating
   *first*; do not paste a record into the Cloudflare dashboard
   (`docs/process/declarative-config.md`).
2. Click **Verify DNS and continue**. On success the button collapses to a plain
   **Verify DNS** re-check.
3. Section **4. Enable email** then appears. It must read **"Email is currently
   enabled."** with a red **Disable email** button next to it. If instead it offers an
   *Enable* button, click it — §3 verifying is not by itself sufficient.

**Verify it actually sends — do not trust the toggle.** From an address that is **not** a
registered household admin (a personal Gmail is ideal — a registered address takes the AI
dispatch path instead), email the environment's support address, then check the API log:

```sh
# staging: srv-d8549fgjo89c73buvkf0, owner tea-d8543pmk1jcs73aqoja0
# the Render logs API `text=` filter is the practical way to find these among router noise
curl -sG -H "Authorization: Bearer $RENDER_API_TOKEN" \
  --data-urlencode "ownerId=$OWNER" --data-urlencode "resource=$SERVICE" \
  --data-urlencode "text=webhook outcome" https://api.render.com/v1/logs
```

Three things together mean it worked:

- `outcome=email_unregistered_rejected` — the reject was decided **and delivered**. If you
  see `outcome=email_reject_send_failed` instead, the send was refused and you are not
  done (that outcome exists *because* of #2471; before it, a refused send reported the
  success label).
- **No** `WARN … plain replyToThread failed` line. Search for `PlainClient` and expect
  nothing.
- A `eventType=thread.email_sent` webhook on the same thread — Plain only emits that when
  a message actually leaves. This is the strongest signal; prefer it over the absence of
  an error.

The rejection message should also land in the sending mailbox.

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

## 5. API key — machine user (write API) {#machine-user-permissions}

1. Settings → create a **machine user**.
2. **Add API Key** (Settings → machine user → **Add API Key**) and copy the secret. This
   mints the key and the bearer token, **but it does NOT grant any permissions** — see the
   next step, which is the part that's easy to miss.
3. **Grant the key its permissions via GraphQL (required — fixes `403 Forbidden`).** See
   below.
4. **Copy the key immediately** — Plain shows the **secret** (`plainApiKey_…`, sent by the
   code as `Authorization: Bearer …`) **once**. → value #3. The key's **id**
   (`apiKey_…`, distinct from the secret) is what you pass to the permission mutations
   below; you can look it up any time (§5.2), so only the secret is truly once-only.

### 5.1 Permissions are an API-key array set via GraphQL — there is no UI toggle {#no-permissions-ui}

> **This cost a live debugging + a Plain support email to discover during staging
> go-live.** `PlainClient` calls to `upsertCustomer` / `upsertTenant` returned
> **`403 {"message":"Forbidden"}`** even though the API key was valid, and the
> machine-user settings page had **no permissions/roles control** anywhere on it.
> Plain support clarified: **machine-user API access is scoped by the API key's
> `permissions` array, and that array is set ONLY through Plain's GraphQL
> `createApiKey` / `updateApiKey` mutations — there is no UI to edit it.** A key whose
> `permissions` array is missing a permission returns `403 Forbidden` on the
> corresponding write. A freshly "Add API Key"'d key can come with an empty/insufficient
> array, which is why the writes 403 until you run the mutation below.

**The exact permission set WifiHaven's support integration needs** (least privilege — one
key is shared by all support write paths):

| Permission | What it's for |
|---|---|
| `customer:create` | `upsertCustomer` — create the identified-widget customer (household → Plain customer) |
| `customer:edit` | `upsertCustomer` — update an existing customer |
| `tenant:read` | `upsertTenant` — read back the tenant's internal `id` (needed to key the tenant-field writes) |
| `tenant:create` | `upsertTenant` — create the household tenant |
| `tenant:edit` | `upsertTenant` — update an existing household tenant (the upsert's update path — its name + the entitlement field writes). **Missing this returned `Insufficient permissions, missing "tenant:edit"` on `/api/support/identity` during staging go-live.** |
| `customerTenantMembership:create` | link the customer to its household tenant — the `tenantIdentifiers` membership on `upsertCustomer`, **and** the `addCustomerToTenants` mutation the [#2435](https://github.com/wifihaven/wifihaven/issues/2435) email-collision reconcile uses (Plain's `UpsertCustomerOnUpdateInput` has no `tenantIdentifiers`, so an already-existing customer can only be joined this way) |
| `customerTenantMembership:delete` | re-link / correct a customer's household tenant membership |
| `label:create` | **#2437** — apply the `needs-human` **escalation label** to a thread when the support agent hands off (`SupportResponder.agentEscalate` → `PlainClient.markThread`, Plain's `addLabels` mutation). This is what makes an escalated thread FILTERABLE in the inbox instead of indistinguishable from an AI-resolved one. Missing it → `addLabels` fails, logged `logError` and metered `support_agent_action_total{op="escalate_mark",outcome="error"}`. **Required.** |
| `thread:reply` | post the AI reply into the customer's existing thread — `SupportResponder.agentReply` → `PlainClient.writeThread`, whose live impl is Plain's `replyToThread` mutation (`api/src/support/PlainClient.scala`, #2408 — the customer-visible send). **Required.** |
| `thread:read` | read the bound thread's timeline so the responder can see the conversation so far — `SupportResponder.dispatchFor` → `PlainClient.threadHistory` (`api/src/support/PlainClient.scala`, #2430 — the stateless responder fires a FRESH cloud session per inbound message, so without this every follow-up is answered with no memory of the thread). **Required.** Fail-open (a missing grant costs context, never the webhook) but **not silent**: each denial is logged at ERROR and metered `support_thread_history_total{outcome="permission"}` (panel in `deploy/grafana/dashboards/support.json`). |
| `timeline:read` | read the thread's **timeline entries** — the `timelineEntries { edges { node … } }` selection inside `PlainClient.ThreadTimelineQuery` (#2430/#2441). `thread:read` alone is **not** enough: it authorizes the `thread` lookup, the entries underneath it need this scope. Discovered during staging validation. |
| `tenantField:create` / `tenantField:update` | write the `plan` / `founding` entitlement **values** on the household tenant — `upsertTenantField` (§7.3). The scope is `:update`, **not** `:edit`. |
| `webhookTarget:read` | in the validated staging array; **no caller in our code** — nothing in the repo references `webhookTarget` (we *receive* Plain's webhooks at `/api/support/webhook`, we never read the target config back). Granted during validation; keep it pending the [#2470](https://github.com/wifihaven/wifihaven/issues/2470) least-privilege audit. |
| `tenantFieldSchema:read` | in the validated staging array. **No explicit caller in our code** — `PlainClient` issues no schema query — so Plain appears to require it implicitly alongside the `tenantField` value writes. Granted during validation; keep it pending the [#2470](https://github.com/wifihaven/wifihaven/issues/2470) least-privilege audit. |
| `customer:read` | in the validated staging array; **no explicit caller in our code** (we only `upsertCustomer`). Granted during validation; keep it pending the [#2470](https://github.com/wifihaven/wifihaven/issues/2470) least-privilege audit. |

This is the array confirmed to clear the `403` on the customer + tenant upserts. The
`plan` / `founding` **tenant-field** writes ([§7.3 entitlement](#entitlement-fields))
additionally exercise `upsertTenantField`; to have those fields populated, add
`tenantField:create` / `tenantField:update` to the array (the scope is `:update`, **not**
`:edit` — Plain's permission enum has no `tenantField:edit`) **and** register the `plan` /
`founding` field schemas ([§7.3](#entitlement-fields) — an **API-only** step; there is no
Plain UI for creating tenant-field schemas. The *machine-user key* does not need
**`tenantFieldSchema:create`** — it only writes field *values*, never creates schemas, and
the schema mutation is run once by the authenticated admin in the API playground. It does
carry **`tenantFieldSchema:read`**, which the validated staging array includes).
**Grant the permission and register the schema as a pair, or leave both
off — a half-configured entitlement path is a broken credential, not an optional feature.**
That path is **fail-open only w.r.t. the customer upsert** — a permission or schema gap does
not fail `upsertCustomer` — but it is **not silent**: each failure is logged and metered as
`support_tenant_upsert_total{outcome="error"}` (`upsertTenantEntitlement` in
`api/src/support/PlainClient.scala`; panel in `deploy/grafana/dashboards/support.json`).
Making that gap fully fail-loud + attributable — a broken credential should *fail*, not
degrade into a metered no-op — is tracked in
[#2410](https://github.com/wifihaven/wifihaven/issues/2410).
[#2430](https://github.com/wifihaven/wifihaven/issues/2430) **added `thread:read` *and*
`timeline:read`** to the required set (the responder reads the bound thread's timeline so it
can see the conversation so far — `thread:read` authorizes the thread lookup, `timeline:read`
the entries under it, and **both** are needed). They are **new grants on an existing key**, so
re-run the `updateApiKey` mutation in
§5.3 for **both** environments — until you do, the responder keeps answering every follow-up
with no memory of the thread and `support_thread_history_total{outcome="permission"}` climbs.
**`thread:create` is no
longer needed** — #2408 replaced the old `createThread` send with `replyToThread`
(`thread:reply`), so the previously-granted `thread:create` can be dropped from the
array. **Do NOT grant** `customer:delete`, `customer:impersonate`, `thread:assign`, or
`thread:unassign` — nothing we do needs them.

**Where to run the mutations:** Plain's **API playground** in the dashboard (it runs as
the authenticated admin, so it can mint/grant keys). Docs for the write operations these
permissions gate: <https://www.plain.com/docs/graphql/customers/upsert> and
<https://www.plain.com/docs/graphql/tenants/upsert>.

#### The API checks this at boot — you do not have to trust this table (#2452) {#permission-audit}

At boot, whenever the Plain write client is enabled, the API reads the key's **own**
permission array (Plain's `myPermissions` query: *"Returns the full list of permission strings
granted to the currently authenticated user or machine user in this workspace"*) and compares
it against what **this deployment's enabled features** need.

The set it checks has ONE home in code —
[`PlainPermissionAudit`](../../api/src/support/PlainPermissionAudit.scala): `CorePermissions`
(always, when the write client is on) plus `ResponderPermissions` (only when
`responderEnabled`). It is deliberately the **needed** set, not the **granted** set: the
validated 16-scope array in §5.3 is a superset, because it also carries the scopes whose
caller is not yet established (`webhookTarget:read`, and arguably `tenantFieldSchema:read` —
[#2470](https://github.com/wifihaven/wifihaven/issues/2470)) plus the recommended-not-required
`customer:read`. So the audit will never nag you about a scope nothing depends on, and if
#2470 trims the array, `PlainPermissionAudit` is the one place to change.

The outcomes:

- **all granted** → one INFO line, `support_permission_probe_total{outcome="ok"}`;
- **something missing** → an **ERROR** naming **every** gap at once (not the first) plus the
  exact `updateApiKey` mutation to paste, and
  `support_permission_probe_total{outcome="missing"}`;
- **Plain rejects the request** (any 4xx except 408/429 — a revoked, rotated, or wrong key; a
  wrong endpoint) **or its probe shape drifted** (a 200 with no `myPermissions` array) → an
  **ERROR** and `{outcome="broken"}`. `myPermissions` needs no permission of its own, so a 403
  here is never an under-grant: the credential is unusable and **every** Plain call is failing,
  not just the probe. Check `WIFIHAVEN_SUPPORT_PLAIN_API_KEY` against the workspace's live key
  (§5.2);
- **Plain unreachable** (transport, timeout, 5xx, and the self-healing 408/429) → a warning and
  `{outcome="unreachable"}` — the grants are simply *unverified* this boot. This is the one
  **transient** bucket, and it is separate precisely so a rejected credential is never mistaken
  for an outage you can wait out. The permanent/transient line is drawn on the HTTP **status**
  by the same predicate the cloud-agent dispatcher uses (#2416), not by matching error wording.

`missing` and `broken` are both permanent and both log at ERROR; only `unreachable` is a
warning.

**It does not crash boot**, deliberately. A missing permission is a misconfiguration
([docs/process/no-dark-by-default.md](../process/no-dark-by-default.md)), but this check is a
live call to a third party — crashing on its answer would let a Plain outage or a Plain-side
permission rename take the whole API down (router enforcement, policy, usage ingest) for a
degraded support desk. So it takes the doc's other sanctioned shape: a **loud alerting runtime
error**. Watch the panel on `deploy/grafana/dashboards/support.json`; `missing` and `broken`
should both be flat zero.

To check by hand, run this in the API playground **authenticated as the machine user's key**
(as the admin it reports *your* permissions, not the key's):

```graphql
query { myPermissions { permissions } }
```

### 5.2 Find the API key's id

Permissions are set on the key's **id** (`apiKey_…`), not its secret. List a machine
user's keys — `apiKeys` is a **Relay connection**, so you must go through `edges { node }`:

```graphql
query {
  machineUser(machineUserId: "<mu_...>") {
    apiKeys(first: 20) { edges { node { id description } } }
  }
}
```

### 5.3 Grant the permissions

Grant the **whole array in one call** — `permissions` is set wholesale, not merged, so a
later mutation that omits a scope **revokes** it. Use the array below verbatim: it is the
exact set the staging key carries after end-to-end validation (customer + tenant upserts,
entitlement field writes, thread history, AI reply), not a set assembled from the table.

> This array is a hand-copy of `PlainPermissionAudit`'s
> `CorePermissions ++ ResponderPermissions ++ RecommendedPermissions`. Nothing mechanically
> enforces that they stay equal yet —
> [#2478](https://github.com/wifihaven/wifihaven/issues/2478) tracks a CI guard. Until it
> lands, the boot audit is a partial backstop: an array that *omits* a required permission shows
> up as `plain api-key permissions INCOMPLETE` on the next boot — but an array carrying a stale
> *extra* permission is invisible to it, since the audit only checks that what we need is present.

**Preferred — `updateApiKey`** (keeps the existing secret, so the
`WIFIHAVEN_SUPPORT_PLAIN_API_KEY` already set in Render does **not** need rotating):

```graphql
mutation {
  updateApiKey(input: {
    apiKeyId: "<apiKey_...>",
    permissions: ["thread:read","webhookTarget:read","timeline:read","tenantFieldSchema:read","customer:read","customer:create","customer:edit","tenant:read","tenant:create","tenant:edit","customerTenantMembership:create","customerTenantMembership:delete","thread:reply","label:create","tenantField:create","tenantField:update"]
  }) { apiKey { id permissions } }
}
```

**Alternative — `createApiKey`** (mints a **new** key; its `apiKeySecret` is returned
**once** at creation → store it immediately and update the Render
`WIFIHAVEN_SUPPORT_PLAIN_API_KEY` secret to the new value):

```graphql
mutation {
  createApiKey(input: {
    machineUserId: "<mu_...>",
    description: "identified-chat integration",
    permissions: ["thread:read","webhookTarget:read","timeline:read","tenantFieldSchema:read","customer:read","customer:create","customer:edit","tenant:read","tenant:create","tenant:edit","customerTenantMembership:create","customerTenantMembership:delete","thread:reply","label:create","tenantField:create","tenantField:update"]
  }) { apiKeySecret }
}
```

Note the `tenantField` scope is `:update`, **not** `:edit` — Plain's enum rejects `:edit`
with `Value needs to be one of: …`. The `tenantField:*` grants only populate `plan` /
`founding` if the field **schemas** are also registered ([§7.3](#entitlement-fields)) —
grant and register as a pair.

`tenantFieldSchema:read` and `customer:read` are in this array because the validated staging
key carries them, even though no code path under `api/src/support/` calls an operation that
obviously needs them (see the table above). They are read scopes on data we already touch, so
keeping them costs little; **do not drop them without re-running
[§7.3](#entitlement-fields)'s *Verify it took* check**, since it is not established which
Plain-side call requires them. Auditing them down to least privilege is tracked in
[#2470](https://github.com/wifihaven/wifihaven/issues/2470).

**Echo the response.** `updateApiKey` returns the resulting `permissions` array — read it
back and confirm all 16 scopes are present before moving on. You can re-read them at any
time as the machine-user key (this is also what the boot audit calls,
§5.1 [permission audit](#permission-audit)):

```graphql
query { myPermissions { permissions } }
```

> The array above is a hand-copy relative to `PlainPermissionAudit`'s *needed* set in code
> (§5.1). Nothing mechanically enforces that the two stay consistent —
> [#2478](https://github.com/wifihaven/wifihaven/issues/2478) tracks a CI guard, and
> [#2470](https://github.com/wifihaven/wifihaven/issues/2470) will change what the right array
> is. The boot audit is a partial backstop in the meantime: it catches an array that *omits*
> something needed, but an array carrying a stale *extra* scope is invisible to it.


### 5.4 Create the escalation label + record its id (#2437) {#escalation-label}

An escalation is not complete until a human has been notified. The support agent's handoff has
two server-side halves: an email to `wifihaven.email.operatorAddress`, and a **label on the
Plain thread** so the operator can filter the inbox for "waiting on a human" rather than reading
every thread. The label half needs one piece of workspace state:

1. In Plain, open **Settings → Labels** *(verify the exact label + location against your
   workspace — Plain's navigation moves)* and **create a label type**, e.g. `Needs human`.
2. Copy its **label type id** (`lt_…`). Plain's UI exposes this by hovering the label type and
   choosing **Copy label ID**; you can also read it with
   `query { labelTypes(first: 50) { edges { node { id name } } } }` in the API playground.
3. **Commit it to `render.yaml`** as `WIFIHAVEN_SUPPORT_PLAIN_ESCALATION_LABEL_TYPE_ID`
   (`value:`, not `sync: false`) on that environment's service — the id is workspace state,
   not a secret, so it belongs in declarative config rather than a dashboard entry. It is
   **per-environment**: staging's test workspace and prod's live workspace have different
   ids for the same label name. Staging's is already committed; prod's goes in the same PR
   that flips prod's `WIFIHAVEN_SUPPORT_RESPONDER_ENABLED` to `true`.
4. Grant the key `label:create` (§5.1) — Plain's `addLabels` is gated on it.

`addLabels` takes label **type ids**, not names — there is no name-based form — which is why
the id has to be config rather than a constant in the code.

**This is required, not optional.** With `support.responderEnabled=true` and the id unset the
API **refuses to boot** (`AppConfig.validateRequired`), because a support escalation that
cannot mark the thread is exactly the invisible handoff [#2437](https://github.com/wifihaven/wifihaven/issues/2437)
exists to fix. Grant the permission and set the id as a pair, per environment. Watch
"Escalated threads NOT marked in Plain" on `deploy/grafana/dashboards/support.json` — it should
sit at zero; non-zero means a wrong id or a missing `label:create`.

### 5.5 Do this for BOTH environments

Each workspace (the **test** workspace wired to staging and the **live** workspace wired
to prod, §0) has its **own** machine user and its own API key, so the `403` fix above is
**per-environment**: run §5.1–§5.4 once against the staging workspace and once against the
prod workspace. If prod go-live 403s on the customer/tenant upserts, the prod key's
`permissions` array is the first thing to check.

> **Status as of 2026-07-26 (#2452).** The **staging** key carries the full §5.3 array —
> verified end-to-end. The **prod** key does **not**: it is still missing `timeline:read` and
> `tenantFieldSchema:read`, so #2430 thread history and #2240 entitlement would ship inert.
> Documentation does not fix a workspace. **Re-run §5.3's `updateApiKey` against the prod key
> before prod go-live**, then confirm with `query { myPermissions { permissions } }` as that
> key. Until you do, every prod boot logs `plain api-key permissions INCOMPLETE` and
> `support_permission_probe_total{outcome="missing"}` is non-zero.

> **Status as of 2026-07-26 (#2452).** The **staging** key carries the full §5.3 array —
> verified live. The **prod** key does **not**: it is still missing `timeline:read` and
> `tenantFieldSchema:read`. Correcting this document does not correct the prod workspace.
> **Re-run the §5.3 `updateApiKey` mutation against the prod key before prod go-live**, then
> confirm with `query { myPermissions { permissions } }` as that key. The boot audit
> (§5.1 [permission audit](#permission-audit)) will otherwise log
> `plain api-key permissions INCOMPLETE` on every prod boot and
> `support_permission_probe_total{outcome="missing"}` will be non-zero.

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
3. <a id="entitlement-fields"></a>**Entitlement fields (on the TENANT, not the customer).** Plain's customer input has **no**
   custom-field/attributes channel — `UpsertCustomerOnCreateInput`/`OnUpdateInput`
   (team-plain/typescript-sdk `src/graphql/types.ts`) expose only `fullName` / `email` /
   `externalId` / `shortName` / `tenantIdentifiers`. Entitlement is **household-level**, and each
   household maps to a Plain **tenant** (`tenantIdentifier = household_id`), so plan/founding/name
   ride on the tenant:
   - **Household name** → the tenant's first-class `name` (set by `upsertTenant`). No registration
     needed.
   - **`plan`** (billing status: beta / active / lapsed) → a Plain **tenant field**, `externalFieldId`
     = `plan`, type **String**.
   - **`founding`** (boolean) → a tenant field, `externalFieldId` = `founding`, type **Boolean**.

   **Register these two tenant-field schemas in the workspace before go-live**, using **exactly**
   the external field ids and types below — they must match what the code sends. `PlainClient` keys
   `upsertTenantField` on `externalFieldId: "plan"` (`STRING_TYPE`) / `"founding"` (`BOOLEAN_TYPE`)
   (`api/src/support/PlainClient.scala`), so a mismatched id or type fails the field write. Today
   that failure is metered (`support_tenant_upsert_total{outcome="error"}`) and fail-open w.r.t. the
   customer upsert — the widget + customer/tenant mapping still work, but the fields silently stay
   empty. A missing schema / permission is a **misconfiguration, not an optional feature**, so
   [#2410](https://github.com/wifihaven/wifihaven/issues/2410) hardens this case to **fail** rather
   than degrade — register the schemas (and grant §5.1's `tenantField` permissions) before go-live
   so the write path is fully wired, not half-configured. Do this **per workspace** (staging + prod),
   same as the API key (§5.4):

   > **Tenant field schemas CANNOT be created in the Plain web UI — they are API-only.**
   > The tenant-fields screen in Plain's settings only *lists* schemas; with none registered it
   > says *"No tenant fields created yet. Tenant fields can be created in the Plain API."*
   > There is no "Add a field" button. Create them with the `upsertTenantFieldSchema` mutation
   > below. (This corrected a wrong UI-first procedure —
   > [#2448](https://github.com/wifihaven/wifihaven/issues/2448).)

   1. Open Plain's **API playground** (dashboard → API playground). It runs as the
      **authenticated admin**, which is the identity that needs the **`tenantFieldSchema:create`**
      permission (per Plain's [tenant-fields docs](https://www.plain.com/docs/graphql/tenants/tenant-fields)
      — permission scopes are free-form strings in the published GraphQL schema, so this one is
      sourced from the prose docs, not the type dump). The *machine-user API key* does **not**
      need `tenantFieldSchema:create` — it only writes field *values* (`upsertTenantField`),
      never schemas. It does carry `tenantFieldSchema:read` (§5.1/§5.3).
   2. Run the mutation below **verbatim**. `upsertTenantFieldSchema` is a create-or-update that
      takes an **array**, so both schemas land in one call. This exact mutation was run
      successfully against the **staging** Plain workspace and returned
      `{"data":{"upsertTenantFieldSchema":{"error":null}}}`:

      ```graphql
      mutation {
        upsertTenantFieldSchema(input: {
          tenantFieldSchemas: [
            { source: "wifihaven", externalFieldId: "plan",     label: "Plan",     type: STRING_TYPE,  isVisible: true, order: 1 },
            { source: "wifihaven", externalFieldId: "founding", label: "Founding", type: BOOLEAN_TYPE, isVisible: true, order: 2 }
          ]
        }) {
          error { message }
        }
      }
      ```

      The `externalFieldId` and `type` values are **load-bearing** and must match exactly what
      `PlainClient` sends — `PlanFieldId = "plan"` (`STRING_TYPE`) and
      `FoundingFieldId = "founding"` (`BOOLEAN_TYPE`) in
      [`api/src/support/PlainClient.scala`](../../api/src/support/PlainClient.scala). The `label`
      is free-form display text.

      **`isVisible` and `order` are required** (`Boolean!` / `Int!`) and are easy to miss — the
      mutation is rejected without them. Neither carries behaviour we depend on; they only
      control display in Plain's tenant UI.

      **`source` is our own namespace, not a Plain-mandated value.** It is a free-form `String!`
      (not an enum) that tags the schema with its originating system; Plain requires no
      particular value. We use `"wifihaven"`. It does **not** participate in our value writes:
      `PlainClient` sends `UpsertTenantFieldInput = { tenantFieldIdentifier { tenantId,
      externalFieldId }, type, <valueKey> }` and never sends `source`, so the schema↔value join
      is `externalFieldId` (+ matching `type`) alone. Still, **pick one and always reuse it** —
      a schema is identified by the pair (`source`, `externalFieldId`), so re-running with a
      different `source` registers a *duplicate* schema rather than updating the first.

      If Plain changes the input shape, re-derive it by introspection rather than guessing:

      ```graphql
      query {
        __type(name: "TenantFieldSchemaInput") {
          inputFields { name type { name kind ofType { name kind ofType { name } } } }
        }
      }
      ```

      *(Shape above confirmed by live introspection of Plain's API, and by the mutation running
      successfully against the staging workspace. `UpsertTenantFieldSchemaInput` has exactly one
      field, `tenantFieldSchemas: [TenantFieldSchemaInput!]!`. `TenantFieldSchemaInput` is
      `{ source: String!, externalFieldId: ID!, label: String!, type: TenantFieldType!,
      options: [String!], isVisible: Boolean!, order: Int! }` — every field non-null except
      `options`, which applies to `ENUM_TYPE` only. Note the published SDK's
      `TenantFieldType` enum is `STRING_TYPE` / `NUMBER_TYPE` / `BOOLEAN_TYPE` / `STRING_ARRAY`
      / `DATETIME_TYPE` — Plain's prose docs also mention `ENUM_TYPE`, which the published enum
      does not contain. Neither of our two fields uses it.)*
   3. **List the schemas back and confirm exactly two exist.** Do this positively — do *not*
      wait for an error. Because `source` is a free-form `String`, a typo'd value is **accepted**
      rather than rejected, and a later re-run with a corrected `source` then creates a *second*
      schema rather than updating the first:

      ```graphql
      query { tenantFieldSchemas(first: 50) { edges { node { id source externalFieldId label type } } } }
      ```

      Expect exactly one `plan` (`STRING_TYPE`) and one `founding` (`BOOLEAN_TYPE`) node. If a
      duplicate `externalFieldId` appears under two different `source` values, delete the stray
      one before going further. If the workspace already had schemas registered under some other
      `source`, copy that value rather than introducing `"wifihaven"`.

      **Staging is done** (registered 2026-07-26). **Prod is still pending** — it must be run in
      the prod workspace before go-live.

      ```graphql
      mutation {
        deleteTenantFieldSchema(input: { tenantFieldSchemaId: "<tfs_...>" }) { error { message } }
      }
      ```

      > **Deleting a schema also removes any tenant field values stored against it**
      > ([Plain docs](https://www.plain.com/docs/graphql/tenants/tenant-fields)). Only delete a
      > schema you just created in error — never one whose fields are already populated.
   4. Ensure the machine-user key carries `tenantField:create` + `tenantField:update` (§5.1/§5.3).
      The schema existing is necessary but **not** sufficient — the key still needs permission to
      write the values.

   **Verify it took:** log in as an admin (§8) to trigger an identity resolve, then confirm the
   household's Plain **tenant** now shows `plan` / `founding`, and that
   `support_tenant_upsert_total{outcome="ok"}` increments (not `outcome="error"`) on the support
   dashboard (`deploy/grafana/dashboards/support.json`).
   (`PlainClient.upsertTenantEntitlement` — the `upsertCustomer` DTO drives the tenant + field writes
   automatically once the write key is set.)

---

## 8. Verify (staging first)

0. **Read the boot log for the permission audit (#2452).** On the first boot after the key is
   granted, look for `config feature 'support-write-api'` followed by
   `plain api-key permissions OK`. If you instead see
   `plain api-key permissions INCOMPLETE — PROVISIONING GAP: … missing <perms>`, the message
   names every gap and the exact `updateApiKey` mutation to run — do that before anything
   below, because the features gated on those permissions are fail-open and will otherwise
   look "fine" while doing nothing. `plain api-key permission probe REJECTED by Plain` is the
   other ERROR to act on immediately — the key itself is unusable, so nothing Plain-side works
   at all. Only `plain api-key permission probe could not reach Plain` is a *warning*: Plain
   didn't answer, so the grants are unverified this boot; it self-heals.

1. Log in as an **admin with an email** on staging → the Plain chat bubble should appear.
   If it doesn't, open the browser console:
   - a **CSP violation** means §7.1 isn't done yet;
   - otherwise check `GET /api/support/identity` returns `configured: true` with an
     `emailHash` + `tenantIdentifier`.
2. In Plain, confirm a **customer** was created with `tenantIdentifier = <household_id>`
   and `externalId` set — that's the household-gating mapping working.
3. Send a test email to `support@wifihaven.net` and a test chat → both land in the Plain
   inbox. (Auto-drafted replies are #2200, separate.)
   > **Landing in the inbox proves only INBOUND.** It says nothing about whether Plain can
   > send — that half is §3.1, and it is the one that was missed (#2471). Do §3.1's
   > unregistered-sender check for this environment before calling the channel done.
4. **Fire one real escalation and confirm the label lands (#2437).** Do this on staging
   *before* the prod flip — the `addLabels` mutation shape and the `label:create` grant are
   only exercised for real here, and a wrong field name or a missing permission otherwise
   surfaces solely as a `logError` in prod (the #2418 lesson: Plain's docs are not always
   complete). From a staging support conversation, get the agent to hand off (ask for a
   human), then check all three:
   - the thread carries the `Needs human` label in the Plain inbox;
   - an escalation email arrived at `WIFIHAVEN_EMAIL_OPERATOR_ADDRESS`;
   - `support_agent_action_total{op="escalate_mark",outcome="ok"}` incremented, and the
     "Escalated threads NOT marked in Plain" panel is still zero.

   If the email arrived but the label is missing, the mark half is broken: check the label
   type id and the `label:create` permission (§5.1, §5.4) before flipping prod.

   While you are here, **escalate the same thread a second time and capture Plain's literal
   response** — whether Plain is idempotent on a duplicate label or returns an error (and with
   what wording) is unverified, and until it is, a duplicate shows up as a non-zero on the
   "expect 0" panel. Paste the captured response into
   [#2449](https://github.com/wifihaven/wifihaven/issues/2449), which is what turns it into
   verified handling instead of a guess.

Once staging looks right, repeat §1–§6 against the live workspace + `wifihaven-api-prod`
— **including §3.1**, which is per-workspace: enabling sending on staging does nothing for
prod, and a prod workspace that cannot send drops every reply to a real customer.

---

## 9. What stays manual (no Terraform)

Plain has **no Terraform provider**, so the workspace, channels, chat app, machine-user
API key, and request-signing secret are dashboard-managed (secrets are shown once, so
inherently out-of-band). The declarative parts already live in the repo:

- **Cloudflare Email Routing DNS/MX** → `infra/cloudflare/main.tf` (#2198).
- **Render env-var declarations** → `render.yaml` (`sync:false`); values entered
  out-of-band, never committed.

This runbook is the reproducible record of the dashboard steps.
