# Press submission pack — WifiHaven beta launch (#2233)

**Every target on the media list is a contact FORM or tip line. None has a publishable
direct email address.** So the outreach is done by submitting each outlet's form by hand
on launch day — Monday 2026-08-17 — not by sending email. The API's press-outreach email
sender was removed for that reason (operator decision, 2026-08-16); the #2203 press
RESPONDER is untouched and still handles inbound press mail at `press@wifihaven.net`.

This file is the working sheet for that session. One block per outlet, in send order:
the form URL, the pitch written for that outlet, and what to paste where. The founder's
LinkedIn post is at the end — that one is posted personally, not submitted.

## Before the first submission

1. `https://wifihaven.net/press` is live and renders. Every submission links to it, so
   check it first — a dead link in the first form is a dead link in all 21.
2. `https://app.wifihaven.net/beta` accepts a request.
3. Read the release once more at [`press-release.md`](press-release.md).

## How to fill a form

Most of these forms have a short free-text field, sometimes a subject line, sometimes a
URL field. The mapping:

| form field | what to paste |
|---|---|
| Subject / title | `WifiHaven: open-source parental controls enforced at the router` |
| Message / pitch / tip | the outlet's pitch below, verbatim |
| Link / URL | `https://wifihaven.net/press` |
| Your name | Sameer Brenn |
| Your email | `press@wifihaven.net` — replies route to the #2203 responder |

If the form has a hard character limit, cut from the END of the pitch. The first two
sentences carry the outlet-specific hook and the mechanism; the closing offer is the
expendable part.

**Do not paste one outlet's pitch into another outlet's form.** They are written per
outlet, which is the whole point of doing this by hand.

## Constraints on this session

- **CAPTCHAs are a hard stop.** Several of these forms are behind one. Claude cannot
  solve or bypass a CAPTCHA — those submissions are the operator's to complete.
- **Each submission needs the operator's explicit go-ahead.** Submitting a form to an
  outside organisation is an outbound act; approval is per form, not once for the batch.
- **Log the outcome** in the Status column below as you go, so a re-run of this sheet
  never double-submits.

## Send order and status

| # | Outlet | Priority | Submitted | Response |
|---|--------|----------|-----------|----------|
| 1 | Hackaday | P1 | | |
| 2 | It's FOSS | P1 | | |
| 3 | Lawrence Systems (YouTube) | P1 | | |
| 4 | selfh.st / Self-Host Weekly | P1 | | |
| 5 | ServeTheHome | P1 | | |
| 6 | Techno Tim (YouTube) | P1 | | |
| 7 | The Register (FOSS desk) | P1 | | |
| 8 | Wolfgang's Channel (YouTube) | P1 | | |
| 9 | XDA (self-hosting) | P1 | | |
| 10 | Ars Technica | P2 | | |
| 11 | Dong Knows Tech | P2 | | |
| 12 | NetworkChuck (YouTube) | P2 | | |
| 13 | PCMag | P2 | | |
| 14 | TechRadar Pro | P2 | | |
| 15 | Tom's Guide | P2 | | |
| 16 | Wirecutter (NYT) | P2 | | |
| 17 | How-To Geek | P3 | | |
| 18 | Jupiter Broadcasting (Linux Unplugged) | P3 | | |
| 19 | OMG Ubuntu | P3 | | |
| 20 | TechRadar Pro (security desk) | P3 | | |
| 21 | Tom's Hardware | P3 | | |

---

## 1. Hackaday — Maya Posch

- **Priority:** 1
- **Form:** https://hackaday.com/submit-a-tip/
- **id:** `hackaday`

```
Your June piece on building your own router in 2026 is roughly the audience we built this for, so we wanted to send you the mechanism rather than the product. WifiHaven does parental controls on OpenWrt by dropping forwarded packets in nftables, and it attributes destination IPs back to hostnames using dnsmasq's nftset callback: each (MAC, hostname) pair gets its own set, populated at resolve time, and the forward chain matches on membership. That means DNS is never the enforcement point, which is what makes it survive DoH and hard-coded IPs — a destination the router can't attribute to a resolved hostname can be dropped outright. Happy to walk through the set layout, or the tmpfs discipline the agent needs to live on a router with 128MB of RAM.
```

