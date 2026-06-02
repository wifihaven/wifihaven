# deploy-webhook — Render deploy events → Grafana annotations (#1245)

A standalone, stdlib-only Python service that receives Render deploy
lifecycle webhooks and writes them as **Grafana Cloud annotations**, so each
deploy shows up as a vertical marker across every dashboard. It exists so the
"which deploy caused this?" question stops being hand-archaeology — the
2026-05-31 DB-CPU regression was correlated to its deploy manually because
Render's metrics stream ([#1244](https://github.com/wifihaven/wifihaven/issues/1244))
carries metrics but **not** deploy events.

## Why standalone (not a route on the API)

The API server's concern is parental-control policy. A Grafana-Cloud client
living there would be dead weight for the self-hosted install, which has no
Grafana Cloud stack. So this is a thin Render service modeled on the Alloy
collector ([`deploy/alloy`](../alloy)): checked-in code, baked into a small
image, deployed declaratively via [`render.yaml`](../../render.yaml)
(`wifihaven-deploy-webhook`).

## What it does

1. Verifies the webhook's **Standard-Webhooks** signature
   (`webhook-id` / `webhook-timestamp` / `webhook-signature`) using
   `WEBHOOK_SECRET`. Unsigned/forged requests get `401`.
2. Classifies the event: `deploy_started` → *started*, `deploy_ended` →
   *live* / *failed* / *canceled*. Canceled and non-deploy events are dropped.
3. Resolves the deployed git sha. The thin webhook payload omits it, so the
   sha is fetched from the Render API when `RENDER_API_TOKEN` is set; otherwise
   the annotation falls back to the service name.
4. POSTs a Grafana annotation: `text = "deploy <sha> <lifecycle>"`,
   `tags = ["deploy", "render", <env>, <serviceName>, <lifecycle>]`,
   `time` = the payload timestamp. `env` is derived from the service name
   (`wifihaven-api-prod` → `prod`, `wifihaven-api-staging` → `staging`).

It deliberately does **not** scaffold any notification/alerting transport —
this is metrics annotation only.

## Endpoints

- `POST /webhook` — the Render webhook target.
- `GET /healthz` — Render health check.

## Configuration (all Render-managed secrets, `sync: false`)

| Env var | Required | Purpose |
| --- | --- | --- |
| `WEBHOOK_SECRET` | yes | Standard-Webhooks signing secret (`whsec_…` or plain). |
| `GRAFANA_URL` | yes | Grafana stack base URL, e.g. `https://<stack>.grafana.net`. |
| `GRAFANA_TOKEN` | yes | Grafana API token with `annotations:write`. |
| `RENDER_API_TOKEN` | no | Resolves the git sha via the Render API; omit to fall back to service name. |
| `ENV_OVERRIDE` | no | Force the env tag instead of deriving it from the service name. |
| `PORT` | no | Injected by Render; defaults to `10000`. |

No secret is committed — `render.yaml` declares the keys with `sync: false`
and the operator sets the values in the Render dashboard.

## Operator wiring (one-time)

1. Apply the blueprint so `wifihaven-deploy-webhook` exists and note its URL.
2. Set `WEBHOOK_SECRET`, `GRAFANA_URL`, `GRAFANA_TOKEN` (and optionally
   `RENDER_API_TOKEN`) in the service's environment.
3. In Render → each API service → **Settings → Webhooks**, add a webhook
   pointing at `https://<this-service>/webhook`. Copy the generated signing
   secret into `WEBHOOK_SECRET` (it must match for signatures to verify).

## Live verification is operator-gated

End-to-end verification depends on a **live Grafana Cloud stack**
([#1208](https://github.com/wifihaven/wifihaven/issues/1208) /
[#1244](https://github.com/wifihaven/wifihaven/issues/1244)) and the
dashboard wiring above, which are out of band of this PR. Once wired, trigger
a deploy and confirm a `deploy …` annotation appears on the Grafana
dashboards lined up against the DB-CPU / pool series — the reproducible form
of the 2026-05-31 scenario.

## Tests

stdlib + pytest, no other deps:

```sh
cd deploy/deploy-webhook
./test/run_tests.sh        # or: python3 -m pytest test/ -v
```
