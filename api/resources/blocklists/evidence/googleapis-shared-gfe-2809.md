# The `googleapis.com` shared frontend — why fetched lists need an ingest exception (#2809)

Prod, 2026-09-23. Google sign-in failed on devices under the `Prima` (6) and
`Octavius` (7) profiles, the two profiles with `ads-extended` enabled. All
evidence below was read from prod read-only. This is #2601 again, through the
door #2601 could not reach: a **fetched** list.

## It was not a hostname block

`PolicyService.decide` allows the host on every MAC checked, including the two
profiles that had `ads-extended` on:

```
GET /api/blocked?mac=8a:8a:0b:86:5a:63&host=oauthaccountmanager.googleapis.com
  {"blocked":false,"profileName":"Prima","usedMinutes":0,"dailyLimitMinutes":30,...}
GET /api/blocked?mac=a6:05:9a:63:83:af&host=oauthaccountmanager.googleapis.com
  {"blocked":false,"profileName":"Octavius","usedMinutes":0,"dailyLimitMinutes":30,...}
```

`oauthaccountmanager.googleapis.com` is not in the upstream StevenBlack file
either — today's fetch carries exactly five `googleapis.com` hosts and that is
not one of them. A hostname-level search for the reported symptom finds nothing,
which is the trap: the drop is on the resolved address.

## Four upstream members put the login pool into `bl_ads_extended`

Router `192.168.10.1`, `/tmp/dnsmasq.d/blocklists/wifihaven-blocklist-ads-extended.conf`
(76,239 directives) carries:

```
nftset=/clientmetrics-pa.googleapis.com/4#inet#wifihaven#bl_ads_extended,6#...
nftset=/firebaselogging-pa.googleapis.com/4#inet#wifihaven#bl_ads_extended,6#...
nftset=/firebaselogging.googleapis.com/4#inet#wifihaven#bl_ads_extended,6#...
nftset=/ogads-pa.googleapis.com/4#inet#wifihaven#bl_ads_extended,6#...
```

and the matching drop rule (Prima's iPad) carves out only that MAC's `ea_` sets
and `@global_allow`:

```
ether saddr 8a:8a:0b:86:5a:63 ip daddr @bl_ads_extended ... ip daddr != @global_allow counter drop
  comment "wh_drop:8a:8a:0b:86:5a:63:category:ads-extended"
```

`bl_ads_extended` is `flags dynamic,timeout` / `timeout 1h`, populated by
dnsmasq at resolve time. So one Firebase-SDK app resolving
`firebaselogging.googleapis.com` arms the drop for the next hour.

## The addresses are the same eight

Resolved 2026-09-23. Every host below answers from **172.217.112.4,
172.217.113.4, 172.217.114.4, 172.217.115.4, 172.217.116.4, 172.217.117.4,
172.217.118.4, 172.217.119.4** — one pool, no exceptions:

| host | in ads-extended | role |
| --- | --- | --- |
| `firebaselogging.googleapis.com` | yes | Firebase telemetry |
| `firebaselogging-pa.googleapis.com` | yes | Firebase telemetry |
| `clientmetrics-pa.googleapis.com` | yes | client metrics |
| `ogads-pa.googleapis.com` | yes | Google ads private API |
| `oauthaccountmanager.googleapis.com` | **no** | **account/token control plane** |
| `securetoken.googleapis.com` | **no** | **token refresh** |
| `people-pa.googleapis.com` | no | contacts private API |
| `signaler-pa.googleapis.com` | no | push signalling |
| `kidsmanagement-pa.googleapis.com` | no | Family Link |

Blocking any one of the first four does not block that host. It blocks the
address, and the address is Google sign-in. That is why the ban is written at
the `googleapis.com` CLASS rather than as the four names in today's file —
upstream adding a fifth would re-open it.

## The same file carries 170 already-banned hosts

Running the existing `SharedGfeHosts` matcher (#2601 + #2369, the set the repo
already forbids in every *authored* catalog) over today's fetched files:

| fetched list | banned-apex members |
| --- | --- |
| ads-extended | **170** |
| adult-extended | 0 |
| social-extended | 0 |
| malware | 0 |

Those 170 include the literal hosts from the #2601 incident —
`static.doubleclick.net`, `pagead2.googlesyndication.com`,
`www.googletagmanager.com`, `www.googleadservices.com` — which were removed from
`ads.yml` precisely because they broke Google Drive. The fetched list has been
putting them back the whole time, unguarded. That is latent breakage nobody had
attributed yet, not a new regression.

## Why not the infra allow-carve

Adding `oauthaccountmanager.googleapis.com` to `InfraHosts.canonical` (so it
rides `PolicyService.infraAllowHosts` into `global.extraAllowed` →
`@global_allow`) is the obvious-looking fix and is exactly what #2369 reverted
for this family of hosts. `@global_allow` matches on resolved IP and beats every
drop (#421), so carving it would punch all eight addresses out of every Google
host-block, for every MAC — silently defeating blocks the operator believes are
in force. The host stays in `InfraHosts.cloudBackground`, which is
attribution-only, and the fix lives at ingest instead.

## Quintus Chromebook (`c4:13:75:68:a1:01`, profile 5) — a different question

Read at the same time: profile 5 does **not** have `ads-extended` enabled, and
the router's ruleset contains **zero** `bl_ads_extended` rules for that MAC. It
is not in `blocked_macs`, has `blockIpOnly:false`, and `decide` returns
`blocked:false` for `oauthaccountmanager.googleapis.com`, `oauth2.googleapis.com`,
`securetoken.googleapis.com` and `accounts.google.com`. Whatever the Chromebook
symptom was, this fix does not explain it — tracked separately.
