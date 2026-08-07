# Prod beta go-live validation runbook (#2527)

The operator-driven half of
[#2527](https://github.com/wifihaven/wifihaven/issues/2527). Everything that
could be verified without hardware or a human mailbox was run ahead of time and
reported on the PR that added this file; what remains is here, in order, in one
sitting.

**Read the preconditions block first.** Two of them were still unsettled as of
2026-07-31 and will make later steps fail in ways that look like product bugs.

Every step gives: the command or URL, what to look for on the device, and the
**Loki query that confirms it server-side**. Loki is the arbiter — a green
screen with no server-side line is the exact failure mode this whole issue
exists to catch (`docs/process/no-dark-by-default.md`).

## Conventions

Load the read-only Grafana token once per shell (see
[`grafana-cloud.md` §Querying](grafana-cloud.md#querying-logs) — the value lives
in the operator's local memory file, never in this repo):

```bash
GRAFANA_READ_TOKEN=$(awk '/^glc_/{print; exit}' \
  ~/.claude/projects/*wifihaven*/memory/grafana_loki_read_token.md)
[ -n "$GRAFANA_READ_TOKEN" ] || echo "no glc_ token in the memory file" >&2

export LOKI_ADDR="https://logs-prod-021.grafana.net"   # query host, NOT the /push URL
export LOKI_USERNAME="1631926"
export LOKI_PASSWORD="$GRAFANA_READ_TOKEN"
```

Then every `logcli query '<LogQL>' --since=15m` below runs as-is. All queries are
over a **range** (`--since`), never an instant — a low-frequency counter goes
stale within minutes of a redeploy and an instant query reads as "never
happened".

Two label footguns, both real:

- Loki's env label is **`production`**; Prometheus's is **`prod`**. Same
  deployment, different string.
- `route` / `op` / `status` / `mac` are **structured metadata**, so they go after
  the selector as ``| route=`…` ``, never inside `{…}`.

Prometheus (same token, different user id — `3272502`, not Loki's `1631926`):

```bash
pq(){ curl -sG -u "3272502:$GRAFANA_READ_TOKEN" \
  "https://prometheus-prod-67-prod-us-west-0.grafana.net/api/prom/api/v1/query" \
  --data-urlencode "query=$1" | jq -c '.data.result[]? | {m:.metric, v:.value[1]}'; }
```

## Step 0 — preconditions (do these before anything else)

| # | Precondition | State 2026-07-31 |
|---|---|---|
| 0.1 | Plain prod machine-user key carries all 14 required permissions | **CONFIRMED** — `plain api-key permissions OK — all 14 required permissions granted (16 total on the key)` at boot |
| 0.2 | Prod support + press responders enabled, dispatcher `claude-code-cloud`, routine id + token set | **CONFIRMED** — the boot gate refuses to start without them and the API is up |
| 0.3 | Prod support widget vars set | **CONFIRMED** — `support-widget: ENABLED — appId + identitySecret required & set` |
| 0.4 | Plain **prod** tenant field schemas (`plan`, `founding`) registered | **UNVERIFIED — do 0.4 below** |
| 0.5 | `WIFIHAVEN_SUPPORT_GITHUB_BOT_TOKEN` set on prod | **UNVERIFIABLE — decide at 0.5 below** |
| 0.6 | #2469 drift detector reports both prod routines CURRENT | **Structurally deferred — lands at step 5.2 / 6.2** |

### 0.4 — register the prod tenant field schemas

API-only; there is no UI. Nothing in prod had ever exercised the tenant-write
path as of 2026-07-31 (`support_tenant_upsert_total` had no prod series at all),
so the schemas' presence is unverified either way. Run
[`plain-setup.md` §7.3 "Entitlement fields"](plain-setup.md#entitlement-fields)'s
`upsertTenantFieldSchema` mutation in the **prod** workspace's API playground —
verbatim, including `isVisible` and `order`, which the mutation is rejected
without:

```graphql
mutation {
  upsertTenantFieldSchema(input: {
    tenantFieldSchemas: [
      { source: "wifihaven", externalFieldId: "plan",     label: "Plan",     type: STRING_TYPE,  isVisible: true, order: 1 },
      { source: "wifihaven", externalFieldId: "founding", label: "Founding", type: BOOLEAN_TYPE, isVisible: true, order: 2 }
    ]
  }) { error { message } }
}
```

Expect `{"data":{"upsertTenantFieldSchema":{"error":null}}}`. Confirmed later, at
step 4, by a real entitlement write.

### 0.5 — decide on issue filing

Prod runs `WIFIHAVEN_SUPPORT_ISSUE_FILING_ENABLED=false`, so:

- the boot gate never checks `WIFIHAVEN_SUPPORT_GITHUB_BOT_TOKEN`, and its
  presence on the prod service is unverified;
- #2527 §B "issue filing: searches before filing, and refuses to file from a
  data-access session" **cannot be validated in prod as configured**.

Pick one before starting, because it changes what step 5.5 means:

- **(a) Leave it off.** Then step 5.5 verifies only the negative — the agent's
  file attempt returns `disabled` and is metered as such. The positive halves of
  #2458/#2454 stay staging-only evidence. Note it on #2527 rather than checking
  the box.
- **(b) Turn it on.** Set `WIFIHAVEN_SUPPORT_GITHUB_BOT_TOKEN` on the prod
  service, then land a PR flipping `WIFIHAVEN_SUPPORT_ISSUE_FILING_ENABLED` to
  `"true"` in `render.yaml` — in that order; a `true` flag with the token unset
  refuses boot (that is the gate working). Then step 5.5 runs in full.

## Step 1 — reflash and bring up a vanilla-OpenWrt router

**GL.iNet stock firmware is not a supported target** — #2334 established it is
blocked outright. Flash vanilla OpenWrt first; per-model instructions:
[`install-flint.md`](../install-flint.md),
[`install-flint2.md`](../install-flint2.md),
[`install-wax206.md`](../install-wax206.md). Generic path:
[`install-openwrt.md`](../install-openwrt.md).

Do **not** enroll yet — the household in step 2 must exist first, and the
enrollment token is what binds the router to it (#2106).

## Step 2 — create a NEW prod household through the real beta gate

Use a fresh email you control. Do not shortcut any hop; the point of the step is
that the real path works, not that a household exists.

1. **Request.** `https://app.wifihaven.net/beta` → submit the new email.

   ```bash
   logcli query '{service="wifihaven-api", env="production"} |= "beta"' --since=15m
   ```

2. **Approve.** Log in as the household-1 operator → **Beta requests** →
   Approve. The invite email is sent by Resend from the apex
   `wifihaven.net` identity (subdomains are not verified).

   ```bash
   logcli query '{service="wifihaven-api", env="production"} |~ "beta.*(approve|invite)"' --since=15m
   ```

3. **Accept.** Open the invite link → `/welcome` → set the initial credentials.
   This is what creates the household + its admin (`POST /api/beta/accept`).

4. **First login + forced password change.** Log in at
   `https://app.wifihaven.net/login` as the new admin. The SPA must force the
   change before any `RequirePwChanged` page renders — if you land on
   `/dashboard` without being asked, that is a finding, not a convenience.

5. **Record the new `householdId`** — every isolation check below needs it.

```bash
logcli query '{service="wifihaven-api", env="production"} |~ "(?i)(household|login|password)"' --since=30m
```

## Step 3 — enroll the router and prove enforcement DROPS

1. **Point the agent at prod and enroll**, per
   [`install-openwrt.md` §4](../install-openwrt.md#4-enrolling-against-the-cloud-api).
   Generate the enrollment token **while logged in as the NEW household's
   admin** (`app.wifihaven.net` → Routers → Add router) — that is what stamps the
   router with the right `household_id`.

   ```sh
   uci set wifihaven.@wifihaven[0].api_url='https://api.wifihaven.net'
   # override lan_prefix if the LAN isn't 192.168.1.0/24 — a wrong value
   # silently mis-attributes every flow
   uci commit wifihaven
   curl -s -X POST https://api.wifihaven.net/api/router/register \
     -H 'Content-Type: application/json' \
     -d '{"enrollmentToken":"et_<from-ui>","platformVersion":"23.05.5","agentVersion":"0.1.0"}'
   ```

   Persist `router_id` / `router_token`, `/etc/init.d/wifihaven enable && start`,
   then `logread -f | grep wifihaven` until `policy snapshot fetched, etag=…`.

   ```bash
   logcli query '{service="wifihaven-api", env="production"} | routerId=`<new-router-id>`' --since=15m
   ```

2. **Block a host** for a test device's profile in the SPA, then prove it
   **drops at the connection layer**.

   > Never write "it resolved, so it isn't blocked" in the notes. DNS always
   > resolves and is never the enforcement plane; the resolved IP is exactly what
   > nftables drops (AGENTS.md Truth #1).

   **Two preconditions first**, both of which otherwise manufacture a false
   "enforcement is broken" finding — read
   [`enforcement-expectations.md`](../enforcement-expectations.md) before this
   step:

   - **Wait for the block to propagate.** The snapshot reaches the router on its
     next poll — `policy_poll_interval`, default **5 s**
     (`openwrt/files/etc/config/wifihaven`). That is the number that applies
     here: the WebSocket push path ships `enabled '0'` and prod was rolled back
     to polling (#1023/#2153), so assume the poll. Even then the block set is
     still **empty** until the device does a fresh lookup, because members are
     added lazily by dnsmasq's `nftset=` callback at resolve time.
   - **Turn off the device-side bypasses.** iCloud Private Relay, browser
     Secure DNS/DoH, and any VPN route around the router entirely and defeat
     host filtering by design. Private Relay is what cost #1891/#1909 a whole
     debugging session.
   - **If you are blocking the `Connectivity Test` app, load
     `http://neverssl.com` BEFORE blocking it, and confirm it renders.** The app
     has one host, on a third party we neither control nor monitor, and its
     failure mode inverts the meaning of this whole step: a `neverssl.com` that
     is simply down is indistinguishable from a successful block, so the test
     appears to PASS while proving nothing. A pre-block reachability check is
     the only thing separating those two outcomes. (Same reasoning as the rest
     of this runbook: a green screen with no corroborating evidence is the
     failure mode the issue exists to catch.)

   Then on the device: flush DNS
   (`sudo dscacheutil -flushcache; sudo killall -HUP mDNSResponder`), and use
   **HTTP** — a blocked `http://` request is DNAT'd to the local block page, so
   the block is visible. HTTPS gives a cert warning or a failed handshake, which
   is also the block working but shows no page.

   On the router, the authoritative check. **Do not type the hostname into the
   set name**: the set is `eb_<host>` with `.` and `:` rewritten to `_`
   (`render.lua` `sanitize`), so `youtube.com` is `eb_youtube_com`, and a literal
   `eb_youtube.com` returns `No such file or directory` — which reads exactly
   like "no block set exists". List them instead, and check the `eb6_` sibling
   too, or a device answering on AAAA looks unblocked:

   ```sh
   nft list ruleset | grep -A5 wh_drop            # the forward-drop rules
   nft list table inet wifihaven | grep -E 'set (eb|eb6)_'   # the real set names
   nft list set inet wifihaven <name-from-the-line-above>   # then its resolved IPs
   ```

   Server-side confirmation of the drop event and of the block page:

   ```bash
   logcli query '{service="wifihaven-api", env="production"} | route=`/api/router/events`' --since=15m
   logcli query '{service="wifihaven-api", env="production"} | route=`/api/blocked`'       --since=15m
   ```

   The block page derives its reason from `GET /api/blocked`, not from the
   router's redirect — so a rendered page with no `/api/blocked` line is a
   different bug from no page at all.

3. **Isolation from household 1.** Logged in as the NEW household's admin, walk
   every read surface and confirm none of household 1's data appears: dashboard
   stats, devices, profiles, usage, alerts, settings, schedules, blocklists,
   routers. Then do the reverse as the household-1 operator.

   The sharper check is by id, not by eyeball — take a household-1 profile id /
   device MAC / router id and request it as the new admin. Expect 404/403, never
   a body.

   ```bash
   logcli query '{service="wifihaven-api", env="production", level="ERROR"}' --since=30m
   logcli query '{service="wifihaven-api", env="production"} | status="403"' --since=30m
   ```

## Step 4 — support conversation via the WIDGET

As of 2026-07-31 prod had never dispatched a support agent session —
`support_dispatch_total` had no prod series at all. This step is the first one
ever.

1. In the SPA as the **new household's admin**, open the support widget and ask
   a **general** question that needs no account data (e.g. "does WifiHaven block
   at DNS or at the connection layer?").

   ```bash
   logcli query '{service="wifihaven-api", env="production"} |= "support webhook outcome"' --since=15m
   ```

   Want `outcome=dispatched` (the UI-origin path — `WebhookOutcome.Dispatched`),
   **not** `skipped_unauthenticated` / `email_unregistered_rejected`.

   ```bash
   pq 'increase(support_dispatch_total{env="prod"}[15m])'
   pq 'increase(support_widget_identity_total{env="prod"}[15m])'
   ```

   `support_widget_identity_total{env="prod"}` had recorded **only `no_email`**
   (5/5 calls, as of 2026-07-31) — i.e. every prod caller so far had no email on
   their user row, so
   `SupportService.identity` returned the dark response and the widget has never
   issued an identity hash in prod. Household 1's operator account is
   username-only; the beta-gate admin created at step 2 has an email, so this
   step should move the counter to `issued`. If it does not, the widget is not
   identifying the logged-in admin and everything downstream is anonymous.

2. **The reply lands in-thread**, not as a new thread (#2408). Check the Plain
   inbox visually, and:

   ```bash
   pq 'increase(support_agent_action_total{env="prod",op="reply"}[15m])'
   ```

3. **Entitlement write** (confirms step 0.4): the customer's tenant in Plain
   shows `Plan` / `Founding`.

   ```bash
   pq 'increase(support_tenant_upsert_total{env="prod"}[15m])'
   ```

   `outcome="error"` here means the schemas or permissions are still wrong —
   this is the fail-loud path from #2410, so an error is a real finding.

## Step 5 — support: consent, refusal, issue filing

1. **Ask something that needs account data** ("how much screen time did my
   son's iPad use today?"). The agent must call `request-consent`; the **server**
   posts the signed single-use link (the agent never mints it).

   ```bash
   pq 'increase(support_consent_total{env="prod"}[30m])'   # want outcome="requested"
   ```

2. **Grant it** from the link → `/support/consent`. The conversation resumes and
   answers with real account facts (#2460 re-dispatch).

   ```bash
   pq 'increase(support_consent_total{env="prod"}[30m])'   # now outcome="granted"
   pq 'increase(support_agent_action_total{env="prod",op="household_read"}[30m])'
   ```

   **This is where #2469 finally reports.** `agent_prompt_version_total` has never
   been emitted in any environment — it is recorded on the agent's reply
   callback, and no callback has happened since #2510 merged. So the drift check
   is not a precondition you can satisfy in advance; it lands here:

   ```bash
   pq 'agent_prompt_version_total{env="prod",channel="support"}'
   logcli query '{service="wifihaven-api", env="production"} |= "prompt DRIFT"' --since=1h
   logcli query '{service="wifihaven-api", env="production"} |= "prompt version UNKNOWN"' --since=1h
   ```

   Want exactly `state="current"`. `stale` → re-paste
   `deploy/support-agent/agent.yaml`'s `system:` block at
   <https://claude.ai/code/routines>. `unknown` → the routine is on a pre-#2469
   prompt; same fix. **No series at all** → the reply callback never reached us,
   which is a bigger problem than drift.

3. **Replay the consent link** after the grant, and again after withdrawing
   consent. Both must be refused (#2453 spent-link ledger, V85).

4. **Loud refusal** (#2462): ask an account question with consent withdrawn. The
   agent must refuse visibly, not synthesize an answer.

   ```bash
   pq 'increase(support_agent_action_total{env="prod",op="household_read",outcome="denied"}[30m])'
   ```

5. **Issue filing** — per the choice made at step 0.5.
   - **(a) off:** ask the agent to file something; expect a refusal and
     `support_agent_action_total{op="issue",outcome="disabled"}`.
   - **(b) on:** verify it searches before filing (#2458 dedup), that the body is
     scrubbed (#2241 `scrubForIssue`), and that a **data-access** session refuses to
     file at all (#2454).

   ```bash
   pq 'increase(support_agent_action_total{env="prod",op="issue"}[30m])'
   pq 'increase(support_issue_dedup_total{env="prod"}[30m])'
   ```

6. **Support by EMAIL** (#2505): send to `support@wifihaven.net` **from the new
   household admin's registered address**.

   ```bash
   logcli query '{service="wifihaven-api", env="production"} |= "support webhook outcome"' --since=15m
   ```

   Want `email_registered_dispatched`. The one inbound prod support email so far
   (2026-07-31T00:29Z) got `email_unregistered_rejected` — the sender wasn't a
   registered household address. That is the gate working, and it is also why
   this step must use the registered address.

7. **Prompt injection**, three placements — message body, thread history, and the
   email **subject**. Each must stay inside the untrusted frame; in particular an
   injected `PROMPT_VERSION:` line must not flip the drift state to `current`
   (the version is read only from the dedicated `promptVersion` field).

8. **The reply must not echo the agent's own bearer token** (#2508).

   ```bash
   pq 'increase(agent_reply_redacted_total{env="prod"}[1h])'
   ```

## Step 6 — press round-trip

As of 2026-07-31 prod had never received a press email —
`press_message_recorded_total` and `press_dispatch_total` had no prod series at
all.

> Do **not** contact a real publication. That is
> [#2233](https://github.com/wifihaven/wifihaven/issues/2233) and it unblocks
> only after this issue closes. Use your own external mailbox playing journalist.

1. Email `press@wifihaven.net` from an outside address with a realistic subject
   and question.

   ```bash
   logcli query '{service="wifihaven-api", env="production"} | route=`/api/press/inbound`' --since=15m
   pq 'increase(press_message_recorded_total{env="prod"}[15m])'
   pq 'increase(press_dispatch_total{env="prod"}[15m])'
   ```

2. **The reply arrives** — check all three, they fail independently:
   - **From identity** is the verified sender (#2407). Only the apex
     `wifihaven.net` is Resend-verified; a subdomain From will not send.
   - **Threading** — the reply carries `In-Reply-To`/`References` matching your
     original `Message-ID` (#2451) and lands in the same mail thread.
   - **No `/press` "Received" auto-notice** (#2480) — you should get exactly one
     email, the answer.

   ```bash
   pq 'agent_prompt_version_total{env="prod",channel="press"}'   # want state="current"
   pq 'increase(press_agent_action_total{env="prod",op="reply"}[30m])'
   ```

3. **`PressToken` capability probe.** Already run against prod ahead of time and
   confirmed — no household/consent/issue endpoint exists on the press channel:

   ```bash
   curl -s -o /dev/null -w '%{http_code}\n' https://api.wifihaven.net/api/press/agent/household
   curl -s -o /dev/null -w '%{http_code}\n' -X POST https://api.wifihaven.net/api/press/agent/issues
   # both 404 — the capability was never minted, not merely gated
   ```

   Re-run after the live conversation only if you changed press config.

4. **Prompt injection into the press subject and body** — a hijacked press agent
   must still be unable to redirect the reply (the destination is inside the
   signed token) or reach any household.

## Step 7 — close out

Check the boxes on #2527 that this run actually covered, and write the
UNVERIFIED ones down as UNVERIFIED with the gap rather than leaving them
ambiguous. Only when every box is genuinely checked does #2233 unblock.

Final sweep for anything that failed quietly during the sitting:

```bash
logcli query '{service="wifihaven-api", env="production", level="ERROR"}' --since=3h
logcli query '{service="wifihaven-api", env="production", level="WARN"}'  --since=3h
```
