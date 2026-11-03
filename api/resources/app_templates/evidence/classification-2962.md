# App-catalog classification — #2962 operator-named pass (2026-11-03)

Operator ask: apps for i-Ready, Minecraft, enVision Math, ClassLink and
Savvas Realize. Minecraft, ClassLink and Savvas Realize already had
templates, so this pass added i-Ready, decided enVision, and checked the
other three for host-set gaps.

Source: prod cloud API, read-only. No policy writes.

```
GET /api/devices                                                       # roster (30 devices)
GET /api/devices/<mac>/recent-apexes?windowDays=30&limit=1000          # every device
GET /api/profiles/<id>/usage-by-app?from=2026-10-04&to=2026-11-03       # profiles 5, 6, 7
```

Kid profiles: 5 (Quintus: Quintus Chromebook, Quintus iPad), 6 (Prima: Prima
iPad), 7 (Octavius: Octavius iPad). `orphanHosts` filtered to
`host.type == "fqdn"`. Profiles 2-4 (adults) timed out on `usage-by-app`
and were not retried (see the skill learnings: the parallel pull briefly
502'd prod). The Quintus Chromebook `recent-apexes` call failed
(`SQLTransientConnectionException`, then 502); its traffic is still
covered by the profile-5 `usage-by-app` row.

## i-Ready (new template `iready`)

Profile 5 `orphanHosts`, 30d:

| host | proportional s | presence s |
| --- | ---: | ---: |
| `login.i-ready.com` | 1799 | 1360 |
| `write-api.i-ready.com` | 1457 | 870 |
| `cdn.i-ready.com` | 1386 | 1060 |
| **total** | **4642 (~77 min)** | 3290 |

`recent-apexes` also showed `cdn.`/`login.i-ready.com` on Rachel Mac (4.4 MB,
4 hits) and `g.i-ready.com` on the test router (0.56 MB, 5 hits).

Vendor allowlist: Curriculum Associates "i-Ready System Requirements"
2026-27 PDF,
`https://cdn.i-ready.com/instruction/content/system-check/iReady_System_Requirements.pdf`
(fetched 2026-11-03, footer dated 07/26). First-party domains it lists:
`*.i-ready.com`, `i-readyconnect.com`, `*.curriculumassociates.com`,
`*.cainc.com`.

| candidate | disposition | reason |
| --- | --- | --- |
| `i-ready.com` | **host** | every observed host is a child; own Route 53 zone (`dig NS`) |
| `i-readyconnect.com` | **host** | first-party, title "i-Ready Login \| Log In to i-Ready Connect"; same four ELB addresses as `login.i-ready.com` on 2026-11-03 |
| `curriculumassociates.com`, `cainc.com` | skip | corporate site, secure mail, Box tenant; no observed traffic |
| `learnosity.com`, `vidyard.com`, `3playmedia.com`, `gainsight*.com`, `box.com`, `status.io`, `bfldr.com`, `colibrio.com`, `respondus2.com`, `stileapp.com`/`stileeducation.com` | skip | third-party services shared with other apps |
| `d1q5sowf7qahc6.`/`d9unvfzorf0bo.`/`dw6y82u65ww8h.cloudfront.net`, `originp3.s3.amazonaws.com` | watch-item | vendor-listed but not in this window's traffic; shared AWS pools |

Checks from this machine, 2026-11-03: `login.i-ready.com` presented
`CN=*.i-ready.com` (Amazon issuer), not our block cert; control `example.com`
presented its own cert. Icon `icons.duckduckgo.com/ip3/i-ready.com.ico`
returned 200.

## enVision Math (no template; covered by `savvas-realize`)

- Savvas Realize system requirements
  (`help.learningservicestechnology.com/realize/en/Announcements/Content/system_requirements.htm`,
  "23.2.4 - 9/8/2026") lists only `savvasrealize.com` for traffic, and
  Savvas-wide domains for pop-ups (`savvas.com`, `savvaseasybridge.com`,
  `mysavvastraining.com`, `learningservicestechnology.com`). No
  enVision-specific domain.
- Savvas's "Approved / Allowed URL list" support article is a
  JavaScript-rendered page and could not be fetched. Recorded as unverified.
- Traffic: no host containing `envision` on any device in 30d.

enVision courseware is delivered inside Realize, so at the hostname level it
cannot be told apart from any other Realize course. A separate template
would share `savvasrealize.com` with `savvas-realize`, and both apps would
claim the same time. Documented in `savvas-realize.yml` instead.

## Savvas Realize (extended)

Profile 5 `savvas-realize` app row: ~21 attributed minutes across 15
`savvasrealize.com` hosts, all covered by the apex entry (including
`feature-management.` and `lst-assets.`, which appeared after #2915).

| candidate | disposition | reason |
| --- | --- | --- |
| `sso.rumba.pk12ls.com` (profile 5, 10 s) | **host `rumba.pk12ls.com`** | Savvas RUMBA sign-in; cert `CN=rumba.pk12ls.com`; Savvas allowlist names `*.pk12ls.com` (search summary). Scoped to the `rumba.` subtree |

## ClassLink (extended)

Profile 5 `classlink` app row: ~26 attributed minutes, all `classlink.com`
hosts.

| candidate | disposition | reason |
| --- | --- | --- |
| `analytics-log.classlink.io` (profile 5, 120 s) | **host `classlink.io`** | ClassLink-owned apex, own Route 53 zone; ClassLink allowlist names `*.classlink.io` (search summary; the help article is JavaScript-rendered) |
| `classlink.svvsd.org` (profile 5, 32 s) | skip | district-run host on the district's apex (Cloudflare, cert `CN=svvsd.org`); exists for one district only |
| `launchpad.classlink.com` (Prima iPad) | already covered | |

## Minecraft (extended)

No `minecraft` app row on profiles 5-7 and no Minecraft apex in kid
`recent-apexes` in 30d. Xbox Live hosts appeared only on adult devices
(Sameer iPhone) and the test router.

Vendor doc: "URLs Used by Minecraft Education",
`https://edusupport.minecraft.net/hc/en-us/articles/4404784181524-URLs-Used-by-Minecraft-Education`
(updated 2026-11-01).

| candidate | disposition | reason |
| --- | --- | --- |
| `minecrafteduservices.com` | **host** | Minecraft Education sign-in / multiplayer / coding backend; only Minecraft uses it. `login.` resolves to Azure Front Door 150.171.109.x, the same pool as the existing `minecraft.net` and `api.minecraftservices.com` |
| `education.minecraft.net`, `*.minecraft-services.net`, `*.xboxlive.com` | already covered | |
| `playfabapi.com` | skip | PlayFab is a Microsoft game backend used by many unrelated games |
| Microsoft 365 / Azure identity, SharePoint, OneNote, MakeCode, `api.github.com` | skip | shared platform hosts |

Consumer Bedrock / Realms firewall hostnames: no official list found, so
nothing added for them.
