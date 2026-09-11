# `ai` blocklist — preemptive hardening (#2768)

**Date:** 2026-09-10
**Trigger:** operator report — the `ai` category is enabled for the kids'
profiles, but the kids are still using AI.
**Scope:** the five devices on the household's child profiles, 30-day window.

Unlike every prior pass in this directory, the headline finding here is a
**negative** one: the curated list was not leaking. The additions that follow
are therefore explicitly *preemptive*, and the operator's actual problem is
**not** solvable in this file at all. Both are recorded below so a later pass
does not mistake this for a routine traffic-driven pass.

> **Device naming.** Devices are referred to by role (`kid laptop`,
> `kid mac`, `kid ipad 1..3`) rather than by their configured names, and MAC
> addresses are omitted. This repo is public, and the devices belong to minors;
> nothing in the analysis below depends on which physical device is which.
> Follow this convention in future evidence docs.

---

## 1. Evidence — the list never fired

Source: prod `https://api.wifihaven.net`, READ-ONLY. `/api/logs` (~80k
connection events, 2026-08-25 → 2026-09-11) and
`/api/devices/<mac>/recent-apexes?windowDays=30` (549 distinct apexes) across
all five child-profile devices.

| Check | Result |
|---|---|
| `category/ai` drop events | **0** |
| Hostnames of any dedicated AI service (blocked *or* allowed) | **0** |
| Curated-`ai` apexes present in 30d traffic | 1 — `deepai.org` |

Observed block reasons, for contrast: `schedule` 7326, `category/ads` 221,
`unmanaged` 42, `category/social-extended` 12, `category/social-media` 10.

### `.ai`-TLD apexes seen, and their disposition

