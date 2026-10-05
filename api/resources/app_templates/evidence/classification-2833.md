# App-catalog classification — #2833 Scratch (2026-10-05)

Operator-named pass: author a Scratch app template. The brand was given; the
traffic pull scoped the host-set and settled the two questions the issue
raised — what Scratch's real subdomain lanes are, and whether anything outside
`scratch.mit.edu` belongs in the set.

Source: prod cloud API, read-only. No policy writes.

```
GET /api/devices                                                     # roster
GET /api/devices/<mac>/recent-apexes?windowDays=90&limit=1000        # bytes/hits + subdomains
GET /api/profiles/<id>/usage-by-app?from=2026-09-05&to=2026-10-05    # profiles 1,5,6,7 — orphanHosts
```

Kid devices sampled: Kid Laptop (`ca:ef:a1:72:6a:a3`), Kid Mac (2)
(`b0:de:28:25:93:89`, no rows in window), Octavius iPad
(`a6:05:9a:63:83:af`), Prima iPad (`8a:8a:0b:86:5a:63`), Prima iPad (3)
(`ae:2f:81:30:53:6a`), Quintus Chromebook (`c4:13:75:68:a1:01`), Quintus iPad
(`26:74:fc:f9:4e:9e`). Adult devices swept separately to confirm the host set
is complete, not to drive the classification.

## Observed traffic

90d bytes under the `mit.edu` apex — **every byte of it `*.scratch.mit.edu`**,
on every device:

| device | bytes | hits |
| --- | ---: | ---: |
| Quintus Chromebook | 215,904,583 (215.9 MB) | 162 |
| Kid Laptop | 85,839,730 (85.8 MB) | 98 |
| Prima iPad | 74,417 (74 KB) | 2 |
| Sameer Mac (adult) | 168,662,266 (168.7 MB) | 98 |
| Sameer iPhone (adult) | 1,764,970 (1.8 MB) | — |

`recent-apexes` reports bytes per APEX, not per subdomain, so those totals
cannot be split per host. The per-host figures below are 30d proportional
minutes from `orphanHosts`, which is per-host.

### Per-host proportional minutes, 30d, kid profiles

| host | profile 1 (Kids) | profile 5 (Quintus) | profile 6 (Prima) |
| --- | ---: | ---: | ---: |
| `projects.scratch.mit.edu` | — | 54 | — |
| `assets.scratch.mit.edu` | 25 | 45 | 1 |
| `uploads.scratch.mit.edu` | 1 | 30 | — |
| `scratch.mit.edu` | 25 | 20 | — |
| `cdn.assets.scratch.mit.edu` | 14 | — | — |
| `clouddata.scratch.mit.edu` | — | 11 | — |
| `cdn2.scratch.mit.edu` | — | 5 | — |
| `backpack.scratch.mit.edu` | 3 | — | — |
| `api.scratch.mit.edu` | 0 | 2 | — |
| `cdn.scratch.mit.edu` | — | 0 | — |

**242 proportional minutes over 30 days, all of it orphaned** — attributed to
no app before this template. Profile 7 (Octavius) had none.

## Disposition: new app template, anchoring presence

