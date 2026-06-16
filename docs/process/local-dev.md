# Running locally + testing commands

This was originally in AGENTS.md §"Running locally" and §"Testing" (bash command set); see AGENTS.md for the TOC.

## Running locally

```bash
# Start Postgres
docker run -d --name wifihaven-pg \
  -e POSTGRES_USER=wifihaven \
  -e POSTGRES_PASSWORD=secret \
  -e POSTGRES_DB=wifihaven \
  -p 5432:5432 postgres:16

# Copy and edit config
cp config/application.conf.example config/application.conf

# Run API
mill api.run

# Run frontend dev server (Vite — talks to the local API at :8080).
# Self-hosted/install.sh deploys bundle the SPA into the API image; the
# cloud staging/prod environments serve it from Cloudflare Pages instead.
cd web && npm run dev
```

## Testing

```bash
# All Scala tests
mill __.test

# Single module
mill api.test
mill shared.test

# Format check
mill __.checkFormatting

# Fix imports
mill __.fix

# OpenWRT agent tests (requires lua5.1 + busted + lua-cjson)
cd openwrt && LUA_PATH="./files/usr/lib/lua/wifihaven/?.lua;$(lua -e 'print(package.path)')" busted test/

# OPNsense agent tests (requires Python 3 + pytest)
cd opnsense && python -m pytest test/ -v
```
