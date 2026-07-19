# WifiHaven

A self-hosted, network-level parental-control system with a web UI. Block
categories of sites, set per-profile schedules ("no internet after 9pm"),
enforce daily and per-site time limits, log queries, and grant temporary
extensions — all on your own hardware, no third-party DNS provider.

```
                 +-------------------+    +-----------+
HTTP / web ────▶ |  wifihaven-api    | ─▶ |  Postgres |
   :8080         +-------------------+    +-----------+
                   ▲              ▲
                   │ policy pull  │ usage push
                   │              │
             OpenWRT router    OPNsense router
             (openwrt/ agent)  (opnsense/ agent)
```

## Enforcement model

DNS always resolves. We do **not** block by failing DNS resolution; blocking
happens at the connection layer (nftables forward-drop on the gateway router).
The block page is reached via HTTP DNAT on port 80, **not** via DNS sinkhole
or NXDOMAIN. dnsmasq is still used on the router for hostname attribution
(forward-lookup ipset population), but it is not the enforcement plane.

All policy decisions — schedules, daily / per-site time limits, pause state,
category membership, failover behaviour — live on the **API server**. The
router agent pulls a policy snapshot every ~60 s and is a **dumb applier**:
it never reasons about schedules, profiles, or categories at enforcement
time. New policy concepts land in the API and present to the router as one
of a small fixed set of fields (`blocked`, `extraBlocked`, `extraAllowed`,
`blocklistIds`, `blockIpOnly`).

See [`docs/architecture.md`](docs/architecture.md) for the snapshot contract
and the full enforcement model.

## Components

| Module      | What it does                                              | Runtime |
| ----------- | --------------------------------------------------------- | ------- |
| `api`       | REST + JWT auth, profiles, devices, policy snapshots      | runnable (Main) |
| `shared`    | Common models, clock                                      | library |
| `web`       | React + Vite admin UI                                     | static bundle (`web/dist`) |
| `openwrt/`  | Lua agent: policy pull, nft enforcement, hostname attribution, usage | OpenWRT ipk |
| `opnsense/` | Python agent: pflog tail, connection_attempt events       | OPNsense plugin |

## Quick install

Get a running stack on a Linux host plus an enforcement agent on your gateway
router. Each script prompts for the values it needs and is safe to re-run.

**1. API + Postgres** — on a Linux host with Docker + Compose v2:

```sh
curl -fsSL https://raw.githubusercontent.com/wifihaven/wifihaven/main/deploy/install.sh | bash
```

Prompts for install path, port, bind address, and a new admin password;
generates secrets, brings the stack up, rotates the seeded `admin/changeme`.
Full walkthrough (TLS, reverse proxy, firewall, debugging): [`docs/install-api.md`](docs/install-api.md).

**2. OpenWRT router agent** — SSH in as root, then:

```sh
sh -c "$(uclient-fetch -qO - https://raw.githubusercontent.com/wifihaven/wifihaven/main/openwrt/install.sh)"
```

Prompts for the API URL, an enrollment token (admin UI → **Routers → Add
router**), and the LAN prefix. Detects 23.05.x (opkg) vs 24.10+ (apk) and
installs the matching artifact. Full walkthrough: [`docs/install-openwrt.md`](docs/install-openwrt.md).