| Apex | Bytes / hits | Verdict |
|---|---|---|
| `deepai.org` | 1.16 MB / 12 | Already curated (#2759). Blocking since 2026-09-08. |
| `flux.ai` | 13.5 MB / 22 | **Not AI.** PCB-design CAD. Standing exclusion re-confirmed — corroborated this pass by the same device's `easyeda.com`, `jlcpcb.com`, `pcb-hero.com`, `arduino.cc` traffic. |
| `trygravity.ai` | 0.10 MB / 11 | Ad-tech. The documented `.ai`-TLD trap (#2759). |
| `koah.ai` | 0.07 MB / 8 | Ad network for AI apps — ads, not a consumer AI tool. |

### Configuration verified correct (so misconfiguration is ruled out)

- The child profile → `blockedCategories` includes `ai`; `paused: false`.
- `household_settings.blockEncryptedDns: true` — DoH bypass closed.
- `/api/household/enforcement` → `enforcementDisabled: false`.
- Prod `ai` list = 50 hosts = repo `ai.yml` at the time of the sweep. In sync.
- No app assigned to the child profile carves out an AI host via `extraAllowed`.

**Conclusion: adding hosts does not explain the operator's report.**

---

## 2. Where the AI use is actually reachable from

The kids are on **Google Family Link supervised accounts**
(`kidsmanagement-pa.googleapis.com`, 121 hits across two child devices).

`gemini.google.com` is curated and shows **zero lookups** — the direct app is
not being used on-network. Gemini is, however, embedded in allowed surfaces:

| Host | Allowed hits | Embedded AI |
|---|---|---|
| `www.google.com` | 58 | AI Overviews / AI Mode |
| `docs.google.com` | 45 | Gemini side panel, "Help me write" |
| `drive.google.com` | 17 | Gemini summarise |
| `mail.google.com` | 9 | Gemini in Gmail |

Blocking any of these at the connection layer would take out Search, Docs,
Drive and Gmail wholesale. Per the `ai.yml` header's host-scoping rule, the
shared vendor apex is never the block target — and here the *product* and the
*shared surface* are the same hostname, so there is no product subdomain to
scope to. **This is a structural limit of hostname enforcement, not a gap in
the list.** It is also why this pass REMOVED `notebooklm.google.com` and
`labs.google` after review: both front on the same shared GFE pool. Resolved
from three vantage points, `notebooklm.google.com` returned three different
/24s, one of them Drive's exact six-address set (two are recorded in the #2772
review thread; the third, `209.85.145.x`, is the author's own unrecorded
observation) — so
`bl_ai` risks dropping Drive/Docs for a child MAC (#2601). Google's own
AI surfaces are a product decision tracked in **#2605**.

### Account-side controls, and what each does not cover

Sourced rather than asserted, per [verify-and-cite](../../../../AGENTS.md#verify-and-cite).
Re-check before relying on these; vendor controls move.

- **Gemini Apps** — `familylink.google.com` → child → Controls → Gemini →
  Gemini Apps. The menu path is confirmed verbatim by the source below, as is
  "You can change your child's access to Gemini Apps at any time." The same
  page gives a SECOND verbatim path for older Family Link versions —
  `Controls → Content restrictions → Gemini → Gemini Apps` — which an operator
  on an older app will need instead.
  **What the source does NOT say:** an earlier draft added that turning it off
  "blocks sign-in to the Gemini app and Gemini on the web, account-wide (so it
  also covers off-network use)." Neither cited page describes the off-state's
  scope. That clause is withdrawn as unverified. It is the operationally
  load-bearing half of this bullet — it is the reason to expect account-side
  control to reach off-network use at all — so do not rely on it until sourced.
  **Correction:** an earlier draft of this doc said Gemini Apps is "on by
  default for eligible supervised accounts." The cited source contradicts that
  for the under-13 band — "A parent must enable access before their child under
  13 (or the applicable age in your country) can use Gemini Apps with a
  supervised account" — i.e. default-OFF there. The default for supervised
  **teens** is not established by this source; treat it as unverified rather
  than assuming either way. The original claim came from secondary blog
  coverage, not the vendor page, which is exactly the failure
  [verify-and-cite](../../../../AGENTS.md#verify-and-cite) exists to prevent.
  [Google For Families help](https://support.google.com/families/answer/16109150?hl=en)
  (the `support.google.com/gemini/answer/16109150` URL is the same article in a
  second help centre, not an independent second source).
- **Gemini in Docs/Gmail** ("Help me write", "Refine") — NOT covered by the
  Gemini Apps toggle. For consumer accounts this rides the Gmail-settings
  "Google Workspace smart features" switch.
  [xFanatical writeup](https://xfanatical.com/blog/guide-to-turn-off-gemini-from-google-workspace/).
- **AI Overviews / AI Mode in Search** — no per-account opt-out reported as of
  2026-04. Residual; the only lever is changing the default search engine.
  [Kinzoo parent guide](https://www.kinzoo.com/blog/a-parents-guide-to-google-gemini-for-kids-everything-you-need-to-know).

**Operator follow-up (2026-09-11): Gemini Apps was already disabled**, so it is
not the explanation — consistent with the default-OFF the source describes.
That leaves the Workspace smart-features surface, AI
Overviews in Search, Apple Intelligence / Siri (`guzzoni.apple.com`,
`api.smoot.apple.com` both appear in the sweep), and off-network use. Tracked in
#2768, not resolved by this PR.

---

## 3. Additions — preemptive, not observed

97 hosts added, 50 → 147. Rationale for departing from the traffic-driven
default: `ai` has no upstream sibling feed (unlike `ads`/`adult`/`social-media`),
so this file is the category's only coverage. A list that only ever adds what a
kid has already reached means the first visit to every new service succeeds.

Selection bias was toward the **free / no-login** tier, since those are the
drop-in substitutes for an already-blocked vendor. Groups: multi-model access
(11), major assistants (9), homework + "AI humanizer" (14), companion /
roleplay (14), image + video generation (28), audio / voice / music (12),
coding assistants (9).

### Verification, stated precisely

Every shipped host returned an A or AAAA record **at the apex** on re-check and
was identity-checked. The first cut of this pass claimed "all DNS-verified live"
on the strength of a check that also passed on NS/SOA-only responses; the
re-check dropped two candidates that claim had wrongly cleared:

| Dropped | Why |
|---|---|
| `play.ht` | No apex A or AAAA. Zone served by `a-d.ns.facebook.com` (Meta). |
| `figgs.ai` | Parked on `0.0.0.0`; service defunct. |

Identity spot-checks where the name alone is not proof: `duck.ai` = DuckDuckGo's
free anonymous multi-model chat; `z.ai` = Zhipu AI's GLM chat; `t3.chat` =
multi-model chat front-end; `perchance.org` = confirmed "free, no sign-up, no
limits, unrestricted" text-to-image.

### Shared-IP collateral — accepted, not overlooked

No entry is a CDN/cloud **apex**, but that is a hostname-level property and not
the same as "no shared-IP collateral": enforcement matches on the resolved IP.
Largest clusters among the new hosts at authoring time:

| Address | Hosts | Frontend |
|---|---|---|
| `76.76.21.21` | 12 | Vercel shared anycast |
| `216.150.1.1` | 5 | Vercel (`VERCEL-09`, per whois) |
| `198.202.211.1` | 5 | Webflow (per whois) |

Resolving any one host in a cluster puts that address in `bl_ai`, dropping other
tenants of the same frontend for that MAC. Accepted, with the claim scoped to
the **50-host baseline** — not to `main`, which now includes this pass and so
trivially carries all of these. That baseline already carried four hosts on
`76.76.21.21` (`pplx.ai`, `runwayml.com`, `udio.com`, `delphi.ai`) and two on
`198.202.211.1` (`jasper.ai`, `copy.ai`), so for those two addresses the
exposure is unchanged in kind. **`216.150.1.1` is newly reached** — the
baseline's nearest was `lumalabs.ai` on `216.150.1.129`, which whois places in
the *same* `216.150.1.0/24` (`VERCEL-09`), so it is a neighbouring address in a
block already touched rather than a new frontend.

On the proportion. The method is stated below, but it is **not** exactly
reproducible and the file should not pretend otherwise: it keys on exact A
records, and CDN edge answers rotate per query and per vantage, so the count
over CDN-fronted names is a snapshot. Measured 2026-09-10 from a single
resolver; a re-measure one round later moved it by two hosts (`monica.im` and
`pixai.art` landing on a shared CloudFront edge). Expect a couple either way.
The method: counting a host as shared when at least one
of its A records is also served for a **different host in the same list** — each
half counted against its own version of the file, baseline against the 50 and
added against the 147 — it is **13/50** before and **29/97** added. Counting
instead by membership of published CDN anycast ranges (Cloudflare + Fastly + the
three cluster addresses in the table above) gives **30/50** before and
**66/97** added. Widening the range set (more Vercel/Google/CloudFront blocks)
gives **36/50 → 70/97** — 72% → 72%, +0.2pt, the reading least favourable to
the "goes up" framing, given here rather than gestured at.

**The direction, stated honestly:** the shared proportion goes *up* slightly —
26.0% → 29.9% (+3.9pt) by co-tenancy, 60% → 68% (+8.0pt) by narrow ranges. An
earlier draft of this section said "flat" and "did not worsen" — Δ = 0 and
Δ ≤ 0 respectively, both asserting a *non-increase*; no method produces one. The defensible claim is that it is unchanged
in **order**, not that it improved. Note also that both figures in a pair must
travel together — an earlier draft gave the range-method numerator with no
baseline, leaving its own conclusion uncheckable. See #2369 for this class.

### Held out as dual-use

`grammarly.com` (school-mandated), `quizlet.com` (flashcards dominate),
`replit.com` (general IDE), `capcut.com` (general editor), `speechify.com`
(**an IEP/dyslexia accommodation**, not a toy), `bing.com` (general search
engine — same shared-surface reasoning as `google.com`), `freepik.com` (stock
library first), `flux.ai` (PCB CAD — see §1).

The coding-assistant group is flagged in-file as the lowest-confidence tier and
the first to drop if a narrower list is wanted: the household's kid device shows
heavy Arduino/PCB traffic, so those tools have a plausible legitimate use.
