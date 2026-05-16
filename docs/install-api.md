# First-install guide: WifiHaven API server

This guide walks you through installing the WifiHaven API + Postgres stack
on a fresh Linux host. By the end, you will have:

- The `api` and `postgres` containers running under Docker Compose.
- The API reachable at `http://<host>:8080`.
- The default admin password rotated.
- (Optional) A reverse proxy terminating TLS in front of the API.

For the OpenWRT router agent install, see `docs/install-openwrt.md` (issue
#133). For the broader CD architecture, see [`deploy.md`](deploy.md).

---

## Quick install (one-liner)

If you have Docker + the Compose plugin already installed and you trust
the script (which you should read first — `curl … -o install.sh && less
install.sh`), this gets you a running stack with sensible defaults and a
rotated admin password:

```sh
curl -fsSL https://raw.githubusercontent.com/sameerparekh/familydns/main/deploy/install.sh | bash
```

The script:

- checks Docker is installed and reachable,
- prompts for install path, host port, bind address, and a new admin password,
- generates a strong DB password and JWT secret with `openssl rand`,
- writes `.env` (chmod 600), pulls the image, brings the stack up,
- waits for the API to become healthy,
- rotates the seeded `admin/changeme` password to whatever you entered,
- prints next-step pointers (reverse proxy, auto-update, router enrollment).

Re-running it on an existing install is safe — it offers to keep your
existing `.env` and `docker compose up -d` is idempotent.

By default the one-liner installs into `$HOME/.familydns` (user-writable, no
sudo required) — this is the recommended path for first-time users and is
what the `curl | bash` invocation above will do. For a system-wide install
under `/opt/familydns` (recommended for production hosts), set
`WIFIHAVEN_PREFIX` explicitly and run the script with `sudo`:

```sh
curl -fsSL https://raw.githubusercontent.com/sameerparekh/familydns/main/deploy/install.sh -o install.sh
sudo WIFIHAVEN_PREFIX=/opt/familydns bash install.sh
```

Piping `curl … | sudo bash` works too, but only if sudo is already warmed up
(`sudo -v` first) — otherwise sudo can't prompt for a password through the
pipe. The script detects this case and prints a recovery hint.

### Interactive prompts under `curl | bash`

Under `curl … | bash`, the script's stdin is the pipe, not your terminal —
so naively `read` would silently return empty for every prompt. The
installer works around this by reading from `/dev/tty` whenever a
controlling terminal is available, so the four configuration prompts
(install dir, port, bind address, location) and the admin-password
rotation prompt all work as you'd expect.

In environments where there is no controlling terminal at all (CI runners,
nohup, some container shells), `/dev/tty` is not available; the script
detects that and switches to non-interactive mode automatically — values
come from env vars (see below) or defaults. To force non-interactive mode
even when a tty is present, set `WIFIHAVEN_NONINTERACTIVE=1`.

### Non-interactive install

For unattended installs, set `WIFIHAVEN_NONINTERACTIVE=1` and any of the
env vars below to skip prompts. You can pass them on the same one-liner:

```sh
curl -fsSL https://raw.githubusercontent.com/sameerparekh/familydns/main/deploy/install.sh \
  | WIFIHAVEN_NONINTERACTIVE=1 \
    WIFIHAVEN_PREFIX=$HOME/.familydns \
    WIFIHAVEN_API_HOST_PORT=8080 \
    WIFIHAVEN_NEW_ADMIN_PW='choose-a-good-one' \
    bash
```

| Env var | Purpose | Default |
|---|---|---|
| `WIFIHAVEN_PREFIX` | install path (preferred name) | `$HOME/.familydns` (non-root) or `/opt/familydns` (root) |
| `WIFIHAVEN_INSTALL_DIR` | legacy alias for `WIFIHAVEN_PREFIX` | — |
| `WIFIHAVEN_API_HOST_PORT` | host port to bind | `8080` |
| `WIFIHAVEN_API_BIND` | host interface to bind on | `0.0.0.0` |
| `WIFIHAVEN_NEW_ADMIN_PW` | new admin password (skips rotation prompt) | prompt |
| `WIFIHAVEN_NONINTERACTIVE` | if set, never prompt; fail if any required value is missing | unset |

Run `bash install.sh --help` to print the same list.

The rest of this document is the manual walkthrough — read it if you'd
rather understand each step, or if the script doesn't fit your environment
(custom install path layout, atypical network setup, etc.). After a
successful one-liner install you can skip ahead to §7 (reverse proxy)
and §8 (firewall).

---

## 1. Prerequisites

- A Linux host (any distro). A small VPS, a home server, or a cloud VM all
  work — the stack is target-agnostic.
- **Docker Engine 20.10+** and the **Docker Compose v2 plugin** (`docker compose`,
  not the legacy `docker-compose`). On Debian/Ubuntu:

  ```sh
  curl -fsSL https://get.docker.com | sh
  ```

- **Port 8080/tcp** open from wherever the OpenWRT router agent will reach
  the API (typically your LAN, or the public internet if the router is
  remote). The port is configurable via `WIFIHAVEN_API_PORT` — see §3.
- **Disk for Postgres data**. Data lives in the Docker named volume
  `pgdata`, which by default lands under `/var/lib/docker/volumes/` on the
  host. Make sure that filesystem has room (a few GB is plenty for typical
  household traffic; query/connection logs grow over time).
- **Outbound HTTPS** to `ghcr.io` so the host can pull the image.
- **NTP / system clock sync.** The API server's clock is **authoritative**
  for schedule enforcement and daily-limit windows — those decisions are
  computed server-side and baked into the policy snapshot the router
  enforces, so a wrong clock on the API host silently shifts kids' bedtime
  blocks and daily quotas. Most Linux distros enable `systemd-timesyncd`
  or `chrony` out of the box; verify with `timedatectl status` and look
  for `System clock synchronized: yes`. If you're running on a host
  without internet (rare for this stack, but e.g. an air-gapped network),
  point chrony at a local stratum-1 source before bringing the API up.
  Skew on the *router* clock has no effect — the agent makes no
  time-based decisions (Truth 2 / #350).

---

## 2. Obtain the image

The API image is published to GitHub Container Registry at
`ghcr.io/sameerparekh/familydns-api`. Tags:

| Tag | When to use |
|-----|-------------|
| `latest` | Production. Tracks the latest green build of `main`. |
| `sha-<commit>` | Pinning to a specific commit (e.g. for rollback). |

After issue #128 lands the package will be public, so anonymous pulls work:

```sh
docker pull ghcr.io/sameerparekh/familydns-api:latest
```

If the package is still private when you install, log in to ghcr.io first
with a GitHub personal access token that has the `read:packages` scope:

```sh
echo "$GHCR_PAT" | docker login ghcr.io -u <your-github-username> --password-stdin
docker pull ghcr.io/sameerparekh/familydns-api:latest
```

You don't strictly need to pre-pull — `docker compose up -d` in §4 will
pull on first run — but doing it now surfaces auth problems before you've
written your `.env`.

---

## 3. Install

### 3.1 Get the deploy directory

You only need the `deploy/` directory on the host, not the full source. The
simplest path is a shallow clone:

```sh
sudo mkdir -p /opt/familydns
sudo chown "$USER" /opt/familydns
git clone --depth 1 https://github.com/sameerparekh/familydns.git /opt/familydns
cd /opt/familydns/deploy
```

Or, if you'd rather not clone the whole repo, copy the two files directly
from GitHub:

```sh
mkdir -p /opt/familydns/deploy && cd /opt/familydns/deploy
curl -fsSLO https://raw.githubusercontent.com/sameerparekh/familydns/main/deploy/docker-compose.prod.yml
curl -fsSLO https://raw.githubusercontent.com/sameerparekh/familydns/main/deploy/.env.example
```

### 3.2 Create your `.env`

```sh
cp .env.example .env
```

Edit `.env` and set each variable. **Never commit this file.** All values:

| Variable | Required? | Purpose | How to set |
|----------|-----------|---------|------------|
| `WIFIHAVEN_DB_NAME` | yes | Postgres database name. | Leave the default (`familydns`) unless you have a reason to change it. |
| `WIFIHAVEN_DB_USER` | yes | Postgres role used by the API. | Leave the default (`familydns`). |
| `WIFIHAVEN_DB_PASSWORD` | yes | Postgres password. Postgres is on the internal compose network only — but use a strong password anyway. | `openssl rand -base64 24` |
| `WIFIHAVEN_JWT_SECRET` | yes | HMAC secret used to sign user session tokens. **Must be ≥32 random characters.** Anyone with this secret can mint admin tokens. | `openssl rand -base64 48` |
| `WIFIHAVEN_JWT_HOURS` | no (default `24`) | Session token lifetime in hours. | Leave default unless you need shorter sessions. |
| `WIFIHAVEN_API_BIND` | no (default `0.0.0.0`) | Host interface the API port binds to. Set to `127.0.0.1` if you're putting a reverse proxy in front (§7). | `127.0.0.1` for proxied installs, `0.0.0.0` for direct LAN access. |
| `WIFIHAVEN_API_PORT` | no (default `8080`) | Host port mapped to the API. | Change only if 8080 is taken. |

After editing, `chmod 600 .env` so secrets aren't world-readable.

---

## 4. Start the stack

From `/opt/familydns/deploy`:

```sh
docker compose -f docker-compose.prod.yml --env-file .env up -d
```

This pulls the image (if not already present), starts Postgres, waits for
its healthcheck, then starts the API. Flyway migrations run automatically
at API startup, including the V1 migration that seeds the default admin
user.

Watch logs until the API reports it's listening:

```sh
docker compose -f docker-compose.prod.yml --env-file .env logs -f api
```

Press `Ctrl-C` to stop tailing — the containers keep running.

---

## 5. Verify health

Hit the unauthenticated `GET /api/health` endpoint. It performs a cheap
`SELECT 1` against Postgres and returns 200 only if both the HTTP server
and its database round-trip are working:

```sh
curl -fsS http://localhost:8080/api/health
# → {"status":"ok","db":"ok"}
```

If the DB is unreachable the endpoint returns 503 with
`{"status":"error","db":"<error class>"}` and a `Retry-After: 30` header.
This is the same probe used by the container's own healthcheck and is safe
to point uptime monitors and reverse-proxy health checks at.

The same 503 shape (JSON body + `Retry-After: 30`) is returned by every
DB-touching `/api/router/*` and admin route on a database blip — the
OpenWRT agent treats a 503 like a transient connection failure and backs
off, where a 5xx without `Retry-After` would be ambiguous. Bare 500
responses are reserved for genuinely unexpected non-DB failures (#310,
docs/resilience.md §3).

You can also check the container healthcheck status directly:

```sh
docker compose -f docker-compose.prod.yml --env-file .env ps
# → STATUS column should read "Up (healthy)" for both services
```

If the API container shows `unhealthy`, check `docker compose logs api`
— common causes are a wrong `WIFIHAVEN_DB_PASSWORD` or a `WIFIHAVEN_JWT_SECRET`
shorter than 32 characters.

---

## 6. Create the first admin user

The V1 database migration seeds a default admin account:

- **Username:** `admin`
- **Password:** `changeme`

**Rotate this password immediately.** From any host that can reach the API:

```sh
curl -s -X POST -H 'content-type: application/json' \
  -d '{"username":"admin","oldPassword":"changeme","newPassword":"<your-new-password>"}' \
  http://localhost:8080/api/auth/change-password
```

A 200 response means the password is updated. You can also do this through
the web UI: navigate to `http://<host>:8080`, log in as `admin / changeme`,
and use the change-password flow.

After rotating, log in to confirm:

```sh
curl -s -X POST -H 'content-type: application/json' \
  -d '{"username":"admin","password":"<your-new-password>"}' \
  http://localhost:8080/api/auth/login
# → {"token":"…","user":{…}}
```

To create additional admin or parent users, use the admin UI
(**Users → Add user**) or `POST /api/users` with the bearer token from the
login response.

---

## 7. Reverse proxy (optional, recommended for production)

For LAN-only deployments where the router and the API are on the same
trusted network, you can skip TLS — the OpenWRT agent doesn't strictly
require it. For anything reachable from the internet, terminate TLS in a
reverse proxy and bind the API to `127.0.0.1` by setting
`WIFIHAVEN_API_BIND=127.0.0.1` in `.env`, then `docker compose up -d` again.

### 7.1 Caddy

`/etc/caddy/Caddyfile`:

```caddy
familydns.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

Caddy obtains and renews a Let's Encrypt certificate automatically.

```sh
sudo systemctl reload caddy
```

### 7.2 nginx

`/etc/nginx/sites-available/familydns`:

```nginx
server {
    listen 443 ssl http2;
    server_name familydns.example.com;

    ssl_certificate     /etc/letsencrypt/live/familydns.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/familydns.example.com/privkey.pem;

    # The API streams responses for some endpoints; keep timeouts generous.
    proxy_read_timeout 300s;
    proxy_send_timeout 300s;
    client_max_body_size 4m;

    location / {
        proxy_pass         http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header   Host              $host;
        proxy_set_header   X-Real-IP         $remote_addr;
        proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto $scheme;
    }
}

server {
    listen 80;
    server_name familydns.example.com;
    return 301 https://$host$request_uri;
}
```

Issue/renew the cert with `certbot --nginx`, then:

```sh
sudo ln -s /etc/nginx/sites-available/familydns /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl reload nginx
```

---

## 7a. Host clock and timezone (#334)

The API host should run an **NTP-synced UTC system clock**. Decision logic
is `Instant`-based and per-row timezone-aware (see
[`time-handling.md`](time-handling.md)) — the host's local timezone setting
is not consulted for schedule or daily-reset evaluation.

The host's local timezone *is* read once on first boot to seed the
household's default daily-reset timezone (`household_settings.daily_reset_tz`).
After that the operator can change it via the UI; the host tz is never
re-read. So:

- If you intend most schedules to be in your own home timezone, set the
  host tz to that zone (`sudo timedatectl set-timezone America/Los_Angeles`)
  *before* the first API boot.
- If you forgot, no harm done — set the household's tz from the
  **Profiles** page after install.

---

## 8. Firewall

The OpenWRT router agent must be able to reach the API. Open the path it
will use, and only that path:

- **Same LAN, no reverse proxy.** Allow inbound `tcp/8080` on the API host
  from your router's LAN IP (or your LAN subnet). Example with `ufw`:

  ```sh
  sudo ufw allow from 192.168.1.0/24 to any port 8080 proto tcp
  ```

- **Behind a reverse proxy.** Allow inbound `tcp/443` from the router's
  public IP (or `0.0.0.0/0` if the router is mobile). Block direct access
  to 8080 from off-host:

  ```sh
  sudo ufw allow 443/tcp
  sudo ufw deny 8080/tcp
  ```

The API does not need any outbound connectivity to the router — the agent
polls in. The host does need outbound HTTPS to `ghcr.io` for image pulls
(continuously, if you enable the auto-update timer from `deploy.md §1.3`).

---

## 9. Debugging

When devices are missing from the UI, showing up as 'unknown', or the
router agent appears silent, three opt-in surfaces help trace the
mac → API → DB → UI hop without exposing anything in normal production.

### 9.1 Verbose logging (`WIFIHAVEN_LOG_LEVEL=DEBUG`)

Each `/api/router/{usage,events,policy}` request emits one log line per
record/event with the mac, hostname, allowed/blocked flag, and ts. Combine
with `docker compose logs -f api` while the offending device is active.
Defaults to `INFO`; set in `deploy/.env` (or use the debug overlay below,
which turns this on for you).

On the **OpenWRT side**, set `uci set familydns.@familydns[0].debug=1; uci
commit; /etc/init.d/familydns restart` to make the agent log every policy
fetch, usage POST, event flush, and per-flow mac/hostname attribution to
`logread -t familydns` (#228).

### 9.2 Loopback-only debug endpoints (`WIFIHAVEN_DEBUG=1`)

When set, the API mounts three unauthenticated read-only JSON dumps,
restricted by both `remoteAddress` and the `Host` header to loopback
callers on the API host:

- `GET /api/debug/devices` — all rows in the `devices` table
- `GET /api/debug/events?limit=N` — recent `connection_events` (default 50, max 500)
- `GET /api/debug/time_usage` — per-(mac, domain) usage for today

These are equivalent to running `psql` against the DB without the network
exposure. Every request — allowed or refused — logs at INFO/WARN, so an
accidentally-left-on debug build is loud in production. The startup banner
also emits a `WIFIHAVEN_DEBUG=1` WARNING.

Usage from the API host:

```sh
curl -s http://localhost:8080/api/debug/devices | jq .
curl -s 'http://localhost:8080/api/debug/events?limit=200' | jq '.[].mac'
```

From off-host: refused with `403`.

### 9.3 Exposed Postgres (`docker-compose.debug.yml`)

For ad-hoc SQL, an overlay file maps the `postgres` service to a host port
(default `127.0.0.1:5433`) and enables the API knobs above:

```sh
docker compose \
  -f deploy/docker-compose.prod.yml \
  -f deploy/docker-compose.debug.yml \
  --env-file deploy/.env up -d
psql -h 127.0.0.1 -p 5433 -U familydns familydns
```

Override the bind with `WIFIHAVEN_DB_BIND=127.0.0.1:5433` in `deploy/.env`
if the default port is taken. Keep the bind on `127.0.0.1` — exposing the
DB on `0.0.0.0` leaks credentials.

To return to a clean prod stack, restart without the debug file:

```sh
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env up -d
```

---

## 10. Next steps

- **Auto-update.** Enabled by default via `familydns-update.timer` (installed
  by `deploy/install.sh`). The host pulls and restarts on each new `latest`
  build, once a day. See `deploy.md §1.3` to disable or force an on-demand pull.
- **Enroll a router.** In the admin UI, **Routers → Add router** generates
  an enrollment token. Then follow `docs/install-openwrt.md` (issue #133)
  on the OpenWRT side.
- **Backups.** `docker compose exec postgres pg_dump -U familydns familydns`
  produces a logical dump. Schedule it however you back up the rest of the
  host.
