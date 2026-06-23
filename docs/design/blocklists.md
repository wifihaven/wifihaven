# Blocklists design (umbrella #952)

Planning artifact for the blocklist track. Resolves the five open design
questions in #952 and sketches schema / wire / flow implications for the
filed sub-issues.

## Design-question answers

### Q1 — Bundled vs URL-imported: **Bundled first, URL-imported as phase 2**

- Ship a curated set of category blocklists (ads, trackers, NSFW, social,
  gambling, malware) baked into the API release. Operator enables per
  profile via `blockedCategories`. List freshness == API release cadence.
- Rationale: licensing clarity (we control distribution), no per-household
  fetch reliability, simpler ops surface, the schema (`blocklist_domains`)
  already exists and seeds well from upstream lists at release-build time.
- URL-imported (e.g. operator supplies a StevenBlack/hosts URL) is a
  phase 2 feature once the SPA management surface is solid. Same DB
  table; new columns `source_url` / `last_fetched_at` / `etag`; a periodic
  fetcher in the API reconciles into `blocklist_domains`.
- Open follow-on: copyright posture if we redistribute community lists —
  prefer permissively-licensed sources (StevenBlack/hosts is MIT, OISD is
  public-domain-equivalent) or rebuild from primary upstream feeds.

### Q2 — Apps vs separate blocklists primitive: **Keep separate**

- Apps remain household-curated, hand-sized host groups assigned per-profile
  with `mode in (allowed | blocked | time_limited)`. Typical app: ~5-50 hosts.
- Blocklists remain curated category lists; typical category: hundreds to
  tens of thousands of hosts; updated on the API release cadence (later, on
  a fetch schedule). The router already enforces them via separate
  `bl_<category>` nftables sets fetched from `/api/blocklists/<category>`
  with per-category ETag.
- DB-shape: keep `blocklist_domains (domain, category)` and the
  `profiles.blocked_categories` array. Do NOT collapse into the `apps` schema.
- Wire-shape: profile snapshot keeps the `blocklists: { <category>: { version, url } }`
  field that the router uses today. Apps remain expanded into
  `extraAllowed` / `extraBlocked` per #763.
- Rationale: scale differences, update-cadence differences, separate fetch
  / ETag path that the router already implements, and clean separation of
  concerns — apps are "things this household cares about" while categories
  are "things we've decided are worth blocking on your behalf."
- Open follow-on: SPA may surface them in a single "block these things"
  flow even though they're separate primitives underneath.

### Q3 — HTTPS block-page: **Self-signed cert primary; captive-portal deferred**

- Implement #383 (DNAT TCP/443 → local block-page server with a self-signed
  cert generated at agent first-boot). Browser warns; click-through lands
  on the block page; redirect proceeds as in #679.
