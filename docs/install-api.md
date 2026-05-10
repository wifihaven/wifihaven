# First-install guide: FamilyDNS API server

This guide walks you through installing the FamilyDNS API + Postgres stack
on a fresh Linux host. By the end, you will have:

- The `api` and `postgres` containers running under Docker Compose.
- The API reachable at `http://<host>:8080`.
- The default admin password rotated.
- (Optional) A reverse proxy terminating TLS in front of the API.

For the OpenWRT router agent install, see `docs/install-openwrt.md` (issue
#133). For the broader CD architecture, see [`deploy.md`](deploy.md).

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
  remote). The port is configurable via `FAMILYDNS_API_PORT` — see §3.
- **Disk for Postgres data**. Data lives in the Docker named volume
  `pgdata`, which by default lands under `/var/lib/docker/volumes/` on the
  host. Make sure that filesystem has room (a few GB is plenty for typical
  household traffic; query/connection logs grow over time).
- **Outbound HTTPS** to `ghcr.io` so the host can pull the image.

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
| `FAMILYDNS_DB_NAME` | yes | Postgres database name. | Leave the default (`familydns`) unless you have a reason to change it. |
| `FAMILYDNS_DB_USER` | yes | Postgres role used by the API. | Leave the default (`familydns`). |
| `FAMILYDNS_DB_PASSWORD` | yes | Postgres password. Postgres is on the internal compose network only — but use a strong password anyway. | `openssl rand -base64 24` |
| `FAMILYDNS_JWT_SECRET` | yes | HMAC secret used to sign user session tokens. **Must be ≥32 random characters.** Anyone with this secret can mint admin tokens. | `openssl rand -base64 48` |
| `FAMILYDNS_JWT_HOURS` | no (default `24`) | Session token lifetime in hours. | Leave default unless you need shorter sessions. |
| `FAMILYDNS_API_BIND` | no (default `0.0.0.0`) | Host interface the API port binds to. Set to `127.0.0.1` if you're putting a reverse proxy in front (§7). | `127.0.0.1` for proxied installs, `0.0.0.0` for direct LAN access. |
| `FAMILYDNS_API_PORT` | no (default `8080`) | Host port mapped to the API. | Change only if 8080 is taken. |
| `FAMILYDNS_DNS_LOCATION` | no (default `home`) | Free-form label persisted with query/connection logs. Useful if you run multiple deployments. | `home`, `vacation`, etc. |

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

There is no dedicated `/api/health` route. The recommended check (and the
one the container's own healthcheck uses) is to hit `POST /api/auth/login`
with an empty JSON body and expect a 400/401 — this proves the HTTP server
is up *and* its database round-trip is working:

```sh
curl -s -o /dev/null -w '%{http_code}\n' \
  -X POST -H 'content-type: application/json' \
  -d '{}' http://localhost:8080/api/auth/login
# → 400 (or 401)
```

You can also check the container healthcheck status directly:

```sh
docker compose -f docker-compose.prod.yml --env-file .env ps
# → STATUS column should read "Up (healthy)" for both services
```

If the API container shows `unhealthy`, check `docker compose logs api`
— common causes are a wrong `FAMILYDNS_DB_PASSWORD` or a `FAMILYDNS_JWT_SECRET`
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
`FAMILYDNS_API_BIND=127.0.0.1` in `.env`, then `docker compose up -d` again.

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

## 9. Next steps

- **Auto-update.** Install the `familydns-update.timer` systemd unit so the
  host pulls and restarts on each new `latest` build. See `deploy.md §1.3`.
- **Enroll a router.** In the admin UI, **Routers → Add router** generates
  an enrollment token. Then follow `docs/install-openwrt.md` (issue #133)
  on the OpenWRT side.
- **Backups.** `docker compose exec postgres pg_dump -U familydns familydns`
  produces a logical dump. Schedule it however you back up the rest of the
  host.
