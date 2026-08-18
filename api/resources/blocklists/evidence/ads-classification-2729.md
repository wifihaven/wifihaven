# Blocklist pass 2026-08-18 (#2729) — evidence

Source: `GET /api/devices/{mac}/recent-apexes?windowDays=30&limit=500` for all 26
prod devices, aggregated by apex (bytes + hits summed across devices). Read-only
against `https://api.wifihaven.net`.

## ads.yml additions

| apex | bytes | hits | what it is |
|---|---|---|---|
| adnxs-simple.com | 4,594,934 | 10 | Xandr/AppNexus's cookie-free variant domain (per Microsoft Advertising docs) — sibling apex of the already-curated adnxs.com/adnxs.net |
| programmaticx.ai | 505,061 | 17 | ProgrammaticX — an Israel-based programmatic ad-tech vendor for mobile/desktop/audio/CTV ads |
| amazon-ads-attestation.com | 234,738 | 20 | Maximally self-descriptive ad-attestation apex; registered via MarkMonitor under the DNStination Inc. privacy proxy (the same privacy-registration pattern documented for other Amazon-affiliated domains, e.g. prime.com). No named third-party company found — added on the inhousedsp.com precedent (#2212) for self-descriptive names |
| pem-teads.tv | 2,494,206 | 19 | Sibling apex of the already-curated teads.tv (Teads, video/native ad platform) |
| fouanalytics.com | 237,447 | 6 | FouAnalytics — Dr. Augustine Fou's ad-fraud/verification analytics platform, embedded in ad calls to detect fraud/pixel-stuffing. Same functional class as the already-curated adlooxtracking.com |
| anzu-rtb.live | 123,361 | 3 | Anzu.io — intrinsic in-game advertising RTB platform (backed by Sony Innovation Fund, NBCUniversal, WPP) |
| admixer.net | 40,060 | 5 | Admixer — established programmatic ad exchange / DSP |

## social-media.yml additions

| apex | bytes | hits | what it is |
|---|---|---|---|
| tiktokv.us | 86,182 | 2 | TikTok's own API/app-delivery infrastructure domain (confirmed via netify, e.g. `api19-normal-useast5.tiktokv.us`) — sibling apex of the already-curated tiktokw.us/tiktokcdn-us.com |

## Re-confirmed already-curated / already-documented (no change)

- **axon.ai** — already present in ads.yml since #2599 (AppLovin's "Axon" ad
  platform, sibling of applovin.com). A naive websearch on "axon.ai" this pass
  surfaced Axon Enterprise (the public-safety body-cam company at axon.com)
  instead — a reminder to check the CURRENT file before trusting a fresh
  websearch's top result for an apex that reads ambiguous.
- **hs-analytics.net, google-analytics.com, app-analytics-services.com,
  app-ads-services.com, freebeacon.com, bdtelemetry.amazon, imganalytics.com,
  siteimproveanalytics.com/.io** — all re-surfaced in this pass's ads keyword
  sweep; all already documented dual-use/wrong-category SKIPs from prior
  passes (#2122/#2212/#2599). No new information, not re-added.
- **opentrackr.org, popcorn-tracker.org** — BitTorrent tracker decoys
  (high-hit, non-ad infra), same recurring class noted in #2064/#2122/#2212.
- **desync.com** — remains HELD OUT unverified; still no confirmed ad-tech
  identity after a fresh web search this pass (results describe an old,
  unrelated community/forum site). Carried forward per #2599's finding.
- **tiktokpangle.us, tiktokpangle-cdn-us.com, tiktokpangle-b.us,
  ads-twitter.com** — ByteDance's Pangle ad network and Twitter/X's own ads
  conversion domain; both already curated in ads.yml (correctly ads, not
  social-media, despite the TikTok/Twitter name match).

## Dual-use / wrong-category — newly checked and SKIPPED this pass

- **opencode.ai** (15,477,217 bytes / 4 hits) — an open-source AI *coding
  agent* run from a terminal by developers. Out of ai.yml's scope (consumer
  chat/assistant/companion/generator products a household blocks); this
  household's traffic is developer-heavy generally (alpinelinux.org,
  githubusercontent.com, warp.dev also top the ranked list this pass).
- **navless.ai** (8,119,448 bytes / 1 hit) — a B2B marketing SaaS platform
  (AI-search visibility + website chat overlay for businesses), not a
  consumer AI product.
- **trinitymedia.ai / trinityaudio.ai** (2,061,599 / 1,168,801 bytes) — an
  AI text-to-speech *widget* embedded by third-party publisher/news sites to
  read articles aloud. Content-collateral / dual-use, same class as the
  Unity SDK exclusion — blocking it breaks an accessibility feature on
  unrelated sites, not a specific AI product a household visits.
- **castify.ai** (133,952 bytes / 17 hits) — an OTT/CTV app-building and ad
  monetization SaaS platform for content owners, not a consumer AI generator.
- **gpteng.co** (152,499 bytes / 5 hits) — the embed-script CDN for
  Lovable/GPT-Engineer-built web apps (`cdn.gpteng.co/gptengineer.js`),
  loaded by many unrelated downstream apps. Infra-CDN collateral, same class
  as unity3d.com; if Lovable itself is ever added it should be `lovable.dev`,
  not this CDN apex.
- **axon.ai (via websearch)** — see re-confirmation note above; verified this
  is the same apex already curated for a different (correct) reason.
- **copilot.money / copilotmoney.app** — already-known false positive
  (personal-finance app), reconfirmed absent from ai.yml, not added.
- **instapundit.com** (53,345,702 bytes / 2,643 hits) — a political
  commentary blog. False positive on the "insta" substring (Instagram sweep).
- **instana.io, freshchat.com, instaread.co, manychat.com,
  yellowmessenger.com** — enterprise SaaS tools (APM monitoring, live-chat
  widgets, TTS widgets, business chatbot platforms) matched by generic
  "insta"/"chat"/"messeng" substrings in the social-media sweep. All dual-use
  B2B infra, not consumer social networks.
- **lazybumblebee.com** (13,784,227 bytes / 744 hits) — matched the "bumble"
  substring (dating-app sweep) but is an unrelated site; not evidence of
  Bumble ownership, held out (not a social-media candidate at all).
- **steamboatpilot.com** — already-known false positive (Colorado newspaper),
  reconfirmed absent from games.yml.
- **prodigygame.com** — already-known exclusion (educational classroom game,
  out of scope for a games *block* list per #2212).

## Categories with zero gaps this pass

- **adult** — only cam4.com surfaced (already curated). Consistent with the
  #2503/#2599 finding that this category can go a full pass with no new
  traffic-observed apexes.
- **ai** — every new candidate this pass resolved to a developer tool, B2B
  SaaS, or embedded publisher widget (see SKIPPED list above); every
  consumer-AI apex actually in traffic (claude.ai, x.ai, mistral.ai) was
  already curated.
- **gambling** — only acebet.cc surfaced (already curated); no new candidates
  at all this pass (not even an unverified hold-out).
- **games** — minecraft.net, minecraft-services.net, and eaglercraftgame.io
  all already curated; steamboatpilot.com and prodigygame.com are known
  exclusions. No new gaps.
