# Support responder agent — provisioning (#2200 / #2241)

The Claude support responder is a **cloud agent**: the API server receives Plain's signed
new-message webhook, gates it to **UI-originated threads only** (the #2199 identified widget stamps
`tenantIdentifier = household_id`; cold email never dispatches), mints a thread- and
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
| `WIFIHAVEN_SUPPORT_RESPONDER_ENABLED` | `true` on staging (#2335) and on prod — flipped for prod by #2537 at go-live, after everything below was set for that environment (#2265). The prod Plain webhook is wired as of 2026-07-31 (#2543), so the responder is reachable there |
| `WIFIHAVEN_SUPPORT_ISSUE_FILING_ENABLED` | `true` on staging (#2427) and on prod (#2549, for the #2527 go-live validation run) — flip with (or after) the responder once that environment's bot token is set. See [GitHub issue filing](#issue-filing) below |
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
| `WIFIHAVEN_SUPPORT_GITHUB_BOT_TOKEN` | fine-grained PAT for the dedicated bot account, **Issues: Read and write ONLY** on `wifihaven/wifihaven` — no `Contents`, no `Pull requests` (the structural no-PR guarantee, #2241). Full recipe + rotation: [GitHub issue filing](#issue-filing) |

**Plain machine-user API key permissions (`WIFIHAVEN_SUPPORT_PLAIN_API_KEY`).** The responder
holds **no Plain key** — it posts the reply back through the API's `/api/support/agent/*`
endpoints, and the **API server's** `PlainClient` is what writes to Plain (`upsertCustomer` /
`upsertTenant`, the thread write — Plain's `replyToThread` into the customer's existing
thread, #2408 — and, since #2437, the **escalation label** write, Plain's `addLabels`). That
machine-user key (set on the `wifihaven-api-*` service, not
here) must carry the right `permissions` array or those writes return **`403 Forbidden`** —
permissions are set via Plain's GraphQL `createApiKey` / `updateApiKey` mutations, **not** a UI
toggle. The exact permission set, the find-key-id query, and the grant mutations live in
[`docs/ops/plain-setup.md` §5.1](../../docs/ops/plain-setup.md#no-permissions-ui) (per-environment:
staging + prod each have their own machine user + key).

> **#2430 added a READ permission: `thread:read`.** The API now fetches the bound thread's prior
> timeline entries per dispatch and renders them into the kickoff, so the agent can see the
> conversation so far (it fires a **fresh session per inbound message** and would otherwise answer
> every follow-up with no memory). Add `thread:read` to the `permissions` array via `updateApiKey`
> (§5.3) **in both workspaces**. The read is fail-open — a missing grant costs context, never the
> webhook — but it is not silent: each denial logs at ERROR with the fix inline and increments
> `support_thread_history_total{outcome="permission"}` (panel: *Thread-history reads — responder
> context watch* on the Support dashboard). Nothing else changes: the agent still holds no Plain
> key, and the read is scoped to the single bound thread.

> **#2437 added ONE permission and ONE config value.** The escalation path needs
> **`label:create`** on the machine-user key — re-run the `updateApiKey` mutation in
> [`docs/ops/plain-setup.md` §5.3](../../docs/ops/plain-setup.md#no-permissions-ui) with
> `label:create` appended to the `permissions` array — and a `Needs human` label type whose id goes
> in `WIFIHAVEN_SUPPORT_PLAIN_ESCALATION_LABEL_TYPE_ID`
> ([§5.4](../../docs/ops/plain-setup.md#escalation-label)). Both are **required** when
> `WIFIHAVEN_SUPPORT_RESPONDER_ENABLED=true`: the API refuses to boot without the id, and without
> the permission `addLabels` 403s and escalated threads stay invisible in the inbox
> (`support_agent_action_total{op="escalate_mark",outcome="error"}`). Do it per environment.

<a id="issue-filing"></a>

## GitHub issue filing — enabling + token rotation (#2241 / #2427)

Issue filing is the support agent's **escalation** path: it turns "this is broken" into a tracked
`wifihaven/wifihaven` issue instead of an answer the agent can't give. Issues are auto-labeled
`support-agent` and rate-limited (3/thread/hour, 10/hour global); the API strips PII from every body
before filing (`SupportPrivacy.scrubForIssue`, applied at the `GithubIssueClient` trait boundary).
The target repo and REST base are **constants**, not config (`GithubIssueClient.Repo` / `ApiBase`) —
the only knobs are the flag and the token.

`POST /api/support/agent/issues` answers `{"ok":true,"number":2455,"url":"https://github.com/..."}`
(#2461) — parsed out of GitHub's create-issue response, so the agent can point the customer at the
tracking issue in the **public** repo (a `html_url` outside `wifihaven/wifihaven` is rejected, so
"safe to show a customer" is structural, not trusted). `number`/`url` are absent if GitHub's body was
unreadable; that is still a successful filing, and the agent is instructed never to invent a link —
it meters as `support_agent_action_total{op="issue",outcome="ok_no_link"}`, a bounded extra value on
the existing series that surfaces on the support dashboard's `by (op, outcome)` panel, so a
systematic unreadable-body regression is not silent. The agent
offers the link **only when the customer asked** for something to be filed — an unrequested
"I filed #2455" reads as noise and implies a fix commitment we have not made.

The response also carries `"duplicate":true` (#2458) when the search-before-file check matched an
already-open `support-agent` issue: nothing was created, and `number`/`url` point at that EXISTING
issue so the customer still gets a canonical link. The agent is instructed to say "already tracked
as #N" rather than "I've filed it". It meters as `outcome="ok_duplicate"` — a success, counted
alongside `ok`/`ok_no_link` on the volume panel.

### 1. The token (operator; cannot be automated)

Create a **dedicated machine account** (e.g. `wifihaven-support-bot`), invite it to the repo, and
mint a **fine-grained** personal access token from that account
(<https://github.com/settings/personal-access-tokens>):

- **Resource owner** → `wifihaven`; **Repository access** → *Only select repositories* →
  `wifihaven/wifihaven`.
- **Repository permissions** → **Issues: Read and write**. **Everything else: No access** —
  explicitly **no `Contents`** and **no `Pull requests`**. That is the #2241 structural no-PR
  guarantee: the bot *cannot* push code or open/merge a PR because the token lacks the scope, not
  because a prompt tells it not to.
- **Account permissions** → none.
- Note the **expiry date** (see rotation below).

The token is never committed. It goes in **one** place: the Render env var
`WIFIHAVEN_SUPPORT_GITHUB_BOT_TOKEN` (declared `sync: false` in `render.yaml`) on the
`wifihaven-api-staging` / `wifihaven-api-prod` service. The cloud agent itself never sees it — only
the API server holds it.

### 2. Enable order: token first, then the flag — the API refuses to boot otherwise

`WIFIHAVEN_SUPPORT_ISSUE_FILING_ENABLED=true` with an empty
`WIFIHAVEN_SUPPORT_GITHUB_BOT_TOKEN` makes `AppConfig` list `support.githubSupportBotToken` in
`missingRequiredKeys` and **crash the boot** (`api/src/Config.scala`) — the #2265 no-dark-by-default
posture: a required secret's absence is a bug, never a silent disable. So, **per environment**:

1. Set `WIFIHAVEN_SUPPORT_GITHUB_BOT_TOKEN` in Render (§1). Config precedes code.
2. *Then* flip that service's `WIFIHAVEN_SUPPORT_ISSUE_FILING_ENABLED` to `"true"` via a
   `render.yaml` PR.

**Staging first, prod after** — validate on staging (below) before flipping prod. Prod's
`WIFIHAVEN_SUPPORT_RESPONDER_ENABLED` has been `"true"` since #2537, and the prod Plain workspace
webhook is wired as of 2026-07-31 (#2543 — signature-valid Plain events with real thread ids reach
`/api/support/webhook` and parse into typed outcomes), so the responder has a producer. Prod issue
filing was flipped `"true"` by #2549 for the #2527 go-live validation run, after the prod bot token
was set.

### 3. Verify after enabling

- Startup log flips from `support-agent issue filing DISABLED (support.issueFilingEnabled=false)` to
  `support-agent issue filing ENABLED` (`api/src/support/GithubIssueClient.scala`). **Grep that
  prefix, not the whole line** — the parenthetical after it describes the token scope and has
  already changed once (#2458). The startup feature report emits a second line for the same flag —
  `wifihaven.support.issueFilingEnabled=true — support-agent files GitHub issues (bot token)`
  (`api/src/StartupFeatureReport.scala`, `support-issue-filing`) — so grep for either.
- Drive one support message that should escalate; confirm a new `support-agent`-labeled issue appears
  in `wifihaven/wifihaven` and that "Agent-filed issues (24h)" increments on the Grafana support
  dashboard (`deploy/grafana/dashboards/support.json`). That panel counts EVERY success outcome,
  `outcome=~"ok|ok_no_link|ok_duplicate"`, so it is the rate of filing ASKS, not of issues created.
  Read a lopsided rise, not just the total:
  - rising on `ok_no_link` alone — filing works but the link does not, which points at the repo
    having been renamed/transferred away from `GithubIssueClient.Repo` (the log line names that
    cause explicitly);
  - rising on `ok_duplicate` alone (#2458) — the search-before-file check is matching EVERYTHING and
    nothing is reaching GitHub. Usually a matcher tuned too loose; cross-check the `matched` series
    on "Issue-filing duplicate check" over the same window.

### 4. Rotation

Fine-grained PATs **cap at roughly one year** of validity, so this token *will* expire — rotation is
scheduled maintenance, not an exception. Put the expiry on the calendar and re-mint with the same
scope (§1), setting the new value in Render before the old one lapses; the flag stays `true` across
the swap (only the secret changes, so no `render.yaml` PR is needed).

An expired, revoked, or mis-scoped token makes GitHub answer `401` / `403` / `404`. That is a
**misconfiguration, not a transient blip** — but **today it does not fail loud**: `GithubIssueClient`
maps every non-2xx to a `logWarning` + `IssueOutcome.Error`, and there is no checked-in alert rule on
`support_agent_action_total` (the dashboard's "volume alert feed" is a panel, not a rule). So
until [#2415](https://github.com/wifihaven/wifihaven/issues/2415) lands, a lapsed token shows up only
as `outcome="error"` on the dashboard and a flat filed-issues count — **watch the panel after each
rotation**. #2415 is what will promote those statuses to a loud failure.

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

> **Re-paste the prompt after every `agent.yaml` `system:` change (#2419).** The Managed Agents
> agent is re-applied automatically on main-merge, but the routine's prompt is web-UI-only — a
> prompt change is INERT on that transport until an operator pastes it into the routine at
> <https://claude.ai/code/routines>. Redo step 1's paste whenever this file's `system:` block
> changes (most recently: the #2437 "escalate before promising human follow-up" instructions, and
> the #2419 "ask for data-access consent instead of dead-ending" ones).

### Post-merge re-paste — the checklist step (#2469)

A stale routine is invisible without this: it **reports a green run while answering from the old
prompt**. That has bitten twice — #2419/#2425 (consent) and #2430/#2441 (thread history) both merged
un-re-pasted, so the agent kept telling customers "reply here confirming you're OK with me accessing
your account", which grants nothing.

**Whenever a PR that changed this file's `system:` block merges, before calling the change live:**

1. Open the **support** routine at <https://claude.ai/code/routines> for EACH environment that runs
   this transport (staging and prod each have their own routine).
2. Paste the new `system:` block from [`agent.yaml`](agent.yaml) verbatim, preserving the
   `<routine-fire-payload>` opt-in (step 1 above) — it is what makes the routine act at all.
3. Confirm the fix took: after the next real message, the callback should record
   `agent_prompt_version_total{channel="support",state="current"}`. Anything landing on
   `state="stale"` or `state="unknown"` means the routine you are looking at is still the old one.

You do not have to remember this unprompted:

- **CI reminds you.** `.github/scripts/check-agent-prompt-repaste.sh` warns on any PR whose diff
  changes a `system:` block, and FAILS the PR if the change did not bump the `PROMPT_VERSION:`
  marker (an un-bumped marker would make the detector below report a stale routine as current).
- **The API detects it.** The prompt carries `PROMPT_VERSION: <channel>-<date>.<serial>`; the agent
  echoes it on `POST /api/support/agent/reply` as the dedicated `promptVersion` field, and the API
  compares it against `AgentPromptVersion.Channel.Support.expected`, logging loudly and emitting
  `agent_prompt_version_total{channel,state}` (panel: *Support* dashboard, "Live routine prompt
  version"). The check is strictly alert-only — a mismatch never fails a webhook or drops a reply.

## Escalation — the handoff that reaches a human (#2437)

"A human teammate will follow up" used to notify nobody. It now takes a call: when the agent hands
off it POSTs `/api/support/agent/escalate` (`{"note": "one line on why"}`) with its thread-bound
token, and the SERVER does two things the agent cannot fake or aim — labels the token-bound thread
with the escalation label (so the operator filters the inbox for "waiting on a human" instead of
reading every thread) and emails `wifihaven.email.operatorAddress`.

Escalation is **structural**: only that call registers one. Nothing text-matches the reply or the
customer's message, so a customer who types "a team member will follow up" has escalated nothing,
and an agent that writes the sentence without calling escalate has not either. It is capped at
3/thread/hour so a looping session cannot firehose the operator. Watch "Escalations to a human" and
"Escalated threads NOT marked in Plain" on the Grafana support dashboard.

## Data-access consent (#2419)

The agent has no household data by default. When a question needs it, the agent calls
`POST /api/support/agent/request-consent` with its thread-bound token and the SERVER posts a fixed
consent prompt — carrying a signed `<appBaseUrl>/support/consent?g=…` link — into that thread. Only
the customer's own authenticated action on that page records the grant, scoped to `(household,
thread)` for 24 hours; the NEXT dispatch for that thread then mints a token with `dataAccess=true`.
The agent can ask, but can never grant itself access. See
[`docs/ops/support-data-consent.md`](../../docs/ops/support-data-consent.md); the lifecycle is on
the Grafana support dashboard ("Data-access consent lifecycle").

## Cost guardrails

Only authenticated UI-originated threads can trigger a session (`skipped_unauthenticated` otherwise),
and dispatch is hard-capped at 4/thread/hour and 50/day globally. The model is Sonnet 5
(`agent.yaml`); at beta volume expect roughly $0.05–$0.50 per replied thread. Watch the
"Agent sessions dispatched (24h)" and "Agent-filed issues (24h)" panels on the Grafana support
dashboard. Because replies send without human review, spot-check early threads in the Plain inbox
— every thread stays visible there, and any customer can pull a human in by asking.
