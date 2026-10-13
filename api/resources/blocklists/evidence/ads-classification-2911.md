# Blocklist pass — ads classification evidence (#2911)

30-day prod `recent-apexes` sweep, all 30 devices, run 2026-10-13 (1,823
unique apexes observed). Also ran a serial `/api/logs` keyword sweep for
common adult-site substrings (`chaturbat`, `livejasmin`, `myfreecams`,
`stripchat`, `bongacams`, `xvideos`, `xhamster`, `brazzers`, `onlyfans`,
`hours=720`) — zero rows across all nine, consistent with the historical
pattern of adult going full passes with zero new signal (#2212, #2503).

Every candidate was checked against `evidence/*.md` history (not just the
current `.yml`) and against every curated category's host set (ads, adult,
ai, gambling, games, social-media) before being treated as a new gap.

## ads.yml — 3 additions

| apex | bytes | hits | what it is |
|---|---|---|---|
| `bttrack.com` | 816,324 | 91 | Bidtellect — US native-advertising RTB exchange. Confirmed via Netify (lists `api.bttrack.com`/`cdn.bttrack.com`/`video.bttrack.com` as Bidtellect infra) and Cookiepedia (classifies its cookies as Targeting/Advertising; AlienVault OTX logs show a `/pixel/cookiesync` endpoint). Also already present in the `ads-extended` (StevenBlack) feed under the apex and all three subdomains. The "bt" prefix superficially resembles the open-source `bttrack` BitTorrent-tracker tool (and the device's traffic included nighttime BT-tracker-decoy hits the same week — see held-out section), but that is an unrelated project with the same name, not the owner — observed firing inside a dense RTB/prebid ad-pixel burst (alongside `tps.doubleverify.com`, `prebid-server.rubiconproject.com`, `pixel-sync.sitescout.com`, etc.) on an ad-heavy publisher page corroborates the Bidtellect identity over the name-collision reading. |
| `theagenticx.ai` | 11,008 | 1 | agenticX — self-described (own marketing page) as "a true agentic advertising platform" where AI agents predict, negotiate, and act in real time to optimize programmatic advertising; serves both publishers (PMP deals) and DSPs/agencies (OpenRTB-compatible demand access). Another instance of the `.ai`-TLD-hides-an-ad-network trap (`axon.ai`/`programmaticx.ai`/`trygravity.ai`/`koah.ai`/`mediayo.ai`/`nexverse.ai`) — filed in `ads.yml`, not `ai.yml`. Observed host: `sync.theagenticx.ai` (cookie-sync-shaped subdomain, consistent with the identity). |
| `loudecho.ai` | 9,611 | 2 | LoudEcho — self-described (own marketing page) as an "agentic AI ad platform" that buys media, generates ad creative, and optimizes placements in real time for marketers and publishers; explicitly "aimed at marketers and publishers, not consumers." Same `.ai`-TLD ad-network trap as above. Observed host: `cs.loudecho.ai`. |

### Held out / skipped this pass (ads)

- `syncingbridge.com` (548,551B/42 hits) — re-observed (same apex #2836 held out), still no confirmed company identity; ScamAdviser shows it as a recently-registered domain with an average/good trust score, Joe Sandbox rates a scanned URL clean, but no source names an owner or product. Co-occurs on the same two MACs as `bttrack.com` within minutes on several dates, which is suggestive but not itself identity confirmation — held out again rather than added on traffic-proximity alone.
- `delivrdsp.ai` (13,081B/1 hit) — maximally self-descriptive ("Deliver DSP") in the `inhousedsp.com` style, but the site didn't load (TLS error), no identity source confirms a company, and it's absent from the `ads-extended` feed — below the two-signal bar `inhousedsp.com` needed (self-descriptive name + independent corroboration). Only 1 hit in 30 days and zero rows in `/api/logs` over 744h (consistent with the #2823 cross-surface-disagreement finding — a `recent-apexes` hit with no `connection_events` corroboration is still treated as real, just thin). Carried forward for a future pass.
- `linkedin-ei.com` (134,702B/24 hits) — confirmed LinkedIn Corporation ownership (whois: registrant org "LinkedIn Corporation", registrar MarkMonitor, created 2012; the bare apex resolves to a dedicated Microsoft address, 150.171.22.16) but the only observed host, `rum22.perf.linkedin-ei.com` (a RUM/performance-monitoring endpoint), resolves onto Cloudflare (`104.18.17.118`/`104.18.16.118`). Per the #2823 rule ("check which host actually appears in traffic, not which address the apex happens to answer on"), this is a structural CDN skip regardless of confirmed ownership — adding it would arm `bl_ads` with shared Cloudflare edges.
- `bttrack.com`'s sibling BT-tracker-decoy traffic this pass: `bttrack.com` was NOT filed as a BT tracker (see above) but genuine BitTorrent-tracker hits recurred this week on the same two MACs at overnight hours — out of scope for any curated category here, noted only as context for the name-collision call above.

## adult.yml / gambling.yml — no changes

Zero hits across both `recent-apexes` and the nine-keyword `/api/logs` sweep
for adult. `gambling.yml`'s existing entries (`stake.us`, `betrivers.com`,
etc.) appeared in traffic but are already curated; `betweendigital.com`
(ad-tech SSP, already correctly filed under `ads.yml` per #2503) was the
only `bet`-substring match and is not a new gap.

## ai.yml — no changes

`.ai`-TLD sweep surfaced several candidates, all resolved to out-of-scope
or ad-tech, none to a genuine `ai.yml` gap:

- `sierra.ai` (26,124,808B/4 hits) — Bret Taylor's enterprise customer-service-AI platform (Sierra, $4.5B valuation per TechCrunch). The household's own traffic includes `sonos.com` (a confirmed early Sierra customer); this is a B2B chat widget embedded in another company's support flow, not a product the household chose directly — same content-collateral/widget class as `trinitymedia.ai`/`kapa.ai` (#2729).
- `typesafe.ai` (3,162,731B/14 hits) — observed host `docs.typesafe.ai`. TypeSafe AI's product (Jev) is explicitly "AI for software, not people" — a developer-facing structured-decision API, out of `ai.yml`'s consumer-product scope (same class as `dxtech.ai`/`castify.ai`, #2792).
- `ivy.ai` (2,833,266B/28 hits) — observed host `bot.ivy.ai`. Confirmed B2B white-label chatbot platform sold to colleges/universities/healthcare/government, embedded on institutional sites — same widget-collateral class as `sierra.ai` above.
- `verysane.ai` (653,884B/38 hits) — confirmed to be a Substack newsletter ("Very Sane AI Newsletter"), not a product at all.
- `wandb.ai` (10,556,153B/34 hits) — Weights & Biases, an MLOps/experiment-tracking SaaS for ML engineers; B2B developer tooling, out of scope regardless of ad-tech status.
- `learnings.ai` (74,309,282B/1,959 hits) — observed hosts `api.learnings.ai`/`api-abtest-cdn.learnings.ai`/`hermes-api.learnings.ai`. Highest-traffic unidentified candidate this pass; no search or page fetch confirmed a company or product. Held out unverified — worth a focused look next pass given the traffic volume.
- `hellohaven.ai` — already curated (added #2836 after the "is this us" check cleared it); re-confirmed present, no action.

## Self-update

Appended to `.claude/skills/blocklist-pass/SKILL.md`'s Learnings log in the
same PR: the `bttrack.com`/Bidtellect name-collision-with-an-unrelated-
open-source-tool finding, and the `linkedin-ei.com` "confirmed first-party
ownership does not clear the #2823 CDN rule" finding.
