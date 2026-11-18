# App-catalog pass — #3010 (2026-11-16, weekly)

## Traffic pull

`recent-apexes?windowDays=30&limit=500`, sequential, on all 5 kid-relevant
devices: Quintus Chromebook, Quintus iPad, Prima iPad, Octavius iPad, and
"Kid Laptop" (profileId null — per the #2998 learning, a null `profileId`
doesn't mean exclude a device from the traffic pull). Aggregated by apex
across devices by bytes (780 distinct apexes). Cross-checked with a one-day
`usage-by-app` `orphanHosts` pull (2026-11-15) on profiles 5/6/7 — that
surfaced nothing beyond platform infra/telemetry/ad-tech already covered by
the byte-ranked sweep.

## Shipped

| Apex(es) | Bytes/hits (30d) | Disposition |
|---|---|---|
| `graphicalanalysis.app`, `spectralanalysis.app`, `instrumentalanalysis.app` | 7.4MB/1, 3.3MB/1, 2.5MB/1 | **→ new `vernier` app** — Vernier's three school science-class PWAs, title/meta-confirmed |
| `virtualnerd.com` + `cdn.`/`psn.` | 16.0MB/7 | **→ new `virtual-nerd` app** — math tutoring video site |
| `follettsoftware.com` (5 subdomains) + `follettdestiny.com` (2 subdomains) | 3.7MB/10 + 2.4MB/5 | **→ new `follett-destiny` app** — school library catalog (Flagstaff Academy's own tenant) |

## Verification detail

- **Vernier**: all three domains answered with vendor-confirmed
  `<title>`/meta-description on direct fetch (e.g. `spectralanalysis.app` →
  "Vernier Spectral Analysis™"). `graphicalanalysis.app`/`spectralanalysis.app`
  are on Cloudflare (104.21.x/172.67.x); `instrumentalanalysis.app` is on a
  CloudFront distribution (99.84.105.x) — a different 4-tuple from any
  already-flagged shared pool. One hit each in the window is thinner than the
  usual bar (cf. `jrustonapps.info`/`adeept.com` below, both skipped at three
  hits) — the signal that overrides the hit count here is institutional:
  the title/meta-description identify dedicated school-science curriculum
  software, the same class of assigned, infrequently-used classroom tool as
  `savvas-realize`/`iready` (neither of which needed a high hit count
  either), not an ordinary consumer site a kid might visit once out of
  curiosity.
- **Virtual Nerd**: title-confirmed ("Virtual Nerd: Real math help for
  school and home"). `virtualnerd.com`/`cdn.virtualnerd.com`/
  `psn.virtualnerd.com` all resolve to the identical 4 IPs
  (99.84.118.{16,55,68,78}) — verified this is a DIFFERENT 4-tuple from the
  99.84.118.{29,99,100,106} pool `arduino.yml`/`instructables.yml` exclude
  as shared collateral (per the #2915 "diff the 4-tuples" rule), so this is
  one dedicated CloudFront distribution for this brand, not a collision.
- **Follett Destiny**: `www.follettsoftware.com` hit a Cloudflare bot
  challenge on direct fetch (inconclusive by title, per the #2774
  precedent — not evidence the site is dead). **Independent review caught
  that the first draft of this entry over-generalized from that check**: it
  cited the BARE apex's dedicated-looking `141.193.213.x`/NS records as
  evidence for all five observed subdomains, but NS records show who runs a
  zone, not where its individual hostnames resolve, and the bare apex's A
  record says nothing about its subdomains'. Re-`dig`ging each subdomain
  individually: `portalapi.`/`titlepeek2.follettsoftware.com` AND
  `flagstafflmc.follettdestiny.com` (this household's own Flagstaff Academy
  library-media-center tenant) all resolve to Azure Public IPs on
  `*.cloudapp.azure.com` — a dedicated-per-resource Azure primitive Follett
  itself provisions, not a generic third-party PaaS edge; `destinypublic3`
  (the name behind `flagstafflmc`) reads as one of several load-balanced
  instances Follett runs across its OWN district customers, the same
  accepted risk class as `unagi.amazon.com` elsewhere in this catalog, not
  the disqualifying class below. But `prd-appcdn.`/`search.`/
  `security.follettsoftware.com` ARE that disqualifying class: Azure Front
  Door (`*.azurefd.net`), a generic multi-tenant PaaS edge used by
  countless unrelated companies — same as `portal.follettdestiny.com` on
  Azure Static Web Apps (`*.azurestaticapps.net`), the same collateral
  class as the CloudFront `/24` pool / `shops.myshopify.com`.
  **Fix (round 2, after a second independent-review pass)**: `portalapi.`/
  `titlepeek2.follettsoftware.com` and `flagstafflmc.follettdestiny.com`
  are `hosts:`; the three Front-Door/Static-Web-Apps hosts are
  `shared_hosts`. Round 1 of this fix wrongly claimed `shared_hosts` "never
  enters a drop/allow set" — review caught that `shared_hosts` only stays
  out of the BLOCK side (`eb_`); the ALLOW side
  (`ProfileAppDispositions.enforcement`'s `hosts` list,
  `exemptUnderCapHosts`/`timeLimitedUnderCapHosts`) uses the full host-set
  including `shared_hosts`, exactly as `onenote.yml` documents — so while
  this app is Allowed or TimeLimited-under-cap, the Front-Door/Static-Web-
  Apps addresses DO get carved into `extraAllowed`. Accepted (not avoided)
  for the same reason `onenote.yml` accepts it: there's no narrower
  alternative that keeps the catalog's search/security/portal surfaces
  reachable. Bare `follettsoftware.com`/`follettdestiny.com` apexes
  excluded — not observed, and the latter is Follett's shared multi-tenant
  platform.

## Watch items / inconclusive (not templated)

- `scalibur.io` (5.7MB/7 hits) — main site is a "Coming Soon" placeholder;
  the only traffic-bearing host (`srv.scalibur.io`) 404s on a bare GET and
  resolves to a scattered set of IPs across unrelated ASNs. Web search
  turned up nothing matching this exact name. Purpose unconfirmed — leave
  as a watch item.
- `bgcdn.com` (`staticu.bgcdn.com`, 42MB/2 hits) — an S3-backed CloudFront
  static-asset bucket (bare GET returns S3 `AccessDenied`, not a page); no
  identifiable brand via web search. Too thin and unidentifiable to
  classify; watch item.
- `jrustonapps.info` (8.1MB/3 hits) — 301-redirects to `jrustonapps.com`, an
  indie iOS/Android developer's site ("My Lightning Tracker", "My Hurricane
  Tracker" weather apps). Real but thin (3 hits/30d) — below the recurring-
  engagement bar (same class as the Scholastic/#2740 single-session skip).
  Watch item; revisit if traffic recurs.
- `adeept.com` (`www.adeept.com`, 57MB/3 hits) — confirmed real (Arduino/
  Raspberry Pi robotics-kit maker, same space as the household's existing
  `arduino` app), but only 3 hits in 30d — reads as a single kit-research/
  download session, not recurring engagement. Watch item.
- `twc.health` (26MB/15 hits) — resolves via CNAME to `shops.myshopify.com`
  (23.227.38.74), the same shared Shopify-origin pool already disqualified
  for `mouldkingcorp.com`/`thetraindepartment.com` (#2774). Skip regardless
  of content (it's an adult supplement-shop site, not kid-facing anyway).
- `githubusercontent.com` (`raw.`/`release-assets.githubusercontent.com`,
  2.0GB/19 hits) — recurring skip-with-note (first logged #2774 at much
  smaller volume): shared GitHub-release CDN infra with zero `github.com`
  navigational traffic to tie it to a specific app. Still no navigational
  signal this pass despite the much larger byte count (likely one large
  release-asset download of unknown origin); stays a watch item per the
  standing precedent — template a `github` app only if real navigational
  traffic to `github.com` itself appears.
- `serato.net` (`crashreports.serato.net`, 87MB/6 hits) — crash-telemetry
  subdomain only, not app content; `serato.yml` already covers the real
  Serato surfaces (`serato.com`, `sera.to`). Skip — telemetry, not a
  host-set gap.

## Self-update

See the `app-catalog-pass` skill's Learnings log (newest-first entry dated
2026-11-16, #3010) for what this run adds to the process.
