# App-catalog pass 2026-09-21 (#2805): HamStudy + icanhazip

Operator request. Orphan-host list (usage-by-app, Kids profiles, 30d) otherwise
showed no new cluster (platform infra, ad-tech, Shopify shops, weather polling).

| host | proportional min (30d) | notes |
|---|---|---|
| hamstudy.org | 69 | Kid Laptop only; 1.7 MB / 80 hits |
| blog.hamstudy.org | 27 | covered by apex suffix match |
| ipv4.icanhazip.com | ~18,368 (all kid profiles) | display-cleanup app; Cloudflare-fronted, collateral unverified |

Both resolve to dedicated 44.x addresses (no shared-pool collateral).
Disposition: new app `hamstudy`, host-set `hamstudy.org`. Also `icanhazip` (host `icanhazip.com`) as a display-cleanup app: top orphan host (~18k min), operator asked for it.
Not templated (candidates next pass): weather.com / weatherbug (background widget
polling), api.ipify.org (IP-check utility).
