# Blocklist pass evidence — #2742 (2026-08-25)

30-day prod `recent-apexes` sweep, 26 devices, all curated categories
(`ads`, `adult`, `ai`, `gambling`, `games`, `social-media`).

## ads.yml — added

| Apex | Bytes | Hits | What it is |
|---|---|---|---|
| `tpdads.com` | 3.1M | 45 | The Publisher Desk — ad infra (`cdn.`/`prebid.` subdomains observed). HELD OUT unverified in #2122; confirmed this run. |
| `ad.gt` | 1.3M | 94 | Audigent — audience advertising-intelligence platform (confirmed via netify.ai). HELD OUT in #2503 for lack of identity confirmation ("general domain-redirect-ads context but no identity confirmation"); confirmed this run. |
| `broadstreetads.com` | 2.9M | 20 | Broadstreet Ads — ad manager for small/local publishers, #1-rated alternative to Google Ad Manager. |
| `blogherads.com` | 623K | 1 | BlogHer Ads — owned by SHE Media / Penske Media Corporation. |
| `admetricspro.com` | 12K | 2 | AdMetricsPro — independent ad network connecting publishers to ad exchanges/agencies. |
| `ad-delivery.net` | 116K | 15 | Flagged "Risky Advertiser" by Netify — obfuscated ad-delivery infra (undisclosed privacy policy, proxied identity). Same obfuscated-but-confirmed-function class as the Bigo Ads domains (#2348). |
| `html-load.com` | 68K | 41 | Documented adware/malvertising — displays fake "your ad blocker broke this page" prompts to coerce disabling ad blockers. |
| `html-load.cc` | 3.9K | 2 | Sibling evasion-TLD variant of `html-load.com`, same adware family. |
| `blisspointmedia.com` | 223K | 36 | Bliss Point Media (now part of Tinuiti) — algorithmic TV/OTT media-buying agency. Same attribution/media-buying class as the already-curated `everesttech.net`/`ml314.com`. |

## ads.yml — dual-use / wrong-category SKIPPED (no change from standing calls)

- `bounceexchange.com` (216K bytes, 20 hits) — Wunderkind/BounceX, an on-site
  exit-intent personalization tool. Already classified dual-use in a prior
  pass (live comment in `ads.yml` around the #2503-era additions); this run's
  "session-recording tools go in ads" reasoning was considered but the
  standing call is respected per the "check prior evidence before re-adding"
  discipline (#2503's xlgmedia.com lesson) — no new fact overrides it.

## ads.yml — held out UNVERIFIED this run

- `ad-score.com` (3.7M bytes, 134 hits) — at least three unrelated companies
  share the "AdScore" name (Adscore.com ad-fraud detection, AdScore.org
  automotive-ad compliance, a marketing-analytics "AdScore" metric). Could
  not confirm which owns this exact hyphenated domain. Previously "deferred
  to ads-extended" in #1923 (lower-priority, not disqualified) — still
  unconfirmed, carried forward.
- `akiads.net` (1.4K bytes, 15 hits) — no identifiable owner found via search.
- `adapexads.io` (147K bytes, 18 hits) — distinct domain from the confirmed
  ad-tech company Adapex (`adapex.io`); no direct confirmation the "-ads"
  variant belongs to the same company. Self-descriptive name is suggestive
  but not confirmed — held out per "don't guess from the name alone."
- `brainlyads.com` (790K bytes, 1 hit) — weak, unconfirmed "offers advertising
  services" description only; no named company or netify/whois confirmation.
- `adsensecustomsearchads.com` (480K bytes, 8 hits) — confirmed Google-owned,
  a dedicated new serving domain for AdSense-for-Search/Shopping (Google's
  own 2023 migration off shared `google.com` for privacy reasons). NOT on the
  `SharedGfeHosts.scala` banned list, but held out anyway rather than risk
  reproducing the #2601 shared-GFE-collateral failure mode without IP-level
  confirmation this domain sits on dedicated (non-shared) frontends. Worth
  revisiting if/when IP-resolution evidence is gathered.
- Re-confirmed already held-out (no new information this run): `desync.com`,
  `powerad.ai`, `componecat.ai`, `conduit.ai`, `connectmachine.ai`,
  `e-volution.ai`, `elixion.ai`, `betrad.com`.

## ads.yml — false positives (name matched a category regex, not ad-tech)

- `4kdownload.com` — video downloader tool (matched `ad[s._-]` inside "downl**oad.**com").
- `tinkercad.com` — Autodesk's 3D design tool for kids/education (matched `ad[s._-]` inside "tinker**cad.**com").
- `badssl.com` — TLS/SSL testing utility (matched `ad[s._-]` inside "b**ads**sl.com").
- `landspace.com` — LandSpace, a Chinese commercial rocket-launch company (matched `dsp` inside "lan**dsp**ace.com"). 12.9M bytes / 4 hits — an odd high-byte-low-hit shape, likely firmware/telemetry downloads unrelated to ads.
- `insiad.com` — redirects to INSEAD business school's official site (matched `ad[s._-]` inside "ins**iad.**com").
- `hellometrics.co` — HelloMetrics, a PPC/SEO marketing agency's own corporate website (not embedded tracking infra).
- `stageagent.com` — theater/musical resource database (already-documented false positive on "tag" substring, re-confirmed).

## adult.yml — no gaps

Only `cam4.com` appeared on the adult keyword sweep, and it is already
curated. Zero-gap pass for this category — a valid, expected outcome per
prior-pass precedent (#2503).

## gambling.yml — no gaps

- `bethsbees.com` (13.0M bytes, 4 hits) — Bee Squared Apiaries, a Colorado
  beekeeping company (false positive on "bet" substring inside "**beth**sbees.com").
- `betrad.com` — already held out as ambiguous (Betradar/Sportradar-adjacent
  B2B sports-data infra, or a standalone gambling-adjacent tracker); no new
  information this run.

Zero genuine gambling gaps this pass — a valid, expected outcome.

## games.yml — added

| Apex | Bytes | Hits | What it is |
|---|---|---|---|
| `eaglercraft.dev` | 35.8M | 94 | Fourth Eaglercraft TLD variant (highest-traffic yet) — same "unblocked games" browser-Minecraft-clone evasion vein as the already-curated `.com`/`.ru`/`-game.io` apexes. |
| `poki-gdn.com` | 24K | 2 | Sibling apex of the already-curated `poki.com`/`poki-cdn.com` cluster — "gdn" CDN-suffix naming pattern, Amazon-registrar + Cloudflare infra consistent with `poki-cdn.com`. |

## games.yml — held out UNVERIFIED this run

- `poki.io` (1.3M bytes, 54 hits) — several unrelated brand-squatting clone
  sites reuse the "Poki" name for copycat game portals (`poki.us.com`,
  `poki.us.org`, `poki.to`, `pokigames2.com`), and the bare `poki.io` apex
  does not currently resolve, so ownership by the real Poki company could
  not be confirmed. Revisit if a future pass finds stronger evidence.

## games.yml — false positives

- `prodigygame.com` — educational/classroom math game, already-documented
  out-of-scope for a games *block* (#2212).
- `steamboatpilot.com` — a Colorado newspaper, already-documented false
  positive on "steam" substring (#2503).

## social-media.yml — added

| Apex | Bytes | Hits | What it is |
|---|---|---|---|
| `telegram.org` | — | — | Telegram's main domain. A categorical absence — every other major messaging/social platform (WhatsApp, Discord, Reddit, TikTok, etc.) was already covered, but Telegram had zero representation anywhere in the file. Same "is a major platform missing outright" check the #2599 whatsapp.net addition established. |

`ads-twitter.com` and the `tiktokpangle*` cluster also matched the
social-media keyword sweep but are already covered — via `ads.yml`, not
`social-media.yml` (they're ad/tracking infra, correctly categorized
there). No action needed; cross-category membership check confirmed they're
enforced already.

## ai.yml — added

| Apex | Bytes | Hits | What it is |
|---|---|---|---|
| `higgsfield.ai` | 198M | 22 | Consumer-facing generative AI video/image platform (22M+ users, cinematic AI video/image creation) — squarely in-scope per the image/video-generator signal. Standout gap this pass. |

## ai.yml — held out UNVERIFIED this run

- `higgs.ai` (664K bytes, 4 hits) — much lower traffic than `higgsfield.ai`
  and no direct confirmation it belongs to the same company; search results
  conflated the two names. Held out rather than assumed.
- `axon.ai` — already curated, but in `ads.yml` (AppLovin's Axon ad
  platform, #2599), not `ai.yml`. Re-confirmed correct as-is.
- `programmaticx.ai` — already curated, but in `ads.yml` (ProgrammaticX, a
  programmatic ad-tech vendor, #2729), not `ai.yml` — despite the `.ai` TLD,
  its function is ad-tech, not a consumer AI product. Re-confirmed correct.

## ai.yml — out of scope (verified identity, not a consumer AI product)

- `castify.ai` — B2B OTT/CTV app-building platform for content creators, not
  a consumer-facing chatbot/generator.
- `dxtech.ai` — B2B AI/digital-transformation consultancy (eKYC, automation)
  for SMEs, not a consumer AI product.
- `ivy.ai` — AI chatbot *widget* embedded on college/hospital/government
  websites, same "embedded SDK/widget riding on unrelated sites" carve-out
  as trinityaudio.ai/gpteng.co (#2729) — not itself the "product" a
  household would browse to.
- `navless.ai` — B2B website-discovery/AI-search-visibility SaaS for
  companies, not a consumer AI product.
- `securiti.ai` — enterprise data-privacy/security SaaS (DSPM, PrivacyOps),
  not a consumer AI product.
- `copilot.money` / `copilotmoney.app` — already-documented false positive,
  a personal-finance app (#2503).

## Self-update

Learning appended to `.claude/skills/blocklist-pass/SKILL.md`'s Learnings
log — see that file's newest entry for this run's takeaways.