## 2. It's FOSS — News desk (Abhishek Prakash, ed.)

- **Priority:** 1
- **Form:** https://itsfoss.com/community-submission/
- **id:** `itsfoss`

```
WifiHaven has just opened its beta: an open-source parental-control and screen-time system for OpenWrt routers, free to self-host and stays that way, with an optional hosted tier. Readers who know Pi-hole or AdGuard Home will recognise the shape but not the mechanism — WifiHaven blocks at the connection layer with nftables rather than answering a DNS query differently, so it holds when a device uses encrypted DNS. It adds the family half those tools don't have: per-child profiles, schedules, daily and per-app time limits, and a pause button. Full release below if it suits a project-launch brief.
```

## 3. Lawrence Systems (YouTube) — Tom Lawrence

- **Priority:** 1
- **Form:** https://lawrencesystems.com/hire-us/
- **id:** `lawrencesystems`

```
You've spent a lot of airtime explaining why DNS filtering doesn't hold, which is exactly the problem WifiHaven is built around. It enforces per-child schedules, time limits and blocks as nftables forward-drops on an OpenWrt gateway; the lookup still succeeds, the connection that follows just gets dropped. There's also a mode that drops any destination the router can't attribute to a hostname it resolved for that device, which is the DoH and hard-coded-IP case. It's open source and free to self-host, and we think it would test well on camera against a phone with DoH forced on.
```

## 4. selfh.st / Self-Host Weekly — Ethan Sholly

- **Priority:** 1
- **Form:** https://selfh.st/about/
- **id:** `selfhst`

```
We've just opened the beta of WifiHaven, an open-source parental-control and screen-time system that runs on the household's OpenWrt router instead of on the kids' phones. It's the whole stack under an open licence and free to self-host permanently; the hosted tier is the optional convenience half. The part your readers will care about is that it isn't a DNS filter: enforcement is an nftables drop on the gateway, so DNS resolves as normal and the connection just doesn't go anywhere. If it fits Self-Host Weekly we'd be glad to send whatever detail helps, and we'll submit it to the apps directory separately.
```

## 5. ServeTheHome — Patrick Kennedy

- **Priority:** 1
- **Form:** https://www.servethehome.com/contact/
- **id:** `servethehome`

```
WifiHaven is a family-policy layer for hardware people already have: an agent on an OpenWrt router plus a control-plane API you can self-host or let us host. Per-child profiles follow every device a kid uses, and enforcement is an nftables drop on the gateway rather than DNS filtering. The self-hosted build is the complete product, with no future paywall on it; the hosted tier runs $10/month at the standard rate. Given STH's networking and home-lab readership, we can send hardware notes or dig into the agent's footprint with you.
```

## 6. Techno Tim (YouTube) — Tim Stewart

- **Priority:** 1
- **Form:** https://links.technotim.com/
- **id:** `technotim`

```
WifiHaven might make a good build video: whole-network screen time and content policy on an OpenWrt router, self-hosted, in about the length of one of your tutorials. Per-child profiles, schedules, daily and per-app limits, category blocklists and a one-tap pause, all applied at the gateway. The bit worth showing on screen is that it isn't DNS filtering — the block is an nftables drop, so it still holds with DoH turned on in the browser. Everything is open source and free to run yourself; say the word and we'll get a unit ready for you to film.
```

## 7. The Register (FOSS desk) — Liam Proven

- **Priority:** 1
- **Form:** https://www.theregister.com/Author/Email/Liam-Proven
- **id:** `theregister`

```
The Register's readers have sat through enough "open-core" launches to be skeptical of the promise, so here's the version that's actually accountable to it: WifiHaven is a parental-control system for OpenWrt routers where the self-hosted build is the complete product, not a stripped demo of the hosted one. The hosted tier will run $10 a month once beta pricing ends; right now, in beta, it's free either way. Enforcement is connection-layer, not DNS: nftables drops the forwarded packet at the gateway, so the encrypted-DNS switch every browser now ships doesn't get around it. Worth a look for the FOSS desk, and there's plenty more detail if you want to push on any of it.
```

