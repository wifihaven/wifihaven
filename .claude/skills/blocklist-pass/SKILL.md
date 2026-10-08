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

> **TWO SURFACES, AND THE OBVIOUS ONE IS NOT THE KEYWORD SURFACE (#2823).**
> `recent-apexes` returns only each device's **top 500 apexes BY BYTES**
> (`limit` is clamped `.min(500)` and rows are `sortBy(-bytes).take(limit)`,
> `api/src/routes/UsageRoutes.scala:108-134`), and `windowDays` is clamped
> `.min(30)`. On a busy device the byte floor is high — measured 2026-10-02 on
> prod, 6 of 30 devices were at the cap with floors of 12 KB … **518 KB**. So
> **low-byte hosts are invisible there, and pop-unders / redirect hops /
> tracking pixels / TDS nodes are low-byte by construction.** In #2823, 15 of
> the 16 apexes in a live pop-under chain were absent from the pull entirely.
> Seven adult passes in a row reported "nothing new" because of this, not
> because of the keyword list.
>
> - **Keyword sweeps go through `GET /api/logs?domain=<substring>&hours=<n>`.**
>   Server-side unanchored `ILIKE` over `connection_events`
>   (`api/src/db/Repos.scala:3579-3583`) — matches *before* any limit, `hours`
>   is uncapped, and rows carry `mac` / `profileId` / `profileName` / `blocked`,
>   so "which profile is hitting this" is answered in the same call. Response
>   rows are under **`.rows`** (not `.logs`/`.items`).
> - **`recent-apexes` is for RANKING, and is NOT redundant** — sweep BOTH.
>   **Neither surface is complete, and #2823 measured the disagreement in both
>   directions.** `recent-apexes` reads `traffic_reports`; `/api/logs` reads
>   `connection_events`. The chain hops were in `/api/logs` and absent from
>   `recent-apexes` (byte truncation) — but `track.cam4tracking.com` on Rachel
>   iPhone was the reverse: 77,198 bytes / 2 hits in `recent-apexes` over 30
>   days, and **zero** rows from
>   `/api/logs?mac=1e:45:b6:68:24:b3&domain=cam4&hours=2160`. Cause not
>   established (differing retention is the likely explanation, unverified —
>   do not assert it). Treat a hit on EITHER surface as real and a miss on
>   either as uninformative.
>
> **Run `GET /api/logs` SERIALLY — never in parallel.** Its `domain` filter is
> a leading-wildcard `ILIKE` that no index can serve
> (`api/src/db/Repos.scala:3579-3583`), and `hours` is uncapped
> (`api/src/routes/Routes.scala:2039`), so each request is a sequential scan
> over that window of `connection_events`. Ten concurrent `hours=720` requests
> took **prod to HTTP 502 for roughly two minutes** on 2026-10-03; it recovered
> on its own ~15s after the load stopped. One keyword at a time. Endpoint-side
> fix options are in
> [#2828](https://github.com/wifihaven/wifihaven/issues/2828).
>
> This warning is about a **pre-existing** endpoint, not about any blocklist
> entry, which is why it is kept here rather than reverted with the rest of the
> #2823 pass (#2831).

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

### The burst-window technique — for pop-unders and any redirect chain (#2823)

A keyword sweep cannot find a chain hop, because the hops are randomly-named by
design (`unhappyweakness.com`, `moonlighthathel.org`, `herefwukou.org`). Anchor
on a host you already know and enumerate everything around it instead:

```bash
# 1. find the burst timestamps for the known host
curl -sS -H "Authorization: Bearer $TOKEN" \
  "https://api.wifihaven.net/api/logs?domain=<known-host>&hours=2160&limit=500" \
  | jq -r '.rows[] | "\(.ts)\t\(.mac)\t\(.host.value)"' | sort
# 2. for each burst, enumerate EVERY host that device resolved around it
curl -sS -H "Authorization: Bearer $TOKEN" \
  "https://api.wifihaven.net/api/logs?mac=<mac>&until=<burst+5min>&hours=1&limit=500" \
  | jq -r '.rows[] | select(.host.type=="fqdn") | "\(.ts[11:19])  \(.host.value)"' | sort
```

One query per burst, and the whole chain falls out in timestamp order — lure
site, TDS hops, landing. Two separate bursts weeks apart also tell you which
hops are *persistent* infrastructure versus one-off rotation. This is far
cheaper and higher-yield than a broad keyword sweep, so reach for it first
whenever the operator reports a *behaviour* ("pop-ups keep appearing") rather
than naming a site. Filter to `.host.type=="fqdn"` — IP-only rows (BitTorrent
peer traffic, on this household) otherwise fill the 500-row page.

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
address BLOCKERs + cheap SHOULD-FIX, push, re-run until no BLOCKER; take the PR
through to MERGED per the
[merge rule](../../../docs/pr-review-checklist.md#monitor-to-merged).
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

- **2026-10-06** (#2836) — **A GCP customer load-balancer IP (`34.117.x` /
  similar single-tenant App Engine/GLB ranges) is NOT the same collateral risk
  as Google's own product shared-GFE pool (`173.194.x`, the range
  `gstatic.com`/`google.com`/`adtrafficquality.google` answer on) — don't
  apply the #2823 CDN-skip rule to every Google-Cloud-hosted IP indiscriminately.**
  `nexverse.ai` (a confirmed third-party AdTech SaaS company) resolved to a
  GCP load-balancer address; that's a normal customer deployment, not Google's
  own multi-tenant product frontend, so it was added. Contrast with
  `adtrafficquality.google`, which resolved into the *same* 173.194.x block
  `google.com` and `gstatic.com` use — that one was skipped. The test is
  "does this address front Google's OWN products," not "is this hosted on
  Google infrastructure at all."
- **2026-10-06** (#2836) — **Two apexes with near-identical byte/hit counts
  and the same naming convention (`adxflyer.com` / `adxvalidation.com`, both
  "ADX"-prefixed, both ~33KB/4 hits) are reasonable to treat as a sibling pair
  from the same vendor even when only one has independent corroboration**
  (`adxflyer.com` was listed in hagezi/dns-blocklists; `adxvalidation.com` had
  no hits anywhere). This is the same "one vendor fronts multiple apexes"
  reasoning as the Verve Group / Vidazoo / AppLovin precedent (#1923) applied
  to a pair with no confirmed company name at all — the traffic-shape
  similarity is standing in for the usual "shared vendor" signal.
- **2026-10-06** (#2836) — **`hellohaven.ai` is a reminder to re-run the
  "is this our own thing" check even on an apex that sounds company-adjacent**
  (the project is itself called WifiHaven; the candidate was `hellohaven.ai`).
  A quick `whois`/`dig` plus a name search cleared it immediately (Anguilla
  .ai registry boilerplate, then a confirmed unrelated "Hello Haven" personal-AI
  startup) — cheap enough to do as a reflex whenever a candidate's name
  overlaps the operator's own product/company name, rather than assuming
  coincidence either way.
- **2026-10-02** (#2823) — **The sweep surface, not the keyword list, is why a
  category can report empty for seven passes running.** `recent-apexes` is
  top-500-BY-BYTES per device with a `.min(500)` hard cap; on prod 6 of 30
  devices were at that cap with byte floors up to 518 KB. `cam4tracking.com`
  was **absent from the top-500 of the one device that generated 70 of its 72
  connection events** — under that device's 506 KB floor, by how much the
  surface cannot say, because the row is gone. It surfaced only by luck, via a
  quieter device (26 KB floor) where it landed rank 401/500. Worse: of the 16
  *other* apexes in the pop-under chain it belonged to (17 in all, counting
  `cam4tracking.com` itself), **15 were absent from the pull across all 30
  devices entirely** — only `waifuoverlord.com`, at ~916 KB, cleared a floor.
  The truncation is an anti-filter for exactly the low-byte classes a blocklist
  pass wants — and the floor *rises* with device busyness, so it is blindest on
  the devices that browse enough to attract this traffic. Fix:
  keyword-sweep through `/api/logs?domain=` (see Step 0), rank with
  `recent-apexes`. Endpoint-side options in #2827.
- **2026-10-02** (#2823) — **For a reported *behaviour* rather than a named
  site, anchor-and-enumerate beats any keyword sweep.** Pop-under hops are
  randomly-named compound words precisely so no keyword matches them. Pulling
  every host the affected device resolved in a ±5min window around each known
  `cam4` hit produced the whole chain in timestamp order — lure
  (`piratebay.com` at 01:24:11; `torrindex.net` 01:24:22 and
  `thepiratebay.org` 01:24:23 land *after* the first hops, so within that
  second the ordering is not strictly resolved) → TDS hops
  (`clickpathworks.com` 01:24:11, `unhappyweakness.com` 01:24:19) → landing
  (`track.cam4tracking.com` 01:25:04 → `cam4.com`) — at one query per burst.
  Comparing two bursts three weeks apart separated persistent infrastructure
  (`unhappyweakness.com`, `show-sb.com` in both) from one-off rotation. Now
  written up as a named technique in Step 1.
- **2026-10-02** (#2823) — **THE BIG ONE: a shared-CDN frontend disqualifies
  most of what a pop-under/malvertising pass would otherwise add, and the
  Google-only `SharedGfeHosts` guard does not catch it.** 14 of the 15
  held-out chain hosts (`cam4tracking.com` among them) resolved onto
  Cloudflare (`104.18.x`, `104.21.x`, `104.26.x`, `172.66.x`,
  `172.67.x`) or CloudFront (`13.226.x`, `18.238.x`, `99.84.x`). One —
  `itefullofeedshen.com` — was **measured sharing the exact address
  `18.238.176.120` with one of the household's own CloudFront distributions**,
  i.e. #2601 reproduced on a non-Google pool. So: **resolve every candidate and
  check it against the addresses the household's own CDN hosts resolve to
  before adding.** A Cloudflare `104.x`/`172.6x.x` answer is an automatic skip
  regardless of how well-identified the domain is — the skip is structural, not
  a judgement about the domain. Corollary for the operator conversation: for
  this whole class, curated entries are **not** the fix; #2377 (SNI
  disambiguation) is. Say so rather than shipping a list that cannot work.
- **2026-10-02** (#2823) — **A per-set nftables `timeout` is NOT a reason to
  accept shared-pool collateral — it is a property of the enforcement plane
  that every member of every `bl_` set shares equally.** This pass initially
  added `cam4tracking.com` (a real, uncovered gap: separate apex from the
  curated `cam4.com`, absent from the porn-only feed, `blocked:false` on every
  MAC) and justified the CloudFront exposure by citing `bl_<id>`'s
  `flags dynamic,timeout` / `timeout 1h`
  (`openwrt/files/usr/lib/lua/wifihaven/render.lua:1107-1118`). The independent
  review correctly killed it: that timeout applies verbatim to the 13
  Cloudflare/CloudFront hops the same pass *refused* to add, so it cannot
  discriminate — and the entry was dropped. Two further traps the same finding
  exposed:
  - **"The apex is on dedicated hosting" is irrelevant if nothing resolves the
    apex.** Only `track.cam4tracking.com` was ever observed, so the set would
    have been armed with CloudFront edges and the apex's dedicated MojoHost
    address would never have entered it. **Check which host actually appears in
    traffic, not which address the apex happens to answer on.**
  - **"The domain carries zero legitimate traffic" is also irrelevant.** The
    #2601 harm is to *other tenants of the pool*; the blocked domain's own
    legitimacy has no bearing on it.
  So: a Cloudflare/CloudFront answer on the OBSERVED host is an unconditional
  skip, and the repo's established handling is to **pin the absence in
  `BundledBlocklistsSpec`** (as #2601 did) rather than add with a risk note.
  `SharedGfeHosts` is Google-only, so nothing mechanical catches a CDN case —
  it is a review-time decision every time. #2377 is the unblock.
- **2026-10-02** (#2823) — **Classify a redirect/TDS hop by what it serves in
  general, not by where the chain happened to land.** The CAM4 pop-under chain
  ends on an adult site, but its hops are a fast-flux TDS and an RTB ad-feed
  network — so `unhappyweakness.com` and `realizationnewestfangs.com` went to
  `ads.yml`, not `adult.yml`. Filing them under `adult` would make the category
  mean "things that led to porn once", and would leave them unblocked on a
  profile running `ads` but not `adult`. The spec pins them present in `ads`
  **and absent from `adult`**, so a later pass cannot quietly relocate them.
  This is the mirror of the `axon.ai`/`mediayo.ai` trap: there an AI-branded
  TLD hid an ad network, here an adult *destination* hid ad infra. Classify on
  function, both directions. (`clickpathworks.com` is the same ad-infra class
  but was **held out**, not filed — one self-derived signal; see the
  two-signals learning below. Do not read it as curated.)
- **2026-10-02** (#2823) — **A fast-flux fleet cannot be hand-curated, and the
  evidence note should say so instead of implying the pass fixed it.**
  `realizationnewestfangs.com` is named in Augur Security's write-up of a TDS
  network of **~300 auto-generated compound-word domains** rotating over 18 IPs
  in six /24s — and that write-up's listed `172.240.x.x` range matched the
  addresses measured here independently, which is what promoted two
  no-public-page domains from held-out to added (shared NS + identical
  9-address set + same registrar/privacy/country across the pair, plus one
  present in `ads-extended`). Adding two observed members is honest; calling it
  a fix is not.
- **2026-10-02** (#2823) — **Smoke-test a JSON response's SHAPE, not just a
  regex, before trusting a zero.** A first `/api/logs` sweep used
  `.logs//.items//[]` where the key is `.rows`, so **every** keyword returned
  `length 0` — a silent all-negative that reads exactly like "category is
  clean". Same failure class as the #2122 macOS `\s` lesson and the #2742
  inline-comment lesson, one layer up. Probe with a keyword you know is present
  (`domain=google`) and confirm the shape before believing any empty sweep.
- **2026-10-02** (#2823) — **Re-read profile policy from prod at the moment you
  need it, and cross-check `/api/profiles` against the router snapshot.** The
  issue's triage table (hours old) said profile 2 blocked *nothing* and profile
  4 only `malware`; live prod had `adult` + `adult-extended` on both — and
  profile 4 gained two categories *mid-session*, so an early read in this same
  session was already stale. The operator edits policy while a pass runs,
  typically *because* of the symptom being investigated. `GET /api/profiles`
  and `GET /api/admin/snapshot` → `profiles.<id>.rules.blocklistIds` are the
  two surfaces; agreement between them is the check. Also: `.blocklistIds` can
  be `null` in the snapshot, so `jq` needs `// []` or `to_entries` dies with
  "Cannot iterate over null".
- **2026-10-02** (#2823) — **`/api/blocked` needs a control host per MAC before
  its answers mean anything.** It returns `blocked:true` for a whole-MAC block
  (paused / schedule / time limit) identically to a category hit, so a `true`
  proves nothing on its own. Probe `example.com` + a nonsense apex on the same
  MAC first; only if both come back `false` does a `true` on your candidate
  isolate the category path. This caught a near-miss where profile 4 appeared
  to block an apex that was in none of its categories — the real explanation
  was a mid-session policy edit, not a whole-MAC block, but the control is what
  made that distinguishable.
- **2026-09-29** (#2816) — **A "dedicated" ad-services apex is only
  dedicated if it RESOLVES to dedicated addresses — the owner registering a
  separate domain proves nothing about the IP layer.** `ebayadservices.com`
  (eBay's own ad-services domain, confirmed via Netify/host.io/MarkMonitor)
  was first added on the reasoning that, unlike `googleadservices.com`
  (`SharedGfeHosts`), it was not shared infra. Independent review (after
  #2823 set the shared-CDN rule) resolved it: the apex and `www.` CNAME
  through `andes.ebay.com.edgekey.net` onto Akamai edges in the same /24
  that `pages.ebay.com` answers in. `akamai*` was already on the Step 1 skip
  list, so it was held out and pinned absent. The deciding test is still
  "does it share an IP pool with unrelated traffic," but answer it with
  `dig`, not with the domain's ownership.
- **2026-09-29** (#2816) — **Two more `track`-substring consumer/SaaS false
  positives: `sail-track.com`** (a legitimate marine GPS-tracking app family,
  unrelated "Sail Track"/SailTrack products) **and `synctrack.io`** (a
  legitimate Shopify post-purchase order-tracking SaaS, founded 2019, 20K+
  merchants) — neither is ad-tech despite matching the `track`/`sync`
  keyword sweep. Low hit-counts (19 and 6 respectively) with legitimate,
  confirmable identities; verify before guessing from the substring.
- **2026-09-29** (#2816) — **A brand's own affiliate/payment-tracking domain
  for content that is ALREADY blocked at the main apex is low marginal value
  even when confirmed genuine — check whether the parent brand is already
  curated before adding the tracking sibling.** `cam4tracking.com` (confirmed
  via urlscan/semrush to redirect to CAM4's affiliate payment portal,
  cam4pays.com) carries no independent adult content of its own; `cam4.com`
  is already in `adult.yml`. Given this list's explicit "conservative starter
  set of top apex domains" scope (not comprehensive infra coverage), the
  tracking-only sibling was held out rather than added. (#2823 later found
  the structural reason that settles it regardless of marginal value: the
  observed `track.cam4tracking.com` answers on shared CloudFront edges. See
  the 2026-10-02 entries above.)
- **2026-09-22** (#2807) — **The #2742 "check membership across every curated
  file, not just the one the sweep bucketed it into" lesson was only being
  applied to hosts already known from prior evidence docs — NOT to a host
  discovered fresh, mid-pass, by identity research. Apply it to every
  candidate before authoring, regardless of how it was confirmed.**
  `bidsystem.ai` surfaced in the `ai.yml` keyword sweep (`.ai` TLD), was
  correctly identified via websearch as Ezoic's programmatic ad-bidding
  engine, and was checked against `ai.yml`'s curated list (absent, as
  expected) — but never checked against `ads.yml`, where it was already
  curated from a prior pass (the RTB/bidder/SSP name-pattern block).
  Independent PR review caught the duplicate before merge; harmless in
  practice (`BundledBlocklists.scala` `.distinct`s inline hosts at load
  time) but it shipped an inaccurate evidence-doc claim. Before writing any
  candidate to a category file, grep ALL SIX curated `.txt` extractions for
  it, not just the category it happened to keyword-match into.
- **2026-09-22** (#2807) — **A `.ai`-TLD company whose actual product is video
  or programmatic ad delivery keeps recurring as an `ai.yml`-sweep false
  positive — checking the OBSERVED SUBDOMAIN for an ad-infra shape (`ortb.`,
  `ads.`, `bid.`) resolves it faster than a fresh identity search each time.**
  `vdo.ai` (video-ad platform/adserver; observed `ortb.vdo.ai` — literally
  "open RTB") is another instance of the `axon.ai`/`programmaticx.ai`/
  `trygravity.ai`/`koah.ai`/`mediayo.ai` trap. Filed in `ads.yml`.
  A non-`.ai` ad-tech company can also read as ambiguous from the apex name
  alone: `responsiveads.com` needed a company-identity search
  (BusinessWire/own-site confirmation) despite its self-descriptive name,
  because "responsive ads" is also a generic industry term (Google Ads has a
  "Responsive Display Ads" feature) — don't skip verification just because a
  name sounds descriptive.
- **2026-09-22** (#2807) — **A brand-new consumer AI product (`hellohaven.ai`
  — a personal-AI-assistant app that launched 5 days before this pass) is a
  reminder that `ai.yml` gaps are not only long-tail obscure apexes; genuine
  gaps can be a company that didn't exist in traffic last week.** Confirmed
  via press coverage (funding announcement, App Store/Play Store listings)
  and the observed subdomain (`my.hellohaven.ai`) matching the product's own
  stated URL exactly. (The name-collision-with-our-own-product check is
  covered by the 2026-10-06 entry above.)
- **2026-09-22** (#2807) — **A `ssp`/`ads`/`track`/`analytic` keyword sweep
  will keep matching inside unrelated words — two new substring false
  positives this pass: `paperlesspost.com` (matched `ssp` inside
  "paperle**ssp**ost" — a legitimate greeting-card company) and
  `livesteamstation.com` (matched `steam` — a model-train store, not Valve
  Steam). Neither needed deep research, just reading the matched substring
  in context before running a search.
- **2026-09-22** (#2807) — **`hs-analytics.net` (confirmed HubSpot) joins the
  dual-use-analytics skip bucket alongside `google-analytics.com` and
  `siteimproveanalytics.com` — a marketing-automation/CRM analytics domain
  that legitimate sites embed for their own first-party visitor tracking is
  not an ads.yml add just because "analytics" matched the keyword sweep.**
  Same reasoning as the standing `app-measurement.com`/Firebase skip; the
  distinguishing test from the *session-recording* tools that ARE curated
  under ads (Hotjar, Mouseflow) is still: does the tool primarily serve ad
  delivery/monetization, or a site owner's own product analytics.
- **2026-09-15** (#2792) — **A "track"-substring apex whose subdomains
  are marketing-site-shaped (`info.`/`www.`) rather than redirect/pixel-shaped
  (`go-us.`/`sync.`/`bid.`) is a strong signal of a false-positive consumer
  brand, not ad infra — check subdomain shape as well as company identity.**
  `insidetracker.com` (a legitimate MIT-spinout health/biomarker-testing
  brand) matched the ads `track` keyword sweep on name alone; its only
  subdomains were `info.` and `www.`, consistent with an ordinary marketing
  site rather than a tracker. Contrast with the same run's `rtbedge.com` /
  `bidgust.com`, whose `go-us.` (redirect) + `img.`/`static.` (creative)
  subdomain shape corroborated the RTB-bidder-name-is-the-function call even
  with zero public company page.
- **2026-09-15** (#2792) — **New BitTorrent-tracker decoy: `desync.com`**
  (17,708 hits, matched the ads `track` substring sweep) — its only observed
  subdomain `exodus.desync.com` is confirmed by two independent sources
  (similarweb + justdailytrackers.com) to be a public BT tracker on port
  6969. Add to the running BT-tracker-decoy list alongside `opentrackr.org` /
  `popcorn-tracker.org` / `demonii.com` / `coppersurfer.tk` /
  `internetwarriors.net` — a high hit-count with a tiny bytes/hit ratio on a
  `track`-shaped apex is as likely to be a torrent tracker as ad infra;
  always check the specific subdomain's identity, never the apex name alone.
- **2026-09-15** (#2792) — **A `.ai`-TLD company whose actual product is
  B2B (IT consulting, docs-search widgets, OTT/CTV app tooling) is
  out-of-scope for `ai.yml` even with zero ad-tech signal — the category is
  consumer chatbot/assistant/companion/generator products a household would
  block, not enterprise SaaS.** Three more instances this pass: `dxtech.ai`
  (AI-driven IT-consulting/digital-transformation for SMEs), `kapa.ai` (a
  RAG docs-Q&A widget OTHER companies embed on their own docs sites — same
  content-collateral-widget class as `trinitymedia.ai`/`gpteng.co`, #2729),
  `castify.ai` (OTT/CTV app-builder SaaS for content owners). None read as
  ad-tech either — they're just outside `ai.yml`'s consumer-product scope
  entirely. Widens the #2729 "developer tools are out of scope" learning to
  B2B SaaS generally: the test is "would a household profile plausibly want
  this blocked as a consumer AI product," not "is this AI-branded."
- **2026-09-15** (#2792) — **When two candidates share a name root but only
  one has been previously investigated and curated (e.g. `higgsfield.ai`
  already in `ai.yml`, a bare `higgs.ai` newly surfacing), do not assume the
  new one is the same company — a generic short-name apex is exactly the
  shape that collides across unrelated products.** `higgs.ai` (single
  `images.` subdomain, 4 hits) and `flux.ai` (real SaaS shape: `app.`/
  `events-api.`/`payments.`/`shortcircuit-cdn.` subdomains, 22 hits) were
  both held out unverified this pass — search could not confirm either
  belongs to a specific company (Higgsfield's own apex is `higgsfield.ai`,
  already curated; "Flux" is also a no-code app-builder brand unrelated to
  Black Forest Labs' image model). A plausible-sounding name plus in-scope
  product category is not enough without a source naming the actual owner —
  carry both forward for a future pass rather than guessing.
- **2026-09-08** (#2759) — **A `.ai`-TLD company whose actual product is AI-
  driven programmatic ad delivery (Dynamic Creative Optimization) is a third
  confirmed instance of the `axon.ai`/`programmaticx.ai`/`trygravity.ai`/
  `koah.ai` trap — check `ads.yml` criteria before defaulting to `ai.yml` on
  TLD alone.** `mediayo.ai` ("MediaYo — AI-driven programmatic advertising")
  surfaced in the `ai.yml` `.ai`-TLD sweep but its entire business is
  AI-optimized ad creative delivery — an ads.yml candidate. This pattern is
  now common enough (4 instances across 3 passes) that it's worth checking
  first for ANY `.ai` apex whose own marketing centers on "advertising",
  "ad network", "programmatic", or "DCO" — the TLD is never the signal, the
  product description is.
- **2026-09-08** (#2759) — **A confirmed scam/phishing domain that
  impersonates a well-known game brand (blacklisted, tracked in AdGuard's
  filter lists as adware) is an `ads.yml` malvertising add, not a
  `games.yml` candidate — even though it matches the games keyword sweep on
  the brand name.** `epicgameshubham.com` matched the "Epic Games" substring
  in the games sweep but a trust-score/blacklist check confirmed it is NOT
  affiliated with the real Epic Games — a scam clone site. Since it's
  confirmed illegitimate (no collateral risk) and independently tracked as
  adware/malvertising, it belongs with the `gamaibids.com`-class
  "malvertising flag doesn't disqualify — still ad-category" reasoning
  (#2122), filed in `ads.yml`, not held out and not filed under the brand's
  actual category.
- **2026-09-08** (#2759) — **A first-party analytics SDK a category's own
  content creators embed in their own products (game-dev analytics, not
  third-party ad infra riding on top) belongs in the same dual-use skip
  bucket as `app-measurement.com`/Firebase — check who integrates the SDK
  before assuming "analytics substring in a `games`-adjacent apex = ad
  infra."** `gameanalytics.com` is a legitimate SDK game *developers*
  embed in their own titles to understand their own players; blocking it
  doesn't stop ads, it silently breaks developer telemetry inside
  otherwise-fine games. Held out as dual-use, not added to any category.
- **2026-09-08** (#2759) — **Cross-check a self-descriptive-name-only
  candidate (no confirmed company page) against the StevenBlack
  `ads-extended` feed before deciding whether to add it — apex-absent from
  the feed is itself corroborating signal that it's a genuine, previously
  unknown gap, not proof it's illegitimate.** `progrtblive.com` had no
  public company page (same shape as `osdrtb.net`/`gamaibids.com` in prior
  passes) but is also completely absent from `ads-extended`, meaning no
  other curated source has flagged it either way — consistent with "small
  new RTB shop," not disqualifying. Contrast with `mathads.com`, where a
  *conflicting* identity signal (search results partly describing the
  unrelated `math-aids.com` worksheets site) was resolved by finding TWO
  independent sources confirming MediaMath ownership (the `creative.
  mathads.com` subdomain shape + a direct mathtag.com/MediaMath ownership
  search) before adding — a single ambiguous source is not enough, but two
  independent confirmations of the same specific claim is.
- **2026-09-01** (#2756) — **Check every candidate's history against
  `evidence/*.md`, not just the current `.yml` content, BEFORE running a
  fresh identity search — a domain can be a documented standing exclusion
  even though it isn't in the curated list.** `claude.com` resurfaced in
  traffic this run (unsurprising — the operator runs Claude Code heavily)
  and a fresh websearch would happily confirm "yes, this is Anthropic's
  domain" — but #2348 and #2503 had already ruled it out twice: it's
  WifiHaven's own vendor infra, and separately the wrong *tier* of apex per
  the `ai.yml` host-scoping convention (bare corporate apex vs. the
  already-curated product subdomain `claude.ai`). Grepping
  `evidence/*.md` for the exact candidate caught this before it got
  re-added as a "new" gap. Same pattern, different resolution:
  `lazybumblebee.com` — a weak, unauthoritative forum post this run
  suggested a link to Bumble's dating app via its `d.lazybumblebee.com`
  subdomain, but FOUR prior passes (#2212, #2348, #2503, #2729) had
  already investigated the apex's actual site content directly and found
  it to be an unrelated lifestyle blog — one ambiguous forum answer isn't
  strong enough new evidence to reverse a repeatedly re-investigated
  rejection, so it stayed held out. Contrast with `adelement.com`, which
  *was* correctly promoted from a two-pass "plausible but unconfirmed"
  hold-out (#2212, #2348) to added — because this run's evidence was a
  first-party company website with concrete self-description (own domain,
  funding, employee count, Inc 5000 ranking), which is categorically
  stronger than the earlier "name sounds plausible" signal. The rule: a
  standing rejection needs comparably strong NEW evidence to reverse, not
  just any evidence.
- **2026-09-01** (#2756) — **A `.ai`-TLD company whose actual product is an
  ad network built for OTHER AI apps belongs in `ads.yml`, not `ai.yml` —
  a second confirmed instance of the trap `axon.ai`/`programmaticx.ai`
  first flagged (#2599/#2742).** `trygravity.ai` ("Gravity — The Ad
  Network for AI") and `koah.ai` ("Koah — AdSense for AI") both surfaced
  in the `ai.yml` keyword sweep (`.ai` TLD) but their entire business is
  embedding sponsored placements into other companies' AI-chat/AI-app
  output — that's an ads.yml candidate wearing an AI-branded TLD. This is
  now a recurring enough pattern (3 instances across 2 passes) that any
  `.ai` apex whose own marketing describes itself as an "ad network" /
  "AdSense for X" should be checked against `ads.yml` criteria first,
  before defaulting to `ai.yml` on TLD alone.
- **2026-09-01** (#2756) — **An ad-monetization platform that serves ONLY
  ads within game/content pages it doesn't otherwise deliver (Playwire's
  RAMP) is a clean ads.yml add, not games.yml content-collateral —
  contrast with a video CDN that also carries the actual content stream
  (target-video.com/brid.tv, mmvideocdn.com).** The distinguishing
  question from the ads-collateral rule: does blocking the apex drop
  content the household is trying to consume, or only the ad slot layered
  on top of content served from elsewhere? `playwire.com` monetizes the
  already-curated Coolmath Games and other casual-game sites but doesn't
  deliver the games themselves — safe to add.
- **2026-08-25** (#2742) — **A hand-written `grep -oE '^  - [a-zA-Z0-9._-]+$'`
  extraction anchored with `$` silently drops any host line carrying a
  trailing inline `# comment`** — `social-media.yml` and `games.yml` both use
  inline comments on some host lines (`- acebet.cc # Acebet.cc US sweepstakes
  social casino`), and the anchored regex under-counted `gambling.yml` by 4
  hosts, `games.yml` by 1, and `social-media.yml` by 10 — enough to make
  `acebet.cc` (already curated) look like a fresh gap. Fix: strip the
  trailing comment first (`sed -E 's/^  - //; s/[[:space:]]*#.*$//'`) rather
  than anchoring the match on it. This is the same class of bug the
  #2122 `\s`-on-macOS lesson warns about — always smoke-test extraction
  against a known sentinel that HAS an inline comment, not just one that
  doesn't.
- **2026-08-25** (#2742) — **A candidate can be a "genuine gap" for the
  category it keyword-matched on while already being fully enforced under a
  DIFFERENT category's file — check membership across every curated file,
  not just the one the sweep bucketed it into.** `ads-twitter.com` and the
  `tiktokpangle-b.us`/`tiktokpangle-cdn-us.com`/`tiktokpangle.us` cluster
  all matched the social-media keyword sweep (Twitter/TikTok branding) but
  are correctly curated in `ads.yml` (X's ad-conversion pixel; Pangle is
  ByteDance's ad network, not TikTok content). Checking only
  `social-media.yml`'s host set would have re-added them as "gaps" when
  they're already dropped. Same applies in reverse — `axon.ai` and
  `programmaticx.ai` both keyword-matched the `ai` sweep (`.ai` TLD) but are
  correctly curated in `ads.yml` (ad-tech companies that happen to use a
  `.ai` domain, not consumer AI products).
- **2026-08-25** (#2742) — **A domain HELD OUT as "unverified — no identity
  confirmation" in a prior pass is not permanently stuck there — a fresh
  websearch that lands a specific, named-source identification (a netify.ai
  company profile, not just a suggestive URL snippet) is legitimate grounds
  to move it from held-out to added.** `tpdads.com` (held out in #2122) and
  `ad.gt` (held out in #2503, explicitly for "no identity confirmation")
  both resolved this run to clear, sourced identities (The Publisher Desk;
  Audigent via netify.ai) and were added. Contrast with the standing
  `bounceexchange.com` SKIP (already explicitly classified dual-use in a
  live `ads.yml` comment, not just held-out) — that is a settled call, not
  an open question, and was left alone per the xlgmedia.com precedent
  (#2503) of respecting a prior pass's explicit classification over a fresh
  guess.
- **2026-08-25** (#2742) — **A confirmed brand name is not enough when
  multiple unrelated companies share it, or when copycat/squatting sites
  reuse it for an unrelated product — check that THIS domain, not just the
  brand string, is the real owner.** `ad-score.com` matched three distinct
  "AdScore" companies (ad-fraud detection, automotive-ad compliance,
  marketing analytics) with no way to tell which owns the hyphenated domain
  — held out. `poki.io` looked like a natural sibling of the already-curated
  `poki.com`/`poki-cdn.com`, but a search surfaced several unrelated
  brand-squatting clone sites reusing "Poki" for copycat game portals
  (`poki.us.com`, `poki.us.org`, `poki.to`, `pokigames2.com`) and the bare
  `poki.io` apex didn't even resolve — held out despite the tempting naming
  pattern. `poki-gdn.com` (a CDN-suffix sibling, Amazon-registrar +
  Cloudflare infra matching the confirmed `poki-cdn.com`) was added instead;
  the naming-pattern signal is much stronger when it's a distinctive
  suffix/infra match, not just brand-substring reuse.
- **2026-08-18** (#2729) — **Before trusting a fresh websearch's top result
  for an ambiguous apex, check the CURRENT curated file first — a prior pass
  may have already resolved the SAME apex to a different, correct identity.**
  `axon.ai` websearched this run as Axon Enterprise (the public-safety
  body-cam company at axon.com) — but `axon.ai` was already curated in
  `ads.yml` since #2599 as AppLovin's "Axon" ad platform (a sibling apex of
  `applovin.com`). Two unrelated companies both plausibly own an `axon.ai`
  -shaped apex; the file already has the verified answer, a fresh search does
  not. Grep the category file for the exact candidate apex before running a
  new identity search on it.
- **2026-08-18** (#2729) — **Embedded third-party WIDGETS that happen to be
  AI-powered (TTS narration players, AI-search-visibility overlays) belong in
  the same content-collateral/dual-use skip bucket as `unity3d.com`, not in
  `ai.yml`.** `trinityaudio.ai`/`trinitymedia.ai` (an AI text-to-speech
  article-narration widget embedded by unrelated publisher/news sites) and
  `gpteng.co` (the embed-script CDN loaded by many Lovable-built web apps,
  not Lovable's own product domain) both matched the `ai.yml` sweep on
  ownership/name but are infra embedded across many unrelated sites — blocking
  either breaks a feature on sites that have nothing to do with the "AI
  product" in question. The question isn't "is this AI-branded" but "is this
  domain the consumer-facing product itself, or an SDK/CDN/widget riding
  underneath many unrelated products."
- **2026-08-18** (#2729) — **Developer-facing AI *tools* (coding agents, AI
  IDEs/CLIs) are out of `ai.yml`'s scope even when consumer-AI-shaped
  keywords match — the category is chat/assistant/companion/generator
  products a household blocks, not professional dev tooling.** `opencode.ai`
  (an open-source terminal AI coding agent) is the clearest case this pass;
  same reasoning that would exclude Claude Code / Cursor / Copilot-for-VS-Code
  domains if they ever surfaced. A developer-heavy household's traffic will
  keep surfacing these — don't add on AI-branding alone, check whether the
  product is something a household profile would plausibly want blocked.
- **2026-08-18** (#2729) — **A domain privacy-registered via MarkMonitor under
  "DNStination Inc." is a pattern independently documented for at least one
  other confirmed Amazon-affiliated domain (`prime.com` per
  DomainInvesting.com) — corroborating but not conclusive on its own.**
  `amazon-ads-attestation.com` used this exact registrar/privacy-proxy
  pairing; combined with a maximally self-descriptive name ("ads-attestation"),
  that was enough to add under the `inhousedsp.com` precedent (#2212: a
  self-descriptive name is sufficient without a named company page). Note the
  distinction from a *plausible* explanation used to justify skipping
  (`youngle.tech`, #2348) — here the corroborating signal supports adding an
  apex whose own name is already the strongest evidence.
- **2026-08-04** (#2599) — **This pass's household traffic was unusually
  developer/infra-heavy (anthropic.com, claude.ai, warp.dev, docker.com,
  github.com dominated the top of the ranked list) — do not let a
  traffic-shape that looks "unrepresentative" discourage a full sweep. The
  category gaps were still there once you grep specifically for
  category-relevant keywords rather than eyeballing the top-N by volume.**
  Confirms the Step 1 guidance: classify by keyword sweep across the FULL
  ranked list, not just the highest-traffic apexes — several genuine gaps
  this run were low-hit/low-byte (e.g. `freshpaint-impression.com` at 1 hit,
  `grokusercontent.com` at 1 hit) and would never surface from a top-40 skim.
- **2026-08-04** (#2599) — **`whatsapp.net` (WhatsApp's actual messaging/
  media protocol domain — api./media-cdn subdomains) was a categorical
  absence from `social-media.yml`, not a gap in an existing sibling-apex
  cluster.** Despite Discord, Reddit, TikTok, etc. all being covered,
  WhatsApp had zero representation. 90MB / 1029 hits this pass — the largest
  single social-media add to date. **Worth an explicit sanity check each
  pass: is any major messaging/social platform the household actually uses
  simply missing outright, not just under-covered on sibling CDNs?** A
  category-membership check against the curated list won't catch a total
  absence — you have to notice the platform isn't mentioned anywhere in the
  file at all.
- **2026-08-04** (#2599) — **A "sync." or "-sync" subdomain pattern
  (`shb-sync.com`, `contextualadv.com`'s `sync.` prefix, `pmbmonetize.live`'s
  `sync.` prefix) is a strong ad cookie-sync signal — but the substring
  "sync" in the APEX itself is a false-positive trap for financial/
  productivity brands** (`synchrony.com` / `synchronycredit.com` /
  `mysynchrony.com` = Synchrony Bank; `siteimproveanalytics.com` = a web
  governance SaaS with "sync" nowhere relevant). Check the *subdomain*
  prefix, not just the apex substring, before classifying a "sync"-named
  domain as ad-tech.
- **2026-08-04** (#2599) — **When two independently-observed apexes both sync
  into the SAME already-curated ad exchange, that's strong corroboration for
  both.** `pmbmonetize.live` and `contextualadv.com` each carry a `sync.`
  subdomain that chains into `cs.openwebmp.com` (openwebmp.com already
  curated as an ad exchange in ads.yml). Neither had a clean company-name
  match on its own, but the shared downstream sync target confirmed both as
  genuine ad infra. Check where a "sync" pixel actually redirects/chains to,
  not just its own name.
- **2026-08-04** (#2599) — **A `-cdn` / `videocdn` apex with an `ads.`
  subdomain is NOT automatically an ads-category add — check whether it also
  carries first-party video CONTENT subdomains (`videos-a.`, `videos-b.`,
  `players.`) before adding.** `mmvideocdn.com` has both `ads.mmvideocdn.com`
  AND `videos-a/videos-b/players.mmvideocdn.com`; it's Minute Media's own
  video CDN (same company already skipped once via
  `minutemediaservices.com`, #2064) — blocking the apex would kill the video
  content itself, not just its ad slot. Same content-collateral class as
  `target-video.com`/`brid.tv`. The presence of an `ads.` subdomain is
  evidence the apex CARRIES ads, not that the apex IS an ad server — a
  content CDN with an ads subdomain is still content-collateral.
- **2026-08-04** (#2599) — **`ihawk.ai` and `yegge.ai` are reminders that the
  `.ai` TLD alone is not an AI-product signal for the `ai.yml` sweep.**
  Neither is ad-tech (checked because they also matched the ads `analytics`/
  `track` sweep — they didn't) nor a consumer AI chatbot/generator (checked
  for `ai.yml`) — one is an enterprise drone-data SaaS, the other a personal
  blog. A dozen more low-traffic `.ai` domains this pass
  (`cimulate.ai`, `componecat.ai`, `conduit.ai`, `connectmachine.ai`,
  `directbooker.ai`, `duvo.ai`, `e-volution.ai`, `elixion.ai`, `joblobster.ai`,
  `mediayo.ai`, `powerad.ai`, `wknd.ai`) got the same treatment: held out
  unverified rather than added on TLD alone. Small businesses use `.ai` as a
  vanity TLD constantly; only add on a confirmed consumer AI-product identity
  or a confirmed ad-tech function, never on the TLD.
- **2026-07-28** (#2503) — **Before trusting a "genuine ad company" name
  match, check whether a PRIOR pass already added and then reverted that
  exact apex.** `xlgmedia.com` (XLMedia) read as a clean ad-network add this
  run — until re-reading #2348's evidence doc showed it was added and then
  explicitly reverted there: XLMedia's business is substantially
  gambling-affiliate **content** publishing, not dedicated ad serving, so the
  apex is content-collateral (same class as `target-video.com`). A
  membership check against the CURRENT curated list isn't enough to catch
  this — the apex genuinely isn't in `ads.yml` right now (it was removed),
  so it silently looks like an open gap. **Grep the category's evidence/*.md
  history for the candidate apex, not just the current .yml, before
  re-adding anything that reads as a "obviously legit ad company" match.**
- **2026-07-28** (#2503) — **A vendor whose core product is anti-adblock /
  adblock-revenue-recovery (e.g. Admiral / `getadmiral.com`) belongs in the
  ads category, not a dual-use skip — its function is literally to defeat
  the user's ad-blocking, i.e. the opposite of our enforcement goal.**
  Different from consent-management/CX tools (OneTrust, Glance, Viafoura)
  which are genuinely dual-use and unrelated to ad delivery. `merequartz.com`
  (an obscured domain independently confirmed to serve Admiral's popup
  scripts) is the same class — obfuscated naming + confirmed
  anti-adblock/ad-enabling function → add, same reasoning as the
  `acobt.tech`/Bigo-Ads precedent (#2348) but for a different function
  (revenue recovery vs. ad delivery itself).
- **2026-07-28** (#2503) — **External corroboration (the apex or a subdomain
  already appearing in the StevenBlack `ads-extended` feed) can clear the
  "no confirmed company name" bar that a prior pass held a self-descriptive
  name out on.** #2348 held out `inhousedsp.com` for lack of a confirmed
  specific company despite the maximally self-descriptive name ("in-house
  DSP"). This run found `content.inhousedsp.com` already present in
  `ads-extended` — a second, independently-curated source treating it as
  ad/tracking infra. Combined with the self-descriptive name, that's enough
  to add even without a named company behind it. Use this as a tie-breaker
  for other still-held-out obscure/numeric domains in future passes.
- **2026-07-28** (#2503) — **Session-recording / heatmap / behavioral
  analytics tools (Hotjar, Mouseflow, Inspectlet) are curated under `ads`,
  not skipped as dual-use — but comment/community-engagement platforms
  (Disqus, Viafoura) and co-browsing/CX tools (Glance) are skipped.** The
  line: tools whose primary purpose is tracking *for* advertising/monetization
  purposes go in ads; tools whose primary purpose is a legitimate on-site
  product feature (comments, screen-share support) stay out even though they
  also collect behavioral data.
- **2026-07-28** (#2503) — **A `bet`/`casino`/`gambl` substring match is
  frequently a false positive for an unrelated ad-tech or SSP company name
  (`betweendigital.com` = Between Digital, a Moscow SSP; `betrad.com` remains
  ambiguous, possibly a Betradar/Sportradar-adjacent B2B data domain, not a
  consumer gambling site) — verify ownership before filing under `gambling`,
  same discipline as the `ai`/`copilot` substring trap below.
- **2026-07-28** (#2503) — **A substring match on a category keyword inside
  an unrelated product name is a recurring false-positive source — always
  verify identity, never file on the substring alone.** This run:
  `copilot.money`/`copilotmoney.app` (personal-finance app, matched `copilot`
  under the `ai` sweep — not an AI chatbot), `steamboat.com`/
  `steamboatpilot.com` (a Colorado newspaper, matched `steam` under the
  `games` sweep — not Steam gaming platform), `snapkit.com` (Snap Inc's OWN
  developer SDK, embedded by unrelated third-party apps for "Login with
  Snapchat" — dual-use, not itself a social network to block).
  `redditmedia.com` was the one genuine social-media gap this pass — Reddit's
  own media CDN, a sibling apex to the already-curated `redd.it`/
  `redditstatic.com`, same "bare apex vs. companion CDN domain" pattern as
  the `gemini.google` addition in #2348.
- **2026-07-28** (#2503) — **adult and gambling can both go a full pass with
  ZERO traffic-observed apexes for the category at all** (not just zero
  gaps — zero hits on any keyword sweep, and even the previously-curated
  `acebet.cc` didn't appear in this week's window). Don't force an add to
  avoid an empty section; an empty category section is a valid, expected
  outcome for categories with low weekly incidence.
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
