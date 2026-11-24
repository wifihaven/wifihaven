# Multi-category blocklist pass — #3017 (2026-11-24)

30-day prod `recent-apexes` pull across all 32 devices (1,930 distinct apexes
observed), keyword-swept against all six curated categories (ads, adult, ai,
gambling, games, social-media).

## Addition

- **ai.yml: `wisprflow.ai`** — Wispr Flow, a confirmed consumer AI
  voice-dictation keyboard (speech-to-text with AI-polished output, Mac/
  Windows/iOS). 1,853,668B / 2 hits. Observed host `api.wisprflow.ai`
  resolves to a dedicated AWS ALB
  (`fastapi-prod-alb-1208802619.us-west-1.elb.amazonaws.com`) — not a shared
  pool, clears the #2823 CDN check. Unobserved `www.`/bare apex sit on
  Webflow's shared marketing-site hosting (`cdn.webflow.com`,
  `198.202.211.1` = Webflow, Inc per whois) — noted as RESIDUAL RISK in the
  `.yml` comment; re-check if that host is ever observed instead of `api.`.

## New spec pin (closing a prior gap)

- **`grok-sandbox.com`** was investigated and disqualified in two prior
  passes (#2918 — held out "unverified"; #3000 — fully confirmed as xAI's
  own Cloudflare-fronted sandbox infra, `hds-*.grok-computer-wild.hades-www.
  grok-sandbox.com` → `104.18.26.226`/`104.18.27.226`) but neither pass
  added a `BundledBlocklistsSpec` pin. Added this pass so a future pass
  cannot re-add it.

## Re-confirmed standing held-outs (already pinned, no new action)

All of these resurfaced in this pass's sweep and were independently
re-investigated before checking history; all match prior findings exactly:

- `xboxlive.com` — one observed host (`accounts.`/`profile.`/`userstats.`/
  `privacy.` = Azure GTM via `akadns.net`, dedicated Microsoft IPs) is clean,
  but `sisu.`/`peoplehub.` CNAME through `edgekey.net` onto shared Akamai
  EdgeSuite edges — one bad observed host disqualifies the apex (#2957).
- `xboxservices.com` — observed `cms-assets.` CNAMEs onto Azure Front Door
  (`*.azurefd.net`), a shared multi-tenant CDN product (#3000).
- `panxo.ai` — confirmed ad-tech (AI-referral-traffic monetization), observed
  `api.` is Cloudflare (`104.26.x`/`172.67.x`) (#2957).
- `gamepix.com` — confirmed HTML5-game distributor, observed `integration.`
  is CloudFront (`13.226.x`) (#2957).
- `mgln.ai` — confirmed podcast-ad-analytics company, Cloudflare-fronted
  (#2957). Not independently re-dug this pass; membership check only.

## Held out — no identity confirmation (new this pass)

- `metabet.io` (133,487B / 1 hit) — observed host `go.metabet.io` CNAMEs to
  `us.api.ruwt.tv` (an unrelated video/streaming platform), consistent with
  an affiliate-redirect pattern but no source confirms what company runs it.
  Possibly gambling-affiliate infra; too thin to add on a `go.` CNAME alone.
- `gamebassadors.network` (12,633B / 11 hits) — a third-party reputation
  checker (Gridinsoft) describes the owner as an advertising-services firm
  (founded 2017, uses HubSpot/Calendly) with a linked X profile
  `@gamebassadors`, but no source names the company or confirms it serves
  ads to end users rather than running its own agency site. Observed host
  `trk.gamebassadors.network` → `34.128.152.43` (GCP) — not independently
  resolved to a specific product. Held out.
- `athletetracker.club` (246,704B / 4 hits) — no search result confirms this
  exact `.club` domain; the closest name match (Athlete Tracker,
  athletetracker.com.au, an Australian club-management SaaS) is a different
  TLD with no stated connection. Held out.
- `redditspace.com` (9,498B / 2 hits) — observed host `matrix.redditspace.com`
  resolves via `dualstack.reddit.map.fastly.net` (Fastly), which is odd
  enough to suggest some Reddit tie (a Matrix homeserver?) but no source
  confirms ownership, and Fastly is shared-CDN regardless. Held out.
- `gssprt.jp` (340B / 2 hits) — carried forward again; already held out
  unverified in #3012 with the same result (JP hosting, no owner found).

## Recurring, still unidentified (carried forward)

- `learnings.ai` — still the single highest-traffic unidentified apex across
  6+ consecutive passes (#2911, #2348, #2836, #2952, #3000, and now #3017).
  Same observed hosts (`api.`, `hermes-api.`, `api-abtest-cdn.`) each time;
  `api.learnings.ai` resolves via AWS Global Accelerator to dedicated
  backend IPs (not evidence either way on identity). No new search result
  this pass. Not re-pinning anywhere since it has never been curated.

## False positives / dual-use — no action

- `trackjs.com` — confirmed client-side JS error-monitoring SaaS (Sentry/
  Bugsnag class), dual-use developer tooling.
- `trackcmp.net` — confirmed ActiveCampaign CRM site-tracking domain,
  dual-use marketing-automation analytics (already pinned absent, #2918).
- `hsadspixel.net` (1,479B / 7 hits) — confirmed HubSpot-operated domain that
  hosts ad-network pixels (Google/Facebook Ads attribution) on behalf of
  HubSpot's many customers — a shared multi-tenant ad-pixel product, same
  class as `app-ads-services.com`/`app-measurement.com`. Skipped.
- `twitchy.com` — Salem Media's conservative Twitter-aggregation news site,
  unrelated to Twitch.tv; matched the games sweep's `twitch` substring.
- `googleadservices.com`, `app-ads-services.com`, `ebayadservices.com`,
  `opentrackr.org`, `popcorn-tracker.org`, `paperlesspost.com`,
  `bounceexchange.com`, `synctrack.io`, `emojitracker.com`, `hotpads.com`,
  `supersonicads.com`, `skmads.com`, `stackexchange.com`, `skypixel.com` —
  all standing documented skips (dual-use shared pools, BT trackers, or
  unrelated-brand substring matches), re-confirmed by membership check only.
- `devin.ai`, `justt.ai` — already-documented B2B/out-of-scope exclusions
  (#3012), re-confirmed.

## Gambling / adult / social-media

- **adult**: zero apexes matched any adult keyword across all 32 devices
  this pass — a valid empty result (per #2503, this recurs).
- **gambling**: only `metabet.io` surfaced (held out above, no other
  confirmed-identity candidates).
- **social-media**: every candidate that matched the sweep (`instagram.com`,
  `whatsapp.net`, `tiktok.com`, `reddit.com`, etc.) was already curated;
  `tiktokpangle-cdn-us.com`/`tiktokpangle-b.us` are correctly filed under
  `ads.yml` (Pangle is ByteDance's ad network, not TikTok content) rather
  than `social-media.yml`.

## Self-update

See the Learnings log entry appended to `SKILL.md` in this same PR.
