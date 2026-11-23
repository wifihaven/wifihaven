# App-catalog pass — 2026-11-23 (#3015)

## Method

- `GET /api/devices`: kid-managed devices are profiles 5 (Arduino Nano R4,
  Arduino UNO R4, Quintus Chromebook, Quintus iPad), 6 (Prima iPad), 7
  (Octavius iPad), plus `ca:ef:a1:72:6a:a3` "Kid Laptop" (`profileId: null`,
  per the #2998 precedent that a device's name is as good a signal as its
  current profile assignment).
- Step 0: `recent-apexes?windowDays=30&limit=500` pulled sequentially (no
  `&`, no `xargs -P`) for the Octavius/Prima/Quintus-Chromebook/Quintus-iPad/
  Kid-Laptop MACs. Aggregated by apex across devices: 756 distinct apexes.
- Step 1: `usage-by-app?from=<day>&to=<day>` pulled sequentially, one day per
  request, for profiles 5/6/7 over 2026-11-17..22 (6 days × 3 profiles = 18
  calls). `/api/health` checked `{"status":"ok"}` throughout — no repeat of
  the #2965 DB-pool / heap incident.

## Byte-ranked sweep (Step 0) — top ~150 apexes by aggregate bytes

Every apex in the top 150 resolved to one of:

- Already templated (`lego.com`/`legogroup.io`, `jetbrains.com`,
  `arduino.cc`/`arduinocontent.cc`, `gdevelop.io`/`gdevelop-app.com`/
  `gd.games`, `mit.edu` (scratch), `khanacademy.org`/`kasandbox.org`/
  `kastatic.org`, `minecraft-services.net`/`minecraft.net`/`xboxlive.com`,
  `i-ready.com`, `codehs.com`, `freckle.com`, `follettdestiny.com`/
  `follettsoftware.com`, `virtualnerd.com`, `mathacademy.com`,
  `savvasrealize.com`, `classlink.com`, `duolingo.com`, `canva.com`,
  `1password.com`, `blocksi.net`, `thingiverse.com`/`instructables.com`/
  `tinkercad.com`, `rebrickable.com`, `poki`/`eaglercraft`/`crazygames`
  family).
- Google/Apple/Microsoft platform infra (`apple.com`, `cdn-apple.com`,
  `mzstatic.com`, `icloud.com`/`icloud-content.com`, `googlevideo.com`,
  `google.com`, `googleapis.com`, `gstatic.com`, `gvt1/2/3.com`,
  `googleusercontent.com`, `ytimg.com`, `googletagmanager.com`,
  `safebrowsing.apple`, `aaplimg.com`, `apple.news`) — skip per standing
  rule.
- Ad/RTB and tracking (`doubleclick.net`, `amazon-adsystem.com`,
  `googlesyndication.com`, `adtrafficquality.google`, `googlezip.net`,
  `unity3d.com` mediation, `2mdn.net`, `fastly-edge.com`,
  `adsafeprotected.com`, `pendo.io`, `casalemedia.com`, `rubiconproject.com`,
  `adsrvr.org`, `media.net`, `openx.net`, `pubmatic.com`,
  `clarity.ms`, `doubleverify.com`, `smartadserver.com`, `gumgum.com`,
  `flashtalking.com`, `3lift.com`, `onetag-sys.com`, `kargo.com`,
  `seedtag.com`, `liadm.com`, `adnxs.com`, `consensu.org`, `trustarc.com`) —
  skip.
- Shared corporate/CDN/analytics collateral (`githubusercontent.com`,
  `pythonhosted.org`, `pypi.org`, `quantummetric.com`,
  `app-analytics-services.com`, `nr-data.net`, `microsoft.com`, `office.net`,
  `live.com`, `cloudfront.net`, `cloudflare.com`, `shopify.com`,
  `discourse-cdn.com`, `akamaized.net`, `jsdelivr.net`, `adobe.io`,
  `azure.com`, `ggpht.com`) — skip.
