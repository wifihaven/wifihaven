---
name: blocklist-pass
description: Do a traffic-driven WifiHaven blocklist pass — pull real device traffic from prod, and for EVERY curated category (ads, adult, ai, gambling, games, social-media, …) find apex domains devices are hitting that the curated list for that category doesn't yet cover, then add the genuine gaps to the right api/resources/blocklists/<category>.yml. Invoke whenever the operator says "do a blocklist pass", "update the blocklists", "refresh the blocklists", "do an ads-blocklist pass", "update the ads/adult/ai/gambling/games/social blocklist", "what <category> traffic isn't blocked", "review traffic for unblocked <category>", or wants new blocklist entries in any category. Self-updating: records new learnings back into this file on every run.
---

# WifiHaven blocklist pass

Repeatable process for turning **observed device traffic** into curated
**blocklist** entries across **all** categories in
[`api/resources/blocklists/`](../../../api/resources/blocklists/). Sibling to the
`app-catalog-pass` skill — same evidence-driven method, same host-set discipline,
same validation + review gate — but the target is the **category blocklists**,
not brand apps.

Originally this skill covered only the **ads** category (`ads.yml` /
`ads-extended`); it now sweeps **every curated category** in one pass. The
ads-specific learnings below are retained verbatim — they are the richest, but
the same discipline applies to `adult`, `ai`, `gambling`, `games`,
`social-media`, and any category added later.

This file is the **process**. The **data + conventions** live elsewhere and are
read live — do not duplicate them here:

- The category lists + the manifest that names them → the `.yml` files in
  [`api/resources/blocklists/`](../../../api/resources/blocklists/) and
  [`api/resources/blocklists/_index.yml`](../../../api/resources/blocklists/_index.yml).
  The file schema (inline `hosts:` vs URL-sourced `url:`, required fields) is in
  [`api/resources/blocklists/_README.yml`](../../../api/resources/blocklists/_README.yml).
- Blocklist design (inline vs URL-sourced, refresh cadence) → [`docs/design/blocklists.md`](../../../docs/design/blocklists.md).
- Enforcement model (per-(mac,host) nftset drops; DNS never enforces) →
  [`AGENTS.md`](../../../AGENTS.md) + [`docs/architecture.md`](../../../docs/architecture.md).
- Host-scoping mechanics (host entries are suffix-matched, not apex-constrained)
  → the sibling [`app-catalog-pass`](../app-catalog-pass/SKILL.md) Step 3.
- The merge-gating independent review → [`docs/pr-review-checklist.md`](../../../docs/pr-review-checklist.md).

When this skill and those docs disagree, **those docs win** — update this skill
if the process itself changed (see Step 5).

---

## Which lists you edit — curated (inline) vs URL-sourced

Enumerate the categories **dynamically** from the directory / `_index.yml` at run
time so a newly-added category is picked up automatically — never hardcode a
stale list. Split them by content type (the `_README.yml` schema: exactly one of
`hosts:` or `url:` per file):

```bash
cd api/resources/blocklists
# curated inline lists — these are the ones a traffic pass HAND-EDITS
for f in *.yml; do case "$f" in _*) continue;; esac
  grep -qE '^hosts:' "$f" && echo "INLINE  ${f%.yml}"
  grep -qE '^url:'   "$f" && echo "URL     ${f%.yml}"
done | sort
```

- **INLINE (`hosts:`) lists are the pass's target.** As of this writing:
  `ads`, `adult`, `ai`, `gambling`, `games`, `social-media`. You append genuine
  apex gaps to these.
- **URL-sourced (`url:`) lists are NOT hand-edited.** As of this writing:
  `ads-extended`, `adult-extended`, `social-extended` (StevenBlack alternates),
  and `malware` (URLhaus). Their hosts are fetched from the upstream feed at API
  startup and the seeder **replaces** the DB rows each boot — a hand-added host
  would be wiped. Their role in this pass is a **coverage cross-check**: an apex
  already in a category's `-extended` feed is lower-value to hand-curate (it only
  helps curated-only profiles). Diff candidates against the sibling feed (Step 2)
  before adding to the curated list. If a genuine gap has no curated home but the
  upstream feed is stale, the fix is upstream / a feed swap — say so, don't
  hand-maintain hundreds of apexes.

