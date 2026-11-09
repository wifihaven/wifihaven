# #2955 — weekly app-catalog pass, 2026-11-02

Prod traffic pull: `GET /api/profiles/<id>/usage-by-app?from=2026-10-03&to=2026-11-02`
for the three kid profiles (Quintus #5, Prima #6, Octavius #7), using the
server's own `orphanHosts[]` gap list as the primary signal, cross-checked
against a `recent-apexes?windowDays=30` byte sweep across the five kid
devices (Quintus Chromebook/iPad, Prima iPad, Octavius iPad, Kid Laptop).

## Shipped: new app templates

### `codehs` — CodeHS (K-12 CS curriculum platform)

~2,649 proportional seconds (~44 min) / ~2,730 presence seconds (~46 min)
across the three kid profiles for the whole CodeHS host cluster seen in
traffic. The shipped host-set covers a subset of that (~24 min) — see the
revision note below for why.

A prior pass (#2943, 2026-10-26) saw only `scalinghub.codehs.com` (403,
backend-looking) and skipped it for lack of a content-facing sibling. This
pass's orphan sweep surfaced that sibling: `codehs.com` itself (title
"The Top Coding and Computer Science Platform for K-12 Schools | CodeHS")
plus three distinct per-exercise sandbox hosts on `codehs.me`
(`vigenere-<id>`, `caesar-cipher-encryption-<id>`, `letter-frequency-<id>`) —
a real CS curriculum sequence, not a beacon.

**Revised after independent review** (see PR #2956 review comment): the
draft host-set listed bare `codehs.com` and bare `codehs.me`. Both are
collateral risk, not acceptable Class-2 sharing as the draft claimed:

- `codehs.com` suffix-matches `blog.codehs.com` (WP Engine shared pool),
  `status.codehs.com` (Statuspage.io shared pool) and `help.codehs.com`
  (the same shared CloudFront 99.84.118.x pool as `codehs.me`, below).
  Excluded, same move `canva.yml` makes for bare `canva.com`. Residual: the
  literal apex was this app's single largest orphan host (~18 proportional
  min/30d) — that traffic goes unattributed.
- `codehs.me` (the per-exercise sandbox domain) itself resolves into that
  same shared CloudFront 99.84.118.x pool — the exact pool
  `arduino.yml`/`instructables.yml` *exclude* their own bare apexes over,
  not accept, contrary to the draft's claim. Its own Route53 delegation
  (distinct NS set from `codehs.com`) doesn't rescue it, since the
  `scratch.mit.edu` delegation precedent (#2833) requires the delegated
  zone to point at dedicated infra, not a shared edge. Excluded entirely;
  residual: the three observed per-exercise sandbox sessions (~2.3
  proportional min/30d) go unattributed.

Shipped host-set: `static.codehs.com`, `static1.codehs.com`,
`cdn.codehs.com`, `uploads.codehs.com`, `video.codehs.com`,
`scalinghub.codehs.com` — ~24 proportional min/30d, each dig-verified onto
its own dedicated CloudFront/Cloudflare distribution (two share a `/24`
with another template's host — `matlab.yml`'s `matlab.mathworks.com` and
`instructables.yml`'s `content.instructables.com` respectively — but on
different exact IPs within it, the standard accepted Class-2 risk).
`codehsstatic.s3-us-west-2.amazonaws.com` (~10s/30d) was also dropped: S3's
shared regional pool, and the kept CDN hosts already carry the same asset
class.

### `coddy` — Coddy (gamified coding-practice platform)

~1,554 proportional seconds (~26 min) / ~1,580 presence seconds (~26 min)
across the three kid profiles for the whole cluster; the shipped host-set
covers ~20 min of that (see the revision note below). A prior pass (#2915,
2026-10-19) found the
same cluster at a similar volume and left it a watch-item because
`coddy.tech` answers a plain `curl` with a Cloudflare bot-challenge page
("Just a moment...") and couldn't be title-confirmed directly. This pass
confirmed purpose via independent third-party company profiles (web search):
Coddy is a gamified, practice-driven coding-education platform (Python/JS/
SQL/HTML/CSS/Java/C/Go lessons, daily streaks, leaderboards), not a company
claim pulled from its own site.

**Revised after independent review**: the draft host-set also kept
`us-central1-coddy-15505.cloudfunctions.net`, reasoning it was dedicated
because the project id in the hostname is Coddy-specific. Review `dig`'d
it — 216.239.36.54, inside Google's shared 216.239.32.0/19 range, the
exact range `canva.yml` documents and excludes `ct.canva.com` over. A
unique-looking hostname on a managed platform (Cloud Functions/Run/Lambda)
doesn't mean a dedicated IP; the platform still shares its regional pool
across tenants. Excluded; residual: that backend's traffic (~5.8
proportional min/30d) goes unattributed.

