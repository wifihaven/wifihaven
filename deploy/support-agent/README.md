# Support responder agent — provisioning (#2200 / #2241)

The Claude support responder is a **cloud agent**: the API server receives Plain's signed
new-message webhook, gates it to **UI-originated threads only** (the #2199 identified widget stamps
`tenantIdentifier = household_id`; cold email never dispatches), mints a short-TTL thread- and
household-bound session token, and creates one **Anthropic Managed Agents session** per message.
The agent drafts a reply and posts it back through the API's token-authenticated
`/api/support/agent/...` endpoints — it holds **no vendor secrets** (no Plain key, no GitHub token,
no Anthropic key). The operator reviews and sends the draft **in Plain** (draft→approve→send; no
autonomous send).

Everything ships **dark**: with the env vars below unset, the webhook no-ops and the agent
endpoints 404.

## One-time provisioning (operator)

Prereqs: `ant` CLI authenticated against the Anthropic org (`ant auth login`), and an Anthropic API
key for the same workspace.

```sh
# 1. Create the agent + environment from the checked-in definitions (once per workspace).
AGENT_ID=$(ant beta:agents create < deploy/support-agent/agent.yaml --transform id -r)
ENV_ID=$(ant beta:environments create < deploy/support-agent/environment.yaml --transform id -r)
echo "$AGENT_ID $ENV_ID"

# 2. Later updates re-apply the same files (agents are versioned; sessions pin at create time):
#    ant beta:agents retrieve --agent-id $AGENT_ID --transform version -r   # current version N
#    ant beta:agents update --agent-id $AGENT_ID --version N < deploy/support-agent/agent.yaml
```

## Render env (staging + prod, all `sync:false` except noted)

| Env var | Value |
|---|---|
| `WIFIHAVEN_SUPPORT_PLAIN_WEBHOOK_SECRET` | Plain workspace webhook signing secret (Plain → Settings → Webhooks; point the webhook at `POST https://<api-host>/api/support/webhook`, thread/message-created events) |
| `WIFIHAVEN_SUPPORT_ANTHROPIC_API_KEY` | Anthropic API key (session creation only) |
| `WIFIHAVEN_SUPPORT_CLAUDE_AGENT_ID` | `$AGENT_ID` from step 1 |
| `WIFIHAVEN_SUPPORT_CLAUDE_ENVIRONMENT_ID` | `$ENV_ID` from step 1 |
| `WIFIHAVEN_SUPPORT_AGENT_TOKEN_SECRET` | `generateValue: true` in render.yaml (auto) |
| `WIFIHAVEN_SUPPORT_AGENT_API_BASE` | set in render.yaml (`api.wifihaven.net` / `api-staging.wifihaven.net`) |
| `WIFIHAVEN_SUPPORT_GITHUB_BOT_TOKEN` | fine-grained PAT for the dedicated bot account, **Issues: read+write ONLY** on `wifihaven/wifihaven` — no `contents`, no `pull_requests` (the structural no-PR guarantee, #2241) |

GitHub bot: create a dedicated machine account (e.g. `wifihaven-support-bot`), grant it the
fine-grained token above, and nothing else. Issues it files are auto-labeled `support-agent` and
rate-limited (3/thread/hour, 10/hour global); the API strips PII from every body before filing.

## Cost guardrails

Only authenticated UI-originated threads can trigger a session (`skipped_unauthenticated` otherwise),
and dispatch is hard-capped at 4/thread/hour and 50/day globally. The model is Sonnet 5
(`agent.yaml`); at beta volume expect roughly $0.05–$0.50 per drafted thread. Watch the
"Agent sessions dispatched (24h)" and "Agent-filed issues (24h)" panels on the Grafana support
dashboard.