## 8. Wolfgang's Channel (YouTube) — Wolfgang (notthebee)

- **Priority:** 1
- **Form:** https://linktr.ee/wolfgangschannel
- **id:** `wolfgang`

```
Your channel's angle on self-hosting for privacy is the reason we're sending this: WifiHaven gives parents screen-time and content controls without shipping a child's browsing history to a vendor. Self-host it and every byte of household data stays on your hardware; the hosted tier exists for people who don't want to run a server, and it's optional. Enforcement is an nftables drop on an OpenWrt gateway rather than a DNS filter, which is also what makes it hold up when a browser switches on encrypted DNS. It's open source end to end if you want to read what it actually does with the data.
```

## 9. XDA (self-hosting) — Adam Conway

- **Priority:** 1
- **Form:** https://www.xda-developers.com/author/adamconway-xda/
- **id:** `xda`

```
Your July piece on the four reasons people self-host lines up with what we've just shipped: WifiHaven is an open-source parental-control system that replaces a per-device subscription with something running on your own OpenWrt router. There's no app on the kid's phone to delete and no per-device fee, and the self-hosted version is free permanently. Technically it's a departure from the Pi-hole-shaped thing readers expect — blocking is an nftables drop at the gateway, not a DNS answer, so encrypted DNS doesn't route around it. If a hands-on would suit XDA we can get you set up on a Flint 2.
```

## 10. Ars Technica — Kevin Purdy / Lee Hutchinson

- **Priority:** 2
- **Form:** https://arstechnica.com/contact-us/
- **id:** `arstechnica`

```
WifiHaven is an open-source parental-control system that enforces at the connection layer on an OpenWrt router, and the technical story is one Ars readers would actually finish: DNS always resolves, blocking is an nftables forward-drop, and destination IPs are attributed to hostnames through dnsmasq's nftset callback so the policy can be per-device and per-host without touching DNS answers. That design is what survives DoH and hard-coded IPs, which is where DNS-filtering parental controls quietly fail. The business model is the Nabu Casa one: the self-hosted build is complete and stays free for good, the hosted tier is the convenience purchase. We're happy to go as deep into the enforcement path as is useful.
```

## 11. Dong Knows Tech — Dong Ngo

- **Priority:** 2
- **Form:** https://dongknows.com/contact/
- **id:** `dongknows`

```
You've put a Firewalla Gold through its paces about as thoroughly as anyone writing about this category, which is exactly the readership WifiHaven needs: the same network-level family control, open source, running on an OpenWrt router instead of a dedicated box. Enforcement is nftables at the gateway, with hostname attribution done from the router's own DNS resolutions, so encrypted DNS and hard-coded IPs don't slip past it. Per-child profiles, schedules, daily and per-app limits, category blocking, one-tap pause. If you want to take it apart properly we'll help you get it onto a Flint 2 and answer anything the docs don't.
```

## 12. NetworkChuck (YouTube) — Chuck Keith

- **Priority:** 2
- **Form:** https://store.networkchuck.com/pages/contact-us
- **id:** `networkchuck`

```
Your Pi-hole videos are how a lot of people first learn that the network can enforce something, and WifiHaven is the next step from there: parental controls that live inside the router, not on the kid's phone. It's an OpenWrt agent that drops disallowed connections with nftables, plus per-child schedules and time limits: the DNS lookup still comes back clean, the connection just doesn't get anywhere. Open source, free to self-host, and the demo is visual: pause a kid's device mid-video and watch it stop. Happy to help set up a build if it fits the channel.
```

## 13. PCMag — Kim Key

- **Priority:** 2
- **Form:** https://www.pcmag.com/about/contact-us
- **id:** `pcmag`

