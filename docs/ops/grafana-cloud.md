# Grafana Cloud stack

This was originally in AGENTS.md §"Grafana Cloud stack"; see AGENTS.md for the TOC.

## Grafana Cloud stack

The cloud metrics + dashboard stack lives at
**`https://wifihaven.grafana.net`** (free tier). It hosts app metrics
(pushed by `wifihaven-alloy`), Render infra metrics (native OTLP), and
deploy annotations (POSTed by `.github/workflows/master-api-ui.yml` via
`.github/actions/grafana-annotation`). Repo secrets driving the
annotation POSTs are `GRAFANA_CLOUD_URL` +
`GRAFANA_CLOUD_ANNOTATION_TOKEN`. Operator runbook in
[`docs/deploy-cloud.md`](../deploy-cloud.md) §11.

## App logs → Grafana Cloud Loki (#1873, epic #1831)

In **deployed environments only**, the API ships its application logs
straight to Grafana Cloud Loki's push API via the
[loki4j](https://loki4j.github.io/loki-logback-appender/) `loki-logback-appender`
configured in [`api/resources/logback.xml`](../../api/resources/logback.xml).
There is **no** table-tailing pipeline and **no** Alloy `loki.write` hop — the
JVM pushes directly.

- **Deployed-env gate.** The appender (and its root attachment) is wrapped in a
  logback `<if condition='isDefined("GRAFANA_CLOUD_LOKI_URL")'>` (janino-backed).
  Presence of the secret IS the gate: local dev and `mill __.test` never set it,
  so the appender is never instantiated there.
- **Fail-open.** Loki4jAppender is async by design (request thread only
  enqueues); a bounded `<sendQueueMaxBytes>` drops on backpressure and
  `<drainOnStop>false` keeps shutdown from blocking on Loki. Loki being
  slow/down can never wedge the request path. (Load-proof + a drop metric +
  panel are the #1831 follow-up, tracked in #1879.)
- **Label / cardinality model.** Loki **stream labels** are whitelisted to
  exactly `service` / `env` / `level`. Every other MDC key (`mac`, `route`,
  `op`, `status`, `etag`, …) rides Loki **structured metadata** via the
  `* = %%mdc` bulk pattern — same bounded-cardinality rule as metrics; `mac` as
  a label would be a per-device explosion and is forbidden.
- **Secrets.** `GRAFANA_CLOUD_LOKI_URL` (push API, `.../loki/api/v1/push`),
  `GRAFANA_CLOUD_LOKI_USER` (numeric instance id), `GRAFANA_CLOUD_LOKI_PASSWORD`
  (API token, logs-push scope) are Render-managed `sync:false` secrets on the
  staging + prod API web services in [`render.yaml`](../../render.yaml) —
  mirroring the `GRAFANA_CLOUD_PROM_*` pattern. `WIFIHAVEN_ENV` (`staging` /
  `production`) supplies the `env` label. Never committed.

## Querying logs from Loki (debugging / incidents) {#querying-logs}

**Prefer Loki over `render logs`** for any log lookup. The API ships every
deployed log line to Grafana Cloud Loki (above), where it is indexed and
LogQL-queryable across staging + prod. Render's own log stream is a **fallback**
(short retention, per-service, no structured-metadata filtering) — reach for it
only when Loki itself is unavailable.

### The label set to query against (verify, don't guess)

Straight from [`api/resources/logback.xml`](../../api/resources/logback.xml)
(the LOKI appender's `<labels>` / `<structuredMetadata>`) — this is the source
of truth, not this doc:

- **Stream labels** (use in the `{…}` selector): `service` is always
  `wifihaven-api`; `env` is `staging` or `production`; `level` is the log level
  (`ERROR` / `WARN` / `INFO` / …). Root logs at `WARN`; the `wifihaven.*`
  loggers log at `INFO` (`WIFIHAVEN_LOG_LEVEL`, default `INFO`), so app INFO
  lines are present.
- **Structured metadata** (filter with a `| key="value"` label-filter *after*
  the selector, never inside `{…}`): `route`, `op`, `status`, `mac`, `etag`,
  `profileId`, `routerId`, `thread`, `logger`, and every other MDC key. `mac`
  lives here (not as a stream label) precisely so "show everything device X hit"
  stays queryable without a cardinality explosion.

### Path A — Grafana Explore UI (works today, no extra creds)

This is the **currently-provisioned** path — the operator's normal
wifihaven.grafana.net login already grants log read:

1. Go to **`https://wifihaven.grafana.net`** → **Explore** (compass icon).
2. Pick the **Loki** data source (top-left datasource dropdown).
3. Paste a LogQL query (examples below) and set the **time range** to the
   incident window (top-right).

Copy-pasteable LogQL:

```logql
# All prod API logs
{service="wifihaven-api", env="production"}

# Errors only, prod
{service="wifihaven-api", env="production", level="ERROR"}

# Staging support-webhook trace (free-text |= filters on the message)
{service="wifihaven-api", env="staging"} |= "support" |= "webhook"

# One route, via structured metadata (route is NOT a stream label)
{service="wifihaven-api", env="production"} | route=`/api/router/usage`

# Errors on a specific route
{service="wifihaven-api", env="production", level="ERROR"} | route=`/api/router/usage`

# A specific status code (e.g. the fast-400 class from incident-investigation)
{service="wifihaven-api", env="production"} | status="400"

# Everything one device hit (mac rides structured metadata)
{service="wifihaven-api", env="production"} | mac="aa:bb:cc:dd:ee:ff"
```

### Path B — CLI (`logcli`) / HTTP query API — provisioned

`logcli` and the `/loki/api/v1/query_range` HTTP API give scriptable, greppable
access to the same logs, without routing the investigation through a browser.
**This path is provisioned and verified** (2026-07-27) — prefer it for anything
you want to grep, diff, or paste into an incident writeup.

> **The push token cannot read.** `GRAFANA_CLOUD_LOKI_PASSWORD` (used by the
> API's logback appender) is **logs-*push* scope** — see [`render.yaml`](../../render.yaml)
> `# PASSWORD is a Grafana Cloud API token with logs-push scope`. It will not
> authenticate a query. The read credential below is a separate access policy.

#### The credential

A read-only Grafana Cloud **access policy** named `wifihaven-read` (realm: the
`wifihaven` stack, region `prod-us-west-0`) carries `logs:read`, `metrics:read`,
`rules:read`, `alerts:read` — and deliberately **no `logs:write`**, so a leak of
this token cannot be used to forge log lines into our stack. It is verified
read-only: a push attempt returns `401 authentication error: invalid scope
requested`.

**The token value is not in this repo and must never be.** It lives in the
operator's local Claude memory at
`~/.claude/projects/-Users-sameer-workspace-wifihaven/memory/grafana_loki_read_token.md`,
which also documents how to load it into a shell variable without echoing it.
Never commit it, never paste it into a PR/issue/comment, and never inline it
into a command that lands in a transcript.

To manage or rotate it: `https://wifihaven.grafana.net` → **Administration →
Users and access → Cloud access policies**. (It is in the *stack* UI, not the
grafana.com org portal.) Do **not** reuse the pre-existing
`stack-1674139-hl-read` policy — it bundles `logs:write`.

#### Connection values (not secret)

| Backend | Query host | Basic-auth user |
|---|---|---|
| Loki | `https://logs-prod-021.grafana.net` | `1631926` |
| Prometheus | `https://prometheus-prod-67-prod-us-west-0.grafana.net/api/prom` | `3272502` |

Two footguns, both of which produce confusing 401s:

- The Loki **query** host is *not* the `/push` URL held in
  `GRAFANA_CLOUD_LOKI_URL`.
- **The user id differs per backend.** Loki is `1631926`, Prometheus is
  `3272502`. Using Loki's id against Prometheus returns
  `401 authentication error: invalid authentication credentials` — which looks
  like a bad token but is a wrong username. Each id is on its own data source's
  page under **Connections → Data sources → `grafanacloud-wifihaven-{logs,prom}`
  → Authentication → Basic authentication → User**.

#### curl — Loki `query_range`

```bash
# Load the token without echoing it (see the memory file above).
GRAFANA_READ_TOKEN=$(awk '/^glc_/{print; exit}' \
  ~/.claude/projects/-Users-sameer-workspace-wifihaven/memory/grafana_loki_read_token.md)

curl -sG -u "1631926:$GRAFANA_READ_TOKEN" \
  "https://logs-prod-021.grafana.net/loki/api/v1/query_range" \
  --data-urlencode 'query={service="wifihaven-api", env="production", level="ERROR"}' \
  --data-urlencode "start=$(($(date +%s)-3600))000000000" \
  --data-urlencode 'limit=20'
```

`start` / `end` are **Unix nanoseconds**. The same LogQL from Path A applies —
`service` / `env` / `level` inside the `{…}` selector, everything else
(`route`, `op`, `status`, `mac`, …) as a `|` label-filter after it.

#### logcli

```bash
export LOKI_ADDR="https://logs-prod-021.grafana.net"   # query host, NOT the /push URL
export LOKI_USERNAME="1631926"
export LOKI_PASSWORD="$GRAFANA_READ_TOKEN"
logcli query '{service="wifihaven-api", env="production", level="ERROR"}' --since=1h
```

`logcli` uses `LOKI_USERNAME` + `LOKI_PASSWORD` (basic auth) for Grafana Cloud —
not `LOKI_BEARER_TOKEN`.

#### curl — Prometheus (same token, `metrics:read`)

```bash
curl -sG -u "3272502:$GRAFANA_READ_TOKEN" \
  "https://prometheus-prod-67-prod-us-west-0.grafana.net/api/prom/api/v1/query" \
  --data-urlencode 'query=up'
```

#### Not covered — dashboards

Cloud access policies gate the **data backends** (Loki / Mimir / Tempo /
Pyroscope) only; there is no dashboard scope. Reading dashboard JSON from
`https://wifihaven.grafana.net/api/dashboards/...` needs a separate Grafana
**service account** token (Administration → Users and access → Service
accounts). Not provisioned as of 2026-07-27. Note the in-repo dashboards under
[`deploy/grafana/dashboards/`](../../deploy/grafana/dashboards/) are the source
of truth anyway — read those rather than the live API.
