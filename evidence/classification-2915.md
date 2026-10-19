# App-catalog pass — 2026-10-19 (#2915)

Window: 30 days (2026-09-19 → 2026-10-19). Kid profiles: Quintus (#5), Prima
(#6), Octavius (#7). Source: `GET /api/profiles/<id>/usage-by-app`
`orphanHosts[]`, aggregated by `proportionalSeconds` across all three
profiles, FQDN hosts only (bare-IP literals excluded).

## New apps

| slug | host-set | ~min/30d | disposition |
|------|----------|----------|-------------|
| `savvas-realize` | `savvasrealize.com` | ~32 | genuine app — K-12 curriculum/assessment platform, 13 distinct BFF/viewer subdomains, same evidence tier as `freckle.yml` |
| `matlab` | 10 `mathworks.com` subdomains | ~78 | genuine app — MATLAB Online + account/docs, analytics/marketing SDK hosts excluded |
| `classlink` | `classlink.com` | ~76 | display-cleanup, attribution only — school SSO/LaunchPad portal, not kid-chosen, same class as `blocksi`/`goguardian` |

## Existing-app host-set extension

`lego.yml` — a second generation of LEGO digital-play products on
`legogroup.io`/`thelegogroup.com` (not `lego.com`), ~73 min/30d combined
across 6 new host entries (7 FQDNs, since `kids-creations.legogroup.io`
covers both its `bff.` and `cdn.` children by suffix match), all
title/HTTP-confirmed live and non-shop:
`kids-creations.legogroup.io` (bff+cdn), `cont.kids.thelegogroup.com`,
`minigames.thelegogroup.com` ("Kids Mini Games"), `buildertool.thelegogroup.com`
("Brick Kit Studio"), `new.avatar.lego.com` ("Avatar Site"), `cube.lego.com`
("LEGO | Cube"). Scope broadens from "3D Builder tool only" to "LEGO kids
digital play"; shop/marketing/corporate apexes stay excluded.

## Skipped (already-documented pattern, no action)

- `download.testnav.com` / `cdn-download-prod.drcedirect.com` (Pearson
  TestNav / DRC eDirect) — single standardized-test-session download
  pattern (presence < proportional), matches the skip-with-note precedent
  from the 2026-10-12 pass (#2908 skill log).
- `store.arduino.cc`, `sgtm.arduino.cc`, `api.aayinltcs.arduino.cc`,
  `evs.aayinltcs.arduino.cc` — already deliberately excluded by `arduino.yml`
  (Shopify shared pool / analytics-telemetry); orphaning is expected, not a
  gap.
- `ic.svvsd.org` (St. Vrain Valley School District student info system) —
  below the engagement bar this pass, watch-item.
- `coddy.tech` (Cloudflare-challenged, ~26 min) and `api.teamwood.games`
  (~12.5 min) — plausible kid-facing sites but too little evidence/could not
  verify cleanly past the Cloudflare challenge this pass; left as watch-items
  for next run rather than templated on partial confidence.
- The usual Google/Apple platform infra, ad-tech/RTB, and shared-CDN pools
  dominate the top of the orphan list as always; not actionable.

## Collateral notes

Several new hosts share AWS CloudFront `/24`s with unrelated brands already
in the catalog (`99.84.118.x`, `99.84.105.x`, `18.154.101.x`) — no specific
IP collided across brands, which is the standard accepted Class-2
CDN-edge-sharing latent risk per `bricklink.yml`/`sportys.yml` precedent, not
grounds for exclusion. Every host added carries its own dedicated bytes with
no alternative dedicated-bytes host to point a block at instead.
