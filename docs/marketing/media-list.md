# Media outreach list — WifiHaven launch

Status: researched 2026-07-12. Companion to
[`press-release.md`](press-release.md) and [`launch-plan.md`](launch-plan.md).
Emails appear ONLY where actually published; nothing fabricated. Items marked
UNVERIFIED need a check before sending.

> **Machine-readable send manifest (#2233):** the operator-run press-outreach tool iterates
> [`api/resources/press/media-contacts.yml`](../../api/resources/press/media-contacts.yml) — a
> transcription of THIS table (id, outlet, person, priority, angle, contactUrl). Keep the two in
> sync. Per the fabrication rule below, NO target has a verified direct email as of the 2026-07-12
> pass — every outlet is form/tip-line only, so the tool's dry-run reports them all as "manual
> submission" and a real email send reaches nobody until the operator supplies a verified address at
> send time (the send request's `emailOverrides` map — no fabricated address ever lives in-repo).
> See the [send runbook](press-outreach-runbook.md).

**Send order:** Priority 1 first (they set community tone and their coverage
gives P2 outlets a reason to care), Show HN same day, Priority 2 with a
1-week exclusive offer to one outlet if desired, Priority 3 as a follow-up
wave.

## Priority 1 — Core audience (self-hosting / OpenWRT / homelab)

| # | Outlet | Person | Why (relevant coverage) | Contact | Pitch angle |
|---|--------|--------|-------------------------|---------|-------------|
| 1 | selfh.st / Self-Host Weekly | Ethan Sholly | Weekly self-hosted news roundup featuring new launches ([example](https://selfh.st/weekly/2025-10-24/)) | [selfh.st/about](https://selfh.st/about/) contact route; @shollyethan on Fosstodon | New open-source self-hosted parental controls enforced at the router, not DNS — exactly his beat |
| 2 | Hackaday | Maya Posch | "[Revisiting Making Your Own Internet Router in 2026](https://hackaday.com/2026/06/01/revisiting-making-your-own-internet-router-in-2026/)" (Jun 2026); active [openwrt tag](https://hackaday.com/tag/openwrt/) | Tip line: https://hackaday.com/submit-a-tip/ | The nftables forward-drop + dnsmasq ipset attribution mechanism as a hack story, not a product pitch |
| 3 | XDA (self-hosting) | Adam Conway (Lead Technical Editor) | [Self-Hosting section](https://www.xda-developers.com/self-hosting/); "[5 reasons to try OpenWrt](https://www.xda-developers.com/reasons-try-openwrt-dd-wrt-router/)"; Pi-hole self-hosting pieces | [Author page](https://www.xda-developers.com/author/adamconway-xda/) / XDA contact form | "The OpenWRT killer app for parents"; XDA loves 'replaced X subscription with self-hosted Y' |
| 4 | The Register (FOSS desk) | Liam Proven | FOSS/OS correspondent; covers OpenWRT releases ([author page](https://www.theregister.com/Author/Liam-Proven/)) | Per-author mail form: https://www.theregister.com/Author/Email/Liam-Proven | Open-source challenger to proprietary parental-control appliances |
| 5 | Lawrence Systems (YouTube ~1M) | Tom Lawrence | pfSense/firewall/network-filtering content; explains DNS-filter bypassability | https://lawrencesystems.com/hire-us/ | Connection-layer enforcement vs DNS filtering — a natural video |
| 6 | ServeTheHome | Patrick Kennedy (EIC) | Networking + open-source for home/SMB | https://www.servethehome.com/contact/ (no published email) | Family policy enforcement on hardware you already own |
| 7 | Techno Tim (YouTube) | Tim Stewart | Homelab/self-hosting tutorials ([technotim.com](https://technotim.com/)) | https://links.technotim.com/ business links | Tutorial: "self-hosted screen time for your whole network in 30 minutes" |
| 8 | Wolfgang's Channel (YouTube) | Wolfgang (notthebee) | Privacy-focused self-hosting, DIY routers ([notthebe.ee](https://notthebe.ee/)) | Business email on YouTube About page (login-gated, UNVERIFIED); https://linktr.ee/wolfgangschannel | Parental controls without shipping kids' browsing history to a cloud vendor |
| 9 | It's FOSS | News desk (Abhishek Prakash, ed.) | "[Self-Hosting Starter Pack](https://itsfoss.com/self-hosting-starting-projects/)" style roundups | https://itsfoss.com/community-submission/ | FOSS project announcement; Pi-hole/AdGuard Home comparison framing |

## Priority 2 — Mainstream tech review press (competitor reviewers)

| # | Outlet | Person | Why | Contact | Pitch angle |
|---|--------|--------|-----|---------|-------------|
| 10 | Tom's Guide | **Brian Nadel** (verified author of the [Gryphon Secure Mesh Router review](https://www.tomsguide.com/us/gryphon-secure-mesh-router,review-6042.html); still their active router freelancer) | Reviewed Gryphon directly | Not published — Tom's Guide editorial (Future plc) or Muck Rack profile | "You reviewed the $430 box that does this — here's the open-source version for the router people already own" |
| 11 | TechRadar Pro | Mike Williams (UNVERIFIED byline) | [Firewalla Gold](https://www.techradar.com/reviews/firewalla) / [Gold Pro](https://www.techradar.com/pro/firewalla-gold-pro-review) reviews | https://www.futureplc.com/contact/ | Firewalla-class network control without the $500 appliance |
| 12 | PCMag | Kim Key (senior security analyst; parental-control beat verified, current-roundup byline UNVERIFIED) | PCMag "Best Parental Control" franchise | pcmag.com/about/contact-us | A parental-control system that can't be uninstalled because there's nothing on the device |
| 13 | Wirecutter (NYT) | Joel Santo Domingo (senior staff writer, networking; 250+ routers tested) | Best Wi-Fi Router guides; [router AMA Sep 2025](https://x.com/wirecutter/status/1967667280306733373) | Wirecutter contact/feedback form or Muck Rack | Longer shot; the paid cloud tier makes it reviewable as a product |
| 14 | Dong Knows Tech | Dong Ngo (ex-CNET, independent) | "[Firewalla Gold Review](https://dongknows.com/firewalla-gold-review/)" — exactly this category, very hands-on | Contact form on dongknows.com | Independent deep review of router-level family control |
| 15 | NetworkChuck (YouTube 4M+) | Chuck Keith | Pi-hole videos with millions of views; home-network security | Circulating emails UNVERIFIED — use https://store.networkchuck.com/pages/contact-us or YouTube About | "I put parental controls INSIDE my router" mass-reach video |
| 16 | Ars Technica | Kevin Purdy (self-hosting/Home Assistant beat) or Lee Hutchinson (senior tech editor) | Ars covers Home Assistant/self-hosted infra regularly | arstechnica.com/contact-us/ | The Nabu Casa open-core parallel + DoH-bypass technical depth |

## Priority 3 — Secondary

| # | Outlet | Person | Why | Contact | Pitch angle |
|---|--------|--------|-----|---------|-------------|
| 17 | Tom's Hardware | Brandon Hill (UNVERIFIED as router lead) | [Router reviews section](https://www.tomshardware.com/networking/routers/reviews) | Future plc tips form | Router-firmware angle |
| 18 | How-To Geek | Homelab desk (no writer verified) | Frequent self-hosting/Pi-hole listicles | howtogeek.com contact form | "Self-hosted alternative to Circle/Bark" listicle placement |
| 19 | OMG Ubuntu | Joey Sneddon | Linux/FOSS project news | https://www.omgubuntu.co.uk/tip (contact@omgubuntu.co.uk seen in search snippet — verify on page before use) | FOSS launch news brief |
| 20 | Jupiter Broadcasting | Chris Fisher | **Self-Hosted podcast ended at ep. 150 (May 2025)** — pitch Linux Unplugged instead; co-host Alex Kretzschmar (LinuxServer.io) still influential in r/selfhosted | jupiterbroadcasting.com contact page | Linux Unplugged segment |
| 21 | TechRadar Pro (security desk) | — | Covered the [OpenWrt One launch](https://www.techradar.com/pro/security/openwrt-debuts-affordable-hacker-friendly-security-focused-wireless-router-with-the-promise-that-it-will-never-be-locked-and-will-forever-be-unbrickable) (2024) | Future plc form | "The OpenWrt ecosystem now has a family-safety layer" |

## Community channels (not email — post directly)

| Channel | Notes |
|---------|-------|
| Show HN | Title: "Show HN: WifiHaven – open-source parental controls enforced by nftables on OpenWRT". Lead top comment with architecture (DNS never blocks; connection-layer drop; DoH resistance) and the open-core model — HN probes both. Tue–Thu ~8–9am ET; founder present all day. |
| r/selfhosted | Launch post; emphasize the self-hosted tier is complete, not crippleware — this sub is hostile to open-core bait-and-switch. Engage in comments. |
| r/openwrt | Frame as a package/agent with install instructions, not marketing. Technical detail (nftables sets, dnsmasq nftset callback, tmpfs discipline) earns credibility. |
| r/HomeNetworking | Low self-promotion tolerance; answer recurring "parental controls without a subscription box" threads contextually, or one transparent launch post. |
| lobste.rs | Invite-only; need a member to post. Tags `networking`, `linux`. |
| OpenWRT forum | Community Builds / Projects & Packages. Upstream community — announce early, respond to packaging feedback, consider proposing an official feed package. |
| selfh.st/apps directory | Submit to the catalog (separate from newsletter pitch). |
| Awesome-Selfhosted | PR per CONTRIBUTING.md; check age/commit-activity minimums and category taxonomy first. |
| r/Parenting / r/ScreenTime | Stretch; only after mainstream coverage lands. |

## Email send workflow (for when the operator says "send")

1. Operator confirms: press release final (brackets filled), press kit URL
   live, beta signup live, send date.
2. For each Priority 1–2 target with only a form/tip-line: submit via the
   form with a 3-sentence version of the pitch + link to release.
3. For targets where an email is verified at send time: individual,
   personalized 5–8 sentence email (never a blast; reference their specific
   article from the "Why" column), release pasted below the sig — no
   attachments.
4. From: sameer@creativedestruction.com. One follow-up after 4–5 business
   days, then stop.
5. Log each send + response in this file (add a Status column).
