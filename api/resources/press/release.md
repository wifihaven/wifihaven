# WifiHaven press release — sendable body (#2233).
#
# This is the machine-sendable release the PressOutreach composer pastes below each
# personalized pitch. It is DERIVED from docs/marketing/press-release.md (the authored
# source of truth, which also carries the internal fact-check ledger) — keep the two in
# sync when the copy changes. This file is stripped of the internal ledger/notes and uses
# {{placeholder}} tokens the operator fills at send time (never a bundled hard-coded date).
#
# Fill tokens (supplied in the send/preview request's `fill` map; a send REFUSES while any
# remain unresolved): city, date, founderName, founderQuote, betaSignupUrl, pressKitUrl.
# Lines beginning with '#' above the '---' marker are stripped before send.
---
FOR IMMEDIATE RELEASE

WifiHaven launches open-source parental controls that live in the router — no apps on kids' devices, free to self-host forever

Cloud-hosted beta now open to 25 founding households; self-hosted version remains free forever

{{city}} — {{date}} — WifiHaven today opened the beta of its open-source, whole-home parental control and screen-time system, built to run on the router a family already owns. Unlike the app-per-device suites and DNS filters that dominate the category, WifiHaven enforces rules at the network connection layer on OpenWRT routers — so there is nothing to install on a child's phone, nothing to delete, and no DNS workaround that gets past it.

Today's parental controls fall into two camps, and kids know how to beat both. Per-device apps (Bark, Qustodio, Net Nanny) stop working the moment a child deletes the app, factory-resets the phone, or borrows a friend's device. DNS-based filters (including most router vendors' built-in controls) are bypassed by the encrypted-DNS setting now built into every major browser and phone OS. WifiHaven takes a third approach: it drops disallowed connections at the home's gateway using the Linux kernel's nftables firewall. DNS still resolves normally — but blocked traffic simply never leaves the house, and destinations that can't be attributed to an approved hostname can be dropped outright, closing the encrypted-DNS and hard-coded-IP loopholes.

On top of that enforcement layer, WifiHaven gives parents per-child profiles that follow every device a child uses: bedtime and school-hours schedules, daily time limits, per-app limits, category blocklists, and one-tap pause — evaluated centrally and applied to the whole network at once.

Free forever if you run it yourself. WifiHaven's entire stack is open source. Families comfortable running a small server can self-host the whole system at no cost, permanently — a commitment the company is making explicitly, following the model of projects like Home Assistant and Tailscale.

Cloud beta for everyone else. For families who want the same control without running a server, WifiHaven is opening its hosted cloud tier to a founding cohort of 25 households. The beta is free, with no credit card required. General availability begins two months after the beta cohort fills, at $10/month or $96/year per household — covering unlimited profiles and devices on one router. Founding beta households keep a lifetime price of $6/month (or $57/year) for as long as they stay subscribed.

"{{founderQuote}}" said {{founderName}}, creator of WifiHaven.

WifiHaven requires a router running OpenWRT, the open-source router firmware that runs on hundreds of consumer models starting well under $100. The company is deliberately starting with the technical-family audience — the same households that run Pi-hole or Home Assistant today — before expanding router support.

The 25-household beta is open now at {{betaSignupUrl}}. Source code, documentation, and a self-hosting quickstart are at github.com/wifihaven/wifihaven. Press kit: {{pressKitUrl}}.

About WifiHaven

WifiHaven is an open-source, router-level parental control and screen-time system for families. It enforces per-child schedules, time limits, and content rules at the network gateway on OpenWRT routers, with a free self-hosted option and a hosted cloud tier. Learn more at wifihaven.net.

Media contact: Sameer, sameer@creativedestruction.com