> **Supported firmware:** the agent is currently validated only on **flashed
> vanilla OpenWRT**. Many recommended routers (e.g. GL.iNet models) ship
> GL.iNet's own *forked* OpenWRT firmware, which is **not yet a verified
> target** ([#2304](https://github.com/wifihaven/wifihaven/issues/2304)). On
> GL.iNet hardware, flash vanilla OpenWRT first — see
> [`docs/install-flint2.md`](docs/install-flint2.md).

**3. OPNsense router agent** — installer not yet published. See the
[`opnsense/`](opnsense/) directory for the in-progress plugin.

## Quick start (development, macOS or Linux)

```bash
# Prereqs: JDK 21, Node 22, mill 1.1.5, scalafmt (cs install scalafmt)
git clone git@github.com:wifihaven/wifihaven.git
cd wifihaven

# Run all tests (also auto-installs git hooks via build.mill — see "Git hooks")
mill __.test

# Run the API locally (uses an embedded Postgres for tests; for dev you
# need a real Postgres — see config/application.conf.example)
cp config/application.conf.example config/application.conf
# edit secrets in config/application.conf, then:
mill api.run

# Frontend
cd web && npm ci && npm run dev
```

Default admin login: `admin / changeme` — **change this immediately** by
hitting `POST /api/auth/change-password`.

## IDE / BSP

Mill ships a Build Server Protocol (BSP) connector. To set it up:

```bash
mill mill.bsp/install
```

This writes `.bsp/mill-bsp.json`, which Metals (VS Code) and IntelliJ both
auto-detect when they open the repository. The file is gitignored — each
developer runs the command on their own machine because it bakes in an
absolute path to the local `mill` binary.

Sanity check:

- **VS Code + Metals**: open the repo, accept the "Import build" prompt;
  Metals will pick `mill-bsp` over sbt/bloop.
- **IntelliJ**: File → Open → select the repo root → choose **BSP** when
  prompted (not sbt). Subsequent reloads are via the BSP refresh button.

## Deployment

### Recommended: api + postgres as a single Docker Compose stack

The api and its postgres database deploy together as one Compose stack.
DNS enforcement and usage tracking run on the gateway router (OpenWRT/OPNsense
agent) and reach the api over the network (see [`docs/architecture-openwrt.md`](docs/architecture-openwrt.md)).

```bash
cp deploy/.env.example deploy/.env
$EDITOR deploy/.env                # set WIFIHAVEN_DB_PASSWORD and WIFIHAVEN_JWT_SECRET

docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env up -d --build
```

Postgres is internal to the compose network — it is not published on a
host port. The api binds to `:8080` (configurable via `WIFIHAVEN_API_BIND`
/ `WIFIHAVEN_API_PORT`). Run `scripts/smoke-prod.sh` to validate the stack.

Full operator notes (backups, reverse-proxy guidance, migration from the
host-based deploy below) live in [`deploy/README.md`](deploy/README.md).

### Legacy: systemd-on-host deploy

The original deployment model: a Linux host runs `scripts/deploy.sh` either
by hand or on a timer; it pulls the latest `main`, builds the assembly +
frontend, and restarts a systemd unit. Every artifact (the unit file, the
deploy script, the bootstrap script) lives in this repo, so updating any of
them is a normal PR.

This path is being phased out in favour of the Compose stack above. The api
unit file under `deploy/` is kept for existing installs.

### One-shot host bootstrap (curl-pipe)

On a fresh Debian / Ubuntu box, as your **normal login user** (not root):

```bash
curl -fsSL https://raw.githubusercontent.com/wifihaven/wifihaven/main/scripts/bootstrap-host.sh | bash
```

The script asks for `sudo` once, then handles everything: installing the
toolchain, creating the `wifihaven` system user, cloning the repo (tracking
the `production` branch — see [Production branch & deploy gate](#production-branch--deploy-gate)),
installing systemd units, and seeding `/etc/wifihaven/application.conf`.
Idempotent — safe to re-run.

Override defaults via env: `WIFIHAVEN_BRANCH`, `WIFIHAVEN_REPO_URL`,
`WIFIHAVEN_PREFIX`, `WIFIHAVEN_USER`, `WIFIHAVEN_MILL_VERSION`.

What it does:

1. apt-installs JDK 21, Node 22, git, curl
2. Installs Coursier, Mill, scalafmt into `/usr/local/bin`
3. Creates the `wifihaven` system user + `/opt/wifihaven`, `/var/lib/wifihaven`,
   `/var/log/wifihaven`, `/etc/wifihaven`
4. Clones the repo into `/opt/wifihaven/repo` **as the `wifihaven` user**
   (root never owns the checkout)
5. Symlinks `deploy/wifihaven-api.service` into `/etc/systemd/system` so
   unit-file updates flow with `git pull`
6. Writes `/etc/systemd/system/wifihaven-deploy.{service,timer}`
   that re-runs `scripts/deploy.sh` hourly
7. Seeds `/etc/wifihaven/application.conf`
8. Adds a minimal sudoers rule for the deploy user

After bootstrap, edit `/etc/wifihaven/application.conf` (set `jwt.secret`
and `db.password`), then:

```bash
sudo systemctl enable --now wifihaven-api.service
sudo systemctl enable --now wifihaven-deploy.timer    # auto-pull every hour
```

#### Testing the bootstrap script in Docker

We don't want to debug bootstrap on the live box, so the script has a
container smoke test:

```bash
docker build -f docker/bootstrap-test.Dockerfile -t wifihaven-bootstrap-test .
docker run --rm wifihaven-bootstrap-test
```

The container creates a non-root login user, points the bootstrap at the
local checkout (via `file://` git remote), runs it, and asserts that the
expected layout (`/opt/wifihaven/repo`, the systemd units, the sudoers
file, the `wifihaven` user, mill/node/java) exists. CI runs this on
every PR (`.github/workflows/e2e.yml` → `bootstrap-smoke`).

### Staging stack (browser + DNS testing, locally and in CI)

The `docker/` directory builds a self-contained staging environment —
postgres + the API server with the React bundle baked in:

```bash
docker compose -f docker/docker-compose.yml up --build
# → http://localhost:8080  (admin / changeme)

# Live API/DB e2e:
scripts/e2e-tests.sh
```

Use this to drive the UI in your browser and to run live tests against
the API exactly as CI runs them. `scripts/e2e-tests.sh` covers the API HTTP
surface.

### Production branch & deploy gate

Branches:

- **`main`** — what PRs merge into. CI runs unit tests + the staging stack
  + `scripts/e2e-tests.sh` (`.github/workflows/e2e.yml`).
- **`production`** — only updated by CI, only when both the bootstrap
  smoke test and the live e2e suite pass against the commit. The job
  fast-forwards `production` to `main`; if `main` ever diverges from
  `production` (e.g. a hotfix landed on `production` directly), CI
  refuses to push and the divergence has to be resolved by hand.

The host's deploy timer and `scripts/deploy.sh` track `production` (via
`WIFIHAVEN_BRANCH=production`, the new default), so the live box only
ever runs commits that have passed the e2e gate.

### Manual deploy

```bash
sudo -u wifihaven /opt/wifihaven/repo/scripts/deploy.sh
# logs:  journalctl -t wifihaven-deploy
# state: cat /opt/wifihaven/deploy.log    # rev + timestamp per deploy
```

Environment knobs (set on the command line or in
`/etc/wifihaven/api.env`):

| Var                   | Default | Effect                                      |
| --------------------- | ------- | ------------------------------------------- |
| `WIFIHAVEN_BRANCH`    | `production` | Branch to track (e2e-gated)            |
| `WIFIHAVEN_PREFIX`    | `/opt/wifihaven` | Install root                       |
| `WIFIHAVEN_NO_WEB`    | `0`     | Skip frontend build                         |
| `WIFIHAVEN_NO_RESTART`| `0`     | Build but don't restart the service         |

### Why the deploy logic lives in the repo

`bootstrap-host.sh` symlinks the systemd units **into the repo checkout**
rather than copying them. That way:

- `git pull` (or the deploy timer) is enough to roll out a new unit file
- the boot script (`bootstrap-host.sh`), the deploy script (`deploy.sh`),
  and the unit file (`deploy/wifihaven-api.service`) are all reviewed via
  PRs against `main` like any other code
- a deploy timer can re-run `scripts/deploy.sh` from the freshly-pulled
  repo — fixes to the deploy logic apply on the next tick
- the script tracks the `production` branch by default, so only commits
  that passed the e2e gate (see [Production branch & deploy gate](#production-branch--deploy-gate))
  get rolled out

If you'd rather not symlink, copy the unit and re-copy after each pull —
but you lose the auto-update property.

## Configuration

`config/application.conf` (HOCON) is the source of truth. The example is at
`config/application.conf.example`. Notable keys:

- `wifihaven.db.*` — Postgres connection
- `wifihaven.http.port` — API port (default `8080`)
- `wifihaven.http.staticDir` — where to serve the React bundle from
- `wifihaven.jwt.secret` — **must be ≥ 32 random chars**, change before going live
- `wifihaven.jwt.expiryHours` — JWT lifetime (default 24h)
- `wifihaven.dns.cacheRefreshSeconds` — how often the policy snapshot cache is refreshed

## Testing

Where does a new test belong? See [`docs/testing.md`](docs/testing.md) for the
suite map (unit / Gate 1 / Gate 2 / Gate 3a+3b).

Tests use [zonky/embedded-postgres](https://github.com/zonkyio/embedded-postgres),
so no external DB is required.

```bash
mill __.test                  # everything
mill api.test                 # api only
mill shared.test
```

CI (`.github/workflows/ci.yml`) runs:
1. `scalafmt --check`
2. `mill __.compile`
3. `mill shared.test && mill api.test`
4. `npm run type-check && npm run lint && npm run build`
5. Lua tests (openwrt/), Python tests (opnsense/)

## Git hooks

`.githooks/` ships pre-commit and pre-push hooks. They install automatically
the first time you run `mill` in a fresh checkout or worktree — `build.mill`
checks `core.hooksPath` on load and runs `scripts/install-hooks.sh` if it
isn't already pointing at `.githooks`. Idempotent and silent in the steady
state.

- **pre-commit (~5s)** — `scalafmt --check` and `eslint` on staged files only
- **pre-push (~5s)** — `scalafmt --check`, `tsc --noEmit`, `eslint` on the
  whole tree

Both can be bypassed in an emergency with `--no-verify`.

### Troubleshooting

If hooks aren't firing (e.g. you cloned but haven't yet run mill, or
`core.hooksPath` was changed by another tool), run the installer manually:

```bash
scripts/install-hooks.sh
scripts/verify-hooks-configured.sh   # confirms core.hooksPath=.githooks
```

## License

Apache License 2.0 — see [LICENSE](LICENSE).
