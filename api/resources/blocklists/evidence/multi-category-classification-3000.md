# Weekly blocklist pass — #3000 (2026-11-10)

30-day prod `recent-apexes` sweep across all 31 devices, aggregated by apex
across devices (1,951 distinct apexes). Keyword-swept the full aggregated list
per category (ads/ai/gambling/adult/games/social-media), then cross-checked
every candidate against all six curated host sets, the three StevenBlack
`-extended` feeds, the current `BundledBlocklistsSpec` held-out pins, and the
`ai.yml`/`ads.yml` comment history before treating anything as a "new" gap.

The household's traffic this run was again developer/infra-heavy (top bytes:
`googlevideo.com`, `apple.com`, `githubusercontent.com`, `google.com`,
`wifihaven.net`, `alpinelinux.org`, `googleapis.com`, …) — consistent with
prior passes; the category gaps below were found by keyword-filtering the
full ranked list, not by eyeballing the top of it.

## Residual-risk watchlist re-check

Grepped `RESIDUAL RISK` in `ads.yml`: `cdn.playablefactory.app` (unobserved
this pass too — no `/api/logs` hits over the window checked). No removals.

## Added

### ads.yml

- **`ads-pubsfusion.com`** — last week's pass (#2957) noted this apex "does
  not currently resolve; no /api/logs hits" and held it out. This pass it
  resolves: the one observed host, `tracker-b.ads-pubsfusion.com` (2 hits,
  14.8KB, one household iOS device), CNAMEs to `tracker-b-use.ortb.net` —
  `ortb.net` is already curated in `ads.yml` as a confirmed RTB exchange/
  bidder apex (one of the existing "RTB exchanges / bidders / DSPs" group),
  so this CNAME lands on infra this list already treats as verified
  ad-tech, not a fresh unsourced ownership claim. The CNAME target
  resolves to `188.42.82.3`/`188.42.82.41` (Server.com dedicated hosting,
  confirmed via whois netname `SERVERS-COM-WAS4`), not a shared CDN.
  Checked unobserved siblings: the bare apex and `www.`/`cdn.` all return
  NOERROR with no A record (do not resolve at all) — no CDN-fronted
  sibling risk observed, but also no corroborating resolution beyond the
  one observed host. Absent from `ads-extended`.

## Held out (CDN-fronted on the observed host — pinned absent, #3000)

- **`customads.co`** — self-descriptive name, classic `cdn1.`/`cdn2.`/`trk.`
  ad-tech subdomain shape (214KB/10 hits), already present in `ads-extended`
  under one of those subdomains. `cdn1.customads.co` CNAMEs to
  `d2ad9b10er1gl5.cloudfront.net` — CloudFront. An unconditional #2823 skip
  on the observed host; held out despite the otherwise-strong identity
  signal.
