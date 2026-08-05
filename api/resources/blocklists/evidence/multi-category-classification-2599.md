# Blocklist pass 2026-08-04 (#2599) — evidence

Source: `GET /api/devices/{mac}/recent-apexes?windowDays=30&limit=500` for all 27
prod devices, aggregated by apex (bytes + hits summed across devices). Read-only
against `https://api.wifihaven.net`.

## ads.yml additions

| apex | bytes | hits | what it is |
|---|---|---|---|
| tritondigital.com | 33,198,574 | 85 | Triton Digital — iHeartMedia-owned audio ad SSP; dynamic pre/mid/post-roll ad insertion for podcast/streaming-audio apps |
| poki-cdn.com* | — | — | *(games, not ads — see below)* |
| axon.ai | 3,603,884 | 142 | AppLovin's "Axon" AI-powered ad platform — sibling apex of already-curated applovin.com |
| assemblyexchange.com | 1,291,402 | 5 | "Assembly Programmatic Exchange" — confirmed RTB exchange (ads./rtb2-useast. subdomains) |
| starlyrtb.live | 457,577 | 40 | RTB name pattern; observed tva*.starlyrtb.live subdomains (ad-campaign-shaped IDs) |
| yourrtb.com | 459,354 | 39 | RTB name pattern; observed track.yourrtb.com |
| hoterbidder.com | 448,198 | 11 | RTB/bidder name pattern; observed rotating-id subdomain |
| matheranalytics.com | 299,619 | 15 | Mather Analytics — behavioral/engagement tracking for ad monetization on news/publisher sites; cataloged as a tracker by Feroot and Ghostery WhoTracksMe |
| pointmediatracker.com | 124,034 | 29 | Point Media (Bliss Point Media Inc) — confirmed ad-tracking/analytics domain |
| bdlrtb.com | 154,108 | 15 | RTB name pattern; observed track-use*.bdlrtb.com subdomains |
| contextualadv.com | 176,097 | 8 | Ad sync/redirect infra; sync subdomain chains into openwebmp.com (already-curated ad exchange) |
| shb-sync.com | 113,658 | 9 | Cookie-sync pixel; 65% of loads initiated by already-curated lijit.com (Sonobi) per DuckDuckGo tracker-radar |
| pmbmonetize.live | 40,133 | 8 | Confirmed "ad monetization platform"; sync subdomain chains into openwebmp.com |
| adlooxtracking.com | 11,728 | 1 | Adloox — established ad-verification/brand-safety vendor (since 2009) |
| rqtrk.eu | 13,092 | 1 | Roq.ad GmbH audience/marketing tracker |
| btrackhub.com | 20,080 | 2 | ads.btrackhub.com subdomain confirms ad-tracking function |
| freshpaint-impression.com | 275 | 1 | Freshpaint's dedicated ad-impression-matching pixel (matches DSP impressions to conversions) — the parent freshpaint.io CDP product is NOT added (dual-use) |

## ai.yml additions

| apex | bytes | hits | what it is |
|---|---|---|---|
| grokusercontent.com | 29,837 | 1 | xAI Grok's generated-artifact CDN (artifacts.grokusercontent.com) — sibling of claudeusercontent.com/oaiusercontent.com |

## games.yml additions

| apex | bytes | hits | what it is |
|---|---|---|---|
| poki-cdn.com | 6,776,697 | 21 | Poki's own CDN — sibling apex of already-curated poki.com |
| minecraft-services.net | 182,083 | 2 | Mojang/Microsoft's Minecraft backend services domain (net-secondary.web.minecraft-services.net) — sibling of minecraft.net |
| eaglercraftgame.io | 139,465 | 3 | Third Eaglercraft "unblocked" browser-Minecraft TLD variant (confirmed via web search), same evasion vein as eaglercraft.com/.ru |

## social-media.yml additions

