# Blocklist pass — multi-category classification evidence (#2759)

30-day prod `recent-apexes` sweep, all 28 devices, run 2026-09-08. 1,792
unique apexes observed. Every candidate's history was checked against
`evidence/*.md` (not just the current `.yml` content) and against **every**
curated category's host set (not just the one the keyword sweep bucketed it
into) before being treated as a new gap — this caught `axon.ai`,
`programmaticx.ai`, `trygravity.ai`, `koah.ai` (already curated in `ads.yml`
but re-surfaced by the `ai.yml` `.ai`-TLD sweep) and `tiktokpangle.us` /
`tiktokpangle-cdn-us.com` / `tiktokpangle-b.us` / `ads-twitter.com` (already
curated in `ads.yml` but re-surfaced by the `social-media.yml` sweep).

## ads.yml — 16 additions

| apex | bytes | hits | what it is |
|---|---|---|---|
| `adspostx.com` | 211,844 | 6 | AdsPostX — retail-media post-transaction ad network (LinkedIn/Crunchbase/BusinessWire confirmed). |
| `progrtblive.com` | 66,488 | 3 | No public company page found; self-descriptive "programmatic RTB live" name shape (same reasoning as `gamaibids.com`/`osdrtb.net`), absent from `ads-extended` entirely. |
| `tradplusad.com` | 54,300 | 1 | TradPlus — Chinese mobile ad-mediation SDK, integrates 40+ ad networks, 50B+ daily ad requests (confirmed via own docs + Yahoo Finance/AccessWire coverage). |
| `connectad.io` | 46,890 | 2 | ConnectAd — independent European SSP, 250+ direct publisher sites, GDPR-native. Corroborated: 6 hosts in `ads-extended`. |
| `ethicalads.io` | 21,479 | 8 | EthicalAds — privacy-focused ad network for developer sites (Flask, ESLint, daily.dev docs use it); still ad-serving infra regardless of its no-tracking framing. |
| `microad.jp` | 8,429 | 1 | MicroAd — established Japanese DSP/SSP, founded 2007. Corroborated: 21 hosts in `ads-extended`. |
| `exelbid.com` | 1,461 | 5 | ExelBid — Korea's #1 mobile ad exchange, OpenRTB-based, 4B+ daily ad requests (own site + Adstxt.com confirmed). |
| `bidence.net` | 1,253 | 9 | Bidence — OpenRTB ad exchange, est. 2016, 200B+ monthly ad requests. |
| `mathads.com` | 87,656 | 1 | Confirmed via two independent sources (sur.ly's `creative.mathads.com` listing + a mathtag.com/MediaMath ownership search) as a MediaMath apex, sibling of the well-known `mathtag.com` ad-tag domain. Corroborated: 1 host in `ads-extended`. |
| `bids.ws` | 499,152 | 47 | Programmatic ad system with `sellers.json` entries per well-known.dev; also flagged by consumer antivirus (Avast) as an intrusive tracker on unrelated sites. |
| `tvpixel.com` | 108,372 | 17 | Nielsen/D+M TV ad-tracking pixel (`p.tvpixel.com`), used for third-party TV-ad-impression tracking. Corroborated: 3 hosts in `ads-extended`. |
| `ladsp.com` | 12,545 | 2 | F@N Communications' ad tracking/personalization domain (Japanese ad-tech; registered to F@N's own director), 4,600+ cookies observed across 960+ sites. Corroborated: 2 hosts in `ads-extended`. |
| `gsspat.jp` | 8,808 | 1 | Geniee's ad-delivery domain — Geniee is the same company behind the already-curated `adpushup.com` (#2756). Corroborated: 2 hosts in `ads-extended`. |
| `mediayo.ai` | 74,302 | 4 | MediaYo — "AI-driven programmatic advertising" / Dynamic Creative Optimization company. A `.ai`-TLD ad-tech company, same trap class as `trygravity.ai`/`koah.ai`/`programmaticx.ai` (#2599/#2742/#2756) — correctly belongs in `ads.yml`, not `ai.yml`, despite matching the `ai` sweep on TLD. |
| `paa-reporting-advertising.amazon` | 723,419 | 228 | Amazon's own ad-reporting/pixel infrastructure for Amazon Ads (netify-confirmed "Amazon Advertising" application; literal hostname is "PAA reporting advertising"). Distinct from the `bdtelemetry.amazon` first-party-telemetry skip (#2122) — that one was general product telemetry, this one is specifically Amazon Ads' own reporting pixel. Corroborated: 1 host in `ads-extended`. |
| `epicgameshubham.com` | 3,320,845 | 1 | A scam/phishing domain impersonating Epic Games — 25/100 trust score (Scamadviser/Gridinsoft), blacklisted, tracked in AdGuard's filter lists (`AdguardTeam/AdguardFilters#233548`) as adware/malvertising. Confirmed NOT affiliated with the real Epic Games (`store.epicgames.com`), so no collateral risk. Same "malvertising flag doesn't disqualify — still ad-category" reasoning as `gamaibids.com` (#2122). |

Cross-checked against StevenBlack `ads-extended`: `adspostx.com`,
`progrtblive.com`, `tradplusad.com`, `ethicalads.io`, `exelbid.com`,
`bidence.net`, `bids.ws`, `mediayo.ai`, `epicgameshubham.com` are absent from
the feed entirely (highest-value gaps); `connectad.io`, `microad.jp`,
`mathads.com`, `tvpixel.com`, `ladsp.com`, `gsspat.jp`,
`paa-reporting-advertising.amazon` appear only via specific subdomains — the
curated apex entry is still the stronger block (suffix-matches every
subdomain, not just the one the feed lists), per the #2122 precedent.

### HELD OUT / REJECTED (ads)

- **`desync.com`** (4,311,260 bytes / 17,731 hits) — high hit count but still
  no confirmed ad-tech identity after a fourth pass (#2599, #2729, #2742,
  now this run). Carried forward held out.
- **`akiads.net`** (795,282 bytes / 10 hits) — same apex #2742/#2756 already
  held out as "no identifiable owner found"; no new corroborating signal
  this run either. Stays held out.
- **`ad-score.com`** (4,356,368 bytes / 127 hits) — still matches three
  distinct unrelated "AdScore" companies with no way to tell which owns the
  hyphenated domain (#2742's standing verdict). Stays held out.
- **`imganalytics.com`** (319,740 bytes / 14 hits) — still ambiguous between
  IMG sports-data analytics and HUMAN anti-bot infra (#2122's standing
  verdict). Stays held out.
- **`impression.link`** (228,719 bytes / 38 hits) — appears in the
  #2212/#2348 "unverified, carry forward" lists; still no confirmed identity
  this run. Stays held out.
- **`rtbedge.com`** (1,202,330 bytes / 2 hits) — no confirmed company found
  despite an RTB-shaped name; low hit count, held out unverified rather than
  guessed.
- **`oasisadx.com`** (70,976 bytes / 4 hits) — no confirmed company found
  despite an ADX-shaped name; held out unverified.
- **`al-ad.com`** (19,903 bytes / 1 hit) — no identifiable owner found; held
  out unverified.
- **`ad-m.asia`** (19,903 bytes / 1 hit) — no identifiable owner found; held
  out unverified.
- **`binsiad.com`** (480 bytes / 2 hits) — Amazon-registrar + identity-proxy
  registration, poor trust score, a `cdn.binsiad.com/ins-ag/ins-ag.min.js`
  script (plausibly "insertion ad", but unconfirmed) — same naming-confusion
  risk as the already-resolved `insiad.com` false positive (#2742: INSEAD
  business school). Insufficient confidence to classify either way; held out.
- False positives on substring matches, not added: `tinkercad.com` (Autodesk
  kids' CAD tool, "**ad**.com" substring), `4kdownload.com` (video/audio
  downloader tool, "**ad**.com" substring in "download"), `landspace.com`
  (already-documented LandSpace rocket company, "**dsp**" substring, #2742),
  `insiad.com` (already-documented INSEAD redirect, "**ad**" substring,
  #2742).
- Dual-use, not added: `app-ads-services.com` / `app-analytics-services.com`
  (Google, already-documented GFE-adjacent shared infra, #1923), `google-
  analytics.com` / `googleadservices.com` (GFE-shared, explicitly removed
  per #2601 — do not re-add), `hs-analytics.net` (HubSpot's own tracking
  domain — widely embedded first-party CRM/marketing tool, same dual-use
  class as Google Analytics), `siteimproveanalytics.com` /
  `siteimproveanalytics.io` (Siteimprove — web governance SaaS, already-
  documented false positive, #2599), `instaread.co` (enterprise TTS-widget
  SaaS, already-documented dual-use, #2729), `merchant-center-
  analytics.goog` (Google's own internal Merchant Center analytics, `.goog`
  gTLD — same first-party-telemetry class as `bdtelemetry.amazon`, #2122).
- Wrong category / content-collateral, not added: `smartborad.com`
  (malware-flagged, ambiguous identity — already-documented as a `malware`-
  list candidate at best, not `ads`, #2064), `opentrackr.org` /
  `popcorn-tracker.org` (BitTorrent trackers, high-hit decoys, already-
  documented), `freebeacon.com` (Washington Free Beacon — news content,
  "**beacon**" substring, already-documented #2122), `gameanalytics.com`
  (game-developer analytics SDK — dual-use, see games.yml section below).

## ai.yml — 2 additions

| apex | bytes | hits | what it is |
|---|---|---|---|
| `delphi.ai` | 9,349,462 | 33 | Delphi — consumer-facing AI-persona platform ("Create Your Digital Mind"); users converse with a personalized AI persona via text/voice/video across website/WhatsApp/Slack/Zoom. Squarely a consumer companion/chatbot product. |
| `deepai.org` | 1,215,146 | 12 | DeepAI — free, no-account-required consumer AI tool (chat, image/video/music generation); "a generalist consumer tool rather than a specialist business platform" per its own positioning. |

### HELD OUT / REJECTED (ai)

- **`claude.com`** (30,130,060 bytes / 8 hits) — documented standing
  exclusion (#2348/#2503/#2756): WifiHaven's own Anthropic-vendor infra, and
  separately the wrong tier vs. the already-curated product subdomain
  `claude.ai`. Re-examined and correctly excluded again.
- **`flux.ai`**, **`navless.ai`**, **`ivy.ai`**, **`trinitymedia.ai`**,
  **`forethought.ai`**, **`castify.ai`** — all documented standing
  exclusions from #2729/#2756 (PCB eCAD tool, B2B marketing platform, B2B
  chatbot widget, dual-use TTS/narration widget, B2B customer-service
  platform, OTT/CTV distribution platform respectively). Re-confirmed, no
  new evidence to reverse.
- **`kapa.ai`** (966,264 bytes / 2 hits) — new candidate this run. Confirmed
  via web search to be a B2B technical-documentation AI assistant embedded
  by OpenAI/Docker/Mapbox/CircleCI on their own product docs sites — same
  "professional tooling embedded on unrelated sites" class as `ivy.ai` /
  `forethought.ai`. Out of `ai.yml`'s consumer-chat/assistant/companion
  scope.
- **`dxtech.ai`**, **`higgs.ai`**, **`theagenticx.ai`** — low hit counts
  (1–79), no confirmed consumer-AI-product identity found across three
  passes now (#2742, #2756, this run). Carried forward held out.

## games.yml — 1 addition

| apex | bytes | hits | what it is |
|---|---|---|---|
| `wherewindsmeetgame.com` | 19,602,437 | 1 | Marketing/companion domain for Where Winds Meet — NetEase wuxia action-RPG, released on PS5/Windows (Nov 2025), Android/iOS (Dec 2025), Xbox Series X/S (Jun 2026). |

### HELD OUT / REJECTED (games)

- **`epicgameshubham.com`** (3,320,845 bytes / 1 hit) — matched the `games`
  sweep's "Epic Games" substring but is confirmed NOT the real Epic Games; a
  scam/phishing clone. Moved to `ads.yml` as a malvertising add instead
  (see that section above) rather than treated as a games.yml candidate.
- **`gameanalytics.com`** (1,249,246 bytes / 177 hits) — confirmed to be
  GameAnalytics, a first-party analytics SDK game *developers* embed in
  their own titles to understand their own players — same dual-use class as
  the already-skipped `app-measurement.com` (Firebase). Not third-party ad
  infra riding on top of game content; held out.
- `viddea.com`, `prodigygame.com`, `shulgfea.com` — re-confirmed standing
  exclusions from #2212/#2503/#2756 (no confirmed single identity;
  educational/classroom game, out of scope; false positive on the
  `ea\.com` substring pattern respectively). No new signal this run.

## social-media.yml — 1 addition

| apex | bytes | hits | what it is |
|---|---|---|---|
| `whatsapp.com` | 450,615 | 26 | WhatsApp's bare apex, sibling of the already-curated `whatsapp.net` (added #2599). That pass explicitly noted `whatsapp.com` "was not observed in traffic that pass so is not added here (add it if...)" — it appeared this run, so adding it now per that pass's own guidance. |

## gambling.yml / adult.yml — 0 additions (empty categories this pass)

- **gambling**: broad keyword sweep (`casino`, `poker`, `betting`,
  `sportsbook`, `slots`, `wager`, `blackjack`, `roulette`, `gambl`, `bet[.-]`
  / `[.-]bet`) surfaced **zero** candidate apexes at all this run — not
  even a false-positive substring match. Consistent with the #2503/#2599/
  #2756 finding that this category can go a full pass with no
  traffic-observed apexes.
- **adult**: broad keyword sweep (`porn`, `xxx`, `xvideo`, `xnxx`, `adult`,
  `nsfw`, `onlyfans`, `cam4`, `chaturbate`, `escort`, `erotic`, `milf`,
  `hentai`) surfaced exactly one apex, already fully covered by the curated
  list (`pornhub.com`, exact match) — zero net-new candidates. Consistent
  with the recurring low-incidence pattern for this category.

## Method notes

- Extraction smoke-tested against sentinels `pubmatic.com` (ads),
  `claude.ai` (ai), `redd.it` (social-media), and the inline-comment-bearing
  `acebet.cc` line (gambling) before trusting the curated-set diff; used
  `sed -E 's/^  - //; s/[[:space:]]*#.*$//'` to strip trailing inline
  comments (the #2742 lesson).
- Checked every keyword-sweep candidate against **all six** curated
  category files combined, not just the file the sweep bucketed it into —
  caught 4 `ai`-sweep matches (`axon.ai`, `programmaticx.ai`,
  `trygravity.ai`, `koah.ai`) and 4 `social-media`-sweep matches
  (`tiktokpangle.us`, `tiktokpangle-cdn-us.com`, `tiktokpangle-b.us`,
  `ads-twitter.com`) that were already curated in `ads.yml` — the #2742
  lesson.
- Every candidate's history was checked against `evidence/*.md` before
  running a fresh identity search — caught `claude.com` (standing
  exclusion), `desync.com`/`akiads.net`/`ad-score.com`/`imganalytics.com`/
  `impression.link` (carry-forward holds), `landspace.com`/`insiad.com`
  (documented false positives), and the `flux.ai`/`navless.ai`/`ivy.ai`/
  `trinitymedia.ai`/`forethought.ai`/`castify.ai`/`viddea.com`/
  `prodigygame.com`/`shulgfea.com` standing exclusions — the #2756 lesson.
