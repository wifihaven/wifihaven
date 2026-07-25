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

### Path B — CLI (`logcli`) / HTTP query API — needs a read token NOT yet provisioned

`logcli` / the `/loki/api/v1/query_range` HTTP API give scriptable, greppable
access, but **the read credential does not exist yet.** The only Loki token in
the repo/Render is `GRAFANA_CLOUD_LOKI_PASSWORD`, which is **logs-*push* scope**
(see [`render.yaml`](../../render.yaml) `# PASSWORD is a Grafana Cloud API token
with logs-push scope`) — it cannot read. Until a read token is provisioned, use
**Path A**. To enable Path B (operator, one-time):

1. In Grafana Cloud → **Access Policies**, create an access-policy token scoped
   **`logs:read`** for the Loki instance (distinct from the push token).
2. Find the Loki **query** host + numeric user id under **Connections → Loki →
   Details** (the query base URL, e.g. `https://logs-prod-NNN.grafana.net`, and
   the instance/user id).
3. Export for `logcli`:

   ```bash
   export LOKI_ADDR="https://logs-prod-NNN.grafana.net"   # query host, NOT the /push URL
   export LOKI_USERNAME="<numeric Loki instance id>"
   export LOKI_BEARER_TOKEN="<logs:read access-policy token>"
   logcli query '{service="wifihaven-api", env="production", level="ERROR"}' --since=1h
   ```

   Keep the token in memory only — never echo, print, or commit it (same
   read-only / cred-masking discipline as prod `EXPLAIN`; see `AGENTS.md`).