| apex | bytes | hits | what it is |
|---|---|---|---|
| whatsapp.net | 90,251,045 | 1,029 | WhatsApp's actual messaging/media protocol domain (api./media-cdn subdomains). WhatsApp had NO representation at all in social-media.yml prior to this pass. whatsapp.com (marketing site) was not observed in traffic and is not added here. |
| facebook.net | 28,248,205 | 312 | Facebook Connect/SDK CDN (connect.facebook.net) — sibling of already-curated fbcdn.net |
| tiktokcdn-us.com | 52,411,135 | 38 | TikTok's US CDN — sibling of already-curated tiktokcdn.com |
| tiktokw.us | 459,054 | 24 | TikTok's own analytics/SDK domain (analytics-ipv6./libraweb./mssdk. subdomains) |
| twitter.new | 304,770 | 8 | Twitter/X's official `.new` gTLD domain (www.twitter.new) |
| redditinc.com | 740,722 | 2 | Reddit Inc's own corporate/investor domain |

## Dual-use / wrong-category — SKIPPED

- **hs-analytics.net** — HubSpot's analytics tracking subdomain. HubSpot is a
  CRM/marketing SaaS; same skip class as the already-noted hubspot.com skip
  (#2212).
- **synchrony.com / synchronycredit.com / mysynchrony.com** — Synchrony Bank
  (financial company). False positive on the "sync" substring.
- **siteimproveanalytics.com / siteimproveanalytics.io** — Siteimprove, a web
  governance/accessibility/SEO SaaS product. Dual-use.
- **stageagent.com** — a theater/acting resource site. False positive on the
  "tag" substring (s-**tag**-eagent).
- **bounceexchange.com** — on-site personalization/cart-abandonment tool
  (Cordial/BounceX), embedded first-party by sites, not an ad network itself.
  Dual-use.
- **revenuecat.com** — mobile subscription/IAP management SDK. False positive
  on the "revenue" substring, unrelated to ad exchanges.
- **stackexchange.com** — Stack Exchange Q&A network. False positive on the
  "exchange" substring.
- **mmvideocdn.com** (6,605,292 bytes / 15 hits) — confirmed Minute Media's
  own video-CONTENT CDN (videos-a./videos-b./players. subdomains alongside an
  ads. subdomain). Same content-collateral skip class as the already-noted
  minutemediaservices.com (#2064) — blocking it would drop the actual video a
  user is watching, not just an ad slot.
- **ihawk.ai** (5,381,426 bytes / 3 hits) — confirmed NOT ad-tech: Cyberhawk's
  enterprise drone/asset-intelligence SaaS. Also not added to ai.yml — it's a
  B2B industrial platform, not a consumer-facing AI chatbot/generator.
- **yegge.ai** (11,427,254 bytes / 3 hits) — confirmed NOT ad-tech: a personal
  content archive / consulting site for engineer Steve Yegge. Not a consumer
  AI product.
- **desync.com** (5,852,504 bytes / 20,413 hits) — high hit count but no clear
  identity found; associated with "Desync Networks," a small hosting ASN.
  Held out unverified rather than guessed.
- **twitchy.com** (471,181 bytes / 4 hits) — confirmed conservative political
  commentary/news site (founded by Michelle Malkin), unrelated to Twitch
  streaming. False positive on the "twitch" substring.
- **prodigygame.com, steamboat.com, steamboatpilot.com** — already-known
  false positives (educational game / Colorado newspaper), reconfirmed absent
  from games.yml, not re-added per prior passes' rationale.
- **coveo.com, copilot.money, copilotmoney.app** — already-known false
  positives / dual-use (enterprise search SaaS; personal-finance app), no new
  information this pass.

## Held out — unverified (do not re-classify from the name alone next pass)

krautmtrk.com (subdomains us-n1..n7.krautmtrk.com, no identity found),
powerad.ai, cimulate.ai, componecat.ai, conduit.ai, connectmachine.ai,
directbooker.ai, duvo.ai, e-volution.ai, elixion.ai, joblobster.ai,
mediayo.ai, wknd.ai — all low-traffic `.ai`-TLD domains with no consumer-AI
signal in their name or any search result; likely unrelated small-business
SaaS products using the `.ai` vanity TLD, not evidence of ad-tech or AI-tool
identity.

## Categories with zero gaps this pass

- **adult** — zero apex hits on the adult keyword sweep beyond the
  already-curated cam4.com. Consistent with the #2503 finding that this
  category can go a full pass with no traffic-observed apexes at all.
- **gambling** — only acebet.cc (already curated) and betrad.com (already
  held out unverified per #2348) surfaced; no new gaps.
