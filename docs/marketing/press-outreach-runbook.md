# Press-outreach send runbook (#2233)

How the operator fires the beta press outreach. The capability is **built and dark by default**;
the real send is a deliberate, operator-gated action taken AT beta launch, on the operator's own
"go". Nothing here sends autonomously.

## What it does

Composes, per media-list contact, an email = that outlet's **authored** pitch (the `pitch` field in
the manifest, written for that outlet and no other — there is no generic template) + the launch
release, and emails it **from the press address** with **Reply-To the press inbox** so a
journalist's reply routes to the [#2203](https://github.com/wifihaven/wifihaven/issues/2203) Claude
press responder (draft → autonomous reply). Sending is:

- **dry-run by default** — you preview the exact emails before anything transmits;
- **explicitly gated** — a real send needs the feature flag ON, `confirm:true` in the request, and
  every release fill token resolved;
- **idempotent + resumable** — each successful send is recorded to the `press_messages` ledger; a
  re-run skips anyone already contacted, so a partial batch resumes without double-sending;
- **rate-limited** — sends are spaced by `pressOutreach.perSendDelayMillis`;
- **admin/operator-only** — the endpoints require an authenticated admin in the operator household
  (household 1); everyone else gets 404.

Sources: [`api/resources/press/release.md`](../../api/resources/press/release.md) (sendable release
template) and [`api/resources/press/media-contacts.yml`](../../api/resources/press/media-contacts.yml)
(send manifest, transcribed from [`media-list.md`](media-list.md)).

## One-time: turn the capability on

**On a Render deploy, set the env var — not the HOCON.** `docker/entrypoint.sh` generates
`/app/config/application.conf` from the environment, and until 2026-08-15 it had no
`pressOutreach` block at all, so the flag could not be set from the environment and the
endpoints 404'd everywhere. The keys now exist:

| env var | staging | prod | meaning |
|---|---|---|---|
| `WIFIHAVEN_PRESS_OUTREACH_ENABLED` | **`true`** | `false` | mounts `/api/press/outreach/{preview,send}`. **This is the one value to flip on prod.** |
| `WIFIHAVEN_PRESS_OUTREACH_FROM_ADDRESS` | `press-staging@wifihaven.net` | `press@wifihaven.net` | verified Resend sender (apex-only verification, #2407) |
| `WIFIHAVEN_PRESS_OUTREACH_REPLY_TO` | `press@staging.wifihaven.net` | `press@wifihaven.net` | → CF Email Worker → #2203 responder |
| `WIFIHAVEN_PRESS_OUTREACH_PER_SEND_DELAY_MS` | `2000` | `2000` | spacing between sends |

Which produces:

```hocon
wifihaven {
  email { enabled = true, resendApiKey = "re_…", fromAddress = "WifiHaven <alerts@wifihaven.net>" }
  pressOutreach {
    enabled     = true
    fromAddress = "WifiHaven Press <press@wifihaven.net>"
    replyTo     = "press@wifihaven.net"
    perSendDelayMillis = 2000
  }
}
```

Boot fails loud if `pressOutreach.enabled=true` without email configured (no dark-by-default). The
resolved state is visible at boot and on the loopback `GET /api/debug/config`.

Turning the flag on does not send anything on its own: a send additionally needs `confirm=true`
in the request, every release fill token resolved, and an admin in the operator household.

## Rehearse on staging first

Staging has the capability **on**, so the whole path can be exercised against a deployed API
without any chance of reaching a journalist. Two independent reasons it cannot:

- `preview` resolves every contact to `dry_run` before the transport is reached, whatever else
  is in the request;
- the bundled manifest ships **no journalist addresses at all**, so even a `confirm=true` call
  returns `skipped_no_email` for all 21 — there is nothing for it to send to. The only way to
  transmit is to supply an address yourself, in `emailOverrides` or `testRecipient`.

```bash
ADMIN_JWT=$(curl -sS -X POST https://api-staging.wifihaven.net/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"<staging admin password>"}' | jq -r .token)

curl -sS -X POST https://api-staging.wifihaven.net/api/press/outreach/preview \
  -H "Authorization: Bearer $ADMIN_JWT" -H 'Content-Type: application/json' \
  -d '{
        "fill": {
          "date": "August 17, 2026",
          "founderName": "Test Founder",
          "betaSignupUrl": "https://app.wifihaven.net/beta",
          "pressKitUrl": "https://wifihaven.net/press"
        }
      }' | jq '{mode, totalContacts, emailable, formOnly, unresolvedPlaceholders,
                outcomes: [.results[].outcome] | group_by(.) | map({(.[0]): length}) | add}'
```

Expect `mode: "preview"`, `totalContacts: 21`, `emailable: 0`, `formOnly: 21`,
`unresolvedPlaceholders: []`, and every outcome `dry_run`. To read the emails as a
journalist would:

```bash
curl -sS -X POST https://api-staging.wifihaven.net/api/press/outreach/preview \
  -H "Authorization: Bearer $ADMIN_JWT" -H 'Content-Type: application/json' \
  -d '{"fill":{"date":"August 17, 2026","founderName":"Test Founder","betaSignupUrl":"https://app.wifihaven.net/beta","pressKitUrl":"https://wifihaven.net/press"}}' \
  | jq -r '.emails[] | "=== \(.outlet) — \(.person) ===\nSubject: \(.subject)\n\(.htmlBody)\n"' \
  > /tmp/press-preview.html
```

Check the **HTML**, not a text rendering of it — #2677 shipped literal `**` to a journalist
because only the text part was ever inspected.

A 404 from either endpoint means one of three things, and they are deliberately
indistinguishable from outside: the flag is off, you are not an admin, or you are not in the
operator household (household 1). Check the boot log or `GET /api/debug/config` on the loopback.

To transmit for real on staging, add `"testRecipient": "you@yourinbox.example"` and
`"confirm": true`. That composes each email for the real journalist but sends every one to your
address only, and records nothing to the ledger.

## Endpoints

Both are `POST`, admin + operator-household only. Body is JSON:

| field | meaning |
|---|---|
| `fill` | the release fill tokens. As of 2026-08-15 there are **four**: `date`, `founderName`, `betaSignupUrl`, `pressKitUrl`. The dateline carries no city, and the founder quote is literal in the copy. The DATE stays a token deliberately — it is the one field whose correctness is time-dependent, so a send that slips a day is stopped by the unresolved-token guard rather than carrying a stale dateline. |
| `emailOverrides` | `contactId -> verified address`, supplied at send time (nothing fabricated in-repo) |
| `testRecipient` | redirect every real transmit to ONE safe address (validate a real send) |
| `confirm` | must be `true` to actually send (ignored by preview) |

### 1. Preview (always first — never transmits)

```bash
curl -sS -X POST https://api.wifihaven.net/api/press/outreach/preview \
  -H "Authorization: Bearer $ADMIN_JWT" -H 'Content-Type: application/json' \
  -d '{
        "fill": {
          "date": "August 17, 2026",
          "founderName": "…",
          "betaSignupUrl": "https://app.wifihaven.net/beta",
          "pressKitUrl": "https://wifihaven.net/press"
        }
      }' | jq
```

The response lists every rendered email (subject/from/reply-to/to/body), the counts
(`emailable` / `formOnly`), and any `unresolvedPlaceholders`. Review the bodies. `unresolvedPlaceholders`
MUST be empty before a send will run.

### 2. Test send (optional — validate the real transport safely)

Add `"testRecipient": "you@yourinbox.example"` and `"confirm": true`: every email is composed for the
real journalist but transmitted to your inbox. It is **not** recorded to the ledger, so it never marks
a real contact as already contacted.

### 3. Real send (on the operator's go)

Add the verified addresses and confirm. Only contacts with a resolvable address are emailed; form-only
contacts come back `skipped_no_email` for manual submission via their `contactUrl`.

```bash
curl -sS -X POST https://api.wifihaven.net/api/press/outreach/send \
  -H "Authorization: Bearer $ADMIN_JWT" -H 'Content-Type: application/json' \
  -d '{
        "confirm": true,
        "fill": { … as above … },
        "emailOverrides": { "selfhst": "ethan@…", "theregister": "liam@…" }
      }' | jq
```

Re-running is safe: already-sent contacts return `skipped_already_sent`.

## After the send

- Replies land at `press@wifihaven.net` → the #2203 responder drafts/sends the reply.
- Watch `press_outreach_total{outcome}` (Grafana "Press outreach" panel) — `sent` vs `failed`.
- For form-only outlets — which as of 2026-08-15 is ALL of them — submit that outlet's own pitch,
  shortened, plus a release link via their `contactUrl`
  (see [`media-list.md`](media-list.md) "Email send workflow").
- Post the community channels (Show HN, r/selfhosted, r/openwrt) directly — those are not emailed.

## Stopping a batch mid-run

The send loop is sequential and spaced by `perSendDelayMillis`, so there is real time between
transmits. To stop:

1. **Kill the request.** Ctrl-C the `curl`. The server-side fiber is tied to the request, so
   interrupting the connection interrupts the loop.
2. **If that doesn't take, flip the flag off** — set `WIFIHAVEN_PRESS_OUTREACH_ENABLED=false` in
   Render and redeploy. The endpoint stops existing.
3. **Then check what actually went out** before doing anything else: the `press_messages` ledger
   records every real send, and `press_outreach_total{outcome}` counts them. Whatever is recorded
   is what a journalist received.
4. **Resuming is safe and is the intended repair.** Re-run the same send; already-contacted
   addresses return `skipped_already_sent`, so nobody is mailed twice.
