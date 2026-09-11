# App-catalog classification — #2762 (2026-09-10)

Operator request, not a discovery sweep: "create apps for amazon and sportys."
Both brands were named up front, so Step 0 was used to *confirm and scope* them
rather than to find candidates.

Source: prod cloud API, `GET /api/devices/<mac>/recent-apexes?windowDays=30&limit=500`,
pulled for all 11 non-IoT devices on 2026-09-10. Read-only.

## Per-apex traffic (30d)

| device | apex | bytes | hits |
| --- | --- | ---: | ---: |
| Kid Laptop | `sportys.com` | 2,035,751,680 | 365 |
| Sameer Mac | `sportys.com` | 23,156,211 | 19 |
| Sameer iPhone | `sportys.com` | 7,831,757 | 5 |
| Kid Laptop | `ssl-images-amazon.com` | 3,085,261,502 | 422 |
| Prima iPad | `ssl-images-amazon.com` | 2,768,653,897 | 155 |
| Octavius iPad | `ssl-images-amazon.com` | 1,442,495,984 | 172 |
| Quintus iPad | `ssl-images-amazon.com` | 422,031,602 | 27 |
| Sameer iPhone | `ssl-images-amazon.com` | 390,694,223 | 222 |
| Rachel Mac | `ssl-images-amazon.com` | 309,592,584 | 138 |
| Rachel iPhone | `ssl-images-amazon.com` | 225,791,210 | 133 |
| Sameer Mac | `ssl-images-amazon.com` | 113,142,784 | 24 |
| Kid Laptop | `amazon.com` | 694,476,092 | 3820 |
| Sameer iPhone | `amazon.com` | 344,569,682 | 1087 |
| Prima iPad | `amazon.com` | 323,040,666 | 570 |
| Octavius iPad | `amazon.com` | 295,949,296 | 664 |
| Rachel Mac | `amazon.com` | 252,947,918 | 2832 |
| Rachel iPhone | `amazon.com` | 144,512,811 | 393 |
| Quintus iPad | `amazon.com` | 54,118,961 | 88 |
| Sameer Mac | `amazon.com` | 48,384,619 | 466 |
| Sameer iPhone | `media-amazon.com` | 199,841,358 | 102 |
| Octavius iPad | `media-amazon.com` | 128,801,170 | 8 |
| Rachel iPhone | `media-amazon.com` | 83,930,797 | 65 |
| Kid Laptop | `media-amazon.com` | 65,060,914 | 26 |
| Quintus iPad | `media-amazon.com` | 63,602,537 | 1 |
| Rachel Mac | `media-amazon.com` | 57,424,070 | 38 |
| Prima iPad | `media-amazon.com` | 33,606,057 | 4 |
| Sameer Mac | `media-amazon.com` | 1,702,793 | 2 |

`recent-apexes` returns bytes/hits per APEX and a flat `subdomains[]` list, with
no per-subdomain byte split. So the `amazon.com` row above is the whole-company
zone, not the storefront — which is exactly the reason the template excludes it.

## Gap check (Step 1)

Neither apex is covered by any existing template. `grep -rniE 'amazon|sportys'`
over `api/resources/app_templates/*.yml` returns only two incidental matches in
`arduino.yml` and `connectivity-test.yml` comments (both about `amazonaws.com`
hostnames, not Amazon-the-app). The Amazon domains already in the catalog are
ad-tech entries in `blocklists/ads.yml`: `amazon-adsystem.com`,
`amazon-ads-attestation.com`, `paa-reporting-advertising.amazon`.

Both are genuine gaps. Both classified as new app templates (Step 2).

## Observed subdomains, per device

### `sportys.com`

| device | subdomains |
| --- | --- |
| Kid Laptop | `dl.videos`, `stream.videos`, `ye.courses` |
| Sameer iPhone | `dl.videos`, `stream.videos`, `ye.courses` |
| Sameer Mac | `dl.videos`, `stream.videos`, `ye.courses`, `www`, `pspdfkit.courses` |

The 2.04 GB on Kid Laptop is course video from Sporty's Online Training and
nothing else — the store host `www` never appears on that device.

### `amazon.com`

`www.amazon.com` is the only subdomain present on all eight devices that hit the
apex. Everything else observed under it is a different Amazon product, telemetry,
or ad surface:

