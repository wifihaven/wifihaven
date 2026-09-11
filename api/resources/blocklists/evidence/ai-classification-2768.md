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
`labs.google` after review: both front on the same shared GFE pool, so putting
them in `bl_ai` risks dropping Drive/Docs for a child MAC (#2601). Google's own
AI surfaces are a product decision tracked in **#2605**.

### Account-side controls, and what each does not cover

Sourced rather than asserted, per [verify-and-cite](../../../../AGENTS.md#verify-and-cite).
Re-check before relying on these; vendor controls move.

- **Gemini Apps** — `familylink.google.com` → child → Controls → Gemini →
  Gemini Apps. On by default for eligible supervised accounts; turning it off
  blocks sign-in to the Gemini app and Gemini on the web, account-wide (so it
  also covers off-network use).
  [Google For Families help](https://support.google.com/families/answer/16109150?hl=en),
  [Gemini Apps help](https://support.google.com/gemini/answer/16109150?hl=en-SG).
- **Gemini in Docs/Gmail** ("Help me write", "Refine") — NOT covered by the
  Gemini Apps toggle. For consumer accounts this rides the Gmail-settings
  "Google Workspace smart features" switch.
  [xFanatical writeup](https://xfanatical.com/blog/guide-to-turn-off-gemini-from-google-workspace/).
- **AI Overviews / AI Mode in Search** — no per-account opt-out reported as of
  2026-04. Residual; the only lever is changing the default search engine.
  [Kinzoo parent guide](https://www.kinzoo.com/blog/a-parents-guide-to-google-gemini-for-kids-everything-you-need-to-know).

**Operator follow-up (2026-09-11): Gemini Apps was already disabled**, so it is
not the explanation. That leaves the Workspace smart-features surface, AI
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
| `216.150.1.1` | 5 | shared |
| `198.202.211.1` | 5 | shared |

Resolving any one host in a cluster puts that address in `bl_ai`, dropping other
tenants of the same frontend for that MAC. Accepted because `main` already
carries four hosts on `76.76.21.21` (`pplx.ai`, `runwayml.com`, `udio.com`,
`delphi.ai`) — the exposure is unchanged in kind, and the proportion is flat
(~26/50 before, ~56/99 after). See #2369 for this class.

### Held out as dual-use

`grammarly.com` (school-mandated), `quizlet.com` (flashcards dominate),
`replit.com` (general IDE), `capcut.com` (general editor), `speechify.com`
(**an IEP/dyslexia accommodation**, not a toy), `bing.com` (general search
engine — same shared-surface reasoning as `google.com`), `freepik.com` (stock
library first), `flux.ai` (PCB CAD — see §1).

The coding-assistant group is flagged in-file as the lowest-confidence tier and
the first to drop if a narrower list is wanted: the household's kid device shows
heavy Arduino/PCB traffic, so those tools have a plausible legitimate use.
