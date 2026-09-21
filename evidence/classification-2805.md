# App-catalog pass 2026-09-21 (#2805): HamStudy

Operator request. Orphan-host list (usage-by-app, Kids profiles, 30d) otherwise
showed no new cluster (platform infra, ad-tech, Shopify shops, weather polling).

| host | proportional min (30d) | notes |
|---|---|---|
| hamstudy.org | 69 | Kid Laptop only; 1.7 MB / 80 hits |
| blog.hamstudy.org | 27 | covered by apex suffix match |

Both resolve to dedicated 44.x addresses (no shared-pool collateral).
Disposition: new app `hamstudy`, host-set `hamstudy.org`.
Watch-items not templated: weather.com / weatherbug (background widget
polling), icanhazip/ipify (shared IP-check utilities).
