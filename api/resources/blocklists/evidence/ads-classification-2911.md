# Blocklist pass — ads classification evidence (#2911)

30-day prod `recent-apexes` sweep, all 30 devices, run 2026-10-13 (1,823
unique apexes observed). Also ran a serial `/api/logs` keyword sweep over
`hours=720` for nine common adult-site substrings (`chaturbat`, `livejasmin`,
`myfreecams`, `stripchat`, `bongacams`, `xvideos`, `xhamster`, `brazzers`,
`onlyfans`). All nine returned zero rows, matching the history of adult passes
with no new signal (#2212, #2503).

Every candidate was checked against `evidence/*.md` history (not just the
current `.yml`) and against all six curated category host sets (ads, adult,
ai, gambling, games, social-media) before being treated as a new gap.

## Observed hosts and where they resolve

The #2823 rule judges the host that actually appears in traffic, not the apex.
Observed hosts are every distinct `host.value` from `/api/logs?domain=<name>`
over `hours=2160` (90 days). Resolution is `dig +short A` on 2026-10-13.

| apex | observed hosts (rows) | observed host resolves to | unobserved siblings |
|---|---|---|---|
| `bttrack.com` | `bttrack.com` (70) | dedicated, two blocks seen on 2026-10-13: 192.132.33.67-69 (whois Bidtellect Inc.) and 64.38.119.42-44 (whois BroadbandONE) | `cdn.`/`video.bttrack.com` → Akamai (`a1093.dscb.akamai.net`, 23.45.123.x) |
| `loudecho.ai` | `cs.loudecho.ai` (1) | 88.214.255.112, dedicated | bare `loudecho.ai` → Cloudflare (104.21.x/172.67.x) |
| `theagenticx.ai` | `sync.theagenticx.ai` (6), **`theagenticx.ai` (1)** | sync: 172.240.45.49; **bare apex: Cloudflare** (172.67.x/104.21.x) | — |

## ads.yml — 2 additions

| apex | bytes | hits | what it is |
|---|---|---|---|
| `bttrack.com` | 816,324 | 91 | Bidtellect, a US native-advertising RTB exchange. Netify lists `api.`/`cdn.`/`video.bttrack.com` as Bidtellect infra; Cookiepedia classifies its cookies as Targeting/Advertising; AlienVault OTX logs a `/pixel/cookiesync` endpoint. The "bt" prefix resembles the unrelated open-source `bttrack` BitTorrent-tracker tool, and one automated classifier (urlert.com) labels the domain a tracker on that basis, but the owner is Bidtellect. It was observed firing inside a dense RTB/prebid burst on an ad-heavy publisher page, not alongside this household's actual BT-tracker traffic, which came from a different device. |
| `loudecho.ai` | 9,611 | 2 | LoudEcho, an agentic AI ad buyer. Its own site describes an ad platform that buys media and generates creative for marketers and publishers, "not consumers". Another `.ai`-TLD ad network (`axon.ai`/`programmaticx.ai`/`nexverse.ai` class), so it is filed in `ads.yml`, not `ai.yml`. |

**Residual risk, accepted and recorded in `ads.yml`.** An apex entry
suffix-matches every subdomain. If a device ever resolves `cdn.bttrack.com`,
`video.bttrack.com`, or the bare `loudecho.ai`, shared Akamai or Cloudflare
edges enter `bl_ads`. None of those was observed in 90 days. If one appears,
the entry should come out and its absence be pinned, as `ebayadservices.com`
was (#2816).

**Reversal of #2836.** #2836 held `loudecho.ai` out as Cloudflare-fronted. That
judged the bare apex, which was never observed. The observed host is
`cs.loudecho.ai`, on a dedicated address, so this pass adds it.

### Held out / skipped this pass (ads)

- `theagenticx.ai` (11,008B/1 hit in `recent-apexes`). Confirmed ad tech: its
  own homepage calls it "a true agentic advertising platform" for publishers
  and DSPs. Held out because the bare apex was observed once and resolves to
  Cloudflare, an unconditional #2823 skip. Its absence is pinned in
  `BundledBlocklistsSpec`. It was briefly added in this PR and removed after
  the independent review prompted the observed-host check.
- `syncingbridge.com` (548,551B/42 hits). Re-observed, still no confirmed
  identity (same as #2836). ScamAdviser shows a recently registered domain; a
  Joe Sandbox scan rated a URL clean; no source names an owner. It co-occurs
  with `bttrack.com` on the same devices within minutes, which is suggestive
  but not identity confirmation.
- `delivrdsp.ai` (13,081B/1 hit). Self-descriptive "Deliver DSP" name, but the
  site failed TLS, no source confirms a company, and it is absent from the
  `ads-extended` feed. That is below the two-signal bar `inhousedsp.com`
  needed. It had zero `/api/logs` rows over `hours=744`, consistent with the
  #2823 finding that the two surfaces disagree; a `recent-apexes` hit still
  counts, it is just thin.
- `linkedin-ei.com` (134,702B/24 hits). LinkedIn Corporation's own domain
  (whois registrant org "LinkedIn Corporation", MarkMonitor, created 2012); the
  bare apex resolves to a dedicated Microsoft address. The only observed host,
  `rum22.perf.linkedin-ei.com`, a RUM endpoint, answers on Cloudflare
  (104.18.16.118/104.18.17.118). Confirmed ownership does not clear the #2823
  rule.
- This week's BitTorrent-tracker traffic (`demonii.com`, `opentrackr.org`,
  `desync.com`, `glotorrents.pw`, `bittor.pw`, `coppersurfer.tk`,
  `popcorn-tracker.org`, `internetwarriors.net`, `rarbg.to`) all came from one
  device that is not either of the devices that hit `bttrack.com`. These are
  standing skips; listed only because a co-location with `bttrack.com` was
  briefly suspected and ruled out.

### Correction to an earlier evidence doc

`multi-category-classification-2836.md:50` lists `theagenticx.ai` as "already
curated in `ads.yml`". It was not, on `main` before this PR or after. Do not
rely on that line.

The `ads-extended` membership claim for `bttrack.com` is from the StevenBlack
`hosts` file fetched 2026-10-13 (apex plus `api.`, `cdn.`, `video.`); the feed
is fetched at API startup and is not in the repo.

## adult.yml / gambling.yml — no changes

Zero hits for adult on both `recent-apexes` and the `/api/logs` keyword sweep.
The gambling apexes seen in traffic (`stake.us`, `betrivers.com`) are already
curated; the only other `bet` match, `betweendigital.com`, is an SSP already in
`ads.yml` (#2503).

## ai.yml — no additions

The `.ai`-TLD sweep surfaced several candidates. None was a consumer AI
product the household would want blocked:

- `sierra.ai` (26,124,808B/4 hits): Sierra, an enterprise customer-service AI
  platform. The household also hits `sonos.com`, an early Sierra customer.
  This is a support widget embedded in another company's site, the same class
  as `trinitymedia.ai`/`kapa.ai` (#2729).
- `typesafe.ai` (3,162,731B/14 hits), observed `docs.typesafe.ai`: TypeSafe
  AI's Jev is a developer API that returns structured decisions to software,
  out of scope like `dxtech.ai`/`castify.ai` (#2792).
- `ivy.ai` (2,833,266B/28 hits), observed `bot.ivy.ai`: a white-label chatbot
  sold to colleges, healthcare and government, embedded on their sites. Same
  widget class as `sierra.ai`.
- `verysane.ai` (653,884B/38 hits): a Substack AI newsletter, as #2816 found.
  Not a product.
- `wandb.ai` (10,556,153B/34 hits): Weights & Biases, MLOps tooling for ML
  engineers. Out of scope.
- `learnings.ai` (74,309,282B/1,959 hits), observed `api.`,
  `api-abtest-cdn.`, `hermes-api.learnings.ai`: the highest-traffic
  unidentified candidate. No search or page fetch named a company or product.
  Held out; worth a focused look next pass.
- `hellohaven.ai`: already curated (#2836), no action.

The stale held-out notes for `theagenticx.ai` and `loudecho.ai` in `ai.yml`
now carry a "Superseded #2911" line pointing here.

## Self-update

Appended two learnings to `.claude/skills/blocklist-pass/SKILL.md` in the same
PR: the `bttrack.com` name collision with an open-source tool, and that
confirmed first-party ownership does not clear the #2823 CDN rule. A third
learning covers the `theagenticx.ai` miss: the observed-host check has to run
on every addition, not only on candidates already headed for a skip.
