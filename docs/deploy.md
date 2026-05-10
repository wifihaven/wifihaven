# FamilyDNS Deploy Architecture

This document covers the full CD pipeline, first-install bootstrap, and
auto-update strategy for both deployment targets.

## Overview

| Component | Runtime | Deploy target | CD mechanism |
|-----------|---------|---------------|--------------|
| API server | Scala + Postgres | Any Docker host (VPS, home server, cloud VM) | ghcr.io image, pulled via docker compose |
| OpenWRT agent | Lua | OpenWRT 23.x router | `.ipk` on GitHub Releases, installed via opkg |

OpnSense support exists in-tree but is deprioritized. The CD approach for OpnSense
is out of scope here; add it later without redesigning the API/OpenWRT path.

---

## 1. API server CD

### 1.1 Build and publish: Docker image → ghcr.io

Workflow: `.github/workflows/docker-publish.yml` (stub, implementation in #128).

**Trigger**: push to `main`, or a `v*` tag push.

**Steps**:
1. Check out source.
2. Build the Scala fat-jar via Mill (`api.assembly`).
3. Build the React/Vite bundle.
4. Build the Docker image (`docker/Dockerfile`).
5. Push to `ghcr.io/sameerparekh/familydns-api` with the tag strategy below.

**Tag strategy**:

| Event | Tags applied |
|-------|-------------|
| Push to `main` | `latest`, `sha-<7-char-commit>` |
| Push of `v1.2.3` tag | `latest`, `sha-<7-char-commit>`, `1.2.3`, `1.2`, `1` |

The `sha-` tag is immutable and safe to reference for rollbacks. `latest` is
what the prod compose stack pulls on auto-update.

**Image name**: `ghcr.io/sameerparekh/familydns-api`

The GHCR token is the built-in `GITHUB_TOKEN`; no manual secret needed.

### 1.2 Deployment target

`deploy/docker-compose.prod.yml` — a two-service stack (Postgres + API server)
that runs on any Linux host with Docker installed.

In production, the `api` service is configured to pull from ghcr.io:

```yaml
api:
  image: ghcr.io/sameerparekh/familydns-api:latest
```

The image is never built on the prod host. All builds happen in CI.

### 1.3 Auto-update: systemd timer

**Chosen approach**: systemd timer that runs `docker compose pull && docker compose
up -d` on the host.

**Why not Watchtower**: Watchtower runs as a privileged container with access to
the Docker socket, which is a significant attack surface expansion. The systemd
timer approach is equally simple, more transparent (standard Linux tooling), and
doesn't add another long-running container to maintain.

**Implementation** (sub-issue #129):

Place on the prod host at `/etc/systemd/system/familydns-update.service` and
`familydns-update.timer`:

```ini
# familydns-update.service
[Service]
Type=oneshot
WorkingDirectory=/opt/familydns/deploy
ExecStart=/usr/bin/docker compose -f docker-compose.prod.yml --env-file .env pull
ExecStart=/usr/bin/docker compose -f docker-compose.prod.yml --env-file .env up -d
```

```ini
# familydns-update.timer
[Timer]
OnBootSec=5min
OnUnitActiveSec=5min
```

This polls ghcr.io every 5 minutes. `docker compose pull` is a no-op when
`latest` already matches the local digest, so it's cheap.

Enable with:
```sh
systemctl enable --now familydns-update.timer
```

---

## 2. OpenWRT agent CD

### 2.1 Build artifact: `.ipk` package

The agent is pure Lua (`PKGARCH:=all`), so no cross-compilation is needed.
`openwrt/build-ipk.sh` assembles the `.ipk` without the full OpenWRT SDK.

Output: `openwrt/familydns_<version>-<release>_all.ipk`

### 2.2 Build and publish: `.ipk` → GitHub Releases

Workflow: `.github/workflows/openwrt-build.yml` (already exists, updated in #130).

**Trigger**:
- Push to `main` touching `openwrt/**` → build + upload as a workflow artifact.
- Push of a `v*` tag → build + attach to a GitHub Release.

On a `v*` tag, `softprops/action-gh-release` creates the release and attaches
the `.ipk`. The release is the distribution mechanism for the router.

To cut a release:
```sh
git tag v0.2.0
git push origin v0.2.0
```

This produces `familydns_0.2.0-1_all.ipk` attached to the `v0.2.0` release on
GitHub. Routers running the auto-update script pick it up within the next poll.

### 2.3 Auto-update on the router

**Approach**: a cron script that polls the GitHub Releases API, downloads the
latest `.ipk` if the version has changed, and upgrades via `opkg install
--force-reinstall`.

**Why not `opkg update && opkg upgrade`**: that requires maintaining a self-hosted
opkg feed (an `Packages` index file on a web server). The GitHub Releases approach
is simpler for a single package: no feed server needed, and the GitHub API is the
canonical source of truth for versions.

**Implementation** (sub-issue #131):

Place at `/usr/sbin/familydns-update` on the router:

```sh
#!/bin/sh
CURRENT=$(opkg info familydns | awk '/^Version:/{print $2}')
LATEST_URL=$(curl -sf https://api.github.com/repos/sameerparekh/familydns/releases/latest \
  | jsonfilter -e '@.assets[0].browser_download_url')
LATEST_VER=$(echo "$LATEST_URL" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+-[0-9]+')
if [ "$LATEST_VER" != "$CURRENT" ]; then
  curl -fsSL -o /tmp/familydns.ipk "$LATEST_URL"
  opkg install --force-reinstall /tmp/familydns.ipk
fi
```

Add to cron (`crontab -e` or `/etc/crontabs/root`):
```
0 */6 * * * /usr/sbin/familydns-update
```

This checks every 6 hours. `opkg install --force-reinstall` preserves
`/etc/config/familydns`, so the bearer token and router ID survive upgrades
without re-enrollment.

### 2.4 Configuration persistence across upgrades

opkg's upgrade behavior for config files:
- Files listed in `conffiles` (or installed under `/etc/config/`) are preserved
  across `opkg install --force-reinstall`.
- `router_token` and `router_id` (written to `/etc/config/familydns` after
  enrollment) survive any upgrade.
- `api_url` is in the same UCI config file and also survives; no re-configuration
  needed after an upgrade.

---

## 3. First-install bootstrap

### 3.1 API server

**Requirements**: a Linux host with Docker and Docker Compose installed.

```sh
# 1. Clone the repo or copy deploy/ to the host
git clone git@github.com:sameerparekh/familydns.git /opt/familydns
cd /opt/familydns/deploy

# 2. Create .env from the example
cp .env.example .env
$EDITOR .env  # fill in DB password, JWT secret

# 3. Log in to ghcr.io (first time only; uses a PAT or GITHUB_TOKEN)
echo $GITHUB_TOKEN | docker login ghcr.io -u $GITHUB_USER --password-stdin

# 4. Start the stack
docker compose -f docker-compose.prod.yml --env-file .env up -d
```

**Minimum required env vars**:

| Variable | Description |
|----------|-------------|
| `FAMILYDNS_DB_USER` | Postgres username |
| `FAMILYDNS_DB_PASSWORD` | Postgres password (strong, random) |
| `FAMILYDNS_DB_NAME` | Postgres database name |
| `FAMILYDNS_JWT_SECRET` | JWT signing secret, ≥32 random characters |

Optional vars have defaults; see `deploy/.env.example` for the full list.

Postgres data is stored in a named Docker volume (`pgdata`) on the host.
Migrations run automatically at API startup via Flyway.

**First-run checklist**:
- [ ] API is reachable at `http://<host>:8080`
- [ ] Create the first admin user (see web UI login flow)
- [ ] Go to **Routers → Add router** to generate enrollment tokens for each gateway

### 3.2 OpenWRT router

**Requirements**: OpenWRT 23.x with internet access from the router.

```sh
# 1. Download the latest .ipk from GitHub Releases
curl -fsSL -o /tmp/familydns.ipk \
  $(curl -sf https://api.github.com/repos/sameerparekh/familydns/releases/latest \
    | jsonfilter -e '@.assets[0].browser_download_url')

# 2. Install (opkg resolves lua, luci-lib-jsonc, conntrack-tools, curl)
opkg install /tmp/familydns.ipk
```

**One-time enrollment**:

```sh
# 3. Set the API URL
uci set familydns.@familydns[0].api_url='http://<api-host>:8080'
uci commit familydns

# 4. Exchange enrollment token (token from the admin UI → Routers → Add router)
curl -s -X POST http://<api-host>:8080/api/router/register \
  -H 'Content-Type: application/json' \
  -d '{"enrollmentToken":"et_…","routerName":"home-gw","platformVersion":"23.05.3","agentVersion":"0.1.0"}'
# → {"routerId":"…","routerToken":"rt_…"}

# 5. Write the returned values
uci set familydns.@familydns[0].router_id='<routerId>'
uci set familydns.@familydns[0].router_token='<routerToken>'
uci commit familydns

# 6. Start the agent (already enabled for autostart by postinst)
/etc/init.d/familydns start

# 7. (Optional) install the auto-update script
#    See §2.3 above for the script content
```

The agent polls the API every 60 s. After a successful policy fetch the admin
UI → Routers → `<name>` will show a fresh `last_seen_at`.

**LAN subnet**: if your LAN is not `192.168.1.0/24`, also set:
```sh
uci set familydns.@familydns[0].lan_prefix='10.0.'   # adjust to your prefix
uci commit familydns
```

---

## 4. Sub-issues

The implementation work is tracked in the following issues, all of which must
close before #123 is resolved:

| Issue | Title |
|-------|-------|
| #128 | CD: publish Docker image to ghcr.io on push to main |
| #129 | Auto-update: systemd timer for API server Docker image |
| #130 | CD: build and publish OpenWRT .ipk to GitHub Releases on push to main |
| #131 | Auto-update: opkg cron job on OpenWRT router |
| #132 | Bootstrap docs: first-install guide for API server |
| #133 | Bootstrap docs: first-install guide for OpenWRT router |