Shipped host-set: `coddy.tech`, `static.coddy.tech` — ~20 proportional
min/30d, Cloudflare-fronted (104.26.x/172.67.x), the standard accepted
Class-2 shared-edge risk already present throughout this catalog.

## Shipped: blocklist gap fix

### `itch.zone` added to `blocklists/games.yml`

`itch.io` is already blocklisted, but `itch.zone` is itch.io's own CDN/embed
domain on a *sibling* apex (not a subdomain of `itch.io`), so blocking
`itch.io` does not suffix-match it. 30d orphan sweep showed real
`html-classic.itch.zone` traffic (~361 proportional sec / ~150 presence sec)
— a kid loading an embedded itch.io game directly via its CDN URL even with
`itch.io` itself blocked. Same two Cloudflare nameservers
(`dawn.ns.cloudflare.com` / `noah.ns.cloudflare.com`) as `itch.io`,
corroborating common ownership (`dig +short NS`); content itself serves via
Akamai (`edgesuite.net`), the standard shared-CDN-edge risk already accepted
throughout the catalog.

## Watch-items (not templated this pass)

- **`kodub.com` / `vps.kodub.com`** — Kodub, the indie studio behind
  *PolyTrack* (a free low-poly browser racing game with online multiplayer;
  web-confirmed via the studio's own project list). The actual play surface
  is `kodub.com/apps/polytrack` (same apex as the studio homepage);
  `vps.kodub.com` is the multiplayer WebSocket backend (403 to a plain GET —
  consistent with a non-HTTP-page endpoint). Only ~360 proportional sec
  (~6 min) / ~390 presence sec (~6.5 min) over 30d, reading as a single play
  session rather than recurring use — below this pass's engagement bar.
  Revisit if traffic grows.
- **`cowg.cap.gov`** (Colorado Wing Civil Air Patrol member portal) — real
  navigational traffic but thin (~341 proportional sec, ~5.7 min) and
  administrative/extracurricular rather than a kid-chosen "app"; skip.
- **`www.calculator.net`** — basic calculator utility, ~330 proportional sec
  (~5.5 min); below bar, no recurring shape; skip.

## Confirmed-already-covered (no action)

- `mathworks.com` family (`smetrics.`/`tracking.mathworks.com`) — already
  deliberately excluded analytics/marketing collateral on the existing
  `matlab.yml` template (#2915); orphan appearance here is expected, not a
  gap.
- `gd.games` (GameDistribution) — already a deliberate blocklist-only
  classification per the existing comment in `games.yml`; not an app.
  **CORRECTED by #2998 (2026-11-09): this was wrong.** A direct fetch of
  the live page shows zero "gamedistribution"/"azerion" occurrences and
  450+ "gdevelop" occurrences; title and meta both name GDevelop directly.
  It's GDevelop's own community game portal and is now templated into
  `gdevelop.yml`'s hosts. See `evidence/classification-2998.md`.
- Top-of-list orphans (`*.google.com`, `*.gstatic.com`, `*.apple.com`,
  ad-tech/RTB domains, `*.doubleclick.net`, `*.criteo.com`,
  `*.googlesyndication.com`, etc.) are platform infra / ad-tech, the usual
  skip pile per Step 2 — not itemized individually here.
