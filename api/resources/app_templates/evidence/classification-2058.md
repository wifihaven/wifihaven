# App-catalog pass — classification (#2058, 2026-06-29)

Traffic-driven pass via the `app-catalog-pass` skill. Source:
`GET /api/devices/<mac>/recent-apexes?windowDays=30&limit=500` aggregated by
apex across the four kid devices (Kid Mac, Octavius / Prima / Quintus iPads),
prod cloud API. IPv6 attribution confirmed healthy (#1796 closed; #1807/#1802
merged) so the sample is not IPv4-biased.

## Disposition

The catalog is mature: every uncovered apex in the >1 MB band is shared
infra/CDN, ad-tech/RTB, shared corporate (Adobe/Autodesk), or analytics/support.
The only genuine gap is on an **existing** app.

| apex | bytes | hits | disposition |
|------|------:|-----:|-------------|
| `eaglercraft.com` | 521 KB | 9 | **Extend `eaglercraft.yml`** — direct mirror of the already-templated Eaglercraft browser-Minecraft game |
| `ytmp3.gg` (`dmca.`) | 3.5 MB | 1 | Skip — YouTube-to-MP3 ripper, single incidental download, below engagement bar, no clean category list |
| Apple/Google/Amazon infra (cdn-apple, icloud-content, gstatic, gvt2/gvt3, mzstatic, aaplimg, akadns, amazonaws, ssl-images-amazon, googleusercontent, media-amazon, edge.apple, …) | — | — | Skip — shared CDN / platform infra |
| Adobe / Autodesk (`adobe.com`, `adobe.io`, `autodesk.com`) | — | — | Skip — shared corporate infra (per skill) |
| Ad-tech / RTB (flashtalking, innovid, adsrvr, pubmatic, casalemedia, sharethrough, amazon-adsystem, the-ozone-project, nextmillmedia, responsiveads, admanmedia, id5-sync, indexww, nitropay, adlightning, acuityplatform, intentiq, infolinks, fastclick, a-mo.net, dblks, a47b, marphezis, ingage.tech, ay.delivery, cootlogix, …) | — | — | Skip — ad/RTB networks |
| Analytics / support (sentry, posthog, segment, quantummetric, zendesk, nr-data, ada.support, disqus, medallia, intercomassets) | — | — | Skip — telemetry/support collateral |
| Payments / shopping (stripe, paypal, temu, amazon) | — | — | Skip — collateral / no app surface |
| `tenor.com`, `pexels.com`, `fontawesome.com`, `ctfassets.net` | <1 MB | — | Skip — shared media/asset CDNs |

## eaglercraft.com — why extend the app (not games.yml, not new app)

- **Same project as the existing `eaglercraft` app.** Web-confirmed
  (`eaglercraft.com/`, `eaglercraft.com/play`) as a direct host of the
  open-source Eaglercraft browser-Minecraft game — the same kid-discovered
  Minecraft-in-browser clone templated in #1705 (which scoped only
  `eaglercraft.dev` + the account-scoped `eaglercraft-99f.workers.dev` runtime).
- **It is a game host, not an "unblocked games" proxy portal.** The skill routes
  filter-bypass / multi-proxy hubs to `games.yml` only. eaglercraft.com serves
  the game itself, not a TitaniumNetwork-style proxy stack, so the proxy-only
  rule does not apply.
- **Operator decision #1705 stands: Eaglercraft is a time-limited app, not a
  block target.** A mirror of the same experience belongs in the same app
  host-set so the kid's play is attributed and counts toward the Eaglercraft
  time budget / allow-block surface. `games.yml` has no eaglercraft entry; this
  pass keeps it that way for consistency.

## Host-set coverage check

`eaglercraft.com` is a bare apex with no observed subdomains, so the single apex
entry suffices. `HostMatch.matchesApex` suffix-matches the entry's own subtree,
so any future `*.eaglercraft.com` is covered without further entries. No shared
CDN/anycast collateral pulled in.

## Outcome

One-host extension to `api/resources/app_templates/eaglercraft.yml`
(`+ eaglercraft.com`). No new slug → no `_index.yml` / `AppTemplatesSpec` change.
No blocklist changes this pass.
