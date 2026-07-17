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
`/api/health` (`features.supportResponder`); the webhook no-ops and the agent endpoints 404.

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
| `WIFIHAVEN_SUPPORT_PLAIN_WEBHOOK_SECRET` | Plain workspace webhook signing secret (Plain → Settings → Webhooks; point the webhook at `POST https://<api-host>/api/support/webhook`, thread/message-created events) |
| `WIFIHAVEN_SUPPORT_ANTHROPIC_API_KEY` | Anthropic API key (session creation only) |
| `WIFIHAVEN_SUPPORT_CLAUDE_AGENT_ID` | agent id printed by `apply.sh` |
| `WIFIHAVEN_SUPPORT_CLAUDE_ENVIRONMENT_ID` | environment id printed by `apply.sh` |
| `WIFIHAVEN_SUPPORT_AGENT_TOKEN_SECRET` | `generateValue: true` in render.yaml (auto) |
| `WIFIHAVEN_SUPPORT_AGENT_API_BASE` | set in render.yaml (`api.wifihaven.net` / `api-staging.wifihaven.net`) |
| `WIFIHAVEN_SUPPORT_DEPLOYMENT_ENV` | set in render.yaml (`prod` / `staging`) — the kickoff's deployment line |
| `WIFIHAVEN_SUPPORT_GITHUB_BOT_TOKEN` | fine-grained PAT for the dedicated bot account, **Issues: read+write ONLY** on `wifihaven/wifihaven` — no `contents`, no `pull_requests` (the structural no-PR guarantee, #2241) |

GitHub bot: create a dedicated machine account (e.g. `wifihaven-support-bot`), grant it the
fine-grained token above, and nothing else. Issues it files are auto-labeled `support-agent` and
rate-limited (3/thread/hour, 10/hour global); the API strips PII from every body before filing.

## Cost guardrails

Only authenticated UI-originated threads can trigger a session (`skipped_unauthenticated` otherwise),
and dispatch is hard-capped at 4/thread/hour and 50/day globally. The model is Sonnet 5
(`agent.yaml`); at beta volume expect roughly $0.05–$0.50 per replied thread. Watch the
"Agent sessions dispatched (24h)" and "Agent-filed issues (24h)" panels on the Grafana support
dashboard. Because replies send without human review, spot-check early threads in the Plain inbox
— every thread stays visible there, and any customer can pull a human in by asking.
