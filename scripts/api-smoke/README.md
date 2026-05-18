# Gate 1 — API contract smoke

Fake-router smoke driver run from a github-hosted runner against the deployed
**staging** API after `deploy-staging` succeeds. Blocks production retag /
`deploy-prod-render` on red, does NOT block `publish-openwrt` (Gate 2 owns
that). See #652 / #653 for the umbrella plan.

## Running locally

```sh
export WIFIHAVEN_API_BASE=https://api-staging.wifihaven.net
export WIFIHAVEN_ADMIN_USER=admin
export WIFIHAVEN_ADMIN_PASS='...'
python3 scripts/api-smoke/smoke.py
```

Pure-stdlib (urllib + json). No `pip install` step.

## What it asserts

- Admin login round-trip (and 401 on a bad password).
- Profile create + PUT-merge for pause / schedule / time-limit / extraBlocked.
- Router create (admin) → register (router) round-trip; bogus enrollment
  token rejected.
- `GET /api/router/policy` with the router bearer:
    - structural shape matches `shared/contract/api-to-router/policy_snapshot.json`
      (key presence + types only — IDs and timestamps differ from the golden),
    - the profile we just created appears in `snap.profiles`,
    - `If-None-Match` round-trip yields `304`.
- `POST /api/router/events` accepts a well-formed batch; rejects missing auth
  with `401`; rejects malformed JSON with `4xx`.
- `POST /api/router/usage` accepts a well-formed batch; rejects missing auth
  with `401`.
- `GET /api/router/policy` with no / bogus bearer → `401`.

## Regression simulation

Use `--inject-failure` to confirm the gate trips on each class of regression
without actually breaking server code:

| Flag                      | Simulates                                            |
| ------------------------- | ---------------------------------------------------- |
| `--inject-failure auth`           | Admin auth broken — wrong password sent           |
| `--inject-failure policy-shape`   | Policy snapshot loses the `profiles` key          |
| `--inject-failure events-status`  | Events endpoint stopped accepting (≥500 expected) |
| `--inject-failure usage-status`   | Usage endpoint stopped accepting                  |
| `--inject-failure malformed-ok`   | Server starts 2xx-ing malformed event JSON        |

In CI we run without the flag; the flags exist so an operator can verify the
gate is doing its job without reverting a real fix on `main`.