- **`xboxservices.com`** — companion API domain to `xboxlive.com` (already
  held out #2957 for an Akamai-EdgeSuite-fronted subdomain) and `xbox.com`
  (already curated in `games.yml`). Only observed host,
  `cms-assets.xboxservices.com` (23.7MB/83 hits across the fleet), CNAMEs
  through `afd-xpci-prod-gxguabfaahdhg2gk.b02.azurefd.net` /
  `mr-b02.tm-azurefd.net` — **Azure Front Door**, Microsoft's shared
  multi-tenant edge product. This is a distinct case from the GTM/
  `akadns.net` traffic-steering pattern that cleared `appier.net` (#2952)
  and three of `xboxlive.com`'s own subdomains — Front Door is an actual
  shared edge cache, the same risk class as Akamai EdgeSuite/Cloudflare/
  CloudFront. **Worth flagging for the self-update log: Azure Front Door
  (`*.azurefd.net`) joins the shared-CDN list alongside Cloudflare,
  CloudFront, and Akamai EdgeSuite.**

## Investigated, no action (already triaged by a prior pass or out of scope)

Cross-referencing this week's candidates against `ai.yml`'s comment history
and `BundledBlocklistsSpec`'s existing held-out pins found that nearly every
other strong-looking candidate from this week's sweep was already fully
triaged last week (#2957) with the same conclusion this pass independently
re-derived:

- `learnings.ai` (129MB/4,848 hits, one household iOS device; `api.`/`api-abtest-cdn.`/
  `hermes-api.` subdomains) — still unidentified; #2957 already held it out
  unverified with the same observed-subdomain set. Carried forward again
  rather than guessed from the name.
- `grok-sandbox.com` (9.2MB/1,058 hits) — **not pre-triaged; a new find this
  pass**, investigated fresh and immediately disqualified. xAI's own
  sandboxed "computer use" agent infra for the Grok product
  (`hds-*.grok-computer-wild.hades-www.grok-sandbox.com`); CloudFlare-fronted
  (`104.18.26.226`/`104.18.27.226`). Even setting the CDN skip aside, it is
  Grok's own backend infra, not a separate product a household would target
  — `grok.com`/`grokusercontent.com` already cover the product.
- `mgln.ai` (Magellan AI, podcast-ad analytics) and `panxo.ai` (Panxo,
  publisher ad-monetization) — both Cloudflare-fronted, already pinned
  absent by #2957.
- `nrich.ai` — already held out of `ai.yml` (#2957) as B2B marketing/ABM.
  This pass's traffic additionally shows `dsp.`/`j.`/`tag.` subdomains
  (ad-tech-shaped — `dsp.` resolves to dedicated OVH, `j.` too, but
  `tag.nrich.ai` CNAMEs onto CloudFront), so it would also fail the ads.yml
  CDN check. Documented in `ads.yml`'s comment; not separately pinned since
  it was never a live ai.yml candidate to begin with.
- `ivy.ai`, `wandb.ai`, `modernintelligence.ai`, `eightfold.ai`, `yofi.ai`,
  `anagram.ai`, `idsequoia.ai` — all already investigated and held out by
  #2957 for B2B/out-of-scope or no-identity reasons; this pass's traffic
  confirms the same subdomain shapes (`bot.ivy.ai`, `app./studio.anagram.ai`
  CNAME to `vercel-dns.com`, etc.) and doesn't change the call.
- `hsadspixel.net`, `heapanalytics.com`, `skypixel.com`, `saasexch.com` —
  confirmed dual-use (HubSpot's shared pixel host, Heap/Contentsquare
  product analytics) or false positives (DJI's photo community, a SaaS
  marketplace matching the "sex" substring); #2957 already noted all four,
  no action.
- `xboxlive.com`, `ygametime.com` — already pinned absent (#2957, CDN-fronted).

Genuinely new candidates this week that could not be identified and were
**held out unverified** (low traffic, no confirmed company, carried forward
rather than guessed): `btttag.com` (2.3MB/237 hits, Cloudflare-fronted),
`regulatedtags.monster` (85KB/7 hits, Cloudflare-fronted), `insiad.com`
(58KB/10 hits, scattered AWS addresses), `mradx.net` (42KB/1 hit, single
unidentified IP, present in `ads-extended`), `liftyad.xyz` (22KB/3 hits,
Linode/Akamai-Cloud dedicated compute, absent from `ads-extended`),
`foisonad.com` (21KB/3 hits, same Linode/Akamai-Cloud range, absent from
`ads-extended`), `gamerhunk.com` (6.6MB/8 hits, no identifiable company).

`betgenius.com` (Genius Sports' sportsbook odds/streaming data feed,
`gsm-adverts.betstream.betgenius.com`) is B2B sportsbook infrastructure, not
a destination gambling site — same out-of-scope class as the
Sportradar/Betradar precedent (#2503). `twitteracquisitionlitigation.com`
matched the social-media sweep on the "twitter" substring but is an
unrelated legal-news site about the Musk/Twitter litigation — not social
media, no action needed.

## Not added

`adult` and `gambling` again surfaced no genuine unblocked apexes this pass
beyond `betgenius.com` (out of scope, above) — a valid, expected outcome per
prior passes' precedent.
