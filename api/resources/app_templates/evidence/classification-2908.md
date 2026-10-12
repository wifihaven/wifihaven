# App-catalog classification — #2908 weekly pass (2026-10-12)

Orphan-driven pass. Source: prod cloud API, read-only. No policy writes.

```
GET /api/devices                                                       # roster
GET /api/profiles/<id>/usage-by-app?from=2026-09-12&to=2026-10-12       # profiles 5,6,7 — orphanHosts
```

Kid profiles: 5 (Quintus — Quintus Chromebook, Quintus iPad), 6 (Prima —
Prima iPad, Prima iPad (3)), 7 (Octavius — Octavius iPad). `orphanHosts`
filtered to `host.type == "fqdn"` (bare IP literals excluded, per the
standing #2775 caveat — a third of orphan time is unattributed IP literals
and isn't catalogable) and aggregated across the three profiles by
`proportionalSeconds`.

## Observed traffic — top non-platform clusters

| host(s) | proportional min (30d) | rows | profile(s) |
| --- | ---: | ---: | --- |
| `blocksi-screenshots-us.s3.us-east-005.backblazeb2.com` | 415 | 1 | 5 |
| `log.blocksi.net` | 309 | 1 | 5 |
| `api.blocksi.net` | 186 | 1 | 5 |
| `k8s-ws-lb.blocksi.net` | 108 | 1 | 5 |
| `service1.blocksi.net` | 71 | 1 | 5 |
| `service.blocksi.net` | 58 | 1 | 5 |
| **Blocksi total** | **~1,150** | **6** | **5** |
| `download.testnav.com` | 174 | 1 | 5 |
| `cdn-download-prod.drcedirect.com` | 147 | 1 | 5 |
| `inquisition.goguardian.com` (×2 rows) | 93 + 25 | 2 | 5, 6 |
| `countvoncount.goguardian.com` | 14 | 1 | 5 |
| `prod-ext-gateway.goguardian.com` (×2 rows) | 1 + 25 | 2 | 5, 6 |
| **GoGuardian total** | **~161** | **5** | **5, 6** |
| `home.testnav.com` | 1 | 1 | 5 |

Everything above `blocksi`/`goguardian`/`testnav`/`drcedirect` in the raw
ranked list was Google/gstatic/googleapis platform infra, Shopify-hosted
storefront infra (`mouldkingcorp.com`, `shop.app`, `*.shopifysvc.com`,
`fonts.shopifycdn.com` — the demonstrated-shared-origin skip from #2774's
`shops.myshopify.com` finding), New Relic browser telemetry
(`bam.nr-data.net`), Cloudflare NEL reporting (`a.nel.cloudflare.com`),
Proofpoint URL-rewrite redirects (`urldefense.com`), and Bing ad pixels
(`bat.bing.com`) — all standard skip categories, not re-litigated here.

## Disposition: Blocksi and GoGuardian → new display-cleanup apps

Both are school-managed Chromebook content-filter / monitoring products
(web-confirmed: Blocksi — cloud web/YouTube filter with a parent dashboard at
`parent.blocksi.net`; GoGuardian — Chromebook activity monitoring, screen
recording, teacher-side tab control, used by 14,000+ districts). Neither is
something a kid chooses to use; both run in the background on the
school-issued Chromebook whenever it's on school WiFi or carries school MDM
policy over this network. That's the exact #2805 display-cleanup shape
(icanhazip, weather, onenote): real recurring background traffic cluttering
the orphan list for the Quintus profile across many days (Blocksi: 6 distinct
hosts, every one a single aggregated row meaning sustained traffic across the
window, not a one-off), with no app a kid would recognize as "theirs."

Treated as **two separate apps**, not merged like icanhazip/ipify — those two
were interchangeable instances of literally the same function (public-IP
lookup); Blocksi and GoGuardian are competing products from different
vendors, and only Blocksi showed enough volume to look like the Chromebook's
actual active filter, with GoGuardian's smaller footprint possibly an
extension that's installed but less active, or a legacy district deployment.
Keeping them separate preserves that distinction for whoever reads per-app
usage later.

### Host-set

`blocksi.yml`: apex `blocksi.net` (all five backend subdomains observed are
its children; the apex is a single-purpose vendor domain, not a shared
platform, so it's the stable anchor) plus
`blocksi-screenshots-us.s3.us-east-005.backblazeb2.com` (the screenshot-upload
bucket — a separate domain, Backblaze B2, so listed explicitly).

`goguardian.yml`: apex `goguardian.com` (all three observed subdomains are
its children; same single-vendor-apex reasoning).

DNS check — none of the backend hosts are on a known shared CDN/anycast
pool:

```
$ dig +short log.blocksi.net api.blocksi.net k8s-ws-lb.blocksi.net service.blocksi.net service1.blocksi.net
34.96.126.102   # log, service, service1 (same GCP LB IP)
34.71.85.19     # api
34.49.147.211   # k8s-ws-lb
$ dig +short inquisition.goguardian.com countvoncount.goguardian.com prod-ext-gateway.goguardian.com
184.34.157.197 32.188.219.11 35.80.133.103 44.233.228.255 44.240.7.151   # inquisition (AWS, multi-region)
52.43.94.52 44.225.247.157 44.253.161.44                                 # countvoncount (AWS)
32.188.193.27 44.240.32.49 16.148.212.153                                 # prod-ext-gateway (AWS)
```

Both vendors' backend IPs are on GCP/AWS ranges that look dedicated to their
own load balancers, not a shared anycast product pool (Google GFE,
Cloudflare edge, Fastly edge) — not independently verified beyond the
resolved ranges, so the templates say so and stay attribution-only.
`blocksi-screenshots-us...backblazeb2.com` IS on shared Backblaze B2 storage
infra (149.137.x.x), called out in the template comment.

**Both templates are attribution-only, not blocking candidates**: this is
school-mandated software on a school-owned device. Blocking either risks
breaking the Chromebook's compliance with district filtering policy, and
per Blocksi's own parent documentation, school-side rules aren't overridable
through the parent dashboard anyway — there's no legitimate reason to put
either host-set in a drop set.

## Disposition: TestNav / DRC eDirect → skip-with-note

`download.testnav.com` (Pearson TestNav, standardized-test delivery) and
`cdn-download-prod.drcedirect.com` (DRC INSIGHT/eDirect, the same category of
state-assessment platform) both showed real proportional minutes but a single
row each with low presence relative to proportional time — consistent with a
one-time test-session download, not recurring engagement. Same reasoning as
the Scholastic Book Clubs skip (#2740): a school-administered assessment
event, not something the kid returns to. Not templated; if a future pass
shows recurring multi-day traffic on either host, revisit.

## Icons

Both resolved 200 from the DuckDuckGo favicon service on the apex:

```
$ curl -sS -o /dev/null -w "%{http_code} %{size_download}\n" https://icons.duckduckgo.com/ip3/blocksi.net.ico
200 1310
$ curl -sS -o /dev/null -w "%{http_code} %{size_download}\n" https://icons.duckduckgo.com/ip3/goguardian.com.ico
200 1344
```

## Validation

`AppTemplatesSpec`'s pinned `expected` set and `_index.yml` both updated with
`blocksi` and `goguardian`. No blocklist files touched.