- Previously-logged watch items, consistent with the prior pass's evidence
  doc (no growth signal to re-evaluate this week):
  `adeept.com` (3 hits, unchanged from #2998/#3010), `jrustonapps.info`
  (3 hits, unchanged), `scalibur.io` (7 hits, unchanged), `bronze.systems`
  (thin, unclear purpose per #2998 — that doc gives only a grouped "low hit
  counts" for this host, not a specific count to compare against).
  `svvsd.org` (19.9 MB/195 hits by byte count, all of it from the Quintus
  Chromebook) breaks down into two separately-decided subdomains: the shared
  district identity host `classlink.svvsd.org` is excluded by name in
  `classlink.yml`'s comment (#2962, excluded because it's a district-run
  host, not for volume reasons), while `ic.svvsd.org` (Infinite Campus) is a
  still-open watch item from #2943, held back there on a single 15-minute
  session. See the time-weighted sweep below for this week's data point on
  `ic.svvsd.org`.
- Shopping/parent-side (`amazon.com`, `aliexpress.com`/`.us`/`-media.com`,
  `mouldkingcorp.com` — Shopify-origin collateral, `twc.health`) — skip.

Two new thin candidates, neither clearing the bar:

- `t13.io` — only observed subdomain is `s2s.t13.io` (5.4 MB / 32 hits). The
  `s2s.` label is the standard "server-to-server" tracking-callback shape;
  web search found no independent confirmation of the brand beyond a
  third-party classifier describing `t13.io` as a URL-shortening service,
  which doesn't rule out ad-tech use of its subdomains. Thin, unidentified,
  single-subdomain — watch item, not templated.
- `skyboxct.com` (1.9 MB / 7 hits, no resolvable subdomains). Web search
  returned no matching record for this exact domain. Too thin and
  unidentifiable to classify — watch item.

## Time-weighted sweep (Step 1) — orphanHosts, profiles 5/6/7, 2026-11-17..22

Top ~100 orphan hosts by aggregate `proportionalSeconds` were entirely
Google/Apple/Microsoft platform infra (`www.google.com`, `ssl.gstatic.com`,
`lh3.googleusercontent.com`, `accounts.google.com`, `yt3.ggpht.com`, Apple's
`app-site-association.cdn-apple.com`/`init-p01md.apple.com`/
`weatherkit.apple.com`, Microsoft `self.events.data.microsoft.com`) and
ad/RTB/consent/analytics noise (`bam.nr-data.net`, `rubiconproject.com`,
`casalemedia.com`, `pubmatic.com`, `teads.tv`, `sparteo.com`, `criteo.com`,
`adsrvr.org`, `trustarc.com`/`onetrust.com`/`cookielaw.org`,
`dynatrace.com`, `hotjar.com`, `cloudflareinsights.com`, `unity3d.com`
telemetry, `demdex.net`, `tapad.com`, `shop.app`, `yotpo.com`,
`klaviyo.com`). No non-platform, non-ad-tech host cleared any meaningful
proportional-seconds threshold.

Notably: `svvsd.org` and its subdomains (`ic.`, `classlink.`, `password.`,
`www.`) do not appear anywhere in this 6-day time-weighted sweep at all, on
the profile (5, Quintus Chromebook) that carried all of this week's byte
volume for that apex — so for that device and window, the byte-table total
reads as infrequent bulk sync rather than sustained use. That is a narrower
claim than "confirmed bulk sync": this sweep is 6 days against the 30-day
byte window, and covers only profiles 5/6/7 (not the unassigned Kid Laptop,
though Kid Laptop carried none of this apex's bytes either, per the
per-device breakdown). It's a data point for `ic.svvsd.org` specifically —
consistent with, but not enough on its own to close, the existing #2943
watch item — not grounds to revisit the separate, volume-independent
`classlink.svvsd.org` exclusion in `classlink.yml` (#2962).

## Host-set coverage spot-check on high-byte already-templated apps

Diffed this week's observed `subdomains[]` (from `recent-apexes`) against the
`hosts:` list of the largest-byte already-templated apps whose apexes are
`lego.com`/`legogroup.io` (`lego.yml`), `jetbrains.com` (`jetbrains.yml`),
`arduino.cc`/`arduinocontent.cc` (`arduino.yml`), `gdevelop.io`/
`gdevelop-app.com` (`gdevelop.yml`), and `minecraft-services.net`
(`minecraft.yml`). All observed subdomains suffix-match an existing host
entry, **except one**:

- `assets.lego.com` (seen on 3 of 4 kid devices this week) is not covered by
  any of `lego.yml`'s ten host entries. Cert check: dedicated CloudFront
  distribution with its own cert (`CN=assets.lego.com`, Amazon-issued), on
  `18.154.101.x` — the same CloudFront range `lego.yml`'s own comment already
  notes `bff.kids-creations.legogroup.io` and `savvas-realize.yml`'s
  `api.savvasrealize.com` share (accepted Class-2 edge-sharing risk, not a
  collision). A bare GET on the root returns `HTTP 200` with the literal body
  `hello there` — not informative about scope.
  **Left as a watch item, not added**, for two reasons: (1) `recent-apexes`
  only gives apex-level byte/hit totals, not a per-subdomain breakdown, so
  `assets.lego.com`'s actual share of the `lego.com` apex's traffic (and
  whether it's even non-trivial) is unconfirmed; (2) its name ("assets") and
  generic root response don't rule out it being LEGO's general cross-site
  asset CDN rather than something Builder-specific — this app's host-set
  deliberately excludes the shop/marketing apex, and a general CDN host could
  be serving shop imagery as much as Builder imagery. Needs per-host traffic
  data (not available from this pass's endpoints) or a confirmed
  Builder-specific reference (e.g. found in the Builder app's own loaded
  page) before adding it with confidence.

## Conclusion

No new app template, no new blocklist entry, no confirmed host-set change.
Per the skill's Step 2 ("When in doubt, skip and say so"), this is a clean
no-op pass — same outcome class as #2129/#2058/#1922. Self-update (Step 6)
and this evidence doc ship as a tiny skill-only PR referencing #3015.