- Reject captive-portal (#701) for v1: OS-level captive detection is
  inconsistent across iOS/Android/desktop; some browsers refuse the
  captive flow on HTTPS; and the UX is more invasive than the cert
  warning. Captive can layer on later if the cert-warning UX proves
  insufficient.
- Both options are router-side and therefore **blocked on Gate 2 (#654)**.
  The SPA-side redirect target (per #679) is unblocked and can ship now.
- Open follow-on: HTTPS-only mode (HSTS-preloaded sites where click-
  through is impossible) — accept "no block page possible, connection
  appears broken" as the residual gap. Document in the block-page sub-issue.

### Q4 — Block-reason granularity: **Category yes; schedule/time-limit details no**

- The kid-facing block page shows:
  - `blocked by category` → name the category ("Ads & Trackers", "Social Media").
  - `blocked by extraBlocked` (operator-specific) → "blocked by your parent".
  - `blocked by extraBlocked-via-app` → "blocked: <App name>".
  - `blocked by schedule` → "outside allowed time" (no schedule details).
  - `blocked by time limit` → "out of time today" (no remaining-minutes leak).
  - `blocked by paused` → "paused".
  - `blocked by default / unmanaged MAC` → "device not enrolled".
- Always render an "ask a parent for access" affordance (per #578) so the
  block page is actionable, not just informational.
- Rationale: category visibility is empowering ("oh, this is one of those")
  without leaking specific parental-control mechanics that teach workarounds.
  Schedule and time-limit details belong in the SPA, surfaced to the
  parent — not painted on the kid's block page.
- Open follow-on: parent-mode block page (when an admin user hits a block
  page on the kid's device because they're testing) — could show the full
  reason. Out of v1 scope.

### Q5 — #349 vs #937: **Same concept; close #349 as superseded by #937**

- #937's global profile with `paused = true` IS the "globally closed" state.
- `globalProfile.paused = false` combined with per-profile policy IS the
  default state. The "globally open" sub-state (allow everything regardless
  of profile) is not a use case the operator has requested — deferred until
  asked.
- Action: close #349 as duplicate of #937, comment cross-link.

## DB-shape sketch (what's there + what changes)

Existing (from `V1__init.sql`):

```sql
CREATE TABLE blocklist_domains (
  id        BIGSERIAL PRIMARY KEY,
  domain    TEXT NOT NULL,
  category  TEXT NOT NULL,
  UNIQUE (domain, category)
);
```

Phase 1 additions (sub-A):

```sql
-- Promote `category` to a first-class row with metadata.
CREATE TABLE blocklists (
  id           TEXT PRIMARY KEY,            -- matches blocklist_domains.category
  display_name TEXT NOT NULL,
  description  TEXT,
  bundled      BOOLEAN NOT NULL DEFAULT TRUE,
  source_url   TEXT,                        -- phase 2: URL import
  last_built_at TIMESTAMPTZ,
  domain_count INT NOT NULL DEFAULT 0
);
-- Categories surfaced to the SPA come from this table.
-- Profile.blocked_categories references blocklists.id.
```

Phase 1 cleanup (sub-A, closes #706): seed only `bundled = true` rows for
real categories; gate the dev-only `test_ads` / `test_social` seed on a
non-prod env flag.

Phase 2 additions (URL import, separate sub-issue): `source_url`,
`refresh_interval`, `etag`, `last_fetched_at`; periodic fetcher reconciles
into `blocklist_domains` for that category.

## Wire-shape sketch

Unchanged from today's `policy_snapshot.json`:

```json
"blocklists": {
  "ads":    { "version": "<sha>", "url": "/api/blocklists/ads" },
  "social": { "version": "<sha>", "url": "/api/blocklists/social" }
}
```

`version` is the SHA of the sorted domain list for that category; bumps on
any membership change. Router fetches with `If-None-Match`. Phase 2 (sub-G,
#709) adds a periodic refresh timer so router catches mid-day URL-imported
list updates without a policy bump.

## Block-page redirect flow (sub-B / #679)

```
device → blocked HTTP request
       ↓ DNAT (router nft)
router uhttpd:8081  → returns HTML that redirects to
       ↓
https://app.wifihaven.net/blocked?mac=<mac>&host=<dest>
       (pre-rename routers DNAT to wifihaven.net/blocked, which 302-redirects
        here via the apex compat shim #1842, preserving the query string)
       ↓
SPA fetches GET /api/blocked?mac=<mac>&host=<dest>
       ↓ API resolves: profile, the BlockReason struct, category name if any
SPA renders kid-friendly block page (per Q4 granularity)
       + "ask a parent for access" button → POST /api/requests (per #578)
```

HTTPS variant (#383, sub-G): same flow, DNAT 443 → uhttpd:8443 with a
self-signed cert; one click-through then the same redirect HTML.

Reaching the SPA from a blocked device requires the SPA hostnames in
`extraAllowed` — handled by #944 (in-flight precursor to #937 global
profile).

## Test surface implications

- **API unit (Gate 0)**: `BlockReason` decoding/encoding (sub-F / #396),
  category-list serialization, snapshot builder with apps + blocklists
  composed.
- **Contract (Gate 1)**: `contract/api-to-router/policy_snapshot.json`
  golden — blocklists field shape stays stable. Now that prod is deployed
  the snapshot is a public contract: changes must be additive and
  ignore-unknown-tolerant (see "Backwards compatibility" in `AGENTS.md`);
  non-additive changes are gated on wire versioning
  ([#376](https://github.com/wifihaven/wifihaven/issues/376)).
- **Router (Gate 2 / #654)**: blocklist fetch via `If-None-Match`, periodic
  refresh timer, empty-status logging (sub-G items #705/#709), HTTPS DNAT
  (#383), unmanaged-MAC fallthrough (#374), block-page redirect emits
  correct query params.
- **E2E (Gate 3)**: real router → SPA → API → block-page render path;
  request-flow approve/deny (#578).

## Cross-references

- Apps track: #105, #761 (schema, merged), #762 (CRUD, merged),
  #763 (PolicyService expansion, in-flight), #768 (starter library, in-flight).
- Global profile: #937 design, #944 precursor in-flight.
- Router-frozen until Gate 2 (#654) — items so labeled.