| subdomain | what it is | seen on |
| --- | --- | --- |
| `aws.amazon.com`, `d2c.aws.amazon.com`, `vs.aws.amazon.com` | AWS console / docs | Sameer iPhone |
| `api.amazon.com` | Login with Amazon (shared vendor API), Alexa | Rachel iPhone, Sameer iPhone |
| `arcus-uswest.amazon.com`, `msh.amazon.com` | Alexa / connected-device infra | Rachel iPhone, Sameer iPhone, Octavius iPad |
| `read.amazon.com`, `music.amazon.com` | Kindle, Amazon Music | Rachel Mac |
| `watch.amazon.com`, `prime.amazon.com`, `atv-ps.amazon.com` | Prime Video | Sameer Mac, Sameer iPhone, Rachel iPhone |
| `apay-us.amazon.com` | Amazon Pay | Kid Laptop |
| `pharmacy.amazon.com` | Amazon Pharmacy | Rachel iPhone |
| `fls-na`, `unagi`, `unagi-na`, `ipv6.unagi-na`, `data`, `transient` | telemetry | most devices |
| `sponsored-ads`, `aax-us-east-retail-direct`, `affiliate-program` | Amazon Ads | most devices |

### `ssl-images-amazon.com` / `media-amazon.com`

`ssl-images-amazon.com`: `images-na`, `images-eu`.

`media-amazon.com`, per device. `metrics` is what forces this apex to be listed
by subdomain rather than as an apex — it is telemetry, and the template carries
only storefront imagery. `c` is then listed alongside `m` on its own evidence:
it is attributed directly, as its own name, on Sameer iPhone and Prima iPad.
(Its absence on the other six proves nothing either way — `subdomains[]` can't
distinguish "never queried `c.`" from "the #1344 fold-back absorbed it".)

| device | subdomains |
| --- | --- |
| Sameer iPhone | `m`, `c`, `metrics` |
| Prima iPad | `m`, `c` |
| Rachel iPhone | `m`, `metrics` |
| Rachel Mac | `m`, `metrics` |
| Kid Laptop, Quintus iPad, Octavius iPad, Sameer Mac | `m` |

## Host-set decisions (Step 3)

### `sportys` — `www.sportys.com`, `videos.sportys.com`, `courses.sportys.com`

Three subdomain entries suffix-match all five observed hosts via
`HostMatch.matchesApex`. Resolution as of 2026-09-10:

| host | resolves to |
| --- | --- |
| `www.sportys.com` | CNAME `sportys.com` → 199.232.66.132 (Fastly) |
| `stream.videos.sportys.com` | 99.84.105.{16,38,60,119} (CloudFront) |
| `dl.videos.sportys.com` | 18.238.176.{58,71,74,112} (CloudFront) |
| `courses.sportys.com` | 16.59.107.150, 3.151.66.199 |
| `ye.courses.sportys.com` | CNAME `prod-ye-training.simplysporty.net` → 16.59.159.202, 3.23.25.151 |
| `pspdfkit.courses.sportys.com` | CNAME `prod-pspdfkit.simplysporty.net` → 77.112.172.80, 77.112.39.76 (whois `AMAZO-4`) |

A whois org name can't separate a CloudFront edge from generic AWS compute —
`AT-88-Z`/`AMAZO-4` covers both — so the classification comes from AWS's
published `https://ip-ranges.amazonaws.com/ip-ranges.json` (`service` field):

| host | address | service |
| --- | --- | --- |
| `www.sportys.com` | 199.232.66.132 | Fastly (whois `SKYCA-3`) |
| `stream.videos.sportys.com` | 99.84.105.16 | `CLOUDFRONT` 99.84.0.0/16 |
| `dl.videos.sportys.com` | 18.238.176.58 | `CLOUDFRONT` 18.238.0.0/15 |
| `courses.sportys.com` | 3.151.66.199, 16.59.107.150 | `EC2` us-east-2 |
| `ye.courses.sportys.com` | 3.23.25.151, 16.59.159.202 | `EC2` us-east-2 |
| `pspdfkit.courses.sportys.com` | 77.112.172.80 | `EC2` us-east-2 |

So three of the six are EC2 origins rather than CDN edges. Two of those three
(`ye.courses`, `pspdfkit.courses`) sit behind the dedicated
`prod-<service>.simplysporty.net` origins; `courses.sportys.com` resolves
directly — self-hosted, but not on that naming pattern.

