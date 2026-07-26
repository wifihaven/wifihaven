# Press responder agent — provisioning (#2203)

The Claude PRESS/PR responder is a **cloud agent** with a **different audience and trust model** from
the support responder (#2200): press arrives from the **public, unauthenticated** at
`press@wifihaven.net`. A **Cloudflare Email Worker** ([`../press-worker/`](../press-worker/)) catches
the address, HMAC-signs a small envelope, and POSTs it to the API's `/api/press/inbound`. The API
verifies the signature, mints a short-TTL session token, and creates one **Anthropic Managed Agents
session** per message. The agent writes a reply and posts it back through the API's
token-authenticated `/api/press/agent/reply` endpoint, where it is **emailed straight to the sender**
(autonomous send — operator decision 2026-07-17; reply directly to the journalist, no approval step).

**What makes press different from support:**
- **No household / customer data — at all.** The press agent's token is reply-target-bound only (no
  household, no data scope), and there is no data endpoint. The no-data guarantee is **structural**.
- **Reply destination is server-locked.** The token carries the original sender's address; the API
  emails only there. A prompt-hijacked agent cannot redirect the reply.
- **No Plain, no helpdesk vendor.** Ingest is the CF Email Worker; egress is the #578 Resend
  transport. The agent holds **no vendor secrets** (no Anthropic key, no email key).

