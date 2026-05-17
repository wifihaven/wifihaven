# Deploying WifiHaven

The api + postgres pair ships as a single Docker Compose stack. The dns and
traffic services do **not** live here — they run on an OpenWRT router and
reach this api over the network (see [`docs/architecture.md`](../docs/architecture.md)).

## What's in this directory

| File                              | Purpose |
| --------------------------------- | ------- |
| `docker-compose.prod.yml`         | Single-stack compose: postgres + api. |
| `.env.example`                    | Template for secrets/config. Copy to `.env`. |
| `wifihaven-api.service`           | Legacy systemd unit for host-based deploys. Kept for reference; new installs should use Compose. |

## Quick install

For a brand-new host, the one-liner installer handles everything in this
README — prereq checks, secret generation, `.env`, image pull, stack
start, health wait, and admin password rotation:

```bash
curl -fsSL https://raw.githubusercontent.com/wifihaven/wifihaven/main/deploy/install.sh | bash
```

See [`docs/install-api.md`](../docs/install-api.md) for the full
walkthrough or to install manually.

## One-command deploy

```bash
cp deploy/.env.example deploy/.env
$EDITOR deploy/.env                # set WIFIHAVEN_DB_PASSWORD and WIFIHAVEN_JWT_SECRET

docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env up -d --build
```

That's it. The api binds to `:8080` on the host; postgres stays inside the
compose network and is unreachable from the host or LAN.

Default admin login: `admin / changeme` — change it immediately via
`POST /api/auth/change-password`.

## Design notes

- **Postgres is internal-only.** No `ports:` mapping. The api reaches it as
  `postgres:5432` over the compose bridge network. Nothing on the host or
  LAN can connect to the database directly. Inspecting the DB requires
  `docker compose exec postgres psql ...`.
- **Named volume for data.** `pgdata` is a Docker named volume; data
  survives `down` / `up` cycles. Backups are a `pg_dump` away
  (`docker compose exec postgres pg_dump -U wifihaven wifihaven`).
- **Restart `unless-stopped`.** Both services come back automatically after
  a crash or host reboot, but stay down if you explicitly `docker compose
  stop` them.
- **Healthchecks on both services.** `api` waits for postgres to be
  healthy before it starts, and its own healthcheck hits the login
  endpoint (a 400/401 response proves the api + DB round-trip works).
- **Cloud vs. local target.** None of the above is target-specific — this
  works on any Linux host with Docker. Bind the api to `127.0.0.1` and
  front it with a TLS-terminating reverse proxy (Caddy, nginx, traefik) for
  production internet exposure. For LAN-only deployments, the default
  `0.0.0.0` bind plus a host firewall is fine.

## Common operations

```bash
# Tail logs
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env logs -f api

# Update to the latest main and restart
git pull && docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env up -d --build

# Backup the database
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env \
  exec postgres pg_dump -U wifihaven wifihaven > backup-$(date +%F).sql

# Open a psql shell
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env \
  exec postgres psql -U wifihaven wifihaven
```

## Smoke test

```bash
scripts/smoke-prod.sh
```

This brings the stack up, waits for the api healthcheck, asserts that
postgres is **not** reachable from the host, and tears the stack down.

## Migrating from the legacy host-based deploy

If you're running the older systemd-on-host install (`scripts/deploy.sh` +
`wifihaven-api.service`):

1. `pg_dump` your existing local postgres.
2. Stop and disable the systemd units.
3. Bring up the compose stack with a fresh `.env`.
4. Restore the dump: `docker compose ... exec -T postgres psql -U wifihaven wifihaven < backup.sql`.

Open an issue if you hit anything missing — this path will get more polish
as more installs migrate.
