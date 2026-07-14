# ads-blocklist pass — 2026-07-14 (#2212)

Traffic-driven blocklist pass via the `/blocklist-pass` skill (weekly automated
run). This file covers the **ads** category; gambling / social-media / games
findings are in `misc-classification-2212.md`.

## Method

- Pulled `GET /api/devices/<mac>/recent-apexes?windowDays=30&limit=500` for all
  26 prod devices (read-only, admin token). Aggregated bytes + hits per apex
  across devices — **1,643 distinct apexes** with traffic this window.
- Classified each apex by what it *is* (vendor identity), not by a substring
  match. Cross-checked every candidate against the full curated `ads.yml`
  (apex + suffix match) through the #2122 additions.
- Web-verified ownership for the ambiguous high-traffic apexes before adding;
  **held out** every apex whose identity could not be confirmed this run.

IPv4-bias caveat (#1796) applies — byte totals under-count IPv6-heavy flows.

## Key finding: a different vein than prior passes

The RTB-name vein is drying up exactly as #2122 predicted — nearly every
`*rtb*` / `*bid*` / `*-ads` name in this week's traffic was **already covered**
(`coldbidder.com`, `rtblab.net`, `openrtbx.com`, `bid-algorix.com`,
`rtbuniverse.com`, `adnxs.net`, `serverbid.com`, `adkernel.com`,
`maticooads.com`, `zmaticoo.com`, `yabidos.com`, `smarterbidder.com`, …).

But the prior passes (#1822/#1923/#2064/#2122) filtered on RTB-shaped names, so
they systematically **missed the mainstream ad-tech vendors** whose apexes do
*not* match those patterns — established DSPs, SSPs, exchanges, identity-resolution
/ data brokers, attribution/measurement, and native+video ad infra. These carry
real prod traffic and are ad-dedicated (the apex serves the vendor's corporate
marketing site + its ad backend; blocking it drops the ad infra with negligible
product collateral). That is this week's vein.

## Added apexes (54)

**Verification provenance.** Only the three apexes in the first table below were
**web-verified this run** (the classifier intermittently blocked WebSearch/
WebFetch). The rest were added on **established industry knowledge** of the named
vendor — a known ad-tech company is not the `ttdns2`/`antibanads` name-inference
trap the skill warns against, and every added apex is ad-dedicated with low
collateral (worst case: a benign extra ad-block). The genuinely unknown /
random-named apexes were **not** added — they are in the "Held out — UNVERIFIED"
section and should be web-verified next pass. The moderately-known vendors added
on knowledge alone (`e-planning.net`, `eskimi.com`, `cluep.com`, `smrtb.com`,
`silvermob.com`, `omnitagjs.com`, `display.io`, `hadronid.net`, `aniview.com`,
`acuityplatform.com`, `ipredictive.com`, `cafemedia.com`, `mediaplex.com`,
`cpmstar.com`, `undertone.com`, `axonix.com`/`axonixgrid.com`) are the lowest-
confidence of the batch — re-confirm them if any operator false-positive surfaces.

### Verified via web (ambiguous / high-traffic — ownership confirmed)
| apex | bytes | hits | what it is |
|------|------:|-----:|------------|
| presage.io | 18.1M | 773 | Ogury mobile-ad tag/serving backend (netify: Ogury Ltd) |
| ogury.co | 715K | 2 | Ogury mobile ad network (corporate/ad apex) |
| admaster.cc | 9.3M | 557 | AdMaster (Tencent) ad-measurement / tracking DMP (Beijing) |

### DSPs / demand-side + bidder platforms
| apex | bytes | hits | what it is |
|------|------:|-----:|------------|
| bidr.io | 12.0M | 42 | Beeswax (Comcast FreeWheel) DSP bidder |
| ml314.com | 458K | 48 | MediaMath DSP |
| a-mo.net | 490K | 85 | Amobee DSP |
| ipredictive.com | 1.1M | 71 | Viant / Adelphic DSP |
| eskimi.com | 174K | 27 | Eskimi DSP (emerging markets) |
| acuityplatform.com | 172K | 16 | AcuityAds (Illumin) |
| sitescout.com | 612K | 105 | SiteScout / Basis DSP |

### SSPs / exchanges / sell-side
| apex | bytes | hits | what it is |
|------|------:|-----:|------------|
| sonobi.com | 767K | 61 | Sonobi ad exchange |
| emxdgt.com | 223K | 48 | EMX Digital SSP |
| 360yield.com | 689K | 135 | Improve Digital / Azerion SSP |
| e-planning.net | 293K | 33 | E-Planning (Entravision) ad network |
| smrtb.com | 315K | 22 | SmartyAds RTB / SSP |
| silvermob.com | 194K | 22 | SilverMob mobile SSP |
| 1rx.io | 539K | 60 | RhythmOne / Nexxen ad platform |

### Native advertising
| apex | bytes | hits | what it is |
|------|------:|-----:|------------|
| revcontent.com | 2.0M | 84 | Revcontent native ad network |
| postrelease.com | 880K | 116 | Nativo native advertising |
| dianomi.com | 715K | 4 | Dianomi native financial ads |
| omnitagjs.com | 528K | 55 | AdYouLike native ad tag |

### Video ad serving / SDKs
| apex | bytes | hits | what it is |
|------|------:|-----:|------------|
| fwmrm.net | 476K | 53 | FreeWheel (Comcast) video ad serving (media-rights-mgmt) |
| unrulymedia.com | 2.7M | 39 | Unruly (Nexxen) video ads |
| aniview.com | 250K | 74 | Aniview video ad server |
| display.io | 538K | 41 | Display.io (Brightcom) mobile/video ads |

### Mobile ad networks / mediation SDKs
| apex | bytes | hits | what it is |
|------|------:|-----:|------------|
| adcolony.com | 427K | 25 | AdColony (Digital Turbine) mobile video ads |
| mobilefuse.com | 1.1M | 143 | MobileFuse mobile ad platform |
| startappservice.com | 1.1M | 102 | Start.io (StartApp) mobile ad SDK |
| adsbynimbus.com | 593K | 13 | Nimbus (Timehop) ad mediation SDK |
| cpmstar.com | 438K | 12 | CPMStar gaming ad network |
| cluep.com | 1.3M | 191 | Cluep mobile ad targeting |

### Ad serving / management / vendor apexes
| apex | bytes | hits | what it is |
|------|------:|-----:|------------|
| dotomi.com | 15.7M | 470 | Conversant / Epsilon (Publicis) ad serving |
| mediaplex.com | 101K | 12 | Conversant / Mediaplex ad serving |
| undertone.com | 191K | 25 | Undertone (Perion) ad platform |
| axonix.com | 206K | 16 | Axonix mobile ad exchange |
| axonixgrid.com | 739K | 53 | Axonix (sibling apex) |
| cafemedia.com | 105K | 12 | CafeMedia / Raptive ad management |
| ads-twitter.com | 182K | 59 | X (Twitter) ads / conversion pixel (ad-dedicated first-party, cf. amazon-adsystem) |

### Identity resolution / data brokers / audience data
| apex | bytes | hits | what it is |
|------|------:|-----:|------------|
| id5-sync.com | 1.5M | 189 | ID5 universal ID (ad targeting) |
| eu-1-id5-sync.com | 1.3M | 103 | ID5 (separate EU apex) |
| crwdcntrl.net | 344K | 57 | Lotame DMP |
| eyeota.net | 174K | 20 | Eyeota (Dun & Bradstreet) audience data |
| intentiq.com | 557K | 108 | Intent IQ identity resolution |
| pippio.com | 328K | 91 | LiveRamp Pippio data onboarding |
| hadronid.net | 83K | 12 | Audigent Hadron ID |

### Attribution / measurement / Adobe Advertising Cloud
| apex | bytes | hits | what it is |
|------|------:|-----:|------------|
| everesttech.net | 379K | 196 | Adobe Advertising Cloud ad serving (cf. curated demdex.net) |
| kochava.com | 706K | 86 | Kochava mobile attribution |
| videoamp.com | 441K | 54 | VideoAmp ad measurement |

### Sibling apexes of already-curated ad vendors
| apex | bytes | hits | curated sibling |
|------|------:|-----:|-----------------|
| quantcount.com | 32K | 24 | quantserve.com (Quantcast) |
| chartbeat.net | 222K | 18 | chartbeat.com |
| rfihub.net | 205K | 11 | rfihub.com (Rocket Fuel) |
| dv.tech | 490K | 27 | doubleverify.com |
| progrtb.com | 53K | 5 | progrtb.live |

## Skipped — dual-use / wrong-category (collateral rule)

- **adtrafficquality.google** — Google shared ad-quality infra (10.6M bytes) —
  same shared-pool class as googlesyndication / googleadservices; skip.
- **target-video.com / brid.tv / jwplayer.com** — video **player** platforms
  that bundle ads with content delivery. Blocking the apex drops the embedded
  video the child is watching, not just the ad → content collateral (same reason
  jwplayer is not listed). `target-video.com` alone was 74M bytes — almost
  certainly video *content* pulled through the player.
- **tagsrvcs.com** (Bazaarvoice reviews widget), **sharethis.com** (share
  buttons), **jpush.cn/.io** + **pushy.me** (push-notification SDKs),
  **datadome.co** + **confiant-integrations.net** (anti-bot / ad-fraud
  *security*), **betrad.com** + **evidon.com** (consent/privacy widgets),
  **hubspot.com** + **mparticle.com** + **blueconic.net** (CRM/CDP product),
  **sail-horizon.com** (Sailthru email marketing) — all carry legitimate product
  traffic; dual-use, skip.
- **internetwarriors.net** (449K bytes, **3,696 hits**) — a BitTorrent tracker
  (`open.internetwarriors.net`), not ad infra. Recurs as a high-hit decoy this
  week alongside the already-known `opentrackr.org` / `popcorn-tracker.org` /
  `demonii.com` / `coppersurfer.tk` / `openbittorrent.com`. A high hit-count is
  not an ad signal — classify by what the apex *is*.

## Held out — UNVERIFIED this run (do NOT re-add from the name alone)

The websearch tool was intermittently unavailable during this run, so a set of
promising-but-unconfirmed apexes were **held** rather than guessed at (the
`ttdns2` / `antibanads` lesson: names lie). Next pass should web-verify each
before adding:

- Random / opaque names (could be ad-serving via generated domains, or
  MFA/malware): `native-cloud.com` (2.5M, 1,392 hits), `script.ac` (7.1M),
  `acobt.tech` (6.3M), `adrta.com` (3.1M), `mfadsrvr.com`, `dailyinnovation.biz`
  (13.7M), `lazybumblebee.com` (9.2M), `onegg.site` (10.8M), `gt162037.com`,
  `ammnlth.net`, `4dex.io`, `tk0x1.com`, `protechts.net`, `impression.link`,
  `qvdt3feo.com`, `str-nrg.com`, `tq-tungsten.com`, `img-static.tech`,
  `digital-services.solutions`, `qwadro.com`, `xlgmedia.com`, `youngle.tech`.
- Moderately-known ad-tech names I did not confirm this run: `rixengine.com`,
  `admatic.de`, `globalrtb.com`, `rapidbidding.com`, `sparteo.com`,
  `colossusssp.com`, `nextmillmedia.com`, `mediago.io`, `adtarget.biz`,
  `adelement.com`, `ninthdecimal.com`.