**No dark-by-default (#2265):** the responder runs only when `WIFIHAVEN_PRESS_RESPONDER_ENABLED` is
`true` (flipped via render.yaml PR at go-live, **after** the secrets below AND outbound email are
set — the API refuses to boot with press on and email off). With the flag `false` (the default), the
off state is logged at boot and visible on `/api/debug/config` (the `pressResponder` feature state;
loopback-only, `WIFIHAVEN_DEBUG`-gated); the webhook no-ops and the agent endpoint 404s.

## Provisioning

**Bootstrap (operator, once):** run [`apply.sh`](apply.sh) with `ant` authenticated against the
Anthropic org (`ant auth login`). It create-or-updates the agent + environment BY NAME from the
checked-in yaml and prints the two ids to wire into Render env:

```sh
deploy/press-agent/apply.sh
```

**After bootstrap, CI owns updates:** every merge to `main` touching `deploy/press-agent/**` re-runs
the script via `.github/workflows/master-press-agent.yml` (gated on the
`PRESS_AGENT_ANTHROPIC_API_KEY` repo secret), so the deployed agent can never drift from the repo.
Agents are versioned and sessions pin their version at create time, so an update never disturbs
in-flight press sessions.

Also provision the **Email Worker** — see [`../press-worker/README.md`](../press-worker/README.md).

## Render env (staging + prod, all `sync:false` except noted)

| Env var | Value |
|---|---|
| `WIFIHAVEN_PRESS_RESPONDER_ENABLED` | `false` in render.yaml — flip to `true` via PR at go-live, after everything below (and email) is set (#2265) |
| `WIFIHAVEN_PRESS_DISPATCHER` | `managed-agents` (default) in render.yaml, or `claude-code-cloud` (#2327 — subscription-billed routine; see below). Only the selected transport's keys are required at boot; an unknown value refuses boot |
| `WIFIHAVEN_PRESS_WEBHOOK_SECRET` | the shared HMAC secret — the SAME value set on the Email Worker (`wrangler secret put PRESS_WEBHOOK_SECRET`) |
| `WIFIHAVEN_PRESS_ANTHROPIC_API_KEY` | (dispatcher=managed-agents) Anthropic API key (session creation only) |
| `WIFIHAVEN_PRESS_CLAUDE_AGENT_ID` | (dispatcher=managed-agents) agent id printed by `apply.sh` |
| `WIFIHAVEN_PRESS_CLAUDE_ENVIRONMENT_ID` | (dispatcher=managed-agents) environment id printed by `apply.sh` |
| `WIFIHAVEN_PRESS_CLAUDE_CODE_ROUTINE_ID` | (dispatcher=claude-code-cloud) the `trig_…` routine id — the path segment of the routine's fire URL (see below) |
| `WIFIHAVEN_PRESS_CLAUDE_CODE_ROUTINE_TOKEN` | (dispatcher=claude-code-cloud) the routine's per-routine bearer token (`sk-ant-oat01-…`), shown once on generation |
| `WIFIHAVEN_PRESS_AGENT_TOKEN_SECRET` | `generateValue: true` in render.yaml (auto) |
| `WIFIHAVEN_PRESS_AGENT_API_BASE` | set in render.yaml (`api.wifihaven.net` / `api-staging.wifihaven.net`) |
| `WIFIHAVEN_PRESS_DEPLOYMENT_ENV` | set in render.yaml (`prod` / `staging`) — the kickoff's deployment line |
| `WIFIHAVEN_EMAIL_RESEND_API_KEY` / `WIFIHAVEN_EMAIL_FROM_ADDRESS` | **required** — the reply is emailed via Resend; the API won't boot with press on and email off. Reuses the #578 sender; set `FROM_ADDRESS` to a press-appropriate identity if desired. |
| `WIFIHAVEN_EMAIL_OPERATOR_ADDRESS` | **required (#2437)** — the operator mailbox every press notice is emailed to. Press has no monitored inbox, so this address IS the inbox: every accepted inbound gets a "new inquiry" email and every escalation gets an "ESCALATION — a human must follow up" one. With email enabled and press on, an unset value **fails boot** rather than dropping the notice. |

## Claude Code Cloud routine transport (#2327)

The press responder can dispatch through **Claude Code Cloud** instead of Managed Agents, billed
against the **Claude subscription** (Pro/Max/Team) rather than API credits — parity with the support
responder (#2300). It is the SAME responder behind the SAME `PressAgentDispatcher` trait — the
reply-target-bound press token, the `/api/press/agent/reply` callback, the CF-Email-Worker gating,
and the no-household-data guarantee are all unchanged; only the wire transport differs. Select it
with `WIFIHAVEN_PRESS_DISPATCHER=claude-code-cloud`.

Trigger model (spike, #2300): Claude Code Cloud **routines** expose an on-demand **API trigger** —
`POST https://api.anthropic.com/v1/claude_code/routines/{routine_id}/fire` with the routine's bearer
token and a `{"text": "<kickoff>"}` body — so this stays a per-message PUSH (no poller), exactly like
the Managed Agents path. Docs: <https://code.claude.com/docs/en/routines.md#add-an-api-trigger>.

**Provisioning (operator, once per environment — staging + prod each get their own PRESS routine,
separate from the support routine):**

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
   - `WIFIHAVEN_PRESS_CLAUDE_CODE_ROUTINE_ID` = the `trig_…` segment between `/routines/` and `/fire`
     (the API rebuilds the full URL from `anthropicApiBase` + that id).
   - `WIFIHAVEN_PRESS_CLAUDE_CODE_ROUTINE_TOKEN` = the generated `sk-ant-oat01-…` bearer token.
3. **Allow the callback host in the routine's environment.** The Default env is **Trusted**, which
   blocks arbitrary hosts — an outbound call to `agentApiBase` would fail with
   `403 x-deny-reason: host_not_allowed`. Edit the routine's cloud environment → **Network access →
   Custom** and add the `WIFIHAVEN_PRESS_AGENT_API_BASE` host (e.g. `api.wifihaven.net` /
   `api-staging.wifihaven.net`), or **Full**. Without this the run cannot post the reply.
4. **Set the two `sync:false` env vars** above in Render for that service, then flip
   `WIFIHAVEN_PRESS_DISPATCHER=claude-code-cloud` (and `WIFIHAVEN_PRESS_RESPONDER_ENABLED=true`) via
   render.yaml PR — config precedes code. With the responder enabled and dispatcher set to
   `claude-code-cloud`, the routine id + token become REQUIRED and the API refuses to boot if either
   is unset (#2265/#2266); the Managed-Agents keys are NOT required on this path.

`GET /api/debug/config` (`pressResponder` feature state — loopback-only, `WIFIHAVEN_DEBUG`-gated)
names the active dispatcher in its `detail`, so the billed path is observable; the same line is
emitted to the startup log. Routine CRUD is web-UI-only today; only `/fire` is API-driven, so step
1's prompt and step 3's network policy are manual per environment.

## Escalation — press has no inbox, so this IS the inbox (#2437)

Two operator emails come out of this pipeline, both through the #578 Resend transport to
`WIFIHAVEN_EMAIL_OPERATOR_ADDRESS`:

- **every accepted inbound** (`kind=received`): sender, subject, and the full message. Press email
  reaches a Cloudflare Email Worker, not a mailbox, and nothing in the SPA reads `press_messages` —
  so before #2437 a journalist could write in and no human at WifiHaven would ever know. Sent
  regardless of whether the agent dispatch succeeded, because a failed dispatch is exactly when the
  operator most needs to know.
- **every escalation** (`kind=escalated`): the agent calls `POST /api/press/agent/escalate` with its
  reply-target-bound token, and the server emails a distinctly-subjected notice carrying the sender,
  the subject, the ORIGINAL message re-read from `press_messages` by the id on the signed token, and
  the agent's one-line reason. The peer identity comes from the token, so a hijacked agent can no
  more misreport a journalist than it can redirect the reply.

Escalation is **structural** — only that endpoint call registers one, so a journalist who writes "a
team member will follow up" in their own email has escalated nothing. Capped at 3/sender/hour.
Panels: "Operator notices: inquiries received vs escalated" and "Press operator notices that never
sent" on `deploy/grafana/dashboards/press.json`.

**Re-paste the routine prompt.** #2437 changed [`agent.yaml`](agent.yaml)'s `system:` block (the new
step 4). On the `claude-code-cloud` transport the routine prompt is web-UI-only, so the change is
INERT until an operator pastes the updated prompt into the press routine at
<https://claude.ai/code/routines>.

## Cost + safety guardrails

Only signature-valid inbound POSTs dispatch a session, and dispatch is hard-capped at 4/sender/hour
and 50/day globally. The model is Sonnet 5 (`agent.yaml`); at press volume expect a few cents per
replied inquiry. Because replies send without human review, **spot-check early threads** — the
`press_ai_draft_total` / `press_agent_action_total` series drive the Grafana **Press responder**
dashboard. The reply recipient is server-locked and the agent has no data access, so the blast radius
of a prompt injection is bounded to "a courteous but unhelpful public reply to the original sender".
