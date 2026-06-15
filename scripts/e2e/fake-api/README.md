# fake-api — WifiHaven router-facing API shim

In-repo fake of the API surface that the OpenWRT router agent talks to.
Lives here (rather than under `api/`) because the Gate 2 (#654) qemu e2e
harness owns it: a fresh fake per scenario eliminates the cross-test
state pollution (#646/#647/#648) that the live-API harness suffers from.

It is NOT a substitute for the Scala API in any production-adjacent
context. Auth shape is checked but not validated; nothing is persisted.

## Endpoints

### Production-shaped (the agent hits these)

- `POST /api/router/register` — body `{enrollmentToken, platformVersion, agentVersion}`. Returns `{routerId, routerToken}` (deterministic per fake instance). Captured for assertion.
- `GET /api/router/policy` — requires `Authorization: Bearer <non-empty>`. Honors `If-None-Match` (header) and `?since=` (query); header wins. Returns 304 when the etag matches, 200 + snapshot body otherwise. Sets `ETag` response header with the snapshot's canonical etag value.
- `POST /api/router/events` — captures the body verbatim.
- `POST /api/router/usage` — captures the body verbatim.

The initial snapshot is loaded from
`contract/api-to-router/policy_snapshot.json` so the CI drift
guard against the live Scala codec keeps the fixture honest. See
[`contract/README.md`](../../../contract/README.md).

### Test-control (NOT exposed in production)

- `POST /test/snapshot` — replace the currently-served snapshot. Bumps the served etag because the new snapshot carries its own.
- `GET /test/events` — return captured event batches. `?since_id=N` returns batches with id > N; `?mac=AA:BB:..` adds a flattened `events` array filtered to that MAC.
- `GET /test/usage` — return captured usage reports. `?since_id=N` supported.
- `GET /test/register` — return `{routerId, routerToken, requests:[...]}`.
- `POST /test/reset` — clear all captured state and restore the initial snapshot.
- `POST /test/clock` — cosmetic; sets the base instant the fake will stamp on newly-loaded snapshots. Not load-bearing for v1.

## Running locally

```sh
cd scripts/e2e/fake-api
pip install -e '.[test]'
python -m fake_api            # serves on 127.0.0.1:8765
```

Override host/port via `WH_FAKE_API_HOST` / `WH_FAKE_API_PORT`.

```sh
# poke it
curl -s -H 'Authorization: Bearer t' http://127.0.0.1:8765/api/router/policy | jq '.etag'
curl -s -X POST http://127.0.0.1:8765/test/reset
```

## Tests

```sh
cd scripts/e2e/fake-api
pip install -e '.[test]'
pytest
```

## Scope

This is sub-issue #682 of #654. Wiring into pytest fixtures lives in
#683; scenario migration in #684; matrix in #685; gate wiring in #686.
