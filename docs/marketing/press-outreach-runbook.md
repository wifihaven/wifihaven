# Press-outreach send runbook (#2233)

How the operator fires the beta press outreach. The capability is **built and dark by default**;
the real send is a deliberate, operator-gated action taken AT beta launch, on the operator's own
"go". Nothing here sends autonomously.

## What it does

Composes, per media-list contact, an email = a personalized pitch (from the contact's angle) +
the launch release, and emails it **from the press address** with **Reply-To the press inbox** so a
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

Set in the API's HOCON/env (self-hosted leaves it off):

```hocon
wifihaven {
  email { enabled = true, resendApiKey = "re_…", fromAddress = "WifiHaven <alerts@wifihaven.net>" }
  pressOutreach {
    enabled     = true
    fromAddress = "WifiHaven Press <press@wifihaven.net>"   # verified Resend sender
    replyTo     = "press@wifihaven.net"                     # → CF Email Worker → #2203 responder
    perSendDelayMillis = 2000
  }
}
```

Boot fails loud if `pressOutreach.enabled=true` without email configured (no dark-by-default). The
resolved state is visible at boot and on the loopback `GET /api/debug/config`.

## Endpoints

Both are `POST`, admin + operator-household only. Body is JSON:

| field | meaning |
|---|---|
| `fill` | the release fill tokens: `city`, `date`, `founderName`, `founderQuote`, `betaSignupUrl`, `pressKitUrl` |
| `emailOverrides` | `contactId -> verified address`, supplied at send time (nothing fabricated in-repo) |
| `testRecipient` | redirect every real transmit to ONE safe address (validate a real send) |
| `confirm` | must be `true` to actually send (ignored by preview) |

### 1. Preview (always first — never transmits)

```bash
curl -sS -X POST https://api.wifihaven.net/api/press/outreach/preview \
  -H "Authorization: Bearer $ADMIN_JWT" -H 'Content-Type: application/json' \
  -d '{
        "fill": {
          "city": "San Francisco", "date": "July 20, 2026",
          "founderName": "Sameer", "founderQuote": "…approved quote…",
          "betaSignupUrl": "https://wifihaven.net/beta", "pressKitUrl": "https://wifihaven.net/press"
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
- For form-only outlets, submit the 3-sentence pitch + release link via their `contactUrl`
  (see [`media-list.md`](media-list.md) "Email send workflow").
- Post the community channels (Show HN, r/selfhosted, r/openwrt) directly — those are not emailed.
