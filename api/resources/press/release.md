# WifiHaven press release — sendable body (#2233).
#
# This is the machine-sendable release the PressOutreach composer pastes below each
# personalized pitch. It is DERIVED from docs/marketing/press-release.md (the authored
# source of truth, which also carries the internal fact-check ledger) — keep the two in
# sync when the copy changes. PressReleaseSyncSpec fails CI if they drift.
#
# Fill tokens (supplied in the send/preview request's `fill` map; a send REFUSES while any
# remain unresolved): founderName, betaSignupUrl, pressKitUrl.
#
# The dateline carries NO city (operator decision 2026-08-15 — the product is internet-wide,
# not local news) and the launch date is the announced send date, not a token.
# Lines beginning with '#' above the '---' marker are stripped before send.
---
FOR IMMEDIATE RELEASE

WifiHaven opens the beta of open-source parental controls that run on the family's own router — nothing to install on a kid's phone, free to self-host forever

Invite-only cloud beta opens to founding households; the self-hosted version stays free forever

August 17, 2026 — WifiHaven today opened the beta of its open-source, whole-home parental control and screen-time system, built to run on the router a family already owns. Unlike the app-per-device suites and DNS filters that dominate the category, WifiHaven enforces rules at the network connection layer on OpenWrt routers, so there is nothing to install on a child's phone, nothing to delete, and no DNS workaround that gets past it.

Today's parental controls fall into two camps, and kids know how to beat both. Per-device apps such as Bark, Qustodio and Net Nanny stop working the moment a child deletes the app, factory-resets the phone, or borrows a friend's device. DNS-based filters, including most router vendors' built-in controls, are bypassed by the encrypted-DNS setting now built into every major browser and phone OS. WifiHaven takes a third approach: it drops disallowed connections at the home's gateway using the Linux kernel's nftables firewall. DNS still resolves normally, so the lookup succeeds and the answer comes back, but the connection to that address never leaves the house. Destinations the router cannot attribute to an approved hostname can be dropped outright, which closes the encrypted-DNS and hard-coded-IP routes around it.

On top of that enforcement layer, WifiHaven gives parents per-child profiles that follow every device a child uses: bedtime and school-hours schedules, daily time limits, per-app limits, category blocklists, and one-tap pause, all evaluated centrally and applied across the whole network at once.

Free forever if you run it yourself. WifiHaven's entire stack is open source. Families comfortable running a small server can self-host all of it at no cost, permanently. That is an explicit commitment from the company, following the model of projects like Home Assistant and Tailscale.

Cloud beta for everyone else. For families who want the same control without running a server, WifiHaven is opening a hosted tier to a founding cohort. The beta is invite-based: a household requests access, each request is reviewed by hand, and approved households get an invite link. It is free and takes no credit card. Once 25 active households are in, a 60-day countdown to general pricing begins and runs to the end; every household is shown its cohort's date when it signs up. General pricing is $10/month or $96/year per household, covering unlimited profiles and devices on one router. Households that join during the beta keep a founding price of $6/month or $57/year for as long as they stay subscribed.

"We went looking for something that would work for our own family, and nothing on the market gave us the coverage or the peace of mind we wanted. So we built it," said {{founderName}} of WifiHaven.

WifiHaven runs on a router flashed with vanilla OpenWrt, the open-source router firmware supported on hundreds of consumer models. Vendor stock firmware is not a supported target, including the OpenWrt-derived firmware some router makers ship, so flashing is part of the setup. The documented lineup starts with the GL.iNet Flint (GL-AX1800, around $80) and treats the Flint 2 (GL-MT6000, around $150) as reference hardware. The company is deliberately starting with the technical-family audience, the households already running Pi-hole or Home Assistant, before broadening router support.

One router per household is the plan limit for the beta rather than a limit of the software. Multi-router support is already built, and the cap is expected to rise as paid tiers roll out.

Beta access: {{betaSignupUrl}}. Source code, documentation and a self-hosting quickstart: github.com/wifihaven/wifihaven. Press kit: {{pressKitUrl}}.

About WifiHaven

WifiHaven is an open-source, router-level parental control and screen-time system for families. It enforces per-child schedules, time limits and content rules at the network gateway on OpenWrt routers, with a free self-hosted option and a hosted cloud tier. More at wifihaven.net.

Media contact: press@wifihaven.net
