# Adult/ads pass — the CAM4 pop-under chain, and the sweep's byte-rank blind spot (#2823)

> **REVISED after independent review. Read this first — it supersedes the
> "Addition 1" and "Shared-IP check" sections below, which are kept for the
> measurements but whose CONCLUSION was wrong.**
>
> 1. **`cam4tracking.com` was NOT added.** It is a genuine, uncovered gap (all
>    three checks below stand), but it is not blockable at the IP layer. The
>    reasoning that originally justified adding it does not survive:
>    - The `bl_<id>` `timeout 1h` is a property of the enforcement plane that
>      **every member of every `bl_` set shares**, so it cannot distinguish
>      this host from the 13 Cloudflare/CloudFront hops this same pass refused
>      to add.
>    - "The apex is dedicated (MojoHost)" is irrelevant, because **nothing in
>      the household resolves the bare apex.** Only `track.cam4tracking.com` is
>      ever observed, so the set would be armed with shared CloudFront edges
>      and the dedicated address would never enter it.
>    - "The domain carries no legitimate traffic" is also irrelevant: the #2601
>      harm is to *other tenants of the pool*.
>    Its absence is now pinned in `BundledBlocklistsSpec`, which is how #2601
>    handled the same situation. #2377 is the unblock.
> 2. **`clickpathworks.com` was NOT added** — one self-derived signal (a CNAME
>    into `ak-is2.net` plus an inference from that domain's subdomain naming),
>    below the two-independent-signals bar.
> 3. **Added: `unhappyweakness.com`, `realizationnewestfangs.com` → `ads`**,
>    the only two chain hosts on dedicated hosting.
> 4. **Neither traffic surface is complete.** `recent-apexes` missed the chain
>    (byte truncation, below) — but `/api/logs` missed
>    `track.cam4tracking.com` on Rachel iPhone, which `recent-apexes` reports
>    at 77,198 bytes / 2 hits over the same 30 days while
>    `/api/logs?mac=1e:45:b6:68:24:b3&domain=cam4&hours=2160` returns **zero**
>    rows. They read different tables (`traffic_reports` vs
>    `connection_events`); the cause of the disagreement is **not established**
>    — differing retention is plausible but unverified. Sweep both; treat a hit
>    on either as real.

Prod, 2026-10-02/03, read-only. **Two** additions, both to `ads`:
`unhappyweakness.com` and `realizationnewestfangs.com`. `cam4tracking.com` and
`clickpathworks.com` were investigated and **held out** — see the revision
banner above for why. Two findings matter more than the entries:

1. **Why seven prior passes reported the adult sweep empty.** Not because the
   host was new — because the sweep surface truncates by bytes, and this whole
   class of host is low-byte by construction. Of the 16 chain hops below
   (17 apexes in all, counting `cam4tracking.com`), **15 are invisible to
   it** — only `waifuoverlord.com` cleared a byte floor.
2. **Most of the pop-under chain cannot be blocked at all** with IP-layer
   enforcement: it hides behind shared Cloudflare and CloudFront frontends, so
   blocking it would reproduce #2601. That is the honest answer to "make the
   pop-ups stop," and it is a product gap (#2377), not a list gap.

## `cam4tracking.com` → investigated, HELD OUT (originally filed here as "Addition 1")

| apex | category | bytes (30d) | hits | what it is |
|---|---|---|---|---|
| `cam4tracking.com` | `adult` | 77,198 | 2 | CAM4's affiliate/redirect tracking apex. Registrant org "Domain Trading" (registrar 101domain, created 2014-11-27); apex A record `208.122.213.154` → `NetName: MOJOHOST`, `208.122.192.0/19` — adult-industry hosting, not a shared pool. Only host observed in traffic: `track.cam4tracking.com`. |

### Why it is a genuine gap

Three independent checks, all read from prod:

1. **Not in any curated list.** `grep -r 'cam4' api/resources/blocklists/*.yml`
   returns exactly one line — `adult.yml:22: - cam4.com`. `cam4tracking.com`
   is a **separate registrable domain**, so the `cam4.com` entry does not
   suffix-match it (blocklist hosts are suffix-matched, and
   `track.cam4tracking.com` does not end in `.cam4.com`).
2. **Not in `adult-extended` either.** Fetched the live StevenBlack
   `alternates/porn-only` feed (76,793 hosts, sentinel `pornhub.com` present):

   | host | exact match | subdomain matches |
   |---|---|---|
   | `cam4.com` | 1 | 8 |
   | `xcdnpro.com` | 1 | 4 |
   | **`cam4tracking.com`** | **0** | **0** |

   Absent entirely — the highest-value class of gap, since no upstream source
   covers it for any profile.
3. **`PolicyService` agrees it is allowed.** `GET /api/blocked` re-runs
   `decide(mac, host)`:

   ```
   mac=52:1a:60:d8:4e:32 (Sameer Mac, profile 2)
     www.cam4.com                -> {"blocked":true}     <- curated `adult`
     cam4-campaigns.xcdnpro.com  -> {"blocked":true}     <- `adult-extended`
     track.cam4tracking.com      -> {"blocked":false}    <- THE GAP
   mac=1e:45:b6:68:24:b3 (Rachel iPhone, profile 4)
     track.cam4tracking.com      -> {"blocked":false}    <- THE GAP
   ```

   Controls on both MACs (`example.com`, `wikipedia.org`,
   `nonsense-abc123-xyz.com` → `blocked:false`; `pornhub.com` →
   `blocked:true`) rule out a whole-MAC block confounding the reads.

### Classification: `adult`, not `ads`

The issue correctly flagged that pop-unders are usually served by ad/redirect
networks, and that `cam4tracking.com` is a tracking apex rather than a content
apex — which would ordinarily argue for `ads.yml`. It goes in `adult` anyway,
on function rather than on the word "tracking":

- It is **not a network**. An `ads.yml` entry is infrastructure that many
  unrelated publishers embed (`doubleclick.net`, `pubmatic.com`,
  `adkernel.com`). `cam4tracking.com` is single-brand: it exists to attribute
  CAM4's own affiliate traffic to CAM4. Nothing else rides it.
- Its **companion hosts are already in `adult`**. `cam4.com` is curated here
  and `xcdnpro.com` (CAM4's campaign CDN, `cam4-campaigns.xcdnpro.com`) is
  covered by `adult-extended`. Splitting one brand's three apexes across two
  categories would mean a profile with `adult` on but `ads` off keeps the
  redirect that starts the pop-under.
- Filing it under `ads` would be the actual category-drift risk: `ads` is
  enabled on 6 of 7 prod profiles **and** is the category whose description
  promises third-party ad infrastructure. A brand-specific adult redirect is
  not that.

This is the inverse of the standing `axon.ai` / `trygravity.ai` / `mediayo.ai`
trap (#2599/#2742/#2756/#2759), where an AI-branded TLD hid an ad network. Here
an ad-shaped word (`tracking`) hides a single-brand adult domain. The test is
the same in both directions: classify on what the apex does, not on the string.

### Shared-IP check (#2369/#2601) — the measurement, and why the "accepted residual" conclusion was wrong

`cam4tracking.com`'s apex is dedicated (MojoHost, above). Its observed host is
not:

```
track.cam4tracking.com. CNAME dw5ee7oih1tmy.cloudfront.net.
                        A     99.84.118.3  .41  .50  .69   (stable over 3 samples)
```

Per the #2601 rule — enforcement matches destination IP, so blocking a host on
a shared frontend blocks everything on that frontend — this needed measuring,
not assuming. Resolved all **226** `cloudfront.net` hostnames this household
actually observed in the 30-day window and compared:

- **6 of 226** land in the same `99.84.118.0/24` edge block as
  `track.cam4tracking.com`: `d1dwhf283nul1c`, `d248djf5mc6iku`,
  `d2763msf1wgvw2`, `d3gq3s1iyyx31w`, `dn1ydp6r0kcy1`, `dp8hsntg6do36`.
- **No exact-address collision today.** cam4tracking holds `.3 .41 .50 .69`;
  the six innocent distributions hold 21 other addresses in the same /24.
- **But the pool demonstrably reuses addresses across distributions**: `.79`
  serves both `d2763msf1wgvw2` and `d3gq3s1iyyx31w`; `.83` serves both
  `d1dwhf283nul1c` and `dn1ydp6r0kcy1`; `.34` serves both `d2763msf1wgvw2`
  and `dn1ydp6r0kcy1`. So an exact collision is a rotation away, not
  impossible.

**Why this is still a different call from #2601.** There the pool was a fixed
anycast set — four to eight addresses served *everything*, so the collateral
was immediate, total and permanent, and the hosts had to leave every curated
list. Here:

- A collision requires an innocent distribution's *current* four addresses to
  overlap cam4tracking's *current* four, **inside one /24** — not guaranteed,
  and not all-or-nothing.
- `bl_<id>` / `bl6_<id>` are declared `flags dynamic,timeout` with
  `timeout 1h` (`openwrt/files/usr/lib/lua/wifihaven/render.lua:1107-1118`,
  pinned by `openwrt/test/render_spec.lua:1642-1660`). Any collision **ages
  out within the hour** and self-heals. #2601's anycast collision never did.
- `cloudfront.net` is **not** in `global.extraAllowed` (16 entries, read from
  the prod snapshot), so there is no allow-carve silently neutering the block
  either way — the trade is the one described here and nothing else.

~~The trade was taken because `cam4tracking.com` carries no legitimate traffic
at all, so the only thing the block can cost is an intermittent, self-healing
CloudFront blip.~~ **Struck — this reasoning is wrong and the entry was not
added.** The #2601 harm is to *other tenants of the pool*, so the blocked
domain's own legitimacy has no bearing on it. See the revision banner. Residual exposure tracked in
[#2826](https://github.com/wifihaven/wifihaven/issues/2826).

Deliberately **not** added, for the same shared-pool reason plus redundancy:
nothing was added for `xcdnpro.com` (already blocked via `adult-extended` on
every profile that has it, and confirmed `blocked:true` on both MACs tested).

### App ↔ blocklist overlap (#1983)

Clean. No app template or prod app references `cam4` or `xcdnpro`:
`grep -rliE 'xcdnpro|cam4' api/resources/app_templates/` → no matches; the
53 prod apps from `GET /api/apps` carry no matching host. So the addition
cannot collide with an Allowed-mode app's `extraAllowed` carve.

## The redirect chain → `ads` — 2 added, 1 held out

The issue asked for "more of the same class — pop-up / pop-under sites the
operator does not want appearing." Those are not adult sites at all. Enumerating
**every** host Sameer Mac resolved in the burst windows around each `cam4` hit
(`GET /api/logs?mac=…&until=…&hours=1`) shows the actual mechanism.

### 2026-09-12, 01:24:11 → 01:26:09Z

```
01:23:10  d.dropbox.com
01:24:11  piratebay.com                 <- LURE
01:24:11  www.powerlineblog.com
01:24:11  filter.clickpathworks.com     <- TDS hop
01:24:11  xml.clickpathworks.com        <- TDS hop
01:24:11  910692.toplakehorizon.com
01:24:11  buying.expert
01:24:19  unhappyweakness.com           <- TDS hop  (x3, 01:24:19-22)
01:24:22  torrindex.net                 <- LURE
01:24:23  thepiratebay.org              <- LURE
01:24:29  astoopolitet.org
01:24:29  cdn.show-sb.com
01:24:29  ghabovethec.info
01:24:29  herefwukou.org
01:24:29  itefullofeedshen.com
01:24:29  moonlighthathel.org
01:24:29  sowve.com
01:25:01  static.nresystems.com
01:25:04  track.cam4tracking.com        <- LANDING begins
01:25:04  ws.cam4.com
01:25:06  static.cam4.com
01:25:07  stackvaults-media.xcdnpro.com
01:26:09  cam4-campaigns.xcdnpro.com
```

### 2026-10-03, 02:27:56 → 02:29:47Z (recurrence, 3 weeks later)

```
02:27:56  api.cam4.com / static.cam4.com
02:28:06  cam4-campaigns.xcdnpro.com
02:29:06  b.waifuoverlord.com
02:29:22  unhappyweakness.com           <- SAME host as 09-12
02:29:23  realizationnewestfangs.com
02:29:24  cdn.show-sb.com               <- SAME host as 09-12
02:29:25  cdn.holdbitter.com
02:29:47  cdn.storageimagedisplay.com
```

`unhappyweakness.com` and `show-sb.com` appear in **both** sessions three weeks
apart — persistent infrastructure, not a one-off.

### What the fleet is

`realizationnewestfangs.com` is named explicitly in [Augur Security's write-up](https://www.augursecurity.com/post/multi-hop-malvertising-infrastructure-exposed) of
a **fast-flux TDS (traffic distribution system) malvertising network**: ~300
auto-generated compound-word domains over 18 IPs in six /24 subnets, serving
obfuscated JavaScript through multi-hop redirect chains and terminating at the
MGID ad network. The write-up lists `172.240.x.x` among the pool's ranges —
which is exactly where this household's resolutions land, an independent
fingerprint match to the measurements below. Every other compound-word apex in
the chain above (`astoopolitet.org`, `ghabovethec.info`, `herefwukou.org`,
`itefullofeedshen.com`, `moonlighthathel.org`, `holdbitter.com`,
`storageimagedisplay.com`, `toplakehorizon.com`) has the same auto-generated
shape and is almost certainly the same fleet.

The chain's terminal network, **`mgid.com`, is already curated** (`ads.yml:166`)
and confirmed `blocked:true` on prod — so the end of the chain was already
covered; it was the hops that were not.

### Added — 2 apexes (and one held out)

| apex | category | observed | hosting | why it is safe to add |
|---|---|---|---|---|
| `unhappyweakness.com` | `ads` | 2026-09-12 (x3) and 2026-10-03 | Servers.com `172.240.108.x` / `172.240.127.x` + `172.255.141.4` (9 addresses) | Dedicated bulk hosting, **not** a shared CDN frontend; none of its 9 addresses appear in the 785 CDN addresses this household resolves. Gridinsoft 1/100 trust score, multiple malware/phishing blacklist detections. |
| `realizationnewestfangs.com` | `ads` | 2026-10-03 | **identical 9-address set** to the above | Same operator as `unhappyweakness.com`: identical address set, shared nameservers `NS1/NS2.PUBLICDNSSERVICE.COM`, both eNom + privacy proxy + registrant country CZ. Named in the Augur Security fast-flux write-up. Also present in StevenBlack `ads-extended` (exact apex match) — an independent second curator. |

**Held out, despite dedicated hosting:** `clickpathworks.com` (2026-09-12, via
`filter.` and `xml.`; one dedicated Webair address `173.239.53.20` the
household's traffic does not otherwise touch). It is a branded front for the
`ak-is2.net` ad-serving network — both observed hosts CNAME into it
(`giantpanda.fs.ak-is2.net`, `giantpanda.xml.ak-is2.net`), and that domain's
own subdomains are `cpm.`, `rtb-as.`, `*.xml.` ad feeds. But that is **one**
self-derived signal: a DNS observation plus an inference from subdomain naming,
with no independent curator and no reputation datum. Below the
two-independent-signals bar the `axon.ai` / `trygravity.ai` false positives
established, so it is pinned absent rather than added. It is therefore the one
held-out host that is **not** CDN-fronted: 14 of the 15 held out are, it is
not.

**Classified `ads`, not `adult`, deliberately.** These are a redirect/TDS
network and an RTB ad-feed network. They happen to land on an adult site in this
chain, but the same hops serve whatever the TDS is paid to serve — filing them
under `adult` would make that category mean "things that led to porn once,"
which is not its description, and would leave them unblocked for a profile
running `ads` but not `adult`. A malvertising flag does not disqualify an
ads-category add (the #2122 `gamaibids.com` precedent); and `malware.yml` is
URL-sourced from URLhaus, so it cannot be hand-curated regardless.

### Held out — 13 apexes, all on shared frontends

This is the substantive finding. **Most of the chain is unblockable** under
IP-layer enforcement, for the #2601 reason: the nftables set holds the
*addresses* a host resolved to, so blocking a host on a shared frontend blocks
every other tenant on that frontend.

| apex | resolves to | disposition |
|---|---|---|
| `itefullofeedshen.com` | `18.238.176.47/80/89/120` | **SKIP — exact collision measured.** `18.238.176.120` is *also* currently serving one of this household's own CloudFront distributions. This is #2601 reproduced exactly: blocking it drops that distribution for every MAC on the category. |
| `moonlighthathel.org` | `13.226.251.40/50/86/109` | SKIP — CloudFront `13.226.251.0/24`, a /24 that 20 of this household's own CloudFront resolutions also use. No exact collision at this instant; one rotation away. |
| `ghabovethec.info` | `18.238.136.x` | SKIP — CloudFront /24 also used by household traffic (8 resolutions). |
| `herefwukou.org` | `99.84.105.x` | SKIP — CloudFront /24 also used by household traffic (12 resolutions). |
| `buying.expert` | `13.226.251.x` | SKIP — same CloudFront /24 as `moonlighthathel.org`. |
| `toplakehorizon.com` | `172.66.42.218`, `172.66.41.38` | SKIP — Cloudflare shared frontend. |
| `astoopolitet.org` | `172.67.194.203`, `104.21.52.38` | SKIP — Cloudflare shared frontend. |
| `show-sb.com` | `172.67.170.115`, `104.21.95.140` | SKIP — Cloudflare shared frontend, despite appearing in **both** burst sessions. |
| `holdbitter.com` | `172.67.156.247`, `104.21.89.77` | SKIP — Cloudflare shared frontend. |
| `storageimagedisplay.com` | `172.67.69.181`, `104.26.4.116/5.116` | SKIP — Cloudflare shared frontend. |
| `waifuoverlord.com` | `172.67.136.27`, `104.21.86.191` | SKIP — Cloudflare shared frontend. (The only chain apex big enough to clear the apex sweep: 915,928 bytes / 7 hits.) |
| `sowve.com` | `104.18.15.30`, `104.18.14.30` | SKIP — Cloudflare shared frontend. |
| `nresystems.com` | `104.18.19.137`, `104.18.18.137` | SKIP — Cloudflare shared frontend. |

Cloudflare's `104.18.x` / `104.21.x` / `104.26.x` / `172.66.x` / `172.67.x`
addresses front millions of unrelated sites; an address-level drop on any of
them is indefensible collateral. None of these were added on any confidence
level — the skip is structural, not a judgement about what the domains are.

**Consequence the operator should know: the pop-unders will keep arriving.**
Fourteen of the 15 held-out hosts sit behind Cloudflare or CloudFront, so the two
entries added here break two hops of a chain that has many more, drawn from a
fleet of ~300 rotating domains. Curated entries are not the fix for this class.
[#2377](https://github.com/wifihaven/wifihaven/issues/2377) (SNI-level
disambiguation) is what makes a Cloudflare-fronted host blockable at all;
blocking the **lure** sites (`thepiratebay.org`, `torrindex.net`,
`piratebay.com`) is the only thing available today that actually stops the chain
starting, and that is a policy decision for the operator, not a blocklist pass.
Tracked in [#2826](https://github.com/wifihaven/wifihaven/issues/2826) (the
Cloudflare/CloudFront shared-frontend question) and
[#2827](https://github.com/wifihaven/wifihaven/issues/2827) (the sweep blind
spot).

## The methodology question — the sweep has a real gap, and this host sat in it

Seven prior passes (#2212, #2348, #2599, #2729, #2742, #2756, #2759) reported
the adult sweep empty, and #2759's keyword list contains `cam4` — which
`cam4tracking.com` contains as a substring. The host is **not** simply new:
its first connection event is **2026-09-12T01:25:04Z**, three days before
#2792's run on 2026-09-15.

The gap is in the surface, not the keyword list.

### The surface prior passes used truncates by bytes

`GET /api/devices/{mac}/recent-apexes` is fed from `traffic_reports` and, in
`api/src/routes/UsageRoutes.scala`:

- `limit` is clamped to **500 max** (`.max(1).min(500)`, line 108-113) — a
  caller cannot ask for more;
- rows are sorted `-bytes` then `.take(limit)` (lines 131-134).

So each device returns only its **top 500 apexes by bytes**. Measured this
run, **6 of 30 devices hit that cap**, and their byte floors are high:

| device | apexes returned | bytes at the cut |
|---|---|---|
| Sameer iPhone | 500 (capped) | 517,785 |
| **Sameer Mac** | **500 (capped)** | **506,130** |
| Test OpenWRT | 500 (capped) | 195,215 |
| Rachel Mac | 500 (capped) | 84,021 |
| Rachel iPhone | 500 (capped) | 26,497 |
| Kid Laptop | 500 (capped) | 12,674 |

`cam4tracking.com` is **absent from Sameer Mac's 500 rows entirely** — and
Sameer Mac is the device that generated **70 of the 72** `cam4*` connection
events (the other 2 are `www.cam4.com` on `Test OpenWRT`). Its traffic there is
by definition under that device's **506,130-byte** cut; the sweep cannot report
how far under, because the row is gone. So on the device where the behaviour
actually happened, the apex surface cannot see this host at all.

The only reason it surfaced this run is a quieter device: on **Rachel iPhone**
the floor is 26,497 bytes, and `cam4tracking.com` (77,198 bytes / 2 hits there)
landed at **rank 401 of 500**. Had that device been slightly busier, this pass
would have reported "adult: 0 gaps" for the eighth time.

**The decisive measurement: the truncation hid the entire chain, not one host.**
Of the **16** chain hops enumerated across the sections above (17 apexes counting `cam4tracking.com`), **15 are absent
from the 30-day `recent-apexes` pull across all 30 devices entirely** — not
ranked low, absent. The single exception is `waifuoverlord.com` (915,928 bytes),
the only one big enough to clear Sameer Mac's 506 KB cut. So the surface every
prior pass used could not have found `clickpathworks.com`,
`unhappyweakness.com`, `realizationnewestfangs.com` or the
Cloudflare/CloudFront-fronted hops at any keyword list, however good. `cam4tracking.com`
surfaced at all only because Rachel iPhone's floor happens to be 26,497 bytes
and it landed at rank 401/500 there.

**This is structural, and it is worst exactly where it matters.** Pop-unders,
redirect hops and tracking pixels are low-byte by construction, and the
truncation floor rises with device busyness — so the sweep is blind to this
whole class on precisely the heavily-used devices that browse enough to attract
it. Byte-rank truncation is an anti-filter for this category.

Two secondary contributors, both real but not the cause here:

- `windowDays` is clamped to **30 max** (`UsageRoutes.scala:102-107`), so the
  apex surface cannot look back further than a month however the caller asks.
- `recent-apexes` requires `(bytes_in + bytes_out) > 0` from `traffic_reports`
  (`api/src/db/Repos.scala:3303`), so a host that is **already blocked**
  contributes no bytes and vanishes from the sweep. Harmless for finding gaps
  (a blocked host is not a gap), but it means the sweep cannot be used to
  confirm that a previously-added entry is still being hit.

### The fix: `/api/logs` is the keyword surface, `recent-apexes` is the ranking surface

`GET /api/logs?domain=<substring>&hours=<n>` reads `connection_events` and is
not subject to any of the above:

- `domain` is a server-side substring match —
  `COALESCE(ce.resolved_host_value, ce.host_value) ILIKE '%<d>%'`
  (`api/src/db/Repos.scala:3579-3583`) — so matching happens **before** any
  limit, not after a byte-rank cut;
- `hours` has **no cap** (`Routes.scala:2039`), unlike `windowDays`;
- rows include **blocked** events (`blocked` filter, `Routes.scala:2036`), so
  coverage can be confirmed as well as gaps found;
- it returns the per-row `mac` / `profileId` / `profileName` / `reason`, which
  is what answers "which profile is hitting this" directly.

Run against this host it returns 72 rows — 70 on Sameer Mac, 2 on Test
OpenWRT. Note it returns NO Rachel iPhone row, although `recent-apexes`
reports `track.cam4tracking.com` there; see correction 4 above:

| host | device | profile | blocked | n | first | last |
|---|---|---|---|---|---|---|
| `static.cam4.com` | Sameer Mac | 2 Sameer | false | 23 | 2026-09-12T01:25:06Z | 2026-10-03T02:28:14Z |
| `ws.cam4.com` | Sameer Mac | 2 Sameer | false | 15 | 2026-09-12T01:25:04Z | 2026-09-15T12:49:47Z |
| `www.cam4.com` | Sameer Mac | 2 Sameer | false | 15 | 2026-09-15T02:21:24Z | 2026-09-27T02:12:06Z |
| `cam4-campaigns.xcdnpro.com` | Sameer Mac | 2 Sameer | false | 6 | 2026-09-12T01:26:09Z | 2026-10-03T02:28:25Z |
| `api.cam4.com` | Sameer Mac | 2 Sameer | false | 4 | 2026-10-03T02:27:56Z | 2026-10-03T02:28:07Z |
| `landers.cam4.com` | Sameer Mac | 2 Sameer | false | 4 | 2026-09-27T02:12:06Z | 2026-09-27T02:12:06Z |
| **`track.cam4tracking.com`** | **Sameer Mac** | **2 Sameer** | false | **3** | **2026-09-12T01:25:04Z** | 2026-09-27T02:12:06Z |
| `www.cam4.com` | Test OpenWRT | 3 Family | false | 2 | 2026-09-15T13:20:06Z | 2026-09-15T13:34:26Z |

`track.cam4tracking.com` is the **first host in the chain** on 2026-09-12
(01:25:04Z, tied with `ws.cam4.com`), consistent with a tracking/redirect hop
rather than a content fetch — the apex earns its classification from the
sequence, not just the name.

The `blocked:false` on the 2026-10-03 rows is not evidence the category was
off: profile 2's `adult` + `adult-extended` were enabled between those events
and the `GET /api/blocked` reads above, which now return `blocked:true` for
`www.cam4.com` on that MAC. See the policy-state section below.

The skill's Step 0/1 are updated to run the keyword sweep through `/api/logs`
and use `recent-apexes` only for byte ranking.

## Which profile is affected, and whether these additions are sufficient

**No category needs enabling — but the additions are not sufficient to stop the
symptom**, for the shared-frontend reason in the chain section above. Both
halves of that matter, and the first contradicts the issue's triage table, which
was read mid-change.

Every `cam4*` event is on **Sameer Mac (`52:1a:60:d8:4e:32`), profile 2
"Sameer"** — plus two `www.cam4.com` events on `Test OpenWRT` (profile 3
Family), which is the operator's own test-router device, not a browsing client.

The triage table recorded profile 2 as blocking **nothing** and profiles 3/4 as
`malware` only. That is no longer true. Live prod at 2026-10-03T02:48:40Z, read
from `GET /api/profiles` **and** cross-checked against the authoritative router
snapshot (`GET /api/admin/snapshot` → `profiles.<id>.rules.blocklistIds`), both
agreeing:

| profile | `adult` | `adult-extended` | all categories |
|---|---|---|---|
| 1 Kids | Y | Y | adult, gambling, malware, games, ai, adult-extended, ads, social-extended, social-media |
| 2 **Sameer** | **Y** | **Y** | ads, adult-extended, gambling, malware, games, adult |
| 3 Family | n | n | malware |
| 4 **Rachel** | **Y** | **Y** | malware, adult, adult-extended, ads |
| 5 Quintus | Y | Y | (full set) |
| 6 Prima | Y | Y | (full set + ads-extended) |
| 7 Octavius | Y | Y | (full set + ads-extended) |

Profiles 2 and 4 both gained adult coverage **during this session** — an
earlier read in the same session showed profile 4 as `[malware, adult]` before
`adult-extended` and `ads` appeared minutes later, so the operator was editing
policy live while this pass ran.

Consequences:

- **The curated addition takes effect on the affected device.** Profile 2 has
  `adult` enabled, so `cam4tracking.com` starts being enforced on Sameer Mac as
  soon as this ships. Nothing for the operator to enable.
- **Profile 3 "Family" is the only profile without `adult`**, and it holds no
  browsing clients — Sonos units, printers, a dishwasher, a thermostat, a
  garage-door opener, a NAS, a Plex server, an Arduino, a sprinkler controller,
  a Skylight calendar, a Lutron bridge, and `Test OpenWRT`. Enabling a category
  there would not change any human's experience, and it is the operator's call
  regardless. No recommendation to change it.
- **Profile 4 "Rachel" is the profile that most needs curated entries**, and
  this is worth knowing for future passes: it did not have `adult-extended`
  until mid-session, so for a window the curated 21-entry list was its *only*
  adult coverage. Curated additions carry more weight on a profile running
  `adult` without the extended feed.

## Other categories

No other category was swept. The pass was scoped to the adult / pop-under class
the issue asked for; `ai`, `gambling`, `games` and `social-media` were last
swept in #2792 and are not re-reported here.

## Prod load — an incident this pass caused, and what it means for the method

Running the keyword sweep as **10 concurrent**
`/api/logs?domain=…&hours=720` requests put prod into **HTTP 502 for roughly
two minutes** (first observed 02:55Z, `200` again at 02:58:47Z after the jobs
were killed, with a second short window after that). Each of those requests is
an unanchored `ILIKE '%…%'` over 30 days of `connection_events` that no index
can serve, and ten at once exhausted the API. Prod recovered on its own within
~15s of the load stopping, and no data was written at any point.

This is a constraint on the method, not just an incident: **the `/api/logs`
keyword surface is the correct one but is expensive — run it serially, one
keyword at a time.** The broad 62-keyword sweep was abandoned rather than
retried, so this pass makes no claim to have swept the full adult keyword
vocabulary. What it does claim is narrower and better evidenced: the burst-window
enumeration around the known `cam4` hits, which is one query per burst and
returns the whole chain at once. That is the technique a future pass should
reach for first for this class — see
[#2828](https://github.com/wifihaven/wifihaven/issues/2828).

## Method notes

- Curated-set membership checked against **all six** inline category files, not
  just `adult`, per the #2742 learning. Extraction smoke-tested against
  sentinel `cam4.com` before being trusted.
- Candidate history grepped against `evidence/*.md` before any identity search,
  per the #2756 learning. `cam4tracking.com` appears in none of them;
  `cam4.com` appears in #2792's "already curated" row.
- `GET /api/logs` response shape: the rows are under **`.rows`**, not `.logs`
  or `.items`. A first probe using `.logs//.items//[]` returned `length 0` for
  every keyword — a silent all-negative of exactly the class the #2122 macOS
  `\s` lesson warns about. Caught by smoke-testing the shape against a
  known-present sentinel (`domain=google`) before trusting any zero.
- All prod access read-only. No policy writes (#2630). Credentials sourced from
  the operator's local memory files, never echoed.