That split decides Class 2 rather than being incidental to it. `_README.yml`
scopes Class 2 to a "third-party cloud-CDN edge", so only `www` (Fastly) and
the two `*.videos` hosts (CloudFront) are Class 2 here; the three EC2 origins
fall outside it, and a dedicated origin per training surface is the good case
rather than a latent risk. Nothing establishes 199.232.66.132 as Sporty's
alone; Fastly anycast addresses are shared across customers by design.

Class-2 overlap check, which `_README.yml` asks for rather than assuming:
`stream.videos.sportys.com` (99.84.105.{16,38,60,119}) and `aws.amazon.com`
(99.84.105.{36,65,73,118}) were OBSERVED in the SAME CloudFront /24 — stated as
observed because `stream.videos` also answered 3.161.225.x (another CloudFront
range) in one lookup, so its address set moves — and `amazon.yml`
excludes the bare `amazon.com` apex precisely so the AWS console is never
collaterally dropped. The addresses are distinct today and only resolved IPs
enter an `eb_` set, so blocking Sporty's drops no AWS-console address. Recorded
as the host to re-check first if #1663 revisits the Class-2 set.

Bare `sportys.com` excluded. `images.sportys.com` CNAMEs off Sporty's own
infrastructure — `media.esp1.co` → `media.espssl.com` →
`media.espssl.com.cdn.cloudflare.net` → 172.64.144.42 / 104.18.43.214, a
broadly-shared Cloudflare pool. Since `render.lua` emits `nftset=/<host>/`
verbatim and dnsmasq matches by pure suffix, an apex entry would sweep that pool
into the `eb_` drop set. It has also never appeared in traffic, so the
"only template what you've seen used" rule excludes it independently.

Sibling Sportsman's Market brands linked from the store — `sportysacademy.com`,
`flighttrainingcentral.com`, `ipadpilotnews.com`, `airfactsjournal.com`,
`sportystoolshop.com`, `aviationgifts.com`, `preferredliving.com` — are all on
separate infrastructure with no observed traffic. Excluded per the #2596
`renaissance.com` precedent: a topically adjacent sibling apex needs its own
evidence.

### `amazon` — `www.amazon.com`, `ssl-images-amazon.com`, `m.media-amazon.com`, `c.media-amazon.com`

Scoped to the shopping surface. Bare `amazon.com` excluded: the observed-subdomain
table above is the argument, and every entry in it is real traffic from this
household, not a hypothetical. Blocking or time-limiting a kid's Amazon shopping
must not take out the AWS console, Login with Amazon, Alexa, Kindle or Amazon Pay.

`ssl-images-amazon.com` IS listed as an apex, deliberately in contrast: it is a
single-purpose static-image zone, so every child is storefront imagery by
construction. That is the whole argument, deliberately not "adds no IP exposure"
— the same unverifiable IP-set claim about DNS-steered hosts that the `metrics.`
note above warns against.

`media-amazon.com` is NOT listed as an apex, because `metrics.media-amazon.com`
is telemetry and this template carries only hosts whose bytes are storefront
imagery. Same call as excluding `telemetry.canva.com` and `sgtm.arduino.cc`.
`metrics.` was observed CNAMEing to `ecp.map.fastly.net` → 199.232.65.51, but
that is NOT the basis for the exclusion and must not be restated as "a pool no
kept host touches": the kept image hosts are DNS-steered across CDNs, Fastly
included, so pool-disjointness here is unverified.

`c.media-amazon.com` is kept because it is attributed directly, as its own
name, on Sameer iPhone and Prima iPad (see the per-device table above). Two rationales were tried and are both wrong,
recorded so they don't get re-derived: it is NOT "adds no incremental IPs"
(that holds only in the steering state where `m.` resolves through `c.`), and it
is NOT "a CNAME target earns no attribution" — #1344/#1346 fold a re-queried
CNAME target back onto its branded chain head, and `each_candidate_host` walks
that recovered head alongside the answered name
(`openwrt/files/usr/lib/lua/wifihaven/dns_tail_sets.lua`). What makes an explicit
entry worth having is that the alias edge is TTL-bounded (`resolve_head`) and
capped with oldest-learned eviction (`evict_oldest_alias`), both in
`dns_log.lua`, so the fold-back is best-effort.

Amazon steers the image hosts between CDNs by DNS: `m.media-amazon.com` answered
on Akamai (23.215.223.x via `a.media-amazon.com.akamaized.net`) and, minutes
later, on CloudFront (13.226.249.165 / 99.84.98.145) via `c.media-amazon.com`.
Any single `dig` of these hosts is a sample, not the host's IP set. Accepted
Class-2 latent risk per `_README.yml` — that IS where the app's bytes live.