```
For PCMag's parental-control coverage, WifiHaven is a category outlier worth knowing about: there's nothing installed on the child's device, so there's nothing to uninstall, and the controls survive a factory reset. It runs on the family's own OpenWrt router and enforces at the connection layer with nftables, which also means the encrypted-DNS setting in modern browsers doesn't route around it. Per-child profiles cover schedules, daily and per-app limits, category blocking and a pause. It does require flashing the router, which is a real barrier and one we state plainly; the software itself is open source and free to self-host.
```

## 14. TechRadar Pro — Mike Williams

- **Priority:** 2
- **Form:** https://www.futureplc.com/contact/
- **id:** `techradarpro`

```
Your Firewalla Gold reviews cover the ground WifiHaven sits on: network-level control for a household, minus the $500 appliance. It runs as an agent on an OpenWrt router plus a control plane you can host yourself, and it enforces per-child schedules, time limits and blocks as nftables drops rather than DNS filtering. The whole stack is open source and free to self-host; the hosted tier is $10/month at full price. Happy to arrange a hands-on if the comparison to the appliance class is of interest.
```

## 15. Tom's Guide — Brian Nadel

- **Priority:** 2
- **Form:** https://www.tomsguide.com/author/brian-nadel
- **id:** `tomsguide`

```
You reviewed the Gryphon, so you know the pitch for a router that does parental controls; WifiHaven is the open-source version of that idea, running on a router a family buys for about $80 rather than a $400 appliance. Per-child profiles, schedules, daily and per-app limits, category blocking and a pause button, all enforced at the gateway. It needs flashing OpenWrt, which is the honest catch and the reason we're starting with technical families. Hosted tier is $10/month at the regular rate, the self-hosted version stays free to run for good, and we can get a review unit configured if you'd like to put it through the usual testing.
```

## 16. Wirecutter (NYT) — Joel Santo Domingo

- **Priority:** 2
- **Form:** https://www.nytimes.com/wirecutter/contact-us/
- **id:** `wirecutter`

```
This is a long shot for Wirecutter but we'd rather you hear it from us: WifiHaven is parental controls and screen time that run on the household's own router, with a hosted tier at $10/month and a self-hosted version that never expires into a paywall. It enforces at the connection layer with nftables on OpenWrt, so unlike the DNS-based controls built into most routers it isn't defeated by a browser's encrypted-DNS switch, and unlike per-device apps there's nothing a kid can delete. The catch, and we'll say it straight: it needs a router flashed with OpenWrt, so today it suits technically comfortable households. If that ever crosses into Wirecutter's territory, we'll get you a unit to run through your usual process.
```

## 17. How-To Geek — Homelab desk

- **Priority:** 3
- **Form:** https://www.howtogeek.com/contact/
- **id:** `howtogeek`

```
For How-To Geek's self-hosting coverage: WifiHaven is a free, open-source, self-hosted alternative to the Circle- and Bark-style subscriptions, running on an OpenWrt router. Per-child profiles, bedtime and school-hours schedules, daily and per-app time limits, category blocking and a pause button, applied to every device a child uses. The mechanism is worth a line in any writeup — it blocks at the connection layer with nftables rather than filtering DNS, so it isn't undone by a browser turning on encrypted DNS. Full release below, and we're around if the setup raises questions.
```

## 18. Jupiter Broadcasting (Linux Unplugged) — Chris Fisher / Alex Kretzschmar

- **Priority:** 3
- **Form:** https://www.jupiterbroadcasting.com/contact/
- **id:** `jupiterbroadcasting`

```
Possible Linux Unplugged segment: WifiHaven is open-source parental controls and screen time that run on your own OpenWrt router, free to self-host, with an optional hosted tier. The design decision worth arguing about on air is that DNS is deliberately not the enforcement plane: blocking is an nftables forward-drop, and DNS only attributes destination IPs to hostnames, which is what lets it survive DoH. It's also a straightforward open-core setup, which the r/selfhosted crowd tends to have opinions about. We're happy to come on and be questioned about either.
```

## 19. OMG Ubuntu — Joey Sneddon

