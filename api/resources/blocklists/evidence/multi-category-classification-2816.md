# Blocklist pass evidence — #2816 (2026-09-29)

Source: `GET /api/devices/{mac}/recent-apexes?windowDays=30&limit=500` across
all 30 prod devices, aggregated by apex (bytes + hits summed across devices).
1809 unique apexes observed.

Categories swept (dynamically enumerated from `api/resources/blocklists/`,
`hosts:`-inline only): `ads`, `adult`, `ai`, `gambling`, `games`,
`social-media`. Every candidate apex was checked against **all six** curated
files, not just the category its keyword match suggested (#2742 learning).

**Note on PR #2808 (issue #2807):** last week's pass is still open/unmerged at
the time of this run. Its 3 pending additions — `responsiveads.com`, `vdo.ai`
(both ads.yml) and `hellohaven.ai` (ai.yml) — were folded into this pass's
"already covered" set before sweeping, so they don't show up as duplicate
gaps below. (As of 2026-10-08, `hellohaven.ai` is on main via #2837;
`responsiveads.com` and `vdo.ai` landed on main when #2808 merged.)

## Added

| Apex | Category | Bytes | Hits | What it is | Why it was a gap |
|---|---|---|---|---|---|
| `al-ad.com` | ads | 171,052 | 12 | No public company page; observed subdomains `ortb-us22/us32/us33/us34/us35/us63/us64/us69.al-ad.com` — literally "open RTB" server pool naming. | Name-is-function precedent (#2122/#2064/#2792) — absent from the `ads-extended` feed entirely, no other curated source flags it either. |

Dropped when main was merged in (2026-10-08): `teamwood.games` (285,697 bytes / 21 hits) and `teamwoodgames.com` (2,170,825 bytes / 9 hits), Teamwood Games, the Super Auto Pets studio, were already added to `games.yml` by #2837 (the #2836 pass), so this PR no longer adds them.

## Investigated and held out / skipped (not added)

| Apex | Category swept into | Bytes | Hits | Disposition | Reason |
|---|---|---|---|---|---|
| `ebayadservices.com` | ads | 1,547,158 | 1 | Held out — shared CDN | eBay's own ad-services apex (confirmed via Netify, host.io, and eBay's MarkMonitor registration). First added in this pass, then dropped during independent review on 2026-10-08: the apex and `www.` both resolve onto shared Akamai edges (`andes.ebay.com.edgekey.net` → `e168507.a.akamaiedge.net`, 23.211.124.194/.195), the same /24 `pages.ebay.com` answers in. That is the #2823 shared-CDN skip, and `akamai*` is on the skill's Step 1 skip list. Pinned absent in `BundledBlocklistsSpec`. |
| `amgtrack.online` | ads (`track` substring) | 31,752 | 2 | Held out — unverified | Search only surfaced an unrelated GPS-tracker product (`amgotrack.com`, different domain/spelling). No confirmed identity for this exact apex; too little traffic to risk a name-guess. |
| `sail-track.com` | ads (`track` substring) | 39,517 | 19 | Skip | Resolves to a legitimate marine/sailing GPS-tracking app family ("Sail Track" / SailTrack), not ad infra. Substring false positive. |
| `synctrack.io` | ads (`sync`/`track` substring) | 21,304 | 6 | Skip | Synctrack — a legitimate Shopify post-purchase/order-tracking SaaS (founded 2019, 20K+ merchant customers). Substring false positive. |
| `cam4tracking.com` | ads (`track` substring) | 77,198 | 2 | Skip | Confirmed CAM4 (adult platform) affiliate/payment redirect domain (→ cam4pays.com). `cam4.com` itself is already curated in `adult.yml`; this tracking-only sibling carries no independent content and is low marginal value per this list's "conservative starter set" scope. #2823 later found a structural reason too: its only observed host, `track.cam4tracking.com`, answers on shared CloudFront edges. |
| `ad-score.com` | ads | 2,900,834 | 72 | Held out (unchanged from #2742) | At least three unrelated "AdScore"-named companies exist (ad-fraud detection, automotive-ad compliance, marketing analytics); no way to confirm which owns this specific domain. |
| `app-ads-services.com`, `googleadservices.com`, `bounceexchange.com`, `smartborad.com`, `freebeacon.com`, `insidetracker.com`, `myfitnesspal.com`, `opentrackr.org`, `popcorn-tracker.org`, `paperlesspost.com`, `desync.com` | ads (various substrings) | — | — | Skip (standing) | Re-confirmed as prior-pass documented skips/false-positives (dual-use Google infra, SharedGfeHosts-banned, BitTorrent-tracker decoys, unrelated brands) — no new signal this pass. |
| `stackexchange.com`, `creativecommons.org` | ads (`exchange`/`creativ` substring) | — | — | Skip | Obvious substring false positives — Stack Overflow network and the Creative Commons nonprofit. |
| `chatra.io`, `freshchat.com`, `gorgias.chat` | ai (`chat` substring) | 97,282 / — / — | 3 | Skip | B2B live-chat widgets embedded by e-commerce sites for customer support — dual-use, not consumer chatbot products. |
| `ivy.ai` | ai | 6,606,551 | 51 | Held out | Higher-ed AI chatbot platform institutions embed on their own sites (B2B SaaS), not a consumer-facing product — same out-of-scope class as `dxtech.ai`/`kapa.ai`/`castify.ai` (#2792). |
| `medchatapp.com` | ai (`chat` substring) | 860,601 | 4 | Held out | HIPAA healthcare patient-messaging/chatbot platform for provider organizations (B2B) — out of scope. |
| `sierra.chat` | ai | 683,724 | 1 | Held out | Enterprise AI-agent platform (Sierra, Bret Taylor) sold to businesses for their own customer service — B2B, out of scope. |
| `typesafe.ai` | ai | 3,156,545 | 12 | Held out | AI infrastructure/research lab (non-consumer models for machine-to-machine decisions) — out of scope. |
| `verysane.ai` | ai (`.ai` TLD) | 623,419 | 33 | Skip | A Substack AI-commentary newsletter, not an AI product at all. |
| `nextmillmedia.com` | ai (`llm` substring) | 3,141,344 | 18 | Skip | Substring false positive — "mi**llm**edia" contains "llm" by coincidence; unrelated media company. |
| `cookiebot.com` | ai (`bot` substring) | — | — | Skip | Cookie-consent management platform, substring false positive on "bot". |
| `axon.ai`, `bidsystem.ai`, `dxtech.ai`, `kapa.ai`, `programmaticx.ai`, `trinitymedia.ai`, `e-volution.ai`, `flux.ai` | ai (`.ai` TLD) | — | — | Already covered / standing | `axon.ai`/`bidsystem.ai`/`programmaticx.ai` already curated in `ads.yml`; `vdo.ai` is in the pending #2808 PR (ads.yml); `dxtech.ai`/`kapa.ai`/`trinitymedia.ai` are standing out-of-scope skips (#2792/#2729); `e-volution.ai`/`flux.ai` remain unverified hold-outs from prior passes, no new signal. |
| `viddea.com` | games (`ea.com` substring) | 414,456 | 53 | Skip | Substring false positive — "vidd**ea.com**" happens to end in "ea.com". Re-confirmed as a standing exclusion (first flagged #2759). No identifiable company. |
| `epicgameshubham.com`, `livesteamstation.com` | games | — | — | Skip (standing) | Already documented: scam clone of Epic Games branding (filed in `ads.yml`, #2759) and a model-train hobby store (substring false positive, #2807/#2503). |
| `snapchat.com` | ai (`chat` substring) | — | — | Already covered | Confirmed already curated in `social-media.yml`; not an ai.yml or social-media.yml gap. |
| `ads-twitter.com`, `tiktokpangle-b.us`, `tiktokpangle-cdn-us.com`, `tiktokpangle.us` | social-media | — | — | Already covered | Confirmed already curated in `ads.yml` (X's ad-conversion pixel; ByteDance's Pangle ad network) — same cross-category pattern flagged in #2742, no action needed. |

## Categories with zero genuine gaps this pass

`adult`, `gambling`, `ai`, `social-media` — every candidate the keyword sweep
surfaced either resolved to an apex already curated (in the swept category or
a different one), a false positive on a substring match, or an out-of-scope
B2B/enterprise SaaS product (table above). Per the #2503/#2212/#2792
learnings, an empty category section is a valid, expected outcome and was
not forced. `gambling` and `adult` in particular surfaced literally zero
keyword-sweep matches at all this week (not even previously-curated apexes
recurring in the window) — consistent with prior low-incidence passes.

## Self-update

See the Learnings log in `.claude/skills/blocklist-pass/SKILL.md` for the new
entries from this run: the `ebayadservices.com` shared-CDN lesson (it is
Akamai-fronted, so it was held out), the `sail-track.com`/`synctrack.io`
`track`-substring false positives, and the `cam4tracking.com`
already-covered-content affiliate-tracking pattern.
