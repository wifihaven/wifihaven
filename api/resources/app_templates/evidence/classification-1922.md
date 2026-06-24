# #1922 — traffic-driven app-catalog pass (kid devices, last 30 days)

Source: read-only prod cloud API (`https://api.wifihaven.net`),
`GET /api/devices/:mac/recent-apexes?windowDays=30&limit=500` for the four kid
devices, aggregated by apex bytes:

- Octavius iPad `a6:05:9a:63:83:af` (profile 7)
- Prima iPad `04:72:ef:d6:e4:5a` (profile 6)
- Quintus iPad `26:74:fc:f9:4e:9e` (profile 5)
- Kid Mac `ca:ef:a1:72:6a:a3` (profile 1)

> **CAVEAT NOW LIFTED.** Prior passes warned the sample was IPv4-biased because
> IPv6 host attribution was broken on prod (#1796). As of this run #1796 is
> **closed** and its fixes **#1807** (record v6 events via NDP neighbor) and
> **#1802** (account v6 traffic in per-device usage) are **merged**. The byte
> sample is no longer IPv4-biased. Candidates were still web-cross-checked.

## Top uncovered apexes (in "Other"), and disposition

| apex | kid bytes / hits | disposition |
| ---- | ---------------- | ----------- |
| `mathplayground.com` | 894 kB / 6 (subs `www.`, `tools.`) | **NEW app `math-playground`** — COPPA-compliant K-6 educational math-games site, school-used. (#1815 skipped this as "below bar / IPv4-biased" at 181 kB; now visible post-#1796 fix and above bar.) |
| `dancemattypingguide.com` | 1.3 MB / 13 (sub `www.`) | **NEW app `dance-mat-typing`** — kid touch-typing tutor hosting the BBC Dance Mat Typing course. Genuine educational, kid-used. |
| `emolingo.games` | 758 kB / 8 (subs `rainbowobby4/12.`) | **`blocklists/games.yml` only** — "Rainbow Obby" browser-game host marketed as *unblocked / works in restrictive school networks*; rotating numbered subdomains are the unblocked-games evasion pattern. A block target, not a time-budget app (operator rule, #1815). Apex suffix-matches all `rainbowobbyN.*`. |
| `lego.com` (596 MB), `kastatic.org`/`khanacademy.org` (674 MB), `mathacademy.com` (123 MB), `tinkercad.com`, `duolingo.com`, `gimkitconnect.com` (15 MB), `thingiverse.com`, `crazygames.com`, `poki.io`, `giphy.com`, `eaglercraft.*`, `mocpilot.com` | large | already covered by existing apps |
| `duckmath.org` (65 MB), `now.gg`, `holyunblocker.org` | large | already in `games.yml` (#1815) |
| `apple.com`/`icloud-content.com`/`cdn-apple.com`/`apple-dns.net`/`aaplimg.com`, `google.com`/`gstatic.com`/`googleapis.com`/`gvt2.com`, `fastly.net`/`akadns.net`/`cloudfront.net`/`ctfassets.net` | very large | infra / Apple+Google service & CDN pools — not templatable (shared) |
| `flashtalking.com`, `innovid.com`, `adsrvr.org`, `pubmatic.com`, `casalemedia.com`, `cootlogix.com`, `yellowblue.io`, `ingage.tech`, `media.net`, `sharethrough.com`, `3lift.com`, `adnxs.com`, … | large | ad / RTB networks — noise, skip |
| `games-to-run123.com`, `geektalesgames.com`, `grandgamestech.com` | small | "game"-named **ad-tech trackers** (`trk2-assets.`, `tracker.`, `api.` RTB endpoints), NOT game sites — skip |
| `elevenlabs.io` (`api.` only, ~50 MB), `adobe.com`/`adobe.io`, `autodesk.com`, `launchdarkly.com`, `unity3d.com`, `stripe.com`, `sentry.io` | medium-large | shared vendor API backends / corporate infra (no branded kid-facing web app) — skip per `_README.yml` shared-pool guidance |

## Host-set coverage check

- `math-playground` → `mathplayground.com`. `HostMatch.matchesApex` suffix-matches
  `www.mathplayground.com` and `tools.mathplayground.com` (the observed subs).
  Tight; no shared-CDN collateral pinned.
- `dance-mat-typing` → `dancemattypingguide.com`. Covers observed
  `www.dancemattypingguide.com`. Single dedicated domain, no collateral.
- `emolingo.games` (games.yml) → apex covers `rainbowobby4.emolingo.games`,
  `rainbowobby12.emolingo.games`, and any future numbered subdomain.

Both apps are educational (not games), so neither is also added to `games.yml`.
