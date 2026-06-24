---
name: ads-blocklist-pass
description: Do a traffic-driven WifiHaven ads-blocklist pass — pull real device traffic from prod, find ad/RTB/tracker hostnames devices are hitting that the curated ads blocklists don't yet cover, and add the genuine gaps to api/resources/blocklists/ads.yml. Invoke whenever the operator says "update the ads blocklist", "do an ads-blocklist pass", "what ad traffic isn't blocked", "review traffic for unblocked ads", or wants new ad-category blocklist entries. Self-updating: records new learnings back into this file on every run.
---

# WifiHaven ads-blocklist pass

Repeatable process for turning **observed ad/tracker traffic** into curated
**ads blocklist** entries (`api/resources/blocklists/ads.yml`, with the bulk of
coverage in the URL-sourced `ads-extended`). Sibling to the `app-catalog-pass`
skill — same evidence-driven method, same validation + review gate — but the
target is the **ad/RTB/tracker** category, not brand apps.

This file is the **process**. The **data + conventions** live elsewhere and are
read live — do not duplicate them here:

- The curated ads lists + index → [`api/resources/blocklists/ads.yml`](../../../api/resources/blocklists/ads.yml),
  [`api/resources/blocklists/_index.yml`](../../../api/resources/blocklists/_index.yml).
- Blocklist design (inline vs URL-sourced, refresh cadence) → [`docs/design/blocklists.md`](../../../docs/design/blocklists.md).
- Enforcement model (per-(mac,host) nftset drops; DNS never enforces) →
  [`AGENTS.md`](../../../AGENTS.md) + [`docs/architecture.md`](../../../docs/architecture.md).
- Host-scoping mechanics (host entries are suffix-matched, not apex-constrained)
  → the sibling [`app-catalog-pass`](../app-catalog-pass/SKILL.md) Step 3.
- The merge-gating independent review → [`docs/pr-review-checklist.md`](../../../docs/pr-review-checklist.md).

When this skill and those docs disagree, **those docs win** — update this skill
if the process itself changed (see Step 5).

---

## Step 0 — Pull active traffic (READ-ONLY prod)

Same source + auth as `app-catalog-pass` Step 0. Prod `https://api.wifihaven.net`;
admin password in local memory (`prod_api_admin_password.md`) — read, never
echo/commit. Pull per-apex bytes/hits across **all** devices (ad traffic is
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

## Step 1 — Identify unblocked ad/RTB/tracker traffic

An apex is an **ads-blocklist candidate** if it is an advertising exchange,
RTB/SSP/DSP, tracker, data broker, attribution/measurement, or tag network — and
is NOT already covered by `ads.yml` / `ads-extended`. The signal: many distinct
subdomains, high hit count, served to many devices, and a name that maps to no
app or content category. Apexes seen unblocked in the #1815 traffic sample that
look like classic ad/RTB infra (verify each before adding):

```
flashtalking innovid adsrvr doubleverify adsafeprotected pubmatic casalemedia
media.net sharethrough onetag-sys 3lift seedtag richaudience adnxs rubiconproject
smartadserver 33across rlcdn openx criteo adform teads outbrain bidswitch
pubmatic mathtag agkn adkernel sitescout rfihub bidr 360yield smaato …
```

**Cross-check before adding** — web-search the apex; many are obvious, but verify
it's ad-dedicated. Be conservative:

- **Skip dual-use / shared infra** that also carries legitimate product traffic.
  Known traps to LEAVE OUT: `google-analytics.com`, `googleadservices.com`,
  `googlesyndication.com` parents that share Google pools; `app-measurement.com`
  (Firebase); `sentry.io` / `bugsnag.com` (error reporting); `cloudflare.com`,
  `amazonaws.com`, `akamai*` (multi-tenant). Same collateral rule as app
  host-sets — a shared pool drags non-ad traffic into the drop.
- **`ads.yml` is hand-curated apex hosts** for clearly ad-dedicated domains; the
  heavy lifting is the URL-sourced `ads-extended` feed. If the gap is really
  "the public feed is stale / not catching this", say so rather than
  hand-maintaining hundreds of apexes — a handful of high-traffic, clearly-ad
  apexes per pass is the right scale.

## Step 2 — Verify the gap is real

Before adding `X`, confirm it isn't already blocked: load `ads.yml` (and, if
fetchable, the `ads-extended` source) and check membership. Only add genuine
gaps. Record per-apex hits + a one-line "what it is" for each addition.

## Step 3 — Author

- Add apexes to `api/resources/blocklists/ads.yml` under a dated comment block
  (`# traffic-driven additions (#<issue>): observed but unblocked ad/RTB infra`).
  Subdomains are suffix-matched at the router, so list apexes.
- No `_index.yml` change unless adding a NEW list file.
- `BundledBlocklistsSpec` asserts *presence* of representative hosts, not an
  exact count — additions are safe; optionally pin one representative new host
  in the spec's presence assertions.
- Keep an evidence doc under `evidence/ads-classification-<issue>.md` (per-apex
  hit table + what-it-is + why-it-was-a-gap).

## Step 4 — Validate + ship

```bash
mill api.test.testOnly 'wifihaven.api.feature.BundledBlocklistsSpec'
scalafmt --check --non-interactive   # only if any .scala changed (usually none)
```

Worktree off `origin/main`; file/identify a tracking issue; open a PR ("Relates
to #<issue>"); run `/pr-review`, address BLOCKERs + cheap SHOULD-FIX, push,
re-run until no BLOCKER; monitor CI through green. Do **not** `gh pr merge` /
enable auto-merge (operator's call). Post a summary on the issue: apexes added +
what each is + why it was a gap.

## Step 5 — Self-update (MANDATORY, every run)

Reflect on what THIS run taught you that the steps didn't capture — a new
dual-use host to skip, a better ad-apex detection signal, a changed caveat, a
stale-public-feed finding. **Append it to the Learnings log below and include
that edit in the same PR.** If a step above is now wrong, fix the step too.

---

## Learnings log (newest first)

- **2026-06-23** (#1923) — **Diff candidates against the StevenBlack
  `ads-extended` feed before adding.** Fetch
  `raw.githubusercontent.com/StevenBlack/hosts/master/hosts`, extract the
  `0.0.0.0 <host>` second field into a set, and test each candidate apex for
  exact + subdomain membership. This cleanly splits **genuine gaps** (feed
  misses the apex entirely → highest value to hand-curate) from apexes the
  extended feed already covers (lower value, only helps curated-only profiles).
  Prioritize the gaps; add a small set of the highest-traffic covered apexes for
  the curated baseline.
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