A kid building, remixing or playing a Scratch project is genuine engagement and
its time belongs in a budget. This is explicitly **not** the
`icanhazip` / `weather` display-cleanup class (#2805/#2811/#2820), which exists
to de-orphan background traffic nobody is "using" and is documented
attribution-only. Scratch is the opposite case: anchoring is the intended
behaviour. No host here goes into a background class, and the template says so
inline so a later pass does not reclassify it.

Per #2813/#2815, the thing to avoid is reaching for a brand APEX to get that
anchoring. This template does not — see below.

## Host set: one entry, `scratch.mit.edu`

Every Scratch host observed is a child of `scratch.mit.edu`, and both
enforcement (dnsmasq suffix match on the verbatim `nftset=/<host>/` the agent
emits) and attribution (`HostMatch.matchesApex`, `host == x ||
host.endsWith("." + x)`) suffix-match the entry's own subtree. So one entry
covers all ten observed hosts. Enumerating them would add no coverage and would
rot as Scratch adds subdomains.

Attribution depth is fine: the deepest observed host,
`cdn.assets.scratch.mit.edu`, is 5 labels, and `HostMatch.lookupApex` →
`apexTails(host, maxHops = 5)` finds `scratch.mit.edu` on the 3rd tail — well
inside the bound that bites `amazon-telemetry.yml`'s deep anchors.

### Why that is a tight FQDN and not "the apex in disguise"

`scratch.mit.edu` is a **delegated DNS zone**. MIT's own `mit.edu` zone is on
Akamai nameservers and hands the whole subtree to a separate Route 53 set:

```
$ dig +short NS mit.edu
eur5.akam.net.  use5.akam.net.  usw2.akam.net.  ns1-37.akam.net.
asia2.akam.net. asia1.akam.net. ns1-173.akam.net. use2.akam.net.

$ dig +short NS scratch.mit.edu
ns-275.awsdns-34.com.  ns-583.awsdns-08.net.
ns-1316.awsdns-36.org. ns-1587.awsdns-06.co.uk.

$ dig @use5.akam.net +noall +authority +answer NS scratch.mit.edu
scratch.mit.edu. 1800 IN NS ns-1587.awsdns-06.co.uk.   (+ the other three)
```

MIT's own authoritative server returns that delegation, so everything under
`scratch.mit.edu` is administered by the Scratch Foundation by construction,
and nothing else in `mit.edu` is reachable through the entry. This is the
stable, checkable argument the `youtube.yml` standard asks for — a nameserver
delegation, not an IP-overlap claim, which would be unverifiable for
DNS-steered hosts.

## The `mit.edu` exclusion

`mit.edu` must never appear in this host set. Both matchers above are pure
suffix tests, so a `mit.edu` entry would pull EVERY MIT hostname into the
per-(MAC, host) `eb_` drop set when Scratch is blocked, and into Scratch's
budget when it is not. Enforcement is IP-layer, so the block half is the #1636
shape: a blocked parent's resolved IPs are dropped, collateral included.

`mit.edu` is demonstrably a multi-service parent right now:

| host | fronting |
| --- | --- |
| `www.mit.edu`, `web.mit.edu` | Akamai (`www.mit.edu.edgekey.net`) |
| `ocw.mit.edu` (OpenCourseWare) | a SECOND independently delegated zone, own Route 53 set (`ns-293.awsdns-36.com`, `ns-620.awsdns-13.net`, …) |
| `alum.mit.edu` | Akamai edge address |
| `scratch.mit.edu` | Fastly + AWS, own Route 53 set |

**Honest scope:** no non-Scratch MIT hostname appeared in this household's
traffic at all. The exclusion is structural and precautionary — it closes a
latent trap rather than an observed one. Said plainly in the template so nobody
reads it as a fixed incident.

## Shared-CDN handling (`_README.yml` Class 2)

| host | CNAME target |
| --- | --- |
| `assets`, `cdn.assets`, `cdn`, `cdn2`, `uploads` `.scratch.mit.edu` | `d.sni.global.fastly.net` |
| `backpack.scratch.mit.edu` | `b.sni.global.fastly.net` |
| `scratch`, `api`, `projects` `.scratch.mit.edu` | no CNAME (A records direct) |
| `clouddata.scratch.mit.edu` | no CNAME; generic AWS EC2 addresses |

The asset lanes sit behind Fastly's shared edge. Class 2 says that is latent
risk and **not** a reason to strip them — they are where the app's own bytes
live, and stripping them would both defeat the block and under-count the time
(they are 129 of the 242 proportional minutes). Equally, no `*.fastly.net`
artifact is pinned, since those rotate. The delegated-zone entry gets both
halves right in one line: the Scratch-branded names are in the set, the shared
Fastly names are not.

`clouddata.scratch.mit.edu` is on generic AWS EC2 rather than a CDN edge, so —
as `amazon-telemetry.yml` records for its own hosts — an address entering an
`eb_` set can later be reassigned to an unrelated AWS tenant. Bounded by the
same mechanism: `eb_`/`eb6_` sets are declared `flags dynamic,timeout` with
`timeout 1h` (`render.lua:1009-1018`) and `eb_refresh.lua` re-resolves every
`eb_` host on `eb_refresh_interval`, default 1800s (`wifihaven-agent:143`),
strictly below that timeout — so the exposure is the residual of one ageing
window. Not a reason to drop the host.

## Scratch mods and third-party players — excluded, with evidence

The issue asked for this to be decided explicitly rather than defaulted.

| candidate | HTTP / title | traffic (90d, all devices) | disposition |
| --- | --- | ---: | --- |
| `turbowarp.org` | 200 "TurboWarp - Run Scratch projects faster" | none | exclude |
| `packager.turbowarp.org` | 200 "TurboWarp Packager" | none | exclude |
| `penguinmod.com` | 200 "PenguinMod - Home" | none | exclude |
| `scratchjr.org` | 200 "ScratchJr - Home" | none | watch-item |
| `scratchfoundation.org` | no answer | none | exclude |

TurboWarp and PenguinMod are third-party Scratch MODS run by different
operators on different sites. They play Scratch projects, but folding them into
this template would bill another site's time to this app's budget — the
mis-attribution the issue warned about. Zero traffic in the sample, so there is
no evidence to override that. If either shows traffic later it gets its own
template, not an entry here.

ScratchJr is the Scratch Foundation's own tablet product for younger kids —
same family, separate apex, zero observed traffic. Watch-item, add only with
its own evidence.

The only off-domain host either Scratch page references is
`www.googletagmanager.com` (checked by fetching `scratch.mit.edu/` and
`scratch.mit.edu/projects/editor/` and grepping the HTML for hostnames). Shared
Google tag infrastructure, not Scratch's bytes — excluded.

## App ↔ blocklist overlap (#1983)

Re-verified this run: `grep -rniE 'mit\.edu|scratch'` over
`api/resources/blocklists/` returns nothing. No chosen host sits on a curated
list.

## Icon

`https://icons.duckduckgo.com/ip3/scratch.mit.edu.ico` returns HTTP 200 with a
real 4,286-byte `image/x-icon` — not the generic placeholder the service serves
on 404 (`mit.edu` itself 404s with a 1,478-byte PNG placeholder, so the
subdomain is the right lookup key here).