Prime Video left out as a distinct service; netflix/youtube/twitch are separate
apps and Prime Video should be too if it ever needs a template.

Residual: `media-amazon.com` also serves IMDb and Prime Video imagery, so a future
Prime Video app would find some of its image bytes already attributed to `amazon`.

## Third app: `amazon-telemetry` (operator follow-up)

The operator saw the Amazon background endpoints rendering as loose per-site
rows on a device page — `unagi.amazon.com` 28m, `data.amazon.com` 24m, a Minerva
host 22m, `fls-na.amazon.com` 15m, `transient.amazon.com` 13m — and asked for an
app to group them.

Their exclusion from `amazon.yml` was deliberate, and they stay out of it: a
time-limited app's host-set is one aggregated budget (#1505), so folding
telemetry in would bill background chatter to the shopping budget. A separate
app gives the operator the choice.

Ubiquity is what confirms these are background infrastructure rather than
anyone's activity — counts are devices-out-of-eight from the same 30d pull:

| host | devices |
| --- | ---: |
| `unagi.amazon.com` | 8 |
| `data.amazon.com` | 8 |
| `fls-na.amazon.com` | 8 |
| `unagi-na.amazon.com` | 4 |
| `transient.amazon.com` | 3 |
| `*.us-east-1.prod.service.minerva.devices.a2z.com` | 3 |

The Minerva hosts carry a 63-hex random label per device (`42fe2b06…`,
`6e5351d7…`, `b79c6607…`), so they cannot be enumerated — `minerva.devices.a2z.com`
is a suffix anchor, which is what both `nftset=/<host>/` and `matchesApex` do.

`unagi.amazon.com` and `unagi-na.amazon.com` are BOTH listed: `unagi-na` CNAMEs
from `unagi`, but suffix matching is `host == x || host.endsWith("." + x)`, and
`"unagi-na.amazon.com"` does not end in `".unagi.amazon.com"` — sibling labels,
not parent/child. Only `ipv6.unagi-na.amazon.com` is a true child, covered by
the `unagi-na` entry.

No apex is listed bare. `a2z.com` and `amazon.dev` are Amazon's internal shared
domains (`a2z.com` alone fronts AWS, Alexa, devices and retail — the same sample
has `redirect.prod.experiment.routing.cloudfront.aws.a2z.com` under it), so
every entry is a deep per-service suffix.

Excluded: all of Amazon's ad surfaces (`sponsored-ads`,
`aax-us-east-retail-direct`, `affiliate-program`, `tahoe-analytics…advertising`,
`paets.advertising.amazon.dev`, `adsqtungsten.a9.amazon.dev`) — ad-tech belongs
in `blocklists/ads.yml`, not in an app whose premise is that blocking it is
safe. Also excluded: `weblab.a2z.com` and
`redirect.prod.experiment.routing.cloudfront.aws.a2z.com`, which are experiment
CONFIG and ROUTING rather than telemetry — nothing reads a telemetry response,
so dropping it is safe, whereas dropping config can change app behaviour in ways
that are hard to attribute later.

Residual, verified against `ip-ranges.json`: unlike every other template here,
these resolve to generic AWS EC2 addresses (44.192.0.0/11, 3.224.0.0/12,
13.216.0.0/13, 54.152.0.0/16, 32.184.0.0/13, 52.44.0.0/15, 100.48.0.0/12,
3.130.0.0/16) rather than CDN edges; only `data.amazon.com` is CloudFront
(99.84.123.87). AWS recycles EC2 addresses between tenants, so an address that
enters an `eb_` set can later belong to an unrelated service, and the set entry
does not expire with the DNS record. That is a different risk from the Class-2
CDN-edge risk — a future stranger on a recycled address rather than a present
co-tenant — and it argues for re-resolving, not for dropping any host here.

## Validation (Step 5)

`mill api.test.testOnly 'wifihaven.api.feature.AppTemplatesSpec'` — 38 tests
passed, 0 failed. Seeder log confirms `slug=amazon (id=44, hosts=4)`,
`slug=sportys (id=45, hosts=3)` and `slug=amazon-telemetry (id=46, hosts=10)`. `scalafmt --check --non-interactive` clean.
No blocklist files touched, so `BundledBlocklistsSpec` was not run.