Category ↔ sibling-feed pairs today: `ads`↔`ads-extended`, `adult`↔`adult-extended`,
`social-media`↔`social-extended`. `ai`, `gambling`, `games` have **no** upstream
sibling — for those the curated list is the only coverage, so a traffic pass is
the primary way they grow. Re-derive these pairings from `_index.yml` at run time
rather than trusting this line.

## Step 0 — Pull active traffic (READ-ONLY prod)

Same source + auth as `app-catalog-pass` Step 0. Prod `https://api.wifihaven.net`;
admin password in local memory (`prod_api_admin_password.md`) — read, never
echo/commit. Pull per-apex bytes/hits across **all** devices (category traffic is
everywhere, not just kid devices):

```bash
PW=$(grep -oE 'is `[^`]+`' ~/.claude/projects/*wifihaven*/memory/prod_api_admin_password.md | head -1 | sed 's/^is `//; s/`$//')
TOKEN=$(curl -sS -X POST https://api.wifihaven.net/api/auth/login -H 'Content-Type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"$PW\"}" | jq -r '.token')
MACS=$(curl -sS -H "Authorization: Bearer $TOKEN" https://api.wifihaven.net/api/devices | jq -r '.[].mac')
for mac in $MACS; do
  curl -sS -H "Authorization: Bearer $TOKEN" \
    "https://api.wifihaven.net/api/devices/$mac/recent-apexes?windowDays=30&limit=500" > /tmp/apex_$mac.json
done
jq -r '.items[] | "\(.apex)\t\(.bytes)\t\(.hits)"' /tmp/apex_*.json \
  | awk -F'\t' '{b[$1]+=$2; h[$1]+=$3} END{for(a in b) printf "%d\t%d\t%s\n", b[a], h[a], a}' \
  | sort -rn
rm -f /tmp/apex_*.json
```

> IPv4-bias caveat applies (#1796) — see `app-catalog-pass`. Re-confirm at run time.

## Step 1 — Bucket unblocked traffic by category

Walk the ranked apex list once and classify each into the category it **belongs
to**, if any. Signals per category (classify by what the apex *is*, never by a
substring match alone — a `track`/`beacon`/`ai` substring is a candidate flag,
never a verdict):

- **ads** — advertising exchange, RTB/SSP/DSP, tracker, data broker,
  attribution/measurement, tag network. Signal: many distinct subdomains, high
  hits, served to many devices, maps to no app or content category.
- **adult** — pornography / NSFW content sites.
- **ai** — consumer AI chatbots, assistants, AI search, companion/roleplay bots,
  image/video/audio/text generators. List the **product subdomain** (e.g.
  `gemini.google.com`), never the bare shared vendor apex — see the `ai.yml`
  host-scoping header.
- **gambling** — online casinos, poker, sportsbooks.
- **games** — game titles, storefronts, gaming platforms.
- **social-media** — consumer social networks, chat, dating.

An apex is a **candidate** for category `C` if it clearly belongs to `C` **and**
is not already covered by `C`'s curated list (nor, as a lower-value signal, by
`C`'s `-extended` feed — Step 2). Cross-check before adding — web-search / netify
/ whois any apex whose owner or function you're inferring from the name alone
(names lie: see the `antibanads` / `ttdns2` learnings). Be conservative.

**Skip dual-use / shared infra** that also carries legitimate product traffic —
same collateral rule as app host-sets: a shared pool drags non-category traffic
into the drop. Known traps to LEAVE OUT (category-agnostic): `google-analytics.com`,
`googleadservices.com`, `googlesyndication.com` (Google shared pools),
`app-measurement.com` (Firebase), `sentry.io` / `bugsnag.com` (error reporting),
and multi-tenant clouds/CDNs (`cloudflare.com`, `amazonaws.com`, `cloudfront`,
`fastly`, `akamai*`). See the Learnings log for the running list of per-category
dual-use skips.

Scale: a handful of high-traffic, clearly-in-category apexes per category per
pass is the right output. The RTB/bidder vein (ads) is the historically richest
and lowest-collateral because *the name is the function*; other categories tend
to yield fewer but higher-confidence adds. A small, honest count beats padding.

## Step 2 — Verify each gap is real

Before adding apex `X` to category `C`, confirm it isn't already blocked:

1. Load `C`'s curated `hosts:` from `<C>.yml` and check exact + subdomain
   membership. **Smoke-test the extraction against a known sentinel** (e.g.
   `grep -c '^doubleclick.net$'` for ads) before trusting it — a bad regex
   (macOS BSD `sed`/`grep` do NOT support `\s`; use `[[:space:]]`) silently
   returns all-negative and would re-add already-curated apexes.
2. If `C` has a sibling `-extended` / upstream feed (per the pairing derived in
   the "Which lists you edit" section), fetch it and check membership at the
   apex AND for any feed host that is a subdomain of the apex. The feed usually
   lists ONE specific subdomain, not the apex — so "apex absent from the feed"
   under-states coverage, and the curated **apex** entry is the stronger block
   regardless (it suffix-matches all subdomains). Use the feed to *prioritize*
   (genuine gaps first), not to *disqualify*.

Only add genuine gaps. Record per-apex hits + a one-line "what it is" for each
addition.

## Step 3 — Author

- Add apexes to the matching `api/resources/blocklists/<category>.yml` under a
  dated comment block (`# traffic-driven additions (#<issue>): observed but
  unblocked <category> infra`). Subdomains are suffix-matched at the router, so
  list apexes (for `ai`, list product subdomains per its header). Never wildcards.
- No `_index.yml` change unless adding a NEW list file.
- `BundledBlocklistsSpec` asserts *presence* of representative hosts, not an
  exact count — additions are safe. Optionally pin one representative new host in
  the spec's per-category presence assertions (it already pins ads additions;
  add the same style for whichever category you grew).
- Keep an evidence doc under
  `api/resources/blocklists/evidence/<category>-classification-<issue>.md`
  (per-apex hit table + what-it-is + why-it-was-a-gap). One doc per category
  touched, or a combined doc if the pass spanned several.

## Step 4 — Validate + ship

```bash
mill api.test.testOnly 'wifihaven.api.feature.BundledBlocklistsSpec'   # covers ALL categories
scalafmt --check --non-interactive   # only if any .scala changed (e.g. you pinned a host in the spec)
```

`BundledBlocklistsSpec` loads and validates every category (`_index.yml` sync,
hostname parse, inline non-empty, seeder), so it is the correct gate no matter
which category files you edited.

Worktree off `origin/main`; **file a FRESH tracking issue per pass** (never
reopen a closed one); open a PR ("Relates to #<issue>"); run `/pr-review`,
address BLOCKERs + cheap SHOULD-FIX, push, re-run until no BLOCKER; monitor CI
through green. Do **not** `gh pr merge` / enable auto-merge (operator's call).
Post a summary on the issue: per category, apexes added + what each is + why it
was a gap.

## Step 5 — Self-update (MANDATORY, every run)

Reflect on what THIS run taught you that the steps didn't capture — a new
dual-use host to skip (note its category), a better detection signal for some
category, a changed caveat, a stale-upstream-feed finding, a new category that
appeared in `_index.yml`. **Append it to the Learnings log below and include
that edit in the same PR.** If a step above is now wrong, fix the step too.

---

## Learnings log (newest first)

- **2026-07-21** (#2348) — **When websearch tooling is available (unlike a
  prior flaky run), re-verify the PREVIOUS pass's "held out — unverified"
  list before scanning for new candidates — it's free signal the last run
  already surfaced.** #2212 held out 27 apexes because its websearch was
  intermittently down. This run re-searched all 27: 9 confirmed genuine ad-tech
  gaps (adrta.com=Pixalate, geoedge.be=GeoEdge, admatic.de=AdMatic GmbH,
  mediago.io=MediaGo/Baidu, ninthdecimal.com=ex-JiWire/InMarket, sparteo.com,
  rixengine.com=Baidu Global, adtarget.biz, globalrtb.com/rapidbidding.com/
  colossusssp.com — all still genuine gaps), 3 resolved as the SAME dual-use
  vendor (see next entry), 15 remain genuinely unidentifiable and are carried
  forward again (do not keep re-guessing from the name — a domain staying
  unverified for 2+ passes just means it's obscure, not that it's ad-tech).
- **2026-07-21** (#2348) — **HUMAN Security (bot-mitigation/anti-fraud,
  formerly White Ops) runs the SAME product under at least three unrelated-
  looking domain names: `tagsrvcs.com`, `script.ac`, `protechts.net`.** All
  three are dual-use SKIPS (same class as `datadome.co`/`confiant-integrations.net`)
  — HUMAN protects login/checkout/general traffic broadly on legitimate sites,
  not ad-serving exclusively. Correcting a prior mistake: the #2212 evidence
  doc attributed `tagsrvcs.com` to "Bazaarvoice reviews widget" — this run's
  websearch instead confirms HUMAN Security ownership via Netify. If a new
  obfuscated-looking domain resolves to HUMAN, treat it as the same skip
  bucket rather than re-litigating.
- **2026-07-21** (#2348) — **A domain owned by a vendor already known for
  rotating obfuscated tracking domains (Bigo Ads) is a genuine ads add, not a
  hold-out, once ownership is confirmed — obfuscation is evidence FOR ad
  infra, not against it.** `acobt.tech` and (already curated) `antibanads.com`
  are both Bigo Ads domains deliberately named to not look ad-related, per
  security research. Contrast with the HUMAN Security case above: obfuscated
  naming + confirmed ad-serving ownership → add; obfuscated naming + confirmed
  bot-mitigation/security ownership → skip. The naming pattern alone doesn't
  decide it — the confirmed *function* does.
- **2026-07-21** (#2348) — **A bare-TLD variant of an already-curated product
  subdomain is a genuine, if low-traffic, coverage gap — check apex vs
  bare-gTLD siblings, not just apex vs subdomain.** `gemini.google` (Google's
  `.google` gTLD, no `.com`) carries `share.gemini.google` — Gemini's
  share-link feature — and is a completely different apex from the curated
  `gemini.google.com`; the existing entry doesn't suffix-match it. Low volume
  (139K bytes, 8 hits) but real: a household relying on the `.com` entry alone
  would leak this one feature. Applies to any vendor using both `<product>.com`
  and a special gTLD (`.google`, `.goog`) for the same product.
- **2026-07-21** (#2348) — **A search result that reads as an implausible
  "harmless" explanation for a domain surfacing in ad/tracking-shaped traffic
  is a reason to hold out, not a reason to add.** `youngle.tech` returned one
  search result describing it as a "tech careers blog" — but the traffic shape
  (25MB bytes, only 7 hits — an extreme bytes/hit ratio typical of ad-creative
  payloads) and other results flagging a subdomain on scam-adviser tools
  contradict that. When a low-confidence identity claim conflicts with the
  observed traffic shape, don't resolve the conflict in the direction of "skip
  it" (nothing to skip — it's already unblocked); hold it out as unverified
  instead of trusting the shakiest positive-sounding explanation.
- **2026-07-14** (#2212) — **There is a second, large ads vein the RTB-name
  passes structurally missed: mainstream ad-tech VENDOR apexes.** #1822/#1923/
  #2064/#2122 all filtered on `*rtb*` / `*bid*` / `*-ads` name shapes, so they
  never surfaced the established DSPs / SSPs / exchanges / identity-resolution /
  data-brokers / attribution / native+video vendors whose apex is the company
  name (dotomi=Conversant, kochava, adcolony, unrulymedia, sonobi, revcontent,
  360yield, id5-sync, crwdcntrl=Lotame, everesttech=Adobe Ad Cloud, fwmrm=
  FreeWheel, ml314=MediaMath, a-mo=Amobee, presage/ogury=Ogury, admaster=
  AdMaster, eyeota, intentiq, sitescout, emxdgt, mobilefuse, start.io, …). This
  pass added **54** such apexes — a much bigger honest count than #2122's 16,
  not padding, because it is a different (and genuinely uncovered) vein. When the
  RTB-name well runs dry, sweep the ranked apex list for **named ad companies**
  next, not just name-pattern matches.
- **2026-07-14** (#2212) — **`target-video.com` / `brid.tv` / any video
  monetization "player + ads" platform is a dual-use SKIP — content collateral.**
  TargetVideo (which acquired Brid.TV in 2023) serves the embedded video PLAYER
  and its ads from the same apex; `target-video.com` was 74 MB of traffic this
  week — almost all of it video *content* pulled through the player. Blocking the
  apex drops the video a child is watching, not just the ad (same reason
  `jwplayer.com` is intentionally absent). Ad-monetization companies that also
  ship the player belong in the same skip bucket as jwplayer.
- **2026-07-14** (#2212) — **New BitTorrent-tracker high-hit decoy:
  `internetwarriors.net` (3,696 hits).** Add it to the running BT-tracker decoy
  list to check before adding any high-hit apex — joins `opentrackr.org`,
  `popcorn-tracker.org`, `demonii.com`, `coppersurfer.tk`, `openbittorrent.com`,
  `bittor.pw`, `glotorrents.pw`, `rarbg.to`, all of which out-hit real ad apexes
  this week but are torrent infra, not ads. A high hit-count is never an ad
  signal.
- **2026-07-14** (#2212) — **The non-ads categories yield a few high-confidence
  adds per pass, and `ai`/`adult` are often already saturated.** This week:
  gambling += americascardroom.eu / betrivers.com / polymarket.com; social-media
  += nextdoor.com / vk.com; games += eaglercraft.com/.ru (the "unblocked games"
  browser-Minecraft filter-bypass vein — add on the evasion-vector rationale even
  at ~zero bytes, like duckmath/emolingo). **ai and adult had NO gaps** — the top
  consumer AI apexes in traffic (grok/openai/elevenlabs/x.ai) and the one adult
  apex (cam4.com) were already curated. Don't force adds where the curated list
  already covers the observed traffic. Skip educational/classroom games
  (prodigygame, mathplayground, arcademics) — out of scope for a games *block*.
- **2026-07-14** (#2212) — **When websearch/verify tooling is flaky, HOLD the
  unverified apexes and record them in the evidence doc — do not guess from the
  name.** This run the classifier intermittently blocked WebSearch/WebFetch, so
  ~35 promising-but-unconfirmed apexes (random-named high-hit ones +
  moderately-known vendors like rixengine/admatic.de/globalrtb/mediago) were
  held with a "verify next pass" note rather than added on name-inference (the
  ttdns2/antibanads trap). The `ads-extended` feed cross-check was likewise
  skipped — acceptable because, per the #2122 learning, the curated **apex**
  entry is the stronger block regardless of what specific subdomain the feed
  happens to list.
- **2026-07-13** — **Skill renamed `ads-blocklist-pass` → `blocklist-pass` and
  generalized from ads-only to ALL curated categories.** Key structural fact for
  future runs: only **inline (`hosts:`) lists** are hand-editable — `ads`,
  `adult`, `ai`, `gambling`, `games`, `social-media` today. The `-extended`
  StevenBlack alternates and `malware` (URLhaus) are **`url:`-sourced**; the
  seeder replaces their DB rows from upstream every API boot, so a hand-added
  host is wiped — use them only as a coverage cross-check for their curated
  sibling (`ads`↔`ads-extended`, `adult`↔`adult-extended`,
  `social-media`↔`social-extended`). `ai`/`gambling`/`games` have NO upstream
  sibling, so a traffic pass is their only growth path. Enumerate categories from
  `_index.yml` at run time so new ones are auto-included. All ads-specific
  learnings below still apply to their category; the classification discipline
  (classify by what the apex *is*, verify ownership, skip dual-use) is
  category-agnostic.
- **2026-07-07** (#2122) — **The RTB/bidder vein is drying up — a small,
  targeted pass is now the expected outcome, not a failure to find volume.**
  Most classic `*rtb*` / `*bid*` / `*-ads` names surfacing in prod this week were
  already covered by the #1923/#2064 `ads.yml` additions (`coldbidder.com`,
  `rtblab.net`, `openrtbx.com`, `one-bid.com`, `servenobid.com`, `tmbid.com`,
  `bid-algorix.com`, `rtbuniverse.com`, `smarterbidder.com`, `lacunads.com`,
  `bm-ads.io`, `adnxs.net`, `adsappier.com`, …). Prior passes did their job;
  don't pad the list to match #2064's 38 — 16 genuine gaps was the honest count.
- **2026-07-07** (#2122) — **The StevenBlack feed usually lists ONE specific
  subdomain of an ad apex, not the apex — so "apex absent from ads-extended"
  UNDER-states coverage, and our apex entry is the stronger block regardless.**
  Ten of this pass's 16 adds (`appsflyersdk.com`, `maticooads.com`, `serverbid.com`,
  `adsninja.ca`, `yabidos.com`, `adkernel.com`, `outbrainimg.com`, `openxcdn.net`,
  `minutemedia-prebid.com`, `zmaticoo.com`) had exactly one subdomain in the feed
  (e.g. `s.appsflyersdk.com`) but not the apex. Check membership BOTH at the apex
  AND for any feed host that is a subdomain of the apex — then note that the
  curated apex entry still wins because it suffix-matches *all* subdomains while
  the feed blocks only the one it happens to list. This is the core argument for
  hand-curating apexes even when the feed nominally "has" the domain.
- **2026-07-07** (#2122) — **macOS BSD `sed`/`grep` do not support `\s` — a
  membership check written with `\s` silently returns all-negative.** My first
  ads.yml extraction (`sed -E 's/^\s*-\s*//'`) produced a host set where even
  `pubmatic.com` tested absent, which would have re-added dozens of
  already-curated apexes. **Always smoke-test a membership set against a known
  sentinel** (`grep -c '^pubmatic.com$'`) before trusting it; use POSIX classes
  (`[[:space:]]`) not `\s` on macOS.
- **2026-07-07** (#2122) — **Classify obscure RTB bidders by subdomain
  structure when the apex has no public writeup; a malvertising/phishing flag
  does NOT disqualify — it's still ad-category.** `gamaibids.com` had no vendor
  page, but its `bid.` / `bids.` / `trk.` subdomains are textbook RTB-bidder +
  impression-tracker infra. Scamadviser's mixed/phishing score is consistent with
  malvertising, which belongs in the ads (or malware) drop either way.
- **2026-07-07** (#2122) — **New dual-use / wrong-category SKIPs seen this pass:**
  `freebeacon.com` (Washington Free Beacon — news *content*, matched the `beacon`
  regex), `myfitnesspal.com` (fitness app), `iclasspro.com` (class-management
  SaaS), `horsebreedspictures.com` (made-for-advertising *content* site — the
  apex is content, the ads ride third-party infra), `bdtelemetry.amazon` (Amazon
  first-party telemetry on the internal `.amazon` gTLD — not a public ad apex),
  `imganalytics.com` (ambiguous: IMG sports-data analytics *or* HUMAN anti-bot
  infra per netify — neither clearly ad-serving). A `beacon`/`track`/`analytics`
  substring is a candidate signal, never a verdict — classify by what the apex
  *is*.
- **2026-06-30** (#2064) — **RTB/bidder/exchange genuine-gaps are the richest
  vein, and they're low-collateral by construction.** This pass added 38 apexes,
  almost all `*rtb*` / `*bid*` / `*-ads` / `oneadtag` / `imptracking`-class names
  the `ads-extended` feed misses entirely. These are safe to add in bulk (not
  just "a handful") because the *name is the function* — no product or shared
  traffic rides `coldbidder.com` / `rtblab.net` / `openrtbx.com`, so the
  collateral rule that governs dual-use hosts simply doesn't bite. Reserve the
  "handful" caution for *covered* apexes (low marginal value) and for
  dual-use/ambiguous names — not for clearly-ad genuine gaps.
- **2026-06-30** (#2064) — **BitTorrent trackers recur as high-hit decoys —
  always check `open*tracker*` / `*-tracker.org` before adding.** `opentrackr.org`
  (4,485 hits) and `popcorn-tracker.org` (1,212 hits) both *out-hit* every real
  ad apex this pass but are BitTorrent trackers, not ad infra (same class as
  #1923's `demonii.com`). A `track`-substring match is NOT an ad signal; classify
  by what the apex *is*.
- **2026-06-30** (#2064) — **`antibanads.com` is real ad infra, not an
  ad-blocker.** Despite the "anti-ban-ads" name it's BIGO Ads' ad-delivery
  backend, deliberately named + multi-cloud-fronted to survive domain bans
  (security-research confirmed). When a name reads like an *anti*-ad tool, verify
  — it may be ad-serving infra wearing camouflage.
- **2026-06-30** (#2064) — **New dual-use / wrong-category SKIPs seen this pass:**
  `zetaglobal.io` (Zeta Global marketing-cloud/CRM — first-party customer
  marketing, dual-use), `minutemediaservices.com` (Minute Media is a *content*
  publisher; only its `minutemedia-prebid` sibling is ad-specific),
  `smartborad.com` (scamadviser-flagged malware, unclear identity — belongs to
  the malware list if anywhere, not ads), `ottadvisors.com` / `advolve.io`
  (AdOps consultancy / AI-marketing platform — apex is the corporate site, ad
  -serving ambiguous; held out). Verify ownership before trusting an ad-ish name;
  unverified apexes are held out, not added.
- **2026-06-23** (#1923) — **Diff candidates against the StevenBlack
  `ads-extended` feed before adding.** Fetch
  `raw.githubusercontent.com/StevenBlack/hosts/master/hosts`, extract the
  `0.0.0.0 <host>` second field into a set, and test each candidate apex for
  exact + subdomain membership. This cleanly splits **genuine gaps** (feed
  misses the apex entirely → highest value to hand-curate) from apexes the
  extended feed already covers (lower value, only helps curated-only profiles).
  Prioritize the gaps; add a small set of the highest-traffic covered apexes for
  the curated baseline. (Generalized: the same diff applies to `adult`↔
  `adult-extended` and `social-media`↔`social-extended`.)
- **2026-06-23** (#1923) — **A name that resembles a known ad co is NOT
  identity — verify ownership.** `ttdns2.com` reads like TheTradeDesk
  (`adsrvr.org`/`ttd*`) but is actually **TikTok** shared DNS infra
  (CapCut/Pangle/app analytics) per netify — content collateral, skip. Web-fetch
  netify/whois for any apex whose owner you're inferring from the name alone.
- **2026-06-23** (#1923) — **New dual-use to skip:**
  `app-analytics-services.com` (Google GA4) and its ATT-segmentation pair
  `app-ads-services.com` — both Google-operated, same shared-product class as the
  already-listed `app-measurement.com` (Firebase). Both showed high prod traffic;
  skip anyway.
- **2026-06-23** (#1923) — **High hit-count alone is not an ad signal — check
  the category.** `demonii.com` had 9,240 hits but is a BitTorrent tracker
  (`open.demonii.com`), not ad infra. Classify by what the apex *is*, not by how
  often it's hit.
- **2026-06-23** (#1923) — **One ad vendor fronts multiple apexes.** Verve Group
  (MGI) served `vervegroupinc.net`, `verve.net`, and `personaly.bid` (its `.bid`
  bidder endpoint) — list every observed apex, not just the obvious one. Same for
  Vidazoo (`vidazoo.com` + `vidazoo.services`) and AppLovin
  (`applovin.com` + `applvn.com` + `safedk.com`).
- **2026-06-21** — Skill seeded from the `app-catalog-pass` method. Reuse the
  same `recent-apexes` pull; the difference is the classification target
  (ad/RTB/tracker infra) and that the bulk of coverage belongs in the
  URL-sourced `ads-extended` feed — reserve hand-curated `ads.yml` for a handful
  of clearly ad-dedicated high-traffic apexes per pass.
- **2026-06-21** — Dual-use guard: do NOT blocklist hosts that also front
  product traffic — `google-analytics.com`, `googleadservices.com`,
  `app-measurement.com`, `sentry.io`, `bugsnag.com`, and multi-tenant clouds
  (`cloudflare.com`, `amazonaws.com`, `akamai*`). Same collateral rule as app
  host-sets.
