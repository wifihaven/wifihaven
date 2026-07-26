# Support responder agent — provisioning (#2200 / #2241)

The Claude support responder is a **cloud agent**: the API server receives Plain's signed
new-message webhook, gates it to **UI-originated threads only** (the #2199 identified widget stamps
`tenantIdentifier = household_id`; cold email never dispatches), mints a short-TTL thread- and
household-bound session token, and creates one **Anthropic Managed Agents session** per message.
The agent writes a reply and posts it back through the API's token-authenticated
`/api/support/agent/...` endpoints, where it is **sent to the customer directly** (autonomous send
— operator decision 2026-07-17; no approval step). The reply is AI-attributed and always names the
escalation path: the customer can ask for a human at any time, the agent confirms and stops, and
the operator — who sees every thread in the Plain inbox — follows up. The agent holds **no vendor
secrets** (no Plain key, no GitHub token, no Anthropic key). Each kickoff also names the
**deployment** it serves (`staging` = operator test, `prod` = real customer).

**No dark-by-default (#2265):** the responder and issue filing run only when their EXPLICIT flags —
`WIFIHAVEN_SUPPORT_RESPONDER_ENABLED` / `WIFIHAVEN_SUPPORT_ISSUE_FILING_ENABLED` — are `true`
(flipped via render.yaml PR at go-live, **after** the secrets below are set: config precedes code).
With a flag `true` and any required key missing, the API **refuses to boot**, listing every gap.
With the flags `false` (the default), the off state is logged at boot and visible on
`/api/debug/config` (the `supportResponder` feature state; loopback-only, `WIFIHAVEN_DEBUG`-gated);
the webhook no-ops and the agent endpoints 404.

## Provisioning

**Bootstrap (operator, once):** run [`apply.sh`](apply.sh) with `ant` authenticated against the
Anthropic org (`ant auth login`). It create-or-updates the agent + environment BY NAME from the
checked-in yaml and prints the two ids to wire into Render env:

```sh
deploy/support-agent/apply.sh
```

**After bootstrap, CI owns updates:** every merge to `main` touching `deploy/support-agent/**`
re-runs the same script via `.github/workflows/master-support-agent.yml` (gated on the
`SUPPORT_AGENT_ANTHROPIC_API_KEY` repo secret — set it to the same Anthropic key), so the deployed
agent definition can never drift from the repo. Agents are versioned and sessions pin their version
at create time, so an update never disturbs in-flight support sessions.

## Render env (staging + prod, all `sync:false` except noted)

| Env var | Value |
|---|---|
| `WIFIHAVEN_SUPPORT_RESPONDER_ENABLED` | `false` in render.yaml — flip to `true` via PR at go-live, after everything below is set (#2265) |
| `WIFIHAVEN_SUPPORT_ISSUE_FILING_ENABLED` | `false` in render.yaml — flip with (or after) the responder once the bot token is set |
| `WIFIHAVEN_SUPPORT_DISPATCHER` | `managed-agents` (default) in render.yaml, or `claude-code-cloud` (#2300 — subscription-billed routine; see below). Only the selected transport's keys are required at boot; an unknown value refuses boot |
| `WIFIHAVEN_SUPPORT_PLAIN_WEBHOOK_SECRET` | Plain workspace webhook signing secret (Plain → Settings → Webhooks; point the webhook at `POST https://<api-host>/api/support/webhook`). Subscribe to the **inbound customer** events only: `thread.thread_created`, `thread.chat_received`, `thread.email_received`. The API additionally loop-guards on the event type + `actorType`, so subscribing to more (e.g. `thread.chat_sent`, our own outbound reply) is safe but unnecessary — those are skipped, never re-dispatched (#2403) |
| `WIFIHAVEN_SUPPORT_ANTHROPIC_API_KEY` | (dispatcher=managed-agents) Anthropic API key (session creation only) |
| `WIFIHAVEN_SUPPORT_CLAUDE_AGENT_ID` | (dispatcher=managed-agents) agent id printed by `apply.sh` |
| `WIFIHAVEN_SUPPORT_CLAUDE_ENVIRONMENT_ID` | (dispatcher=managed-agents) environment id printed by `apply.sh` |
| `WIFIHAVEN_SUPPORT_CLAUDE_CODE_ROUTINE_ID` | (dispatcher=claude-code-cloud) the `trig_…` routine id — the path segment of the routine's fire URL (see below) |
| `WIFIHAVEN_SUPPORT_CLAUDE_CODE_ROUTINE_TOKEN` | (dispatcher=claude-code-cloud) the routine's per-routine bearer token (`sk-ant-oat01-…`), shown once on generation |
| `WIFIHAVEN_SUPPORT_AGENT_TOKEN_SECRET` | `generateValue: true` in render.yaml (auto) |
| `WIFIHAVEN_SUPPORT_AGENT_API_BASE` | set in render.yaml (`api.wifihaven.net` / `api-staging.wifihaven.net`) |
| `WIFIHAVEN_SUPPORT_DEPLOYMENT_ENV` | set in render.yaml (`prod` / `staging`) — the kickoff's deployment line |
| `WIFIHAVEN_SUPPORT_GITHUB_BOT_TOKEN` | fine-grained PAT for the dedicated bot account, **Issues: read+write ONLY** on `wifihaven/wifihaven` — no `contents`, no `pull_requests` (the structural no-PR guarantee, #2241) |

**Plain machine-user API key permissions (`WIFIHAVEN_SUPPORT_PLAIN_API_KEY`).** The responder
holds **no Plain key** — it posts the reply back through the API's `/api/support/agent/*`
endpoints, and the **API server's** `PlainClient` is what writes to Plain (`upsertCustomer` /
`upsertTenant`, and the thread write — `createThread` today, a reply mutation post-#2240). That
machine-user key (set on the `wifihaven-api-*` service, not
here) must carry the right `permissions` array or those writes return **`403 Forbidden`** —
permissions are set via Plain's GraphQL `createApiKey` / `updateApiKey` mutations, **not** a UI
toggle. The exact permission set, the find-key-id query, and the grant mutations live in
[`docs/ops/plain-setup.md` §5.1](../../docs/ops/plain-setup.md#no-permissions-ui) (per-environment:
staging + prod each have their own machine user + key).

GitHub bot: create a dedicated machine account (e.g. `wifihaven-support-bot`), grant it the
fine-grained token above, and nothing else. Issues it files are auto-labeled `support-agent` and
rate-limited (3/thread/hour, 10/hour global); the API strips PII from every body before filing.

## Claude Code Cloud routine transport (#2300)

The responder can dispatch through **Claude Code Cloud** instead of Managed Agents, billed against
the **Claude subscription** (Pro/Max/Team) rather than API credits. It is the SAME responder behind
the SAME `CloudAgentDispatcher` trait — the #2241 token, the `/api/support/agent/*` callback
contract, and the Plain gating are unchanged; only the wire transport differs. Select it with
`WIFIHAVEN_SUPPORT_DISPATCHER=claude-code-cloud`.

Trigger model (spike, #2300): Claude Code Cloud **routines** expose an on-demand **API trigger** —
`POST https://api.anthropic.com/v1/claude_code/routines/{routine_id}/fire` with the routine's bearer
token and a `{"text": "<kickoff>"}` body — so this stays a per-message PUSH (no poller). Docs:
<https://code.claude.com/docs/en/routines.md#add-an-api-trigger>.

**Provisioning (operator, once per environment — staging + prod each get their own routine):**

1. **Create the routine** at <https://claude.ai/code/routines> → **New routine**. Paste the
   `system:` prompt from [`agent.yaml`](agent.yaml) **verbatim** as the routine prompt — it is the
   canonical, transport-agnostic prompt (the routine's prompt is web-UI-only, so `agent.yaml` is its
   source of truth; keep them in sync). Model: Sonnet (match `agent.yaml`).
   - **Prompt opt-in is load-bearing.** A routine wraps the fired `text` in an untrusted
     `<routine-fire-payload>` block and treats it as inert UNLESS the prompt opts in to acting on it.
     The `agent.yaml` prompt already does this (its "WHERE THE KICKOFF ARRIVES" section names the
     block and instructs the agent to act on the dispatch scaffold while keeping the nested
     `<customer_message>` untrusted). If you hand-edit the routine prompt, preserve that opt-in — a
     Managed-Agents-only prompt that never names `<routine-fire-payload>` makes the routine post
     nothing while still reporting a green run.
2. **Add an API trigger** (Edit routine → Select a trigger → **API**), save, then **Copy the URL**
   and **Generate token** (the token is shown ONCE). The URL looks like
   `https://api.anthropic.com/v1/claude_code/routines/trig_…/fire`.
   - `WIFIHAVEN_SUPPORT_CLAUDE_CODE_ROUTINE_ID` = the `trig_…` segment between `/routines/` and
     `/fire` (the API rebuilds the full URL from `anthropicApiBase` + that id).
   - `WIFIHAVEN_SUPPORT_CLAUDE_CODE_ROUTINE_TOKEN` = the generated `sk-ant-oat01-…` bearer token.
3. **Allow the callback host in the routine's environment.** The Default env is **Trusted**, which
   blocks arbitrary hosts — an outbound call to `agentApiBase` would fail with
   `403 x-deny-reason: host_not_allowed`. Edit the routine's cloud environment → **Network access →
   Custom** and add the `WIFIHAVEN_SUPPORT_AGENT_API_BASE` host (e.g. `api.wifihaven.net` /
   `api-staging.wifihaven.net`), or **Full**. Without this the run cannot post the reply.
4. **Set the two `sync:false` env vars** above in Render for that service, then flip
   `WIFIHAVEN_SUPPORT_DISPATCHER=claude-code-cloud` (and `WIFIHAVEN_SUPPORT_RESPONDER_ENABLED=true`)
   via render.yaml PR — config precedes code. With the responder enabled and dispatcher set to
   `claude-code-cloud`, the routine id + token become REQUIRED and the API refuses to boot if either
   is unset (#2265/#2266); the Managed-Agents keys are NOT required on this path.

`GET /api/debug/config` (`supportResponder` feature state — loopback-only, `WIFIHAVEN_DEBUG`-gated)
names the active dispatcher in its `detail`, so the billed path is observable; the same line is
emitted to the startup log. Routine CRUD is web-UI-only today; only `/fire` is API-driven, so step
1's prompt and step 3's network policy are manual per environment.

## Cost guardrails

Only authenticated UI-originated threads can trigger a session (`skipped_unauthenticated` otherwise),
and dispatch is hard-capped at 4/thread/hour and 50/day globally. The model is Sonnet 5
(`agent.yaml`); at beta volume expect roughly $0.05–$0.50 per replied thread. Watch the
"Agent sessions dispatched (24h)" and "Agent-filed issues (24h)" panels on the Grafana support
dashboard. Because replies send without human review, spot-check early threads in the Plain inbox
— every thread stays visible there, and any customer can pull a human in by asking.
