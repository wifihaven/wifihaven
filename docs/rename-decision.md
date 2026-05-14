# Project rename: `familydns` → `wifihaven` (pencilled in)

**Status:** Pencilled in pending family consultation. Not executed yet — execution
is tracked in the META tracker issue (filed alongside this doc) and the per-slice
rename issues it links to.

**Closes (when execution lands):** #38

## Decision

Rename the project from `familydns` to **`wifihaven`**.

- Package / image / config namespace: `wifihaven` (lowercase).
- Scala package root: `wifihaven.*`.
- OpenWRT package name: `wifihaven`.
- Env-var prefix: `WIFIHAVEN_*`.
- Docker image: `ghcr.io/sameerparekh/wifihaven-api`.
- GitHub repo (eventually): `sameerparekh/wifihaven`.

## Why rename at all

The current name `familydns` is wrong on two axes:

1. **It implies DNS-based blocking, which contradicts how the product works.**
   Per the canonical architecture statement (#350): we do not block by failing
   DNS resolution. DNS resolves normally; per-domain blocking happens at the
   connection level via nftables on the forwarded path. The name `familydns`
   tells every reader — humans and AI agents alike — to reach for DNS-based
   mechanisms that do not apply.

2. **`dns` is too narrow and technically misleading for the product's scope.**
   The product is per-device internet policy on a home router (parental controls,
   focus / screen-time tooling, schedules, time limits). DNS is one input among
   many, and not the enforcement plane.

The rename has to happen *before* we have meaningful external user adoption.
Once people have installed agents whose cron auto-update path embeds the old
repo URL and the old package name, renaming becomes dramatically harder.

## Why `wifihaven`

- **"Haven" frames the household as a safe place** — protected from, not at war
  with, the outside internet. That matches the product's posture: gentle
  guardianship rather than militant filtering. It echoes the protective-presence
  theme (a refuge for the family) without leaning on religious or martial
  imagery.
- **"Wifi" is the household network from the user's point of view.** Parents
  setting this up don't think about nftables or forwarded packets; they think
  about *the wifi*. Pairing "wifi" with "haven" puts the product in the
  vocabulary the audience already uses.
- **The compound is distinctive and brandable.** "haven" alone is everywhere;
  "wifihaven" is rare. That matters both for namespace availability and for
  later trademark posture.

## Availability sweep (informal)

| Namespace | wifihaven | Notes |
|---|---|---|
| npm | free | |
| PyPI | free | |
| GitHub user `wifihaven` | free | |
| GitHub org `wifihaven` | free | |
| `wifihaven.com` | registered (parked) | not active brand |
| `wifihaven.io` | free | |
| `wifihaven.net` | free | |
| `wifihaven.app` | unknown — Google registry whois opaque; check via registrar |

No obvious large incumbent brand surfaced in a casual search. Not a legal
trademark clearance — that's a separate exercise before any public launch.

## Alternates considered

In rough order of how close they came:

- **nethaven** — strongest *conceptual* fit (haven on the network = the
  household's network is a refuge). Rejected because (a) a US IT-services
  firm already trades under "NetHaven" with the .com, raising brand-collision
  risk, and (b) the GitHub user namespace is squatted.
- **homehaven** — warm and on-message, but every namespace that matters is
  taken (com, io, net, GitHub user, GitHub org) and several unrelated
  furniture / real-estate businesses use the name.
- **haven** alone — too generic, heavily contested namespace, undifferentiated
  as a product mark.
- **cloister** — best conceptual bullseye (devices "inside the cloister,"
  internet outside). Rejected for negative connotations (monastic isolation,
  feels punitive for a family product).
- **sanctum, refugium, fold, nave, hearth, aerie** — explored as "home as
  refuge" candidates. Each has merits but read more religious / more obscure
  than the audience wants.
- **cherub, seraph, angelus, custos, tutela, mantle, lumen** — explored as
  the guardian-angel direction. Strong imagery, but too explicitly devotional
  for a product whose users are not all Catholic / not all religious.
- **aegis, bastion, citadel, praesidium, michael, gabriel** — rejected
  outright: shield-and-fortress / archangel-warrior framing is wrong for the
  product's gentle-protection posture.

## What this document does *not* do

- It does not execute the rename. See the META tracker issue and per-slice
  rename issues for the actual landing plan.
- It does not commit to trademark registration, marketing copy, or a public
  rollout — those happen after the in-tree rename has stabilized.
- It does not lock the name in stone before the family consultation. If
  `wifihaven` is rejected during that consultation, this document gets
  updated with the chosen alternate and the rename issues retitled.
