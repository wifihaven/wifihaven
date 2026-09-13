# App-catalog classification — #2778 Emoji Kitchen (2026-09-13)

Operator-named pass: author an Emoji Kitchen app template. The brand was given;
the traffic pull was used to *scope* the host-set and to settle the central
question the issue raised — whether any Emoji Kitchen host is blockable at all
without shared-Google-infra collateral.

Source: prod cloud API, read-only.

```
GET /api/devices                                                     # roster
GET /api/devices/<mac>/recent-apexes?windowDays=30&limit=1000        # bytes/hits + subdomains
GET /api/profiles/<id>/usage-by-app?from=2026-08-30&to=2026-09-13    # profiles 1,5,6,7 — orphanHosts
```

Kid devices sampled: Kid Laptop (`ca:ef:a1:72:6a:a3`), Kid Mac (2)
(`b0:de:28:25:93:89`, no rows in window), Octavius iPad, Prima iPad,
Quintus iPad.

## Observed Emoji Kitchen traffic

| host | bytes / 30d | hits | prop-min / 14d | device |
| --- | ---: | ---: | ---: | --- |
| `emojikitchen.dev` | 10,263,281 | 55 | 28 | Kid Laptop |
| `backend.emojikitchen.dev` | (in apex total) | | 7 | Kid Laptop |

Both were `orphanHosts` rows — real engagement credited to no app. That is the
gap this template closes. No Emoji Kitchen traffic on any of the three iPads.

## The central question: which surface is blockable?

**Google's Emoji Kitchen (Gboard keyboard + Search) — NOT blockable, excluded.**
Its stickers come from `www.gstatic.com/android/keyboard/emojikitchen/…`.
Enforcement is IP-layer (`eb_<host>` nftables drop on resolved dst_ips), so a
`gstatic.com` entry drops every Google product sharing those addresses — the
#1636 failure (blocking `youtubei.googleapis.com` broke Drive sign-in on the
kids' iPads). The breadth is not theoretical here: this household's
`gstatic.com` apex is **781 MB / 5,447 hits** across `fonts.`, `ssl.`, `csi.`,
`maps.`, `gemini.`, `connectivitycheck.`, `encrypted-tbn0-3.`,
`encrypted-vtbn0-3.` and `*.metric.` — fonts, Search thumbnails, Maps,
captive-portal detection. Already skipped as shared Google infra in
`classification-2490.md`, `-2596.md`, `-2774.md`, `-2331.md`, `-2058.md`. Same
bar excludes `googleapis.com`, `googleusercontent.com`, `ggpht.com`,
`gvt1-3.com`.

**`emojikitchen.dev` (the community sticker browser) — blockable, templated.**
It is what the kid actually uses, and blocking it genuinely stops the site. It
is not collateral-free, though, and the two halves differ:

| half | CNAME target | addresses | collision |
| --- | --- | --- | --- |
| `emojikitchen.dev`, `www.` | `xsalazar.github.io` | `185.199.108-111.153` | **demonstrated** |
| `backend.emojikitchen.dev` | `d-0bmbouithe.execute-api.us-west-2.amazonaws.com` | `52.35.189.200`, `32.184.233.146` | none in sample |

*Front-end.* GitHub Pages answers on one fixed global four-address pool. Every
Pages site checked returns the identical set — `xsalazar.github.io`,
`pages.github.com`, `jekyllrb.com`, `bootstrap-vue.github.io` — stable across
repeated lookups. And it collides in this household's own sample: Kid Laptop
hits `cics110.github.io` (a course site, 132 KB / 1 hit / 30d) on those same
four addresses. Blocking this app on that device drops GitHub Pages generally.
Small in bytes; the #1636 shape exactly.

*Back-end.* AWS API Gateway custom domain on the us-west-2 regional pool. The
kid devices' other API Gateway endpoints (`pgi7j6i5ab.`, `eydtptig4h.`,
`mbdvgoj27h.`, all us-east-1) answer on a disjoint `18.238.176.x` pool, so no
same-address collision is demonstrated. README Class 2, latent only.

Every claim above is a CNAME/answer argument, reproducible with one `dig`. No
IP-pool-overlap reasoning is used to justify an inclusion — those answers are
DNS-steered and move between lookups.

## Disposition

Shipped, with the collateral written into the template rather than papered
over. The issue's stop-and-report branch is for a template that *silently does
nothing*; this one enforces — the objection is that it enforces slightly wider
than its name, which is an operator-visible tradeoff, so the file documents it
and names the narrower alternative (`backend.emojikitchen.dev` alone: mashups
stop, page still loads, GitHub Pages pool never enters the drop set, at the
cost of 28 of the 35 attributed minutes).

Attribution is hostname-based and carries no collateral at all, so the
visibility half of the template is unconditionally safe.

## Look-alike domains checked and excluded

No prod traffic to any of these; the catalog templates only what is observed.

| domain | what it actually is |
| --- | --- |
| `emojikitchen.com` | emoji-meanings content site, different operator (Cloudflare) |
| `emojikitchen.net` | separate mashup site, different operator (Cloudflare) |
| `emoji-kitchen.com` | "Emoji Kitchen - The Game!", a different product |
| `emojikitchen.io` | domain-squatted — serves "Socolive TV", a Vietnamese football-streaming page |
| `emojikitchen.org` | no HTTP answer at authoring time |
| `emojikitchen.google` | does not resolve — there is no Google-branded web surface |

## Adjacent watch items (not templated)

* `emojipedia.org` — 3.6 MB / 23 hits / 12 prop-min, `blog.` + apex. Emoji
  reference site, a different product from Emoji Kitchen. Below the engagement
  bar on its own; re-check next pass.
* `ping.emojitracker.com` — 1 prop-min. Beacon, skip.

## #1983 app↔blocklist overlap

`emojikitchen.dev` appears on no curated blocklist
(`grep -rniE 'emojikitchen|emoji-kitchen' api/resources/blocklists/` → no match).
