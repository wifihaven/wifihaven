# Phase-3 classification of prod top apexes (last 14 days, kid devices)

Source queries: `evidence-1705-kids-by-bytes.txt`, `evidence-1705-kids-by-active.txt`,
`evidence-1705-subdomains.txt` (read-only prod SELECTs with `statement_timeout`,
2026-06-14). Kid MAC list mirrors `scripts/analysis/fetch_prod_data.sh`.

## Already TEMPLATED — gap-checked

| apex                  | kid bytes / active-min  | template       | gap? |
| --------------------- | ----------------------- | -------------- | ---- |
| khanacademy.org       | 264 MB / 29 min         | khan-academy   | none — kastatic.org (361 MB) + kasandbox.org (12 MB) both covered |
| kastatic.org          | 361 MB / 15 min         | khan-academy   | covered |
| mathacademy.com       | 76 MB / 218 min         | math-academy   | none — all traffic on apex / www subdomain (suffix match catches both) |
| youtube.com           | 34 MB / 24 min          | youtube        | none — kid use modest; existing 4-host set adequate |
| instagram.com         | 53 MB (broader, n/a kid)| instagram      | n/a — kid use negligible |
| gimkitconnect.com     | 2.9 MB / 20 min         | gimkit         | covered |

No host-set updates required on existing templates. Khan Academy & Math
Academy were the gap-risk surfaces and both attribute correctly.

## NEW templates proposed (7)

Kid-driven brand-specific apexes with real engaged minutes, not covered by an
existing template, with host-sets that contain **no shared-platform apexes**
per #1661.

| apex          | kid bytes / active-min | rationale                                             |
| ------------- | ---------------------- | ----------------------------------------------------- |
| tinkercad.com | 36 MB / 50 min         | Autodesk 3D modeling — kid uses heavily; all subdomains brand-specific (`editor.*`, `csg-prd.*`, `colab.*`) |
| duolingo.com  | 39 MB / 23 min         | language learning — all subdomains brand-specific (`simg-ssl.*`, `ios-api-cf.*`, `avatars.*`) |
| brave.com     | 46 MB / 26 min         | browser — all subdomains brand-specific (`redirector.*`, `collector.bsg.*`, `variations.*`) |
| plex.tv       | 9 MB / 49 min          | media — pair with plex.direct (403 MB) which is the streaming pool |
| plex.direct   | 403 MB / —             | (paired with plex.tv above) |
| crazygames.com| 1.5 MB / 52 min        | browser-game portal — all subdomains brand-specific (`cza.*`, `auth.*`, `sdk.*`) |
| poki.io       | 824 kB / 25 min        | browser-game portal — observed subdomains `t.poki.io`, `leveldata.poki.io`. poki.com (marketing surface) deliberately omitted — no kid traffic seen there |
| thingiverse.com| 40 MB / 4 min         | MakerBot 3D-model repository — all subdomains brand-specific (`img.*`, `resize.*`, `cdn.*`, `api.*`) |

## INFRA — should ride InfraHosts (#1672 family), NOT a template

| apex                | kid bytes / active-min | notes                                            |
| ------------------- | ---------------------- | ------------------------------------------------ |
| apple.com           | 434 MB / 1728 min      | iOS/macOS background (gdmf, configuration, sjc-courier, swdist, mzstatic, …) — operator infra concern, not an app |
| apple-dns.net       | 277 MB / 72 min        | Apple anycast DNS edge for iCloud/CloudKit |
| icloud-content.com  | 265 MB / 28 min        | iCloud asset blobs |
| icloud.com          | 15 MB / 114 min        | iCloud sync / push |
| apple-cloudkit.com  | 1.5 MB / 11 min        | iCloud Drive/CloudKit |
| aaplimg.com         | 8.6 MB / 77 min        | Apple imagery CDN |
| gvt2.com            | 8.8 MB / 216 min       | Google Chrome auto-update channel |
| gstatic.com         | 6.4 MB / 71 min        | Google static asset CDN — shared |
| akadns.net          | 19 MB / 181 min        | Akamai EdgeDNS — shared resolver tier |
| google.com          | 128 MB / 331 min       | Google search + bunch of subdomains; not a single user-facing app |
| safebrowsing.apple  | 2 MB / 12 min          | Apple Safe Browsing background |
| app-analytics-services.com | 5.5 MB / 8 min  | Apple device-analytics pipeline |
| launchdarkly.com    | 1.2 MB / 52 min        | feature-flag SaaS — runtime in multiple apps |
| cdn-apple.com       | 2.3 MB / 18 min        | Apple CDN |
| 1password.com       | 0.4 MB / 57 min        | mostly desktop-app background sync, not a kid-app concern (excluded; see Out-of-scope) |
| goguardian.com      | 0 on kid MACs          | school-issued laptop traffic, not on kid MACs in scope here |

These are candidates for the **InfraHosts** background-host suppression list
that the #1672 chain owns, not for app templates. Filing a follow-up to fold
the brand-new ones into that list rather than adding them in this PR.

## SHARED_PLATFORM — explicitly excluded per #1661

Any traffic to these apexes must be attributed via brand-specific subdomain
attribution (the SNI / DNS chain #1651 / #1652 / #1655) — NEVER added to a
template host-set:

| apex                | seen in kid traffic? |
| ------------------- | -------------------- |
| googleapis.com      | yes (2.8 MB / 60 min) — must NOT enter any template |
| fastly.net          | yes (182 MB / 26 min) — shared CDN |
| cloudfront.net      | yes (22 MB / 9 min) — shared CDN |
| akamaiedge.net      | yes — Akamai shared edge |
| amazonaws.com       | yes — AWS infra shared |
| windows.net         | yes — Azure shared |
| ctfassets.net       | yes — Contentful shared CMS |
| squarespace-cdn.com | yes — Squarespace shared |

## ORPHAN — single-host pseudo-apps, no template needed per App-Centric Model

| apex                       | kid bytes / active-min |
| -------------------------- | ---------------------- |
| duckmath.org               | 62 MB / 0 min          |
| dancemattypingguide.com    | 1.3 MB / 0 min         |
| emolingo.games             | 740 kB / 5 min         |
| flashtalking.com           | 122 MB / 35 min (ad)   |
| innovid.com                | 54 MB / 10 min (ad)    |
| primis.tech                | 3.4 MB / 5 min (ad)    |
| (long tail of ad-tech)     | mixed                  |

Long-tail ad-tech is already covered by the InfraHosts / ad-blocker side of
the system; not a template surface.

## Validation strategy

Each new template ships with a unit-test fixture that pins:
1. The actual subdomains observed in kid traffic resolve to the template
   (suffix-match through the YAML `hosts:` list).
2. A nearby shared-platform apex (e.g. `cdn.cloudflare.com`) does NOT match.

The replay-based attribution gate (Phase 5) is run against the same evidence
file — the "Other" tail per kid shrinks measurably because tinkercad,
duolingo, brave, plex, crazygames, poki, and thingiverse get reattributed
from orphan rows to their named apps.
