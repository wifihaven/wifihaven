# Blocklist pass — multi-category classification evidence (#2756)

30-day prod `recent-apexes` sweep, all 28 devices, run 2026-09-01. 1,781
unique apexes observed. Traffic this pass was unusually developer/infra-heavy
(apple.com, google.com, anthropic.com, openwrt.org, wifihaven.net, zoom.us,
claude.ai, warp.dev dominated the top of the ranked list) — consistent with
the #2599 finding that this does not suppress genuine gaps once the full
ranked list is swept by category keyword rather than by top-N volume.

## ads.yml — 14 additions

| apex | bytes | hits | what it is |
|---|---|---|---|
| `trygravity.ai` | 107,161 | 11 | Gravity — "The Ad Network for AI"; embeds contextual ads into AI-chat/AI-app output. Own docs URL is a `track/click` redirect. |
| `koah.ai` | 73,820 | 8 | Koah — "AdSense for AI" / "The Ad Network Built for AI"; $26M+ raised (TechCrunch, AdWeek, SiliconANGLE confirmed). |
| `adpushup.com` | 4,647,297 | 452 | AdPushup — ad-revenue-optimization + adblock-recovery platform, Google Certified Publishing Partner, acquired by Geniee. |
| `btloader.com` | 1,517,202 | 119 | Blockthrough — the market-leading adblock-revenue-recovery script loader (netify-confirmed). Same anti-adblock function class as getadmiral.com (#2503). |
| `adlightning.com` | 2,842,844 | 94 | Ad Lightning (now Boltive) — programmatic ad-quality/ad-security platform for publishers, SSPs, DSPs. |
| `s4mdsp.com` | 1,830,524 | 7 | S4M — Paris-founded mobile-native DSP, 500+ advertisers. |
| `adelement.com` | 1,742,346 | 18 | AdElement — AI-native app-focused DSP; Inc 5000, "Google Agency of the Year" (confirmed via own site — stronger than the #2212/#2348 "plausible, unconfirmed" hold-out). |
| `adentifi.com` | 653,010 | 48 | AdTheorent — predictive digital advertising; confirmed via netify (bm./px. subdomains are its opt-out + pixel infra). |
| `advanseads.com` | 9,533,057 | 17 | Advanse — creative-automation platform for advertisers. |
| `mediatradecraft.com` | 747,974 | 18 | Media Tradecraft — ad-tech monetization / programmatic consulting firm; Digiday/AdExchanger award winner. |
| `playdigo.live` | 78,079 | 5 | Playdigo — mobile user-acquisition DSP; sibling of the already-curated `ssp.playdigo.com` family. |
| `osdrtb.net` | 362,796 | 7 | No public company page, but `cdn.`/`trk.` subdomains + literal "rtb" in the apex is the textbook RTB-bidder shape (same reasoning as gamaibids.com, #2122). |
| `displayio.cloud` | 121,965 | 9 | Sibling apex of the already-curated `display.io` (in-app ad-monetization platform) — same multi-apex pattern as Verve Group (#1923). |
| `playwire.com` | 55,860 | 4 | Playwire's RAMP ad-monetization platform for casual/browser games (used by the already-curated Coolmath Games among others) — pure ad-monetization infra, not the game content itself, so not the target-video.com/brid.tv content-collateral class. |

All 14 confirmed absent from `ads.yml`'s curated set before this run.
Cross-checked against StevenBlack `ads-extended`: `trygravity.ai`, `koah.ai`,
`s4mdsp.com`, `mediatradecraft.com`, `osdrtb.net`, `displayio.cloud`, and
`playdigo.live` are absent from the feed entirely (highest-value gaps);
`adpushup.com`, `adlightning.com`, `adelement.com`, `btloader.com`,
`adentifi.com`, `advanseads.com` appear only via specific subdomains in the
feed — the curated apex entry is still the stronger block (suffix-matches
every subdomain, not just the one the feed lists), per the #2122 precedent.

### HELD OUT / REJECTED (ads)

- **`claude.com`** (41,460,856 bytes / 11 hits) — resurfaced in traffic
  (unsurprising: this operator runs Claude Code heavily) but this is a
  **documented standing exclusion**, not a fresh candidate. #2348 and #2503
  both already ruled on it: it's WifiHaven's own Anthropic-vendor
  infrastructure, and separately, per the `ai.yml` host-scoping convention
  (list product subdomains, not the bare vendor apex), the product itself is
  the already-curated `claude.ai` — `claude.com` is the corporate/support
  apex. Re-examined and correctly excluded again; not added to any list.
- **`akiads.net`** (1,433,041 bytes / 15 hits) — this run found `loader.`/
  `serve.` subdomains, a plausible ad-serve shape, and "ads" literally in the
  apex. But this is the exact same apex #2742 already held out one pass ago
  as "no identifiable owner found" (1.4K bytes / 15 hits then — same hit
  count, i.e. no new traffic signal either). A subdomain-naming pattern is
  weaker evidence than a confirmed company/product identity and isn't new
  corroboration over what #2742 already had visibility into. Stays held out.

## social-media.yml — 1 addition

| apex | bytes | hits | what it is |
|---|---|---|---|
| `truthsocial.com` | 4,554,969 | 5 | Truth Social — Trump Media & Technology Group's consumer social network. Categorical absence: every other major platform (Facebook, Instagram, TikTok, Reddit, WhatsApp, Telegram, etc.) was already curated but Truth Social had zero representation — same "missing outright" check established by #2599 (WhatsApp) and #2742 (Telegram). |

### HELD OUT / REJECTED (social-media)

- **`lazybumblebee.com`** (6,770,464 bytes / 304 hits, only the
  `d.lazybumblebee.com` subdomain observed) — a JustAnswer forum post this
  run asked "what is d.lazybumblebee.com" and a commenter linked it to
  Bumble's dating app. That is weak, unauthoritative sourcing. **FOUR** prior
  passes (#2212, #2348, #2503, #2729) already investigated this exact
  apex's actual site content directly and found it to be an unrelated
  lifestyle/wellness blog — "not evidence of Bumble ownership" (#2729's
  explicit verdict). One ambiguous forum answer does not meet the bar to
  reverse a repeatedly re-investigated rejection. Stays held out.

## games.yml — 1 addition

| apex | bytes | hits | what it is |
|---|---|---|---|
| `wordplays.com` | 373,898 | 11 | Wordplays.com — a dedicated browser word-game site (Scrabble/Boggle/Words-with-Friends helper plus playable games), same casual-browser-game-portal class as the already-curated coolmathgames.com/miniclip.com/y8.com. |

### HELD OUT / REJECTED (games)

- `mathplayground.com`, `prodigygame.com` — already-known educational/
  classroom-game exclusions (#2212), out of scope for a games *block* list.
- `viddea.com` — no confirmed single identity (several similarly-named,
  unrelated video products); already noted unverified in a prior pass, no
  new signal this run.
- `shulgfea.com` — false positive: matched the games sweep's
  `ea\.com`-substring pattern (`...gfea.com` contains the literal substring
  `ea.com`), not an actual EA/gaming-related domain. No identifiable content.

## ai.yml — 0 additions

Every AI-shaped candidate this run resolved to a developer tool, B2B SaaS
widget, embedded third-party service, or an existing-vendor apex that's
already curated (or, for the two ad-network cases, correctly belongs in
`ads.yml` instead — see above):

- `opencode.ai` (already-known dev-tool exclusion, #2729), `yegge.ai`
  (already-known personal-blog exclusion, #2599).
- `axon.ai`, `trinitymedia.ai`, `trinityaudio.ai`, `programmaticx.ai` —
  already-curated elsewhere (`ads.yml`) or already-documented dual-use
  widget skips; re-confirmed absent-because-not-applicable, not new gaps.
- `higgsfield.ai`, `x.ai`, `openai.com`, `gemini.google` — already curated.
- `forethought.ai` (B2B customer-service AI platform, enterprise-embedded —
  same "professional tooling, not a consumer product a household would
  block" reasoning as opencode.ai), `ivy.ai` (B2B chatbot widget for
  higher-ed/healthcare/public-sector institutions, embedded on unrelated
  sites — dual-use, same class as trinitymedia.ai), `castify.ai` (OTT/CTV
  app-distribution platform, not a consumer AI product), `flux.ai` (a PCB
  eCAD design tool — developer tooling, not the unrelated FLUX
  text-to-image model), `navless.ai` (B2B buyer-experience/marketing
  platform) — all confirmed via web search, all out of `ai.yml`'s
  consumer-chat/assistant/companion/generator scope.
- `dxtech.ai`, `higgs.ai`, `koah.ai` (moved to ads.yml — see above),
  `trygravity.ai` (moved to ads.yml — see above), `theagenticx.ai` — low
  hit counts (1–59), no confirmed consumer-AI-product identity found; held
  out unverified, consistent with the long-standing ".ai TLD alone is not a
  signal" pattern (#2599).
- `claude.com` — see the ads.yml HELD OUT section above; documented standing
  exclusion, not an ai.yml candidate either.

## gambling.yml / adult.yml — 0 additions (empty categories this pass)

- **gambling**: broad keyword sweep (`bet`, `casino`, `poker`, `gambl`,
  `sportsbook`, `wager`, `slot`, `lottery`, `jackpot`) surfaced exactly one
  candidate, `bethsbees.com` (1,178,928 bytes / 3 hits) — confirmed via web
  search to be Bee Squared Apiaries, a Colorado honey/beeswax-products
  company ("Beth's Bees"). False positive on the `bet` substring. No other
  candidates; every already-curated gambling apex (draftkings, fanduel,
  betmgm, acebet.cc, etc.) that appeared in traffic was already covered.
- **adult**: broad keyword sweep (`porn`, `xxx`, `sex`, `adult`, `cam4`,
  `xnxx`, `xvideo`, `onlyfans`, `nsfw`, `escort`, `hookup`, `milf`,
  `fetish`, `redtube`, `pornhub`, `brazzers`, `chaturbate`, `stripchat`,
  `livejasmin`) surfaced zero apexes at all. Consistent with the #2503/#2599
  finding that this category can go a full pass with no traffic-observed
  apexes.

## Method notes

- Extraction smoke-tested against sentinels `pubmatic.com` / `adnxs.net`
  (both hit) before trusting the curated-set diff; used
  `sed -E 's/^  - //; s/[[:space:]]*#.*$//'` to strip trailing inline
  comments (the #2742 lesson) before matching.
- Every candidate's history was checked against `evidence/*.md` (not just
  the current `.yml` content) before adding — this caught `claude.com` and
  `lazybumblebee.com`, both of which would otherwise have been silently
  re-added as "new" gaps despite standing rejections.
