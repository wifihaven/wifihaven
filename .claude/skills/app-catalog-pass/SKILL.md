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

> **CAVEAT — RESOLVED as of 2026-06-23 (#1922 run).** IPv6 host attribution
> *was* broken on prod (v6 recorded as bare literals / dropped — #1796), making
> the sample IPv4-biased. #1796 is now **closed** and its fixes **#1807** (NDP-
> neighbor v6 events) and **#1802** (v6 in per-device usage) are **merged**, so
> the sample now includes v6 traffic. **Still cross-check every candidate with
> web research**, not byte counts alone. Re-confirm this state at run time
> (`gh issue view 1796/1807/1802`) — if a new attribution gap appears, restore
> the IPv4-biased warning here.

## Step 1 — Gap-check against existing apps

**Start from the server's own gap list, not a hand diff.**
`GET /api/profiles/<id>/usage-by-app?from=YYYY-MM-DD&to=YYYY-MM-DD`
(`UsageRoutes.scala:150`; `from` defaults to today, `to` defaults to `from`)
returns `apps[]` — what IS attributed — and `orphanHosts[]`, every host carrying
real time that no app covers, each with `proportionalSeconds`/`presenceSeconds`.
That is already time-weighted, which the raw byte table is not.

Two cautions before you treat an orphan as a gap:

- **`orphanHosts` is not a clean "uncovered" list.** Per #1898
  (`UsageRoutes.scala:801-807`) a host declared under a template's
  `shared_hosts:` still contributes its *unattributed* span to the orphan
  bucket, possibly alongside its own app row. A plain `hosts:` entry that
  matches a template never orphans, so most orphans ARE genuine gaps — but
  confirm against `_index.yml` and the `*.yml` host-sets before authoring.
- **Google/Apple platform infra dominates the top of the list.** That is the
  usual skip pile (Step 2), not a finding.

Then, for each surviving candidate, decide: already covered? adjacent to an
existing app (extend that app's host-set instead of duplicating)? or a genuine
gap? Use `recent-apexes` to scope the host-set — it is the endpoint that
returns `subdomains[]`.

## Step 2 — Classify each cluster: app, blocklist, or skip

- **New app template** — a brand-specific apex/sub-experience a kid actually
  uses, worth a per-app **time limit / allow / block** surface. Tinkercad,
  Duolingo, LEGO Builder, etc.
- **Curated blocklist entry** — a whole **category** the operator blocks
  wholesale (`api/resources/blocklists/games.yml`, `social-media.yml`,
  `adult.yml`, …). A legit browser-game portal can be BOTH an app (time-limit)
  and a games.yml entry (category block) — duplicates dedupe at the router
  (precedent: roblox, crazygames, poki are both). **But a filter-bypass /
  "unblocked games" / web-proxy site (duckmath.org, holyunblocker.org, now.gg,
  the TitaniumNetwork stack) is blocklist-ONLY — never an app.** There's no
  reason to give a kid a daily time *budget* for a filter-evasion tool; it's a
  block target, full stop (operator decision, #1815). Put it in `games.yml` and
  do NOT author an app template for it.
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

- **2026-09-13 (#2778)** — **A shared-pool host-set has TWO collateral
  directions, and every pass so far has only reasoned about one.** The
  `eb_`/over-drop argument is the reflex. The other one is worse: `extraAllowed`
  beats every drop it reaches (#421) and the `AppMode.Allowed` branch of
  `ProfileAppDispositions.enforcement` carves such an app's hosts into it — so
  allowing an app whose host sits on a shared pool carves EVERY tenant of that
  pool out of the category lists, the per-host drops AND the whole-MAC blocks on
  that MAC. **Do not flatten the modes together**: a `TimeLimited` under-cap
  carve is gated on `!state.blocked` (#1980), so it beats the category and
  per-host drops but stays subordinate to pause / schedule / daily-limit; and a
  Hard pause zeroes every per-profile carve (#1418). The first draft of this
  entry said TimeLimited "does the same" and the review caught it. For GitHub Pages that
  means `*.github.io`, which is a common home for web proxies and
  unblocked-games mirrors. The #2369 / #2601 shape in mirror image, on a
  platform no Google-oriented ban list catches. **When you write a collateral
  paragraph, write both directions — Blocked over-drops, Allowed over-permits —
  and say which narrowing fixes both.** This one was caught by the independent
  review, not by the author.

- **2026-09-13 (#2778)** — **GitHub Pages is a demonstrated-sharing origin, and
  it is the static-hosting analogue of `shops.myshopify.com`.** Every Pages site
  — custom domain or `*.github.io` — answers on the SAME fixed global four
  addresses `185.199.108-111.153`, stable across repeated lookups
  (`emojikitchen.dev`, `xsalazar.github.io`, `pages.github.com`, `jekyllrb.com`,
  `bootstrap-vue.github.io` all returned the identical set). Note the CNAME test
  alone does NOT catch this: `www.emojikitchen.dev` CNAMEs to
  `xsalazar.github.io`, an ACCOUNT-scoped name that looks like the accepted
  `eaglercraft-99f.workers.dev` precedent. The addresses are what give it away,
  so **run both tests** — platform-wide CNAME target, and identical A records
  across unrelated sites. Same shape to check for: `*.netlify.app`,
  `*.pages.dev`, `*.vercel.app`, `*.surge.sh`.
- **2026-09-13 (#2778)** — **AWS API Gateway collateral is REGIONAL, so check
  the region before calling it shared.** `backend.emojikitchen.dev` CNAMEs to
  `d-….execute-api.us-west-2.amazonaws.com`; the kid devices' three other
  `execute-api` endpoints are all us-east-1, a different regional edge with a
  different address pool. Record the REGION, not the addresses — the ones
  observed here rotated within a day. A blanket
  "API Gateway is a shared vendor pool, skip" would have been wrong here — the
  regions don't overlap, so it stays README Class 2 (latent), not Class 1.
- **2026-09-13 (#2778)** — **"Demonstrated collateral" is not automatically
  "skip" — that conclusion only follows when a collateral-free host exists to
  fall back to.** `emojikitchen.dev` has no dedicated-bytes host at all: the
  front-end is GitHub Pages, the back-end is API Gateway. Skipping would have
  shipped nothing while the kid kept using it unseen. What actually resolved it
  is that **attribution is hostname-based and carries zero collateral**, so the
  visibility half of a template is always safe even when the block half is
  compromised. Ship, document the collateral at the same volume as the headline
  exclusion, and name the narrower host-set an operator can edit to if they want
  zero collateral (here `backend.` alone: mashups stop, page loads, Pages pool
  never enters the drop set, at the cost of 28 of 35 attributed minutes).
  Operator host edits win over the seeder, so that guidance is actionable.
- **2026-09-13 (#2778)** — **Look-alike domains around a brand are mostly other
  people's businesses — check what each one SERVES before listing it.** Of six
  emoji-kitchen-shaped domains, one was the real site, three were unrelated
  operators' own emoji products, one did not answer, and `emojikitchen.io` was
  squatted and serving a Vietnamese football-streaming page. Padding a host-set
  with untrafficked look-alikes to "cover the brand" would have put a stranger's
  streaming site into a kid's app budget. `curl` the `<title>` of every
  candidate; it takes one command and it is the whole check.

- **2026-09-11 (#2774)** — **First pass driven by `orphanHosts`, and the method
  works: it put the single highest-value finding at the top of the list instead
  of buried in a byte table.** `www.youtube-nocookie.com` — YouTube's
  privacy-enhanced EMBED domain — was 799 unattributed proportional minutes and
  had been missing from `youtube.yml` since the template was written. A
  byte-ranked `recent-apexes` sweep had missed it across many passes because
  31.9 MB is unremarkable; time-weighted, it was the biggest genuine gap in the
  catalog. **When a brand serves the same content from a second domain for
  privacy/embed/no-cookie reasons, that domain is a host-set gap by default** —
  check for `-nocookie`, `-static`, `embed.` and regional-privacy variants of
  every already-templated brand.
- **2026-09-11 (#2774)** — **A shared multi-tenant ORIGIN is disqualifying in a
  way a shared CDN edge is not, and the allow side is what makes it bite.** Two
  unrelated shops in this household's own traffic — `mouldkingcorp.com` and
  `thetraindepartment.com` — both CNAME to the literal hostname
  `shops.myshopify.com` and answer on the identical `23.227.38.74`. The usual
  argument against templating them is the `eb_` one `arduino.yml` makes for its
  own store, but the stronger one is the ALLOW side: per #1899 the block side
  takes distinctive hosts only, while the under-cap carves take the full set, so
  the shared address reaches `ea_` — and `extraAllowed` beats every drop it
  reaches (#421). **Do not try to write the exact scope from memory: two
  successive drafts of this entry got it wrong and review caught both.** What is load-bearing for the skip is just
  that the carve exists and reaches the `bl_`/`eb_` drops. If a future decision
  actually turns on the precise scope, read `PolicyService.computeBlockRules`
  and `ProfileAppDispositions.enforcement` — the gates that kept getting missed
  are `val timeLimitedUnderCap = if (state.blocked) Nil else
  timeLimitedUnderCapHosts(state)` (`:1506-1507`),
  `if (isHardPause) Nil`, which zeroes all the PER-PROFILE carves together,
  Allowed-mode included (`:1509-1511`, #1418) — `global.extraAllowed` survives it
  by design (`:1486-1497`), though an app template's hosts never land there, `capGroups = perApp.filter(_.mode == AppMode.TimeLimited)`
  (`ProfileAppDispositions:53-54`) which makes the exempt carve TimeLimited-only
  even with `exemptFromDaily` set (#2747), and `suppressedByScheduleToggle`
  (`:145`, #1679). The `eb_` half bites regardless of any of this — a brand host
  is distinctive, so it lands there too.
  **Before templating any brand, resolve `www.<brand>` and check whether the
  CNAME target is a platform-wide hostname** (`shops.myshopify.com`,
  `*.hosted-by-discourse.com`, `*.zendesk.com`, `wp.wpenginepowered.com`). If
  two unrelated candidates in your own sample land on the same address, that is
  demonstrated sharing, not a hypothetical — and it is the strongest skip
  argument available. Contrast Cloudflare's 104.26/172.67: multi-tenant too, but
  ordinary accepted Class 2, and no address in the sample was shown serving a
  second unrelated site.
- **2026-09-11 (#2774)** — **A third of orphan time (33.7%, 48,079 of 142,672
  minutes across the kid profiles) is bare IP literals — 1,093 distinct
  addresses, nearly all IPv6 Google/Apple/Akamai.** Budget for this when reading
  an orphan list: the denominator is not all catalogable, so "what fraction of
  screen time is unattributed" overstates how much the catalog can ever fix.
  Filter literals out before ranking candidates (`$3 ~ /:/ || $3 ~ /^[0-9.]+$/`)
  or they crowd the top. **Do not diagnose this inside a catalog pass** — some
  literal share is expected by design (`blockIpOnly` exists for destinations with
  no attributable hostname) and #1796's v6 fixes are merged, so it is not simply
  that bug. Filed as #2775 with first steps rather than guessed at.
- **2026-09-11 (#2774)** — Two smaller traps this pass hit. A Cloudflare
  bot-challenged site (`rebrickable.com` returns "Just a moment…" / HTTP 403 to
  `curl`) looks dead to a fetch-based check but is perfectly healthy — say so in
  the template so the next author doesn't "fix" a live app. And when an issue
  number is needed in a template comment or evidence filename, **file the issue
  BEFORE writing them**: guessing the next number cost a repo-wide renumber this
  pass when the real number came back four higher than expected.
- **2026-09-11 (#2762)** — **There is a server-side gap list; stop deriving it
  by hand.** `GET /api/profiles/<id>/usage-by-app?from=YYYY-MM-DD&to=YYYY-MM-DD`
  (`UsageRoutes.scala:150`; `from` defaults to today, `to` defaults to `from`)
  returns `apps[]` — what IS attributed, with per-host `proportionalMins` — AND
  `orphanHosts[]`: every host carrying real time that **no app covers**, each
  with `proportionalSeconds`/`presenceSeconds`. That is precisely the Step-1
  gap-check, computed by the server, and it beats diffing `recent-apexes`
  against `_index.yml` by hand because it is already time-weighted. **It is NOT
  a clean "uncovered hosts" list, though** — per #1898 (`UsageRoutes.scala:801-807`)
  a host declared under a template's `shared_hosts:` still contributes its
  *unattributed* span to the orphan bucket, possibly alongside its own app row.
  Mechanically (`allocByHost`, `UsageRoutes.scala:724-740`) there are TWO ways a
  host gets the `None` key, and only the first is the obvious one:
  `distinctiveAppOf(h)` returns `Option[AppId]` (`:603-604`) and yields `None`
  for a host in no template — the ordinary uncovered-host orphan you are
  hunting for; and a host with a non-empty `sharedAppsOf(h)` (built from
  `mappings.filter(_.shared)`) goes through `allocateSharedHostSeconds`, whose
  `None`-keyed allocation is the #1898 leftover. A plain `hosts:` entry that
  matches a template resolves to `Some(appId)` and never orphans. So the trap is
  narrow but real: a `shared_hosts:` host can look like a gap while already
  being in the catalog. So always confirm a
  promising orphan is genuinely uncovered (grep `_index.yml` and the
  `*.yml` host-sets) before authoring an app for it, or you will ship a
  duplicate of an app that already exists. Use it to
  FIND candidates, then `recent-apexes` to scope the host-set (it's the one
  that returns `subdomains[]`). Verified live this pass: with `amazon` and
  `sportys` merged and seeding on prod, `unagi.amazon.com` still showed up as an
  orphan at 55 proportional minutes — the exact gap the `amazon-telemetry` app
  closes. Expect Google/Apple platform infra to dominate the top of the list;
  that's the usual skip pile, not a finding.
- **2026-09-11 (#2762)** — **`HostMatch.hasApexMatch`'s 5-hop bound costs you a
  block-page REASON, never a drop — and mistaking it for the latter is the
  AGENTS.md anti-pattern in a new costume.** A draft of this entry claimed a
  blocklist-category apex deeper than five labels "stops matching on the
  enforcement side too." Inverted. `hasApexMatch` has exactly ONE production
  caller — `PolicyService.scala:1249`, inside `categoryBlock` under
  `decideDetailed` — and `decideDetailed` is reached only from
  `BlockedRoutes.scala:153` (the block page's reason) and
  `POST /api/router/decision` (`RouterRoutes.scala:181`), which **no agent code
  calls**. Category ENFORCEMENT is the `bl_`/`bl6_` nftables sets, populated one
  verbatim `nftset=/<host>/4#inet#wifihaven#bl_<id>,…` line per blocklist member
  (`blocklists.render_shards`; format at `render.lua:527-531`) — dnsmasq suffix
  matching, as unbounded as the per-host `eb_` path. `grep -rn 'apexTails\|maxHops'
  openwrt/files` returns NOTHING: the agent has no bounded tail walk anywhere.
  **Before writing that anything is bounded "on the enforcement side", check
  whether the code you are reading is even on the enforcement plane** — an API
  decision endpoint the router never calls is not. Resist the urge to write the
  tidy two-column taxonomy of which matcher is bounded and which plane it serves:
  five drafts of this entry tried, and every one mis-sorted something, because
  bounded-vs-unbounded (`apexTails` walk vs `matchesApex` suffix test) and
  API-vs-enforcement are INDEPENDENT axes: a matcher's boundedness tells you
  nothing about which plane it serves. If you need
  to know where a specific matcher runs, grep its call sites and read the
  enclosing function — don't consult a summary, including this one.
- **2026-09-11 (#2762)** — A PR you opened THIS session can merge while you are
  still working, which silently turns its branch into a dead branch: a follow-up
  commit pushed there is unreachable from `main` and ships nothing. This
  happened here — #2763 merged at 22:53:35Z and the `amazon-telemetry` commit
  was committed at 00:36:32Z, ~103 minutes later, onto the dead branch; only
  the review caught it. **Re-check
  `gh pr view <n> --json state` immediately before pushing any follow-up, even
  one to a PR you opened minutes ago** — the standing "never push to a merged
  PR's branch" rule is usually read as being about OLD PRs, and that reading is
  what makes this one easy to walk into. Recovery is cheap and lossless:
  branch fresh off `origin/main`, `git cherry-pick <sha>`, open a new PR
  referencing the old one.
- **2026-09-11 (#2762)** — On prod, `GET /api/apps` returns rows nested under
  `.app` (`{app: {id, slug, name, icon, iconType, templateId}, hosts: [...],
  assignments, blocklisted}`), NOT a flat app object — a naive
  `jq '.[] | select(.slug==...)'` silently returns nothing and reads as "the
  template didn't seed." Confirm a seed with
  `jq -r '.[] | "\(.app.id) \(.app.slug)"'` before concluding a deploy failed.
- **2026-09-10 (#2762)** — **A template's icon MUST be `icon_type: url` with an
  http URL** — `AppTemplatesSpec:271` pins it for EVERY starter template
  (#1041), so an `icon_type: emoji` template fails two tests even though
  `_README.yml` documents emoji as a valid type. Convention is
  `https://icons.duckduckgo.com/ip3/<domain>.ico`; check it actually returns
  200 first, because the service answers 404 with a generic placeholder PNG for
  domains it doesn't know (`a2z.com` and `amazon.dev` both do). When the honest
  favicon duplicates a sibling app's, take the duplicate — reaching for a
  different brand's icon to look distinct (the AWS logo, here) mislabels the
  app, and the NAME is what disambiguates in the list.
- **2026-09-10 (#2762)** — Telemetry hosts an app template deliberately EXCLUDES
  don't vanish; they surface as loose per-site rows on the device page, and at
  real durations (`unagi.amazon.com` 28m, `data.amazon.com` 24m). That is worth
  its own app rather than an extension of the brand's: a time-limited app's
  host-set is ONE aggregated budget (#1505), so folding telemetry into the
  shopping app would bill background chatter to the shopping budget, while a
  sibling app lets the operator see and budget it separately. **Ubiquity is the
  classifier** — `unagi`/`data`/`fls-na` on all eight devices is the tell that
  it's background infrastructure, not anyone's activity. Keep the split honest
  inside the new app too: ad surfaces go to `blocklists/ads.yml`, and experiment
  CONFIG/ROUTING (`weblab.a2z.com`) stays out of a "telemetry" app, because
  nothing reads a telemetry response but something does read config.
- **2026-09-10 (#2762)** — A CNAME sibling is NOT covered by its parent-looking
  name: `unagi.amazon.com` CNAMEs to `unagi-na.amazon.com`, but `matchesApex` is
  `host == x || host.endsWith("." + x)`, and `"unagi-na.amazon.com"` does not
  end in `".unagi.amazon.com"`. Both need listing; only `ipv6.unagi-na.` is a
  true child. Conversely, a suffix anchor is the ONLY way to cover hosts with
  randomized per-device labels — the Minerva device-telemetry endpoints are
  63-hex-labelled per device, so `minerva.devices.a2z.com` is not a shortcut but
  a necessity.
- **2026-09-10 (#2762)** — An operator-named pass ("create apps for amazon
  and sportys") still runs Step 0, just inverted: the traffic pull is no longer
  for *finding* candidates but for *scoping* the ones you were handed, and it
  is what turns a guess into a host-set. Here it produced the catalog's
  strongest apex-exclusion argument to date — every host that made the case
  against a bare `amazon.com` entry (`aws.amazon.com`, `api.amazon.com`,
  `read`/`music`/`watch`/`apay-us`/`pharmacy`) was in this household's own 30d
  traffic, not a hypothetical from web research. **Don't skip the traffic pull
  because the operator already named the brand.**
- **2026-09-10 (#2762)** — Two apexes of the same brand can take OPPOSITE
  apex-vs-subdomain calls in one template, and the contrast is worth stating
  inline: `ssl-images-amazon.com` is listed as a bare apex (single-purpose
  static-image zone, every child is storefront imagery by construction) while
  `media-amazon.com` is not (its `metrics.` child is telemetry). The test isn't
  "apex or subdomain" as a house style; it's **how single-purpose the zone is**,
  which you can only answer by enumerating its observed children. **Rest that
  call on what the children ARE, never on an IP-pool-disjointness claim** — the
  first draft of this pass justified the `metrics.` exclusion as "a Fastly pool
  no kept host touches" and the independent review disproved it with one `dig`,
  because the kept hosts are DNS-steered across CDNs (Fastly included). A
  disjointness claim over CDN-fronted hosts is close to unfalsifiable; don't
  make one.
- **2026-09-10 (#2762)** — A brand's per-device subdomain split can be the
  whole classification: Sporty's showed 2.04 GB / 365 hits on Kid Laptop with
  `stream.videos`/`dl.videos`/`ye.courses` and NO `www` — pure course video from
  Sporty's Online Training, zero store browsing — while the adult device hit all
  five hosts including the shop. Per-apex bytes alone would have read this as
  ambiguous shop-or-courses traffic. **`recent-apexes` gives no per-subdomain
  byte split, so when a brand has both a kid surface and a parent-purchasing
  surface, diff the `subdomains[]` lists ACROSS devices** — the device that
  lacks the store host tells you what the kid actually uses.
- **2026-09-10 (#2762)** — A `dig` snapshot of a CDN-fronted host is a SAMPLE,
  not the host's IP set, and this pass got caught assuming otherwise twice.
  Amazon's `images-na`/`images-eu.ssl-images-amazon.com` and `m.media-amazon.com`
  were seen resolving through `c.media-amazon.com` to CloudFront in one minute
  and through Akamai (23.215.223.x) in another. So: resolve the whole chain
  (`dig +short` prints it), repeat it, and **never justify keeping or excluding
  a host with a claim about which pools it does or doesn't share** — describe
  the steering and rest the decision on what the host IS (app content vs
  telemetry vs shared vendor API), which doesn't move between lookups.
  Corollary, and get this one right: a host observed as a DIRECTLY-QUERIED name
  is worth its own entry — but NOT because "a CNAME target earns no
  attribution." That is false. #1344/#1346 fold a re-queried CNAME target back
  onto its branded chain head, and `each_candidate_host` walks that recovered
  head alongside the answered name
  (`openwrt/files/usr/lib/lua/wifihaven/dns_tail_sets.lua`). The real reason is
  that the alias edge is TTL-bounded (`resolve_head`) and capped at
  `max_aliases` with oldest-LEARNED eviction (`evict_oldest_alias`, which
  drops the lowest `seq` and is not refreshed on read) — both in
  `dns_log.lua` — so the fold-back is best-effort and an explicit entry is
  the reliable version. Two drafts of this pass asserted a mechanism instead of
  reading the agent code; **go read the Lua before writing "how attribution
  works" into a template comment.**
- **2026-09-10 (#2762)** — The prod traffic pull can be refused by the Claude
  Code permission classifier: the block lands on READING the credential
  (`prod_api_admin_password.md`), not on the API call — a plain
  `curl https://api.wifihaven.net/api/health` succeeds while the login command
  is denied. Splitting the read from the POST doesn't help. Say so and ask the
  operator rather than reshaping the command to slip past it; the fix is on
  their side (auto-mode setting or a Bash permission rule).
- **2026-08-31 (#2754)** — A brand-new template can ship with its own
  host-set gap: `arduino.yml` merged this same week (#2753) missing
  `login.arduino.cc` — the sign-in host every already-kept Arduino Cloud page
  (`app`/`create`/`cloud.arduino.cc`) links to directly. **When a template is
  brand new, don't just diff observed traffic against its host-set — `curl`
  the already-kept pages' own HTML and grep for same-apex hostnames they
  reference.** Byte/hit volume alone wouldn't have caught this: the gap is a
  broken user flow (can load the shell, can't log in), not a missing-bytes
  cluster. Also confirmed a zero-incremental-IP add is worth taking even with
  unconfirmed purpose: `builder.arduino.cc` resolved to the *identical* 4 IPs
  and `awselb`+CloudFront signature as the already-kept `api2.arduino.cc`.
  **Careful with the inference this licenses** — matching edge IPs prove the
  same CDN *edge*, not the same *distribution* (edge IPs are shared across
  many distributions within a PoP), so don't generalize this to "matching A
  records ⇒ same distribution ⇒ safe to add." What actually licenses the add
  is narrower and still correct: no IP *outside the already-accepted set*
  enters the drop set, which is all the collateral argument needs regardless
  of distribution identity.
- **2026-08-31 (#2754)** — Extended the "randomized-label subdomain = likely
  CNAME-cloaked third-party telemetry" tell (first noted implicitly via the
  `aayinltcs.arduino.cc` cluster this pass) as a general heuristic: a
  same-apex subdomain with a non-descriptive, non-brand-related label (no
  recognizable product/service name) paired with `api`/`evs`(events)/`t2`-style
  child names is analytics/tracking infra hiding behind a first-party domain
  to dodge ad-blockers — exclude it even when `dig` can't identify the actual
  vendor behind the CNAME. Server-side Google Tag Manager (`sgtm.<apex>`,
  resolving to an isolated Google Frontend IP with `x-cloud-trace-context` in
  the response headers) is the same call under a more legible name — both
  get the same disposition as `ct.canva.com` (a Google-routed click-tracking
  redirector found the same pass): tracking relay, not app content, exclude
  regardless of first-party hosting.
- **2026-08-31 (#2754)** — Autodesk's product family keeps producing genuine
  gaps even in a mature catalog: `instructables.com` (DIY/maker tutorials,
  same Autodesk family as the already-templated `tinkercad`/`thingiverse`)
  cleared the bar at just 8.1 MB / 18 hits. Its bare apex sits on AWS
  CloudFront's shared `99.84.118.x` pool — the *exact* pool `arduino.yml`
  already flags as collateral — while its two real subdomains each have their
  own dedicated CloudFront distribution. When a candidate's apex lands on a
  pool already named as shared-risk elsewhere in the catalog, that's a strong
  prior for scoping to observed subdomains over templating the bare apex,
  even before checking whether those subdomains have dedicated infra.
- **2026-08-24 (#2740)** — Another clean no-op: no new app, no host-set gap,
  no blocklist entry. Two new things worth recording:
  - `api.mcsrvstat.us` (7.8 MB / 89 hits, real recurring volume) is a **shared
    third-party Minecraft-server-status API** used by many unrelated
    sites/bots (web-confirmed) — not owned or branded by Mojang or
    Eaglercraft. Treat "single `api.` host, real bytes, but the *owner* is a
    generic multi-consumer service" as its own collateral class, distinct
    from both "vendor-API-with-no-branded-surface" (skip, e.g.
    `elevenlabs.io`) and "brand-dedicated infra for an app you already
    template" (extend the app's `hosts:`, e.g. the eaglercraft relay
    servers). The tell is ownership, not volume: don't add a shared-service
    apex to an app's host-set just because the app's users are the ones
    hitting it — confirm the *service itself* is brand-owned first.
  - `scholastic.com` (8.0 MB / 6 hits, spread thin across `clubs`/`ltm`/
    `sstats`/`webchat-customer`/`www`) is a parent/school book-ordering
    commerce flow (Scholastic Book Clubs), not a kid-facing recurring app —
    skip even though the byte count alone would clear prior "thin cluster"
    bars (cf. `serato`/#2331, `freckle`/#2596). When a candidate's real-world
    function is a parent/administrative transaction rather than something
    the kid personally engages with, that's disqualifying regardless of
    subdomain diversity or byte volume.
  - The device roster itself can rotate between passes (the #2129-era "Kid
    Mac" is gone; Prima's MAC changed) — always pull `GET /api/devices`
    fresh each run rather than reusing a MAC list from a prior evidence doc.
- **2026-08-14 (#2699)** — A #1705-era "watch-item" deferral can graduate
  years later on a plain re-check, not just a byte-growth trigger: `poki.com`
  was explicitly deferred in the original `poki.yml` comment as "marketing
  surface only... add it later if traffic shows otherwise" — this pass's 30d
  window showed `games.poki.com`/`poki-auth.poki.com`/`devs-api.poki.com`
  (real navigational + auth traffic), so it graduated along with two branded
  Poki CDN/GDN domains (`poki-cdn.com`, `poki-gdn.com`). **Grep every existing
  template's inline comments for "add it later" / "watch" / "if traffic
  shows" language before starting the traffic sweep** — those are standing
  TODOs the same as a skipped apex in a prior evidence doc.
- **2026-08-14 (#2699)** — Multiplayer/relay support infra for an
  already-templated browser game is a real host-set gap, not noise: three
  near-identical-byte-pattern apexes (`lax1dude.net`, `deev.is`,
  `shhnowisnottheti.me`, each ~69KB+23KB or similar paired hits) turned out to
  be Eaglercraft's WebSocket LAN-world relay servers (web-confirmed —
  `lax1dude` is the game's creator; the client tries its relay list in order,
  producing the matched-pattern byte pairs). Treat a cluster of thin apexes
  with matching byte/hit shapes as one signal, not N separate below-bar
  apexes — and route relay/infra hosts like this into the app's `hosts:` (not
  `games.yml`), same precedent as the existing account-scoped `workers.dev`
  entry: support infra for an app you already time-limit, not a new
  game-hosting mirror.
- **2026-08-14 (#2699)** — A communications/utility app (not games/social/
  entertainment) can still clear the app-catalog bar: `zoom.us` showed 213 MB
  / 95 hits on one kid device across `cdn.zoom.us` + `us.telemetry.zoom.us`
  only — no bare `zoom.us` or dated meeting-join subdomain. Byte volume
  consistent with real video-call media plus a non-trivial, non-single-burst
  hit count was read as genuine engagement (same "recurring shape over raw
  volume" bar as `serato`/#2331) even without a page-load host to point to.
  Don't require a literal "the kid navigated to this URL" host before
  templating — CDN + telemetry alone can be the right signal for an app whose
  UI is a native/embedded client, not a browser tab.
- **2026-08-14 (#2699)** — Host attribution for a shared-Google-content apex
  isn't just about GFE anycast IPs (the #1307/#1636 class): `ggpht.com`
  (`yt3.ggpht.com`, YouTube avatar CDN) was held out of `youtube.yml` even
  though the observed subdomain is YouTube-specific, because the bare
  `ggpht.com` apex is also used for Blogger image hosting and legacy Google
  Photos — the collateral risk is at the **apex** even when the **observed
  subdomain** looks brand-specific. When the README's Class 1 check passes at
  the subdomain level, still ask whether the apex itself is a shared Google
  content-hosting pool before adding it.
- **2026-08-14 (#2699)** — Local `mill api.test.testOnly` validation can be
  blocked by host contention severe enough (load avg 300+, seen this run)
  that a single-file compile takes an hour+ with near-zero CPU share. When
  this happens: kill the stuck run, re-verify the YAML/Scala changes by hand
  (no tabs, 2-space list indent matching sibling files, balanced quotes,
  `_index.yml` slug present, pinned test's `expected` set updated), and open
  the PR anyway rather than blocking indefinitely — CI runs on isolated
  runners and is the real gate. Note the skipped local validation explicitly
  in the PR description so the reviewer knows to lean on CI. (Also: if you
  background a `mill` invocation with a literal `cd <repo-root> && mill ...`
  inside a worktree session, double check the `cd` target is the **worktree**
  path, not the main checkout — the main checkout has none of your changes
  and the test would silently validate nothing.)
- **2026-08-03 (#2596)** — A "watch-item" flagged in a prior pass's evidence
  doc can graduate into a real gap: #2331 noted `eaglercraft.ru` at 1 hit/40
  bytes as "extend the app if it grows" — this pass it was 17.5 MB/10 hits, a
  clean confirmation. Similarly `youtubekids.com` went from "below engagement
  bar" (14.8 KB/1 hit, #2331) to a genuine multi-device pattern (16.6 MB/167
  hits across 3 of 5 kid devices) and was added to `youtube.yml`. **Always
  re-check prior passes' skipped/watch-item apexes against this run's
  traffic** — don't just scan for brand-new apexes; a below-bar apex from a
  past pass is a standing TODO, not a closed question.
- **2026-08-03 (#2596)** — `student.freckle.com` (Freckle by Renaissance, K-8
  math/ELA practice) surfaced at only 2.9 MB/7 hits — thin by byte volume, but
  the subdomain spread (`api`/`images`/`student`/`translations`/`tts`/
  `tts-assets`/`vendor-assets`) is what made it a genuine app rather than a
  beacon: a real page load pulls a dashboard + API + localized TTS audio +
  vendor assets, which a stray tracking pixel never does. When hit count is
  low, check subdomain *diversity/shape* as a substitute engagement signal.
- **2026-08-03 (#2596)** — A brand can have more than one adjacent apex where
  only one clears the bar: `freckle.com` templated, but sibling
  `renaissance.com` (parent company, 1 hit) and unrelated `readingeggspress.com`
  (different vendor, 2 hits, zero resolved subdomains) stayed watch-items. Don't
  fold a thin sibling apex into a new app's host-set just because it's
  topically adjacent — require its own evidence.
- **2026-07-27 (#2490)** — Another clean no-op: no new app, no host-set gap
  (LEGO's `thelegogroup.com` corporate-analytics exclusion reconfirmed).
  Clarified a latent trap in the gap-check method itself: a naive per-apex
  match against `_index.yml` host lists reports `apple.com` and `google.com`
  as "covered" because the aggregate apex traffic includes the templated
  sliver hosts (`ess.apple.com` for `imessage`, `play.google.com` for
  `google-play`) — but the vast majority of those multi-GB apexes is
  unrelated shared-platform traffic (App Store, Search, Drive, iCloud sync),
  not actionable. Don't read a large-byte apex matching an app slug as "fully
  covered" — check which specific host inside the app's template actually
  produced the match.
- **2026-07-27 (#2490)** — `apple.news` (only `c.apple.news`, a single
  content-delivery edge) is the same shape as `ess.apple.com`: one edge host
  for an OS-bundled Apple app, no independent branded surface. Treated as
  skip-with-note rather than templated, since iMessage's template was driven
  by an explicit operator ask (#1529), not just traffic presence — don't
  auto-template every single-edge Apple service that shows up.
- **2026-07-27 (#2490)** — `genius.com` traffic was 100% `assets`/`t2`/
  `librato-collector` (analytics/tracking subdomains), zero navigational
  lyrics-page hits — a reminder that a brand-name apex with real bytes can
  still be pure SDK/analytics collateral; check subdomain shape before
  assuming a page-view.
- **2026-07-20 (#2331)** — A mature catalog can still find a genuine new app in
  a low-byte, high-consistency cluster: `serato.com` (DJ software) was only
  8.9 MB / 106 hits, far below the multi-hundred-MB clusters that usually
  trigger a new template, but the hit *pattern* (spread across license checks,
  rewards, notifications over the full 30d window — not a single burst) is what
  made it a real app rather than a one-off page load. Byte volume alone isn't
  the bar; recurring engagement shape matters as much for small clusters.
- **2026-07-20 (#2331)** — A brand's own short-link/CDN domain (`sera.to` for
  Serato) belongs in the app's host-set alongside the main apex when it's
  confirmed first-party (web search showed `a.cdn.sera.to` serving the vendor's
  own downloads directly) — same reasoning as `plex.direct` and the
  account-scoped `eaglercraft-99f.workers.dev` entry: a brand-dedicated
  subdomain of a shared platform is fine to include; the shared platform's
  bare/generic apex is not.
- **2026-07-20 (#2331)** — Existing-app host-set gaps keep recurring even on
  mature apps: `1password.yml` was missing `agilebits.com` (1Password's legacy
  company-name domain, serves the extension's favicon/icon cache) and
  `1passwordusercontent.com` (encrypted vault attachments) despite being
  present on every kid device. When gap-checking a top-byte apex that maps to
  an existing app, also check whether *sibling* apexes tied to the same vendor
  (old company names, `usercontent`/`cache`/`cdn` variants) are traffic-visible
  but unlisted — don't stop at confirming the primary apex is covered.
- **2026-07-08 (#2129)** — A pass can be a clean no-op: not only ZERO new apps
  but ZERO host-set gaps either (unlike #2058, which found an existing-app
  mirror gap). Every apex with real kid traffic mapped to an existing app or
  `games.yml`, and the coverage sweep against per-app host-sets found nothing
  missing. When the traffic surfaces no cluster AND no gap, that IS the outcome
  — file the pass issue, ship the evidence doc + this Step-6 entry as a tiny
  skill-only PR, and don't force a marginal app.
- **2026-07-08 (#2129)** — `unity3d.com` is a trap: it surfaced at 1.6 MB but
  every observed subdomain was `*.mediation.unity3d.com` / `*-adq.` / `*.isx.` —
  Unity **Ads** RTB/mediation, not the game engine. Reinforces the #1922
  "classify by subdomain shape, not apex name" rule: `mediation`/`adq`/`isx`/
  `sync`/`prebid`/`rtb`/`exchange` = ad-tech, skip.
- **2026-07-08 (#2129)** — `media.tenor.com` (Google's embedded-GIF CDN,
  giphy-adjacent) and `api.elevenlabs.io` are collateral, not apps: a single
  `media.`/`api.`/CDN host with no branded web surface the kid navigates to is
  skip-with-note even if giphy/an-app exists for the same *category*. Tenor is a
  watch-item — template it alongside `giphy` only if real navigational traffic
  (not just embedded media) appears. Also: music-gear retail (`sweetwater.com`,
  `reverb.com`) is adult shopping, never a kid app.
- **2026-07-08 (#2129)** — LEGO sub-experience scoping (#1815) held up under a
  fresh 1 GB / 1089-hit sample: all observed building subdomains
  (`api.prod.{cobuild,dbix}.i.lego.com`, `*.services.lego.com`,
  `buggy.apps.lego.com`) suffix-match the 4 scoped entries; the uncovered
  `www`/`avatar`/`identity`/`consent`/`analytics` hosts are the correctly-
  excluded shop side. A scoped app needs no extension just because new
  subdomains appear — check whether they fall under an existing entry first.
- **2026-06-29 (#2058)** — A mature catalog means the typical pass yields ZERO
  new apps: every >1 MB uncovered apex was shared infra/CDN, ad-tech/RTB,
  shared corporate (Adobe/Autodesk), or analytics/support. The valuable finding
  was an **existing**-app host-set gap — a *mirror domain* of an already-templated
  game (`eaglercraft.com` vs the templated `eaglercraft.dev`) the kids hit but
  that was unattributed. Always run the coverage sweep against `_index.yml` slugs
  AND the per-app host-sets, not just slugs: a covered slug can still be missing
  observed brand domains. Extending an app's host-set is a fully valid pass outcome.
- **2026-06-29 (#2058)** — Distinguish a "game host" from an "unblocked-games
  proxy portal" before routing. A single-game mirror that serves the game
  directly (eaglercraft.com/play) extends the app; only multi-proxy / filter-bypass
  hubs (TitaniumNetwork stack, now.gg, duckmath) go to `games.yml`-only. When a
  brand the operator already chose to template as an app (#1705) shows a new mirror
  apex, keep it in the app for consistency — don't reclassify it to a blocklist.
- **2026-06-29 (#2058)** — YouTube-to-MP3 rippers (`ytmp3.gg`) surface as
  high-byte / very-low-hit (3.5 MB, 1 hit = one download). No clean piracy
  category list exists; treat as below-bar incidental and skip-with-note rather
  than forcing it into games/ads.
- **2026-06-23 (#1922)** — #1796 IPv6-attribution gap is FIXED (#1807/#1802
  merged); sample is no longer IPv4-biased. Step 0 caveat updated. A site #1815
  skipped as "below bar / IPv4-biased" (mathplayground.com, 181 kB → now 894 kB
  visible) became a legit app this pass — re-evaluate prior-skipped educational
  apexes now that v6 lands.
- **2026-06-23 (#1922)** — Watch for "game"-NAMED ad-tech: `games-to-run123.com`
  (`trk2-assets.`), `geektalesgames.com` (`tracker.`), `grandgamestech.com`
  (`api.` RTB) are tracker/RTB endpoints, NOT game sites — skip. Classify by the
  subdomain shape (trk/tracker/sync/prebid/rtb/exchange = ad-tech), not the
  apex's name.
- **2026-06-23 (#1922)** — A vendor-API backend hit ONLY at its `api.` host with
  no branded web app the kid navigates to (e.g. `api.elevenlabs.io` at ~50 MB) is
  shared-API collateral — skip, don't template. Bytes alone don't make an app.
- **2026-06-23 (#1922)** — "Unblocked games" framing extends past proxy stacks to
  single-game hosts: `emolingo.games` (Rainbow Obby) markets "works in restrictive
  school networks" and serves rotating numbered subdomains (`rainbowobbyN.`) — the
  evasion pattern. → `games.yml` apex block (suffix-matches all N), NOT an app.
- **2026-06-21 (#1815)** — Host entries can be subdomains; enforcement AND
  attribution both suffix-match the entry's own subtree (`HostMatch.matchesApex`),
  so you can scope an app to a sub-experience (LEGO Builder → `*.i.lego.com` +
  `services`/`apps.lego.com`) and exclude the brand's shop. Verified verbatim
  host flow through `Hostname.parse` → `ProfileAppDispositions` → `render.lua`
  `nftset=/<host>/`.
- **2026-06-21 (#1815)** — `recent-apexes?windowDays=&limit=` is the right traffic
  source: pre-grouped by apex with a `subdomains[]` list. Aggregate across kid
  devices by bytes with the awk one-liner in Step 0.
- **2026-06-21 (#1815)** — "Unblocked games" / filter-bypass / web-proxy portals
  (duckmath.org, holyunblocker.org, invisiproxy.com, titaniumnetwork.org, now.gg)
  go in `blocklists/games.yml` ONLY — they are block targets, NOT apps. Don't
  give a filter-evasion tool a daily time budget (operator decision). Harvest the
  proxy ecosystem a portal embeds (TitaniumNetwork: Holy Unblocker, InvisiProxy,
  Rammerhead, Ultraviolet/Scramjet) into the same list.
- **2026-06-21 (#1815)** — Prod v6 host attribution is broken (#1796); the byte
  sample is IPv4-biased. Always web-cross-check candidates; never infer "no
  traffic" from a quiet apex.
