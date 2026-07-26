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
| `customerTenantMembership:create` | link the customer to its household tenant (the `tenantIdentifiers` membership on `upsertCustomer`) |
| `customerTenantMembership:delete` | re-link / correct a customer's household tenant membership |
| `label:create` | **#2437** — apply the `needs-human` **escalation label** to a thread when the support agent hands off (`SupportResponder.agentEscalate` → `PlainClient.markThread`, Plain's `addLabels` mutation). This is what makes an escalated thread FILTERABLE in the inbox instead of indistinguishable from an AI-resolved one. Missing it → `addLabels` fails, logged `logError` and metered `support_agent_action_total{op="escalate_mark",outcome="error"}`. **Required.** |
| `thread:reply` | post the AI reply into the customer's existing thread — `SupportResponder.agentReply` → `PlainClient.writeThread`, whose live impl is Plain's `replyToThread` mutation (`api/src/support/PlainClient.scala`, #2408 — the customer-visible send). **Required.** |
| `thread:read` | read the bound thread's timeline so the responder can see the conversation so far — `SupportResponder.dispatchFor` → `PlainClient.threadHistory` (`api/src/support/PlainClient.scala`, #2430 — the stateless responder fires a FRESH cloud session per inbound message, so without this every follow-up is answered with no memory of the thread). **Required.** Fail-open (a missing grant costs context, never the webhook) but **not silent**: each denial is logged at ERROR and metered `support_thread_history_total{outcome="permission"}` (panel in `deploy/grafana/dashboards/support.json`). |

This is the array confirmed to clear the `403` on the customer + tenant upserts. The
`plan` / `founding` **tenant-field** writes ([§7.3 entitlement](#entitlement-fields))
additionally exercise `upsertTenantField`; to have those fields populated, add
`tenantField:create` / `tenantField:update` to the array (the scope is `:update`, **not**
`:edit` — Plain's permission enum has no `tenantField:edit`) **and** register the `plan` /
`founding` field schemas ([§7.3](#entitlement-fields) — an **API-only** step; there is no
Plain UI for creating tenant-field schemas. The *machine-user key* still needs no
`tenantFieldSchema:*` permission, since the code only writes field *values*, never creates
schemas — the schema mutation is run once by the authenticated admin in the API playground).
**Grant the permission and register the schema as a pair, or leave both
off — a half-configured entitlement path is a broken credential, not an optional feature.**
That path is **fail-open only w.r.t. the customer upsert** — a permission or schema gap does
not fail `upsertCustomer` — but it is **not silent**: each failure is logged and metered as
`support_tenant_upsert_total{outcome="error"}` (`upsertTenantEntitlement` in
`api/src/support/PlainClient.scala`; panel in `deploy/grafana/dashboards/support.json`).
Making that gap fully fail-loud + attributable — a broken credential should *fail*, not
degrade into a metered no-op — is tracked in
[#2410](https://github.com/wifihaven/wifihaven/issues/2410).
[#2430](https://github.com/wifihaven/wifihaven/issues/2430) **added `thread:read`** to the
required set (the responder reads the bound thread's timeline so it can see the conversation
so far). It is a **new grant on an existing key**, so re-run the `updateApiKey` mutation in
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

**Preferred — `updateApiKey`** (keeps the existing secret, so the
`WIFIHAVEN_SUPPORT_PLAIN_API_KEY` already set in Render does **not** need rotating):

```graphql
mutation {
  updateApiKey(input: {
    apiKeyId: "<apiKey_...>",
    permissions: ["customer:create","customer:edit","tenant:read","tenant:create","tenant:edit","customerTenantMembership:create","customerTenantMembership:delete","thread:reply","thread:read","label:create"]
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
    permissions: ["customer:create","customer:edit","tenant:read","tenant:create","tenant:edit","customerTenantMembership:create","customerTenantMembership:delete","thread:reply","thread:read","label:create"]
  }) { apiKeySecret }
}
```

**To also populate the `plan` / `founding` entitlement fields** (§5.1, §7.3), use the full
array below — the same core set plus `tenantField:create` / `tenantField:update` (the scope
is `:update`, **not** `:edit`, which Plain's enum rejects with `Value needs to be one of: …`):

```graphql
mutation {
  updateApiKey(input: {
    apiKeyId: "<apiKey_...>",
    permissions: ["customer:create","customer:edit","tenant:read","tenant:create","tenant:edit","customerTenantMembership:create","customerTenantMembership:delete","thread:reply","thread:read","label:create","tenantField:create","tenantField:update"]
  }) { apiKey { id permissions } }
}
```

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
      permission. The *machine-user API key* does **not** need `tenantFieldSchema:*` — it only
      writes field *values* (`upsertTenantField`), never schemas.
   2. Run the mutation below. `upsertTenantFieldSchema` is a create-or-update that takes an
      **array**, so both schemas land in one call. Each schema is identified by the pair
      (`source`, `externalFieldId`), so re-running it is idempotent.

      ```graphql
      mutation {
        upsertTenantFieldSchema(input: {
          tenantFieldSchemas: [
            { source: "API", externalFieldId: "plan",     label: "Plan",     type: STRING_TYPE,  isVisible: true, order: 1 },
            { source: "API", externalFieldId: "founding", label: "Founding", type: BOOLEAN_TYPE, isVisible: true, order: 2 }
          ]
        }) {
          tenantFieldSchemas { id source externalFieldId label type }
          error { message }
        }
      }
      ```

      The `externalFieldId` and `type` values are **load-bearing** and must match exactly what
      `PlainClient` sends — `PlanFieldId = "plan"` (`STRING_TYPE`) and
      `FoundingFieldId = "founding"` (`BOOLEAN_TYPE`) in
      [`api/src/support/PlainClient.scala`](../../api/src/support/PlainClient.scala). The `label`
      is free-form display text. `isVisible` / `order` are required by
      `TenantFieldSchemaInput` but carry no behaviour we depend on.

      **`source` is the one value we have not confirmed against a live workspace.** In Plain's
      schema it is a free-form `String` (not an enum) that namespaces the schema; the analogous
      `Tenant.source` enum is `API` / `HUBSPOT` / `SALESFORCE`, so `"API"` is the reasonable
      choice for schemas we create over the API. It does **not** affect our value writes —
      `UpsertTenantFieldInput` keys on `tenantFieldIdentifier { tenantId, externalFieldId }` and
      never mentions `source`. If the mutation rejects `"API"`, list what an existing workspace
      uses and copy it:

      ```graphql
      query { tenantFieldSchemas(first: 50) { edges { node { id source externalFieldId label type } } } }
      ```

      If Plain changes the input shape, re-derive it by introspection rather than guessing:

      ```graphql
      query {
        __type(name: "TenantFieldSchemaInput") {
          inputFields { name type { name kind ofType { name kind ofType { name } } } }
        }
      }
      ```

      *(Shape above verified against Plain's published GraphQL schema —
      `team-plain/typescript-sdk` `src/graphql/types.ts`: `MutationUpsertTenantFieldSchemaArgs
      { input: UpsertTenantFieldSchemaInput }`, `UpsertTenantFieldSchemaInput
      { tenantFieldSchemas: Array<TenantFieldSchemaInput> }`, `TenantFieldSchemaInput
      { source, externalFieldId, label, type, options?, isVisible, order }`. Note the live
      `TenantFieldType` enum is `STRING_TYPE` / `NUMBER_TYPE` / `BOOLEAN_TYPE` / `STRING_ARRAY`
      / `DATETIME_TYPE` — Plain's prose docs also mention `ENUM_TYPE`, which the published enum
      does not contain. Neither of our two fields uses it.)*
   3. Ensure the machine-user key carries `tenantField:create` + `tenantField:update` (§5.1/§5.3).
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

1. Log in as an **admin with an email** on staging → the Plain chat bubble should appear.
   If it doesn't, open the browser console:
   - a **CSP violation** means §7.1 isn't done yet;
   - otherwise check `GET /api/support/identity` returns `configured: true` with an
     `emailHash` + `tenantIdentifier`.
2. In Plain, confirm a **customer** was created with `tenantIdentifier = <household_id>`
   and `externalId` set — that's the household-gating mapping working.
3. Send a test email to `support@wifihaven.net` and a test chat → both land in the Plain
   inbox. (Auto-drafted replies are #2200, separate.)
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
