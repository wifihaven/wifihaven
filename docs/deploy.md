# WifiHaven Deploy Architecture

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

Workflow: `.github/workflows/e2e.yml`, `publish` job (implementation in #128).

**Trigger**: push to `main`, after all e2e and smoke jobs pass.

The publish step is the CD gate — artifacts only reach ghcr.io after the full
test suite is green. No separate publish workflow exists.

**Steps**:
1. `bootstrap-smoke`, `e2e`, `prod-stack-smoke` jobs run in parallel.
2. On all green, the `publish` job logs in to ghcr.io and runs `docker/build-push-action`.
3. GHA layer cache (`cache-from/cache-to: type=gha`) reuses layers built during the e2e job.

**Tag strategy**:

| Event | Tags applied |
|-------|-------------|
| Push to `main` (after green e2e) | `latest`, `sha-<7-char-commit>` |

Semver tags (`1.2.3`, `1.2`, `1`) are not applied automatically — cut a
release by pushing a `v*` git tag, which can be done after the `main` push
has already published `latest`.

The `sha-` tag is immutable and safe to reference for rollbacks. `latest` is
what the prod compose stack pulls on auto-update.

**Image name**: `ghcr.io/wifihaven/wifihaven-api`

The GHCR token is the built-in `GITHUB_TOKEN`; no manual secret needed.

### 1.2 Deployment target

`deploy/docker-compose.prod.yml` — a two-service stack (Postgres + API server)
that runs on any Linux host with Docker installed.

In production, the `api` service is configured to pull from ghcr.io:

```yaml
api:
  image: ghcr.io/wifihaven/wifihaven-api:latest
```

The image is never built on the prod host. All builds happen in CI.

### 1.3 Auto-update: systemd timer (on by default, daily)

**Chosen approach**: systemd timer that runs `docker compose pull && docker compose
up -d` on the host.

**Why not Watchtower**: Watchtower runs as a privileged container with access to
the Docker socket, which is a significant attack surface expansion. The systemd
timer approach is equally simple, more transparent (standard Linux tooling), and
doesn't add another long-running container to maintain.

**Status**: enabled automatically by `deploy/install.sh` (issue #254). The
units live in-tree at [`deploy/systemd/wifihaven-update.service`](../deploy/systemd/wifihaven-update.service)
and [`deploy/systemd/wifihaven-update.timer`](../deploy/systemd/wifihaven-update.timer)
(sub-issue #129). The bootstrap installer copies them into
`/etc/systemd/system/`, runs `systemctl daemon-reload`, and
`systemctl enable --now wifihaven-update.timer` on first install. The step
is idempotent — re-running `install.sh` is safe.

**Units** (excerpt — see the files for the full content):

```ini
# wifihaven-update.service
[Service]
Type=oneshot
WorkingDirectory=/opt/wifihaven/deploy
ExecStart=/usr/bin/docker compose -f docker-compose.prod.yml --env-file .env pull
ExecStart=/usr/bin/docker compose -f docker-compose.prod.yml --env-file .env up -d
```

```ini
# wifihaven-update.timer
[Timer]
OnBootSec=5min
OnUnitActiveSec=1d
Unit=wifihaven-update.service

[Install]
WantedBy=timers.target
```

This polls ghcr.io once a day (with a 5-min post-boot run so a freshly
rebooted host catches up quickly). `docker compose pull` is a no-op when
`latest` already matches the local digest, so it's cheap. To pull a new
image **on demand** rather than wait for the daily tick, run
`systemctl start wifihaven-update.service` (or the `wifihaven-update-now`
operator skill).

**User-mode installs** (`WIFIHAVEN_PREFIX=$HOME/.wifihaven`): `install.sh`
rewrites `WorkingDirectory=` to the actual install dir before copying the
unit into `/etc/systemd/system/`, so user-mode installs also get auto-update.

**Disable** — for operators who prefer manual control over when prod pulls
a new image:

```sh
sudo systemctl disable --now wifihaven-update.timer
```

After that, run updates by hand from the install dir:

```sh
/opt/wifihaven/update.sh
```

---

## 2. OpenWRT agent CD

### 2.1 Build artifact: `.ipk` package

The agent is pure Lua (`PKGARCH:=all`), so no cross-compilation is needed.
`openwrt/build-ipk.sh` assembles the `.ipk` without the full OpenWRT SDK.

Output: `openwrt/wifihaven_<version>-<release>_all.ipk`

### 2.2 Build and publish: `.ipk` → GitHub Releases

Workflow: `.github/workflows/openwrt-build.yml`.

**Release strategy: tagged releases only (Option B from #130).**
Routers auto-update only when a `vX.Y.Z` tag is pushed. We do *not* publish
per-commit pre-releases. Rationale: tagged releases are the natural cadence
for router updates, avoid GitHub Release churn on every commit, and let the
router's auto-update script target the simple `releases/latest` endpoint
(which excludes pre-releases by default).

**Trigger**:
- PR touching `openwrt/**` → build (verify only).
- Push to `main` → build + upload as a workflow artifact (smoke test only,
  not published as a release).
- Push of a `v*` tag → build + attach to a GitHub Release.

On a `v*` tag, `softprops/action-gh-release` creates the release named after
the tag (e.g. `v0.2.0`) and attaches `openwrt/wifihaven_*.ipk`. The release
is the distribution mechanism for the router.

To cut a release:
```sh
git tag v0.2.0
git push origin v0.2.0
```

This produces `wifihaven_0.2.0-1_all.ipk` attached to the `v0.2.0` release
on GitHub. Routers running the auto-update script (§2.3) pick it up on their
next poll.

**Cadence**: cut a tag whenever a meaningful change to the agent lands on
`main` — there is no fixed schedule. Typical expectation is at most weekly,
often less. Trivial doc-only or test-only changes do not need a tag.

### 2.3 Auto-update on the router

**Approach**: a cron script that polls the GitHub Releases API, downloads the
latest `.ipk` if the version has changed, and upgrades via `opkg install
--force-reinstall`.

**Why not `opkg update && opkg upgrade`**: that requires maintaining a self-hosted
opkg feed (an `Packages` index file on a web server). The GitHub Releases approach
is simpler for a single package: no feed server needed, and the GitHub API is the
canonical source of truth for versions.

**Implementation** (sub-issue #131):

Place at `/usr/sbin/wifihaven-update` on the router:

```sh
#!/bin/sh
CURRENT=$(opkg info wifihaven | awk '/^Version:/{print $2}')
LATEST_URL=$(curl -sf https://api.github.com/repos/wifihaven/wifihaven/releases/latest \
  | jsonfilter -e '@.assets[0].browser_download_url')
LATEST_VER=$(echo "$LATEST_URL" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+-[0-9]+')
if [ "$LATEST_VER" != "$CURRENT" ]; then
  curl -fsSL -o /tmp/wifihaven.ipk "$LATEST_URL"
  opkg install --force-reinstall /tmp/wifihaven.ipk
fi
```

Add to cron (`crontab -e` or `/etc/crontabs/root`):
```
0 4 * * * /usr/sbin/wifihaven-update
```

This checks once a day at 04:00 router-local time. The `.ipk` postinst
installs this entry automatically and replaces any older entry from a
previous package version (e.g. the historical `0 */6 * * *` cadence), so
upgrades migrate the schedule without operator action. `opkg install --force-reinstall` preserves
`/etc/config/wifihaven`, so the bearer token and router ID survive upgrades
without re-enrollment.

### 2.4 Configuration persistence across upgrades

opkg's upgrade behavior for config files:
- Files listed in `conffiles` (or installed under `/etc/config/`) are preserved
  across `opkg install --force-reinstall`.
- `router_token` and `router_id` (written to `/etc/config/wifihaven` after
  enrollment) survive any upgrade.
- `api_url` is in the same UCI config file and also survives; no re-configuration
  needed after an upgrade.

---

## 3. First-install bootstrap

The full step-by-step install guides live in their own documents:

- API server → [`install-api.md`](install-api.md)
- OpenWRT agent → [`install-openwrt.md`](install-openwrt.md)

The summaries below capture the shape of each bootstrap; consult the linked
guides for runnable commands, prerequisites, and verification steps.

### 3.1 API server

> **See [`install-api.md`](install-api.md) for the full first-install guide.**
> The summary below is enough orientation for an architecture reader; new
> operators should follow the standalone guide instead.

**Requirements**: a Linux host with Docker and Docker Compose installed.

```sh
# 1. Clone the repo or copy deploy/ to the host
git clone git@github.com:wifihaven/wifihaven.git /opt/wifihaven
cd /opt/wifihaven/deploy

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
| `WIFIHAVEN_DB_USER` | Postgres username |
| `WIFIHAVEN_DB_PASSWORD` | Postgres password (strong, random) |
| `WIFIHAVEN_DB_NAME` | Postgres database name |
| `WIFIHAVEN_JWT_SECRET` | JWT signing secret, ≥32 random characters |

Optional vars have defaults; see `deploy/.env.example` for the full list.

Postgres data is stored in a named Docker volume (`pgdata`) on the host.
Migrations run automatically at API startup via Flyway.

**First-run checklist**:
- [ ] API is reachable at `http://<host>:8080`
- [ ] Create the first admin user (see web UI login flow)
- [ ] Go to **Routers → Add router** to generate enrollment tokens for each gateway

### 3.2 OpenWRT router

**Requirements**: OpenWRT 23.05.x with internet access from the router.

End users install via the one-shot script — see
[`install-openwrt.md`](install-openwrt.md) for the full guide and the
manual-fallback path. The headline command, run as root on the router:

```sh
sh -c "$(uclient-fetch -qO - https://raw.githubusercontent.com/wifihaven/wifihaven/main/openwrt/install.sh)"
```

It prompts for the API URL, the one-time enrollment token (generated in the
admin UI → **Routers → Add router**), a router name, and the LAN prefix
(auto-detected from `network.lan.ipaddr`); downloads the latest `.ipk` from
GitHub Releases; installs it; POSTs `/api/router/register` to exchange the
enrollment token for `routerId` + `routerToken`; writes everything to UCI;
sets up the `uhttpd` block-page listener on `127.0.0.1:8081`; and starts
the agent.

The auto-update cron job (#131) is optional and not yet implemented.

---

## 4. Sub-issues

The implementation work is tracked in the following issues, all of which must
close before #123 is resolved:

| Issue | Title |
|-------|-------|
| #128 | CD: wire Docker image publish into e2e.yml publish job |
| #129 | Auto-update: systemd timer for API server Docker image |
| #130 | CD: build and publish OpenWRT .ipk to GitHub Releases on push to main |
| #131 | Auto-update: opkg cron job on OpenWRT router |
| #132 | Bootstrap docs: first-install guide for API server |
| #133 | Bootstrap docs: first-install guide for OpenWRT router |
