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
off state is logged at boot and visible on `/api/health` (`features.pressResponder`); the webhook
no-ops and the agent endpoint 404s.

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
| `WIFIHAVEN_PRESS_WEBHOOK_SECRET` | the shared HMAC secret — the SAME value set on the Email Worker (`wrangler secret put PRESS_WEBHOOK_SECRET`) |
| `WIFIHAVEN_PRESS_ANTHROPIC_API_KEY` | Anthropic API key (session creation only) |
| `WIFIHAVEN_PRESS_CLAUDE_AGENT_ID` | agent id printed by `apply.sh` |
| `WIFIHAVEN_PRESS_CLAUDE_ENVIRONMENT_ID` | environment id printed by `apply.sh` |
| `WIFIHAVEN_PRESS_AGENT_TOKEN_SECRET` | `generateValue: true` in render.yaml (auto) |
| `WIFIHAVEN_PRESS_AGENT_API_BASE` | set in render.yaml (`api.wifihaven.net` / `api-staging.wifihaven.net`) |
| `WIFIHAVEN_PRESS_DEPLOYMENT_ENV` | set in render.yaml (`prod` / `staging`) — the kickoff's deployment line |
| `WIFIHAVEN_EMAIL_RESEND_API_KEY` / `WIFIHAVEN_EMAIL_FROM_ADDRESS` | **required** — the reply is emailed via Resend; the API won't boot with press on and email off. Reuses the #578 sender; set `FROM_ADDRESS` to a press-appropriate identity if desired. |

## Cost + safety guardrails

Only signature-valid inbound POSTs dispatch a session, and dispatch is hard-capped at 4/sender/hour
and 50/day globally. The model is Sonnet 5 (`agent.yaml`); at press volume expect a few cents per
replied inquiry. Because replies send without human review, **spot-check early threads** — the
`press_ai_draft_total` / `press_agent_action_total` series drive the Grafana **Press responder**
dashboard. The reply recipient is server-locked and the agent has no data access, so the blast radius
of a prompt injection is bounded to "a courteous but unhelpful public reply to the original sender".
