---
name: app-catalog-pass
description: Do a traffic-driven WifiHaven app-catalog pass — pull real device traffic from prod, find high-volume hostname clusters no existing app covers, and author new app templates (and/or curated-blocklist entries) for them. Invoke whenever the operator says "do an app-catalog pass", "update the apps", "author app templates from traffic", "what apps are we missing", "add a <brand> app", or wants new entries in api/resources/app_templates/. Self-updating: records new learnings back into this file on every run.
---

# WifiHaven app-catalog pass

Repeatable process for turning **observed device traffic** into **app templates**
(`api/resources/app_templates/*.yml`) and, where appropriate, **curated
blocklist** entries (`api/resources/blocklists/*.yml`). The point is
consistency: the same evidence-driven method, the same host-set discipline, the
same validation + review gate every time.

Apps are **template-authored only** (UI app editing is being removed, #1798) —
this is repo YAML work, not DB/UI edits.

This file is the **process**. The **data + conventions** live elsewhere and are
read live — do not duplicate them here:

- Host-set authoring rules, icon fields, shared-CDN/collateral guidance →
  [`api/resources/app_templates/_README.yml`](../../../api/resources/app_templates/_README.yml).
- The registry the loader validates against →
  [`api/resources/app_templates/_index.yml`](../../../api/resources/app_templates/_index.yml).
- Enforcement/attribution model (DNS never enforces; per-(mac,host) nftset
  drops) → [`AGENTS.md`](../../../AGENTS.md) + [`docs/architecture.md`](../../../docs/architecture.md).
- The merge-gating independent review → [`docs/pr-review-checklist.md`](../../../docs/pr-review-checklist.md).

When this skill and those docs disagree, **those docs win** — update this skill
if the process itself changed (see Step 6).

---

## Step 0 — Pull active traffic (READ-ONLY prod)

Prod cloud API `https://api.wifihaven.net`. The admin password lives in **local
Claude memory** (`prod_api_admin_password.md`) — read it, never echo/commit it.

```bash
# read the password WITHOUT printing it, log in, cache the JWT
PW=$(grep -oE 'is `[^`]+`' ~/.claude/projects/*wifihaven*/memory/prod_api_admin_password.md | head -1 | sed 's/^is `//; s/`$//')
TOKEN=$(curl -sS -X POST https://api.wifihaven.net/api/auth/login \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"$PW\"}" | jq -r '.token')
curl -sS -H "Authorization: Bearer $TOKEN" https://api.wifihaven.net/api/devices \
  | jq -r '.[] | "\(.mac)\t\(.name)\t\(.profileId)"'
```

Then pull **per-apex bytes per device** over a recent window. The
`recent-apexes` endpoint already groups FQDNs by apex and returns
`subdomains[]` — exactly what you need:

```bash
# focus on the kid devices (the app catalog is about kid-facing apps)
for mac in <kid-macs>; do
  curl -sS -H "Authorization: Bearer $TOKEN" \
    "https://api.wifihaven.net/api/devices/$mac/recent-apexes?windowDays=30&limit=500" \
    > /tmp/apex_$mac.json
done
# aggregate across devices, ranked by bytes
jq -r '.items[] | "\(.apex)\t\(.bytes)\t\(.hits)"' /tmp/apex_*.json \
  | awk -F'\t' '{b[$1]+=$2; h[$1]+=$3} END{for(a in b) printf "%d\t%d\t%s\n", b[a], h[a], a}' \
  | sort -rn | head -120
# inspect a candidate's real subdomains (drives host-set scoping):
jq '.items[] | select(.apex=="<apex>")' /tmp/apex_*.json
rm -f /tmp/wh_token.txt   # don't leave creds on disk
```

> **CAVEAT — the sample is IPv4-biased.** IPv6 host attribution has been broken
> on prod (v6 recorded as bare literals / dropped — #1796, fixes #1807/#1802).
> Do **not** conclude "no traffic" from a quiet apex; many sites (lego.com et al)
> serve heavily over v6. **Cross-check every candidate with web research**, not
> byte counts alone. Re-confirm whether this caveat still holds at run time
> (check #1796/#1807/#1802 state) and update this note.

## Step 1 — Gap-check against existing apps

List `_index.yml` slugs and the `*.yml` host-sets. For each high-byte apex in
"Other", decide: already covered? adjacent to an existing app (extend that app's
host-set instead of duplicating)? or a genuine gap?

## Step 2 — Classify each cluster: app, blocklist, or skip

- **New app template** — a brand-specific apex/sub-experience a kid actually
  uses, worth a per-app **time limit / allow / block** surface. Tinkercad,
  Duolingo, LEGO Builder, etc.
- **Curated blocklist entry** — a whole **category** the operator blocks
  wholesale (`api/resources/blocklists/games.yml`, `social-media.yml`,
  `adult.yml`, …). "Unblocked games" / proxy / filter-bypass portals belong in
  `games.yml`. The two surfaces are **complementary**, not exclusive: a
  browser-game portal can be BOTH an app (time-limit) and a games.yml entry
  (category block) — duplicates dedupe at the router (precedent: roblox,
  crazygames, poki are both).
- **Skip** — ad/RTB networks (flashtalking, adsrvr, pubmatic…), shared
  service/CDN pools (icloud-content, apple-dns, fastly, akadns, googleapis),
  shared corporate infra (adobe.com, autodesk.com), and below-engagement-bar
  incidental hosts. When in doubt, skip and say so.

## Step 3 — Author tight, correct host-sets

Follow `_README.yml`. Key discipline:

- **Host entries are NOT limited to registrable apexes.** You can scope an app
  to a **sub-experience** by listing the building/feature subdomains and leaving
  the rest of the brand's apex untouched. This is verified end-to-end:
  - `Hostname.parse` ([`shared/src/types/Hostname.scala`](../../../shared/src/types/Hostname.scala))
    accepts any valid multi-label host — no apex requirement.
  - Nothing in the API→agent path reduces a host to its apex; app `_.hosts` flow
    verbatim into `extraAllowed/extraBlocked`/exempt/attribution sets
    ([`api/src/policy/ProfileAppDispositions.scala`](../../../api/src/policy/ProfileAppDispositions.scala)).
  - The agent emits **one verbatim `nftset=/<host>/...` per host**
    ([`openwrt/files/usr/lib/lua/wifihaven/render.lua`](../../../openwrt/files/usr/lib/lua/wifihaven/render.lua), ~L559).
  - **Both enforcement AND attribution suffix-match the host's own subtree.**
    `HostMatch.matchesApex(host, x) = host == x || host.endsWith("." + x)`
    ([`shared/src/types/HostMatch.scala`](../../../shared/src/types/HostMatch.scala)).
    So a host entry of `services.lego.com` covers `appconfig.services.lego.com`
    for **both** the drop set and the per-app time budget, and never
    `www.lego.com`. (Worked example: the `lego` app is scoped to
    `cobuild.i.lego.com` / `dbix.i.lego.com` / `services.lego.com` /
    `apps.lego.com` — the LEGO Builder building experience — excluding the shop.)
  - The `_README.yml` "list apex hostnames" line is the **default** convention,
    not a constraint. Deviate only with a clear reason, and document it inline.
- **Keep it tight:** apex + the app's real branded subdomains/CDNs you actually
  observed. Don't pin rotating shared-CDN artifacts; don't pull in a shared
  vendor-API anycast host (collateral — see `_README.yml` shared-pool section).
- **Only template what you've seen used** (or web-confirmed as the app's real
  domains). Don't add marketing apexes with no observed kid traffic.

## Step 4 — Register + keep the pinned test in sync

- Add the slug to `api/resources/app_templates/_index.yml`.
- **Update the hardcoded expected slug set** in
  [`api/test/src/feature/AppTemplatesSpec.scala`](../../../api/test/src/feature/AppTemplatesSpec.scala)
  (the `expected` set ~L90-122) — it pins the full slug list and WILL fail
  otherwise. This is an additive edit, not a weakening.
- New blocklist entries: edit the `*.yml`; `BundledBlocklistsSpec` asserts
  *presence* of representative hosts, not an exact count, so additions are safe.
- Keep a short evidence doc under `evidence/classification-<issue>.md` with the
  per-apex byte table + disposition + host-set coverage check.

## Step 5 — Validate + ship

```bash
mill api.test.testOnly 'wifihaven.api.feature.AppTemplatesSpec'
mill api.test.testOnly 'wifihaven.api.feature.BundledBlocklistsSpec'   # if you touched blocklists
scalafmt --check --non-interactive                                     # only if any .scala changed
```

- Worktree off `origin/main` (`git worktree add .claude/worktrees/<slug> -b <branch> origin/main`).
- Open a PR. Use "Relates to #<issue>" (don't auto-close unless fully covered).
- Run `/pr-review`, address BLOCKERs + cheap SHOULD-FIX, push, re-run until no
  BLOCKER. Monitor CI through green; do **not** `gh pr merge` / enable
  auto-merge (operator's call).
- Post a brief summary on the issue: new apps (slug + host-set + rationale) and
  any app-vs-blocklist / sub-experience-scoping decisions.

## Step 6 — Self-update (MANDATORY, every run)

Before you finish, reflect on what THIS run taught you that the steps above
didn't already capture — a new endpoint, a new shared-CDN to skip, a host-set
gotcha, a changed caveat (e.g. #1796 fixed), a new blocklist category, a
classification judgment call. **Append it to the Learnings log below and include
that edit in the same PR** (or, if the pass shipped no code PR, a tiny
skill-only PR). Keep entries one or two lines, newest first, dated. If a step
above is now wrong, fix the step too — don't just log around it.

---

## Learnings log (newest first)

- **2026-06-21 (#1815)** — Host entries can be subdomains; enforcement AND
  attribution both suffix-match the entry's own subtree (`HostMatch.matchesApex`),
  so you can scope an app to a sub-experience (LEGO Builder → `*.i.lego.com` +
  `services`/`apps.lego.com`) and exclude the brand's shop. Verified verbatim
  host flow through `Hostname.parse` → `ProfileAppDispositions` → `render.lua`
  `nftset=/<host>/`.
- **2026-06-21 (#1815)** — `recent-apexes?windowDays=&limit=` is the right traffic
  source: pre-grouped by apex with a `subdomains[]` list. Aggregate across kid
  devices by bytes with the awk one-liner in Step 0.
- **2026-06-21 (#1815)** — "Unblocked games" / filter-bypass portals (e.g.
  duckmath.org) go in `blocklists/games.yml` for the category-block surface, AND
  optionally as an app for the time-limit surface — they're complementary and
  dedupe at the router.
- **2026-06-21 (#1815)** — Prod v6 host attribution is broken (#1796); the byte
  sample is IPv4-biased. Always web-cross-check candidates; never infer "no
  traffic" from a quiet apex.
