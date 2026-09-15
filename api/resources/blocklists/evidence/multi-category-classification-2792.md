# Blocklist pass evidence — #2792 (2026-09-15)

Source: `GET /api/devices/{mac}/recent-apexes?windowDays=30&limit=500` across
all 28 prod devices, aggregated by apex (bytes + hits summed across devices).
1666 unique apexes observed.

Categories swept (dynamically enumerated from `api/resources/blocklists/`,
`hosts:`-inline only): `ads`, `adult`, `ai`, `gambling`, `games`,
`social-media`. Every candidate apex was checked against **all six** curated
files, not just the category its keyword match suggested, per the #2742
learning that a candidate can be fully enforced under a different category's
file.

## Added

| Apex | Category | Bytes | Hits | What it is | Why it was a gap |
|---|---|---|---|---|---|
| `rtbedge.com` | ads | 1,202,330 | 2 | No public company page; subdomains `go-us.` (redirect) + `img.` (creative serving) match textbook RTB-bidder infra shape. | Name-is-function precedent (#2122/#2759) — not in `ads-extended`. |
| `bidgust.com` | ads | 1,006,242 | 8 | No public company page; subdomains `go-us.` (redirect) + `static.` (creative serving), same shape as rtbedge.com. | Same as above. |
| `impression.link` | ads | 173,651 | 32 | No public company page; name is a literal ad-metric term. | Corroborated by presence in the StevenBlack `ads-extended` feed — apex itself, not just a subdomain. |
| `stake.us` | gambling | 1,588,933 | 8 | Stake's US-facing sweepstakes-casino product (Gold Coins / Stake Cash dual-currency model, crypto-payout). Confirmed via multiple review-site sourcing (casino.org, thelines.com, gamingtoday.com). | Dedicated gambling product apex, zero collateral (same class as the already-curated `acebet.cc`). |

## Investigated and held out / skipped (not added)

| Apex | Category swept into | Bytes | Hits | Disposition | Reason |
|---|---|---|---|---|---|
| `desync.com` | ads (`track` substring) | 3,716,036 | 17,708 | Skip | Its only observed subdomain, `exodus.desync.com`, is confirmed (similarweb + justdailytrackers.com) to be a public BitTorrent tracker on port 6969 — same decoy class as `opentrackr.org`/`popcorn-tracker.org`, not ad infra. |
| `smartborad.com` | ads (`track` substring) | 9,468,233 | 147 | Held out (unchanged from #2064) | No new identity signal this pass either; prior passes flagged scamadviser-malware/unclear — belongs to malware list if anywhere, not ads. |
| `insidetracker.com` | ads (`track` substring) | 8,968,349 | 10 | Skip | Legitimate consumer health/biomarker-testing brand (MIT spinout); subdomains `info.`/`www.` are marketing-site-shaped, not ad-tech. Substring false positive. |
| `app-ads-services.com` | ads | 186,178,012 | 756 | Skip | Google GA4 ATT-segmentation pair, same shared-product class as `app-measurement.com` (dual-use, standing skip). |
| `freebeacon.com` | ads (`beacon` substring) | 22,105,192 | 14 | Skip | Washington Free Beacon — news content, standing skip. |
| `googleadservices.com` | ads | 10,070,971 | 541 | Skip | Shared-GFE banned apex, explicitly removed 2026-08-06 (#2601) — never re-add. |
| `opentrackr.org`, `popcorn-tracker.org` | ads (`track` substring) | 4.8M / 0.6M | 8657 / 10742 | Skip | BitTorrent trackers, standing decoy class. |
| `bethsbees.com` | gambling (`bet` substring) | 1,178,928 | 3 | Skip | Bee Squared Apiaries — beekeeping/honey shop. Substring false positive on "bethsbees". |
| `livesteamstation.com` | games (`steam` substring) | 22,159,625 | 2 | Skip | Live-steam model-train hobby store. Substring false positive, same class as `steamboat.com` (#2503). |
| `gameanalytics.com` | games | 4,627,684 | 524 | Skip (standing, #2759) | First-party game-dev analytics SDK, developers embed in their own titles — dual-use. |
| `prodigygame.com` | games | 216,332 | 2 | Skip (standing, #2212) | Educational classroom game, out of games-block scope. |
| `higgsfield.ai`, `axon.ai`, `delphi.ai`, `programmaticx.ai`, `gemini.google`, `trygravity.ai`, `mediayo.ai`, `koah.ai`, `ads-twitter.com`, `tiktokpangle*`, `whatsapp.net`, `redditmedia.com`, `truthsocial.com`, `redditstatic.com`, `steamstatic.com`, `wherewindsmeetgame.com`, `eaglercraftgame.io`, `cam4.com`, `poki.com`, `poki-cdn.com` | various | — | — | Already curated | Confirmed present in the relevant category's `.yml` from prior passes — no action needed. |
| `dxtech.ai` | ai | 1,187,711 | 99 | Held out | AI-driven IT-consulting/digital-transformation SaaS for businesses (B2B) — not a consumer chatbot/assistant/generator; out of `ai.yml` scope, same reasoning as excluding dev tools (#2729). |
| `kapa.ai` | ai | 966,264 | 2 | Held out | RAG docs-Q&A widget embedded by OTHER companies' docs sites (OpenAI, Monday.com, Logitech) — content-collateral widget, same class as `trinitymedia.ai`/`gpteng.co` (#2729). |
| `castify.ai` | ai | 52,563 | 9 | Held out | OTT/CTV app-builder SaaS for content owners (B2B), not a consumer AI product. |
| `higgs.ai` | ai | 664,413 | 4 | Held out — unverified | Single `images.` subdomain observed; no confirmed distinct company identity (search only surfaced the unrelated Higgsfield AI, which has its own `higgsfield.ai` apex already curated). |
| `flux.ai` | ai | 14,176,183 | 22 | Held out — unverified | Subdomain shape (`app.`/`events-api.`/`payments.`/`shortcircuit-cdn.`) suggests a real SaaS product, but search could not confirm whether this is Black Forest Labs' official Flux image-gen domain or an unrelated "Flux" product (a no-code app builder brand also uses the name) — ambiguous identity, held rather than guessed. |
| `trinitymedia.ai` | ai | — | — | Skip (standing, #2729) | Embedded AI TTS widget, content-collateral. |

## Categories with zero genuine gaps this pass

`adult`, `games`, `social-media` — every candidate the keyword sweep surfaced
either resolved to an apex already curated, or to a false positive / standing
skip (table above). Per the #2503/#2212 learnings, an empty category section
is a valid, expected outcome and was not forced.

## Self-update

See the Learnings log in `.claude/skills/blocklist-pass/SKILL.md` for the new
entries from this run (BitTorrent-tracker decoy `desync.com`; the
`insidetracker.com` "track"-substring health-brand false positive; the
`dxtech.ai`/`kapa.ai`/`castify.ai` B2B-out-of-scope pattern for `ai.yml`).
