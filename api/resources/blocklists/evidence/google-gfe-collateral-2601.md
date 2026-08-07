# Google shared-GFE collateral — why eight ad apexes left `ads.yml` (#2601)

Prod, 2026-08-06. The `Sameer` profile (id 2, categories `[malware, adult,
gambling, ads]`) could not download from Google Drive. All evidence below was
read from prod read-only.

## It was not a hostname block

`PolicyService` says the host is allowed on every MAC on that profile:

```
GET /api/blocked?mac=52:1a:60:d8:4e:32&host=drive.usercontent.google.com
  {"blocked":false,"profileName":"Sameer","usedMinutes":2147,"extensionMinutes":0}
```

Profile 2 is `paused:false`, `defaultDeny:false`, `blockIpOnly:false`, with no
`extraBlocked` entry for any Google host and no app assignment. The drop came
from the category path.

## The `ads` rule was firing on destination IP

Router `192.168.10.1`, `nft list table inet wifihaven`:

```
ether saddr 52:1a:60:d8:4e:32 ip  daddr @bl_ads  ip  daddr != @global_allow  counter packets  14 bytes  19142 drop
ether saddr 52:1a:60:d8:4e:32 ip6 daddr @bl6_ads ip6 daddr != @global_allow6 counter packets 182 bytes 120607 drop
```

`bl6_ads` held exactly four addresses, all Google GFE frontends:

| address | resolved from |
|---|---|
| `2607:f8b0:400f:800::2008` | googletagmanager.com |
| `2607:f8b0:400f:805::200e` | doubleclick.net |
| `2607:f8b0:400f:806::2002` | adservice.google.com |
| `2607:f8b0:400f:806::2004` | google-analytics.com |

`bl_ads` held the v4 equivalents: `142.250.72.194`, `142.250.72.200`,
`142.250.188.34`, `142.250.188.36`, `142.250.188.38`, `142.251.35.130`,
`142.251.35.134`, `142.251.46.130`, `142.251.46.142`.

`drive.usercontent.google.com` resolves to `142.251.46.129` and
`2607:f8b0:400f:800::2001` — the same pools, one address away from entries
already in the set.

## One address, five hostnames

The decisive evidence. Five hostnames dropped for one MAC at one timestamp,
which is only possible if one destination IP served all five:

```
2026-08-06T22:14:23Z  static.doubleclick.net          ads
2026-08-06T22:14:23Z  pagead2.googlesyndication.com   ads
2026-08-06T22:14:23Z  www.googletagmanager.com        ads
2026-08-06T22:14:23Z  www.googleadservices.com        ads
2026-08-06T22:14:23Z  drive.google.com                ads   <- collateral
2026-08-06T22:11:27Z  ci3.googleusercontent.com       ads   <- collateral
2026-08-06T22:11:27Z  clients4.google.com             ads   <- collateral
```

`drive.google.com`, `ci3.googleusercontent.com` and `clients4.google.com` are
in no curated list.

## Hosts removed

Every Google-owned apex in `ads.yml` that fronts on the shared pool. Each was
resolved on 2026-08-06:

| host | A | AAAA |
|---|---|---|
| doubleclick.net | 142.251.46.142 | 2607:f8b0:400f:805::200e |
| googleadservices.com | 142.250.188.34 | (via `www.`) 142.251.35.130 |
| googlesyndication.com | 142.251.46.132 | 2607:f8b0:400f:805::2004 |
| googletagmanager.com | 142.250.72.200 | 2607:f8b0:400f:800::2008 |
| googletagservices.com | (via `www.`) 142.250.72.194 | (via `www.`) 2607:f8b0:400f:805::2002 |
| google-analytics.com | 142.250.188.36 | 2607:f8b0:400f:806::2004 |
| adservice.google.com | 142.250.188.34 | 2607:f8b0:400f:805::2002 |
| 2mdn.net | (via subdomains, same pool) | — |

Non-Google ad apexes are unaffected. `ads-extended` (the StevenBlack feed) is
unchanged: it is a separate, opt-in list, and narrowing it is a different
decision from narrowing the curated baseline.

## What this costs, and what would undo it

Profiles with `ads` enabled no longer block Google's own ad and analytics
properties. That is a real weakening, accepted deliberately: an IP-layer block
of these hosts cannot be made to hit only them, and the collateral takes out
Drive downloads for every household on the category.

The mirror image of this is #2369, where Google GFE-shared hosts on the infra
allow-carve defeated a host block; the same fact drives both — at the IP layer
we cannot tell one Google name from another. #2377 (SNI-level disambiguation)
is what would make these hosts blockable again. Until it lands they stay out
of every curated list, pinned by the `BundledBlocklistsSpec` guard so a later
traffic-driven pass cannot re-add them from the hostname alone.