- **Priority:** 3
- **Form:** https://www.omgubuntu.co.uk/tip
- **id:** `omgubuntu`

```
Sending this as a FOSS launch note: WifiHaven, an open-source parental-control and screen-time system for OpenWrt routers, has opened its beta. The whole stack is open, and self-hosting it stays free indefinitely, with a hosted tier for people who'd rather not run the server themselves. The interesting technical detail for a Linux audience is that enforcement is nftables on the gateway rather than DNS filtering, so it holds when a device switches on encrypted DNS. Release below if it's worth a news brief.
```

## 20. TechRadar Pro (security desk) — Security desk

- **Priority:** 3
- **Form:** https://www.futureplc.com/contact/
- **id:** `techradarpro_security`

```
You covered the OpenWrt One launch, and WifiHaven fits right alongside it: family screen-time and content policy enforced as nftables drops on an OpenWrt gateway, open source and free to self-host. The security-relevant part is that it doesn't rely on DNS. DNS resolves normally and the drop happens on the connection, so encrypted DNS doesn't bypass it, and destinations that can't be attributed to a hostname the router resolved for that device can be dropped outright. Release below; happy to answer technical questions.
```

## 21. Tom's Hardware — Brandon Hill

- **Priority:** 3
- **Form:** https://www.tomshardware.com/about-us
- **id:** `tomshardware`

```
Tom's Hardware covers router firmware closer than most outlets, so here's the pitch straight: WifiHaven is open-source parental controls and screen-time limits that run as an agent on a router flashed with OpenWrt, not as an app on each device. Enforcement is an nftables drop at the gateway, so it isn't the DNS filtering most vendor firmware ships. Reference hardware is the GL.iNet Flint 2, with the Flint at around $80 as the entry point. Free to self-host, hosted tier at $10/month at list price, and we can get you a Flint 2 configured if you want to put it through its paces.
```

---

## LinkedIn — founder post (operator posts this personally)

Not a submission. This goes out from Sameer's own LinkedIn account, in his own voice,
which is why it is first person singular where the pitches are plural.

**Timing:** post it AFTER `wifihaven.net/press` is live and after the Priority 1 forms are
in, so a journalist who sees it can already read the release. Not before — the post links
to a page that only exists once the PR merges.

**Deliberately names no competitor.** The opener is drawn from a real experience (two
router-based screen-time products that disappointed) but names neither and attributes no
specific technical failure to either. The failure modes in the second paragraph are stated
as properties of two general APPROACHES, not of any product. An earlier draft placed "two
routers" and "two approaches" back to back, which invited a reader to resolve them as one
claim about those specific products; that echo was removed. A reader who owns one of them
should be able to read this without feeling got at.

Went through an independent copy-review pass. Post as plain text — LinkedIn does not render
markdown, and the three links are meant to be bare on their own lines.

```
I bought two different routers that promise to handle screen time for a family. Neither gave me what I wanted, so I dug into how these tools actually work under the hood. That became WifiHaven. The beta opens today.

Screen-time tools on the market mostly take one of two approaches, and kids get around both. The apps you install on a child's phone stop working the moment the app is deleted or the phone is reset. Filtering that works through DNS stops being consulted the moment a browser turns on encrypted DNS, which every major browser and phone OS now ships.

WifiHaven enforces a layer lower down. It runs on the router the family already owns and drops disallowed connections at the gateway with nftables. DNS still resolves normally, the lookup succeeds, the answer comes back. The connection to that address just never leaves the house. Nothing is installed on the child's device, so there is nothing to delete and nothing to reset around.

On top of that: per-child profiles that follow every device a kid uses, bedtime and school-hours schedules, daily and per-app time limits, category blocking, and a pause button.

The whole stack is open source and free to self-host, permanently. That is a real commitment. For families who would rather not run a server there is a hosted tier, free during the beta with no card.

The beta is invite-based and I read every request myself. If you already run Pi-hole or Home Assistant at home, this is built for you right now.

Request access: app.wifihaven.net/beta
The release: wifihaven.net/press
Source: github.com/wifihaven/wifihaven
```
