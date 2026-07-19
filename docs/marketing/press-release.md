# Press release (draft)

> Draft for review. Bracketed items need operator input before send:
> [CITY], [FOUNDER NAME], [QUOTE APPROVAL], [BETA SIGNUP URL], [PRESS KIT URL].

---

FOR IMMEDIATE RELEASE

## WifiHaven launches open-source parental controls that live in the router — no apps on kids' devices, free to self-host forever

**Cloud-hosted beta now open to 25 founding households; self-hosted version
remains free forever**

[CITY] — [DATE] — WifiHaven today released its open-source, whole-home
parental control and screen-time system, built to run on the router a family
already owns. Unlike the app-per-device suites and DNS filters that dominate
the category, WifiHaven enforces rules at the network connection layer on
OpenWRT routers — so there is nothing to install on a child's phone, nothing
to delete, and no DNS workaround that gets past it.

Today's parental controls fall into two camps, and kids know how to beat both.
Per-device apps (Bark, Qustodio, Net Nanny) stop working the moment a child
deletes the app, factory-resets the phone, or borrows a friend's device.
DNS-based filters (including most router vendors' built-in controls) are
bypassed by the encrypted-DNS setting now built into every major browser and
phone OS. WifiHaven takes a third approach: it drops disallowed connections at
the home's gateway using the Linux kernel's nftables firewall. DNS still
resolves normally — but blocked traffic simply never leaves the house, and
destinations that can't be attributed to an approved hostname can be dropped
outright, closing the encrypted-DNS and hard-coded-IP loopholes.

On top of that enforcement layer, WifiHaven gives parents per-child profiles
that follow every device a child uses: bedtime and school-hours schedules,
daily time limits, per-app limits, category blocklists, and one-tap pause —
evaluated centrally and applied to the whole network at once.

**Free forever if you run it yourself.** WifiHaven's entire stack is open
source. Families comfortable running a small server can self-host the whole
system at no cost, permanently — a commitment the company is making
explicitly, following the model of projects like Home Assistant and Tailscale.

**Cloud beta for everyone else.** For families who want the same control
without running a server, WifiHaven is opening its hosted cloud tier to a
founding cohort of 25 households. The beta is free, with no credit card
required. General availability begins two months after the beta cohort fills,
at $10/month or $96/year per household — covering unlimited profiles and
devices on one router. Founding beta households keep a lifetime price of
$6/month for as long as they stay subscribed.

"[Placeholder quote — suggested draft:] We built WifiHaven because every
parental control we tried was either an app our kids could delete or a DNS
trick their browsers quietly walked around. The router is the one place in
the house every packet has to pass through — that's where enforcement
belongs. And because most families shouldn't have to trust a black box with
their home network, the whole thing is open source and free to run yourself,"
said [FOUNDER NAME], creator of WifiHaven. [QUOTE APPROVAL]

WifiHaven requires a router running OpenWRT, the open-source router firmware
that runs on hundreds of consumer models starting around $30. The company is
deliberately starting with the technical-family audience — the same households
that run Pi-hole or Home Assistant today — before expanding router support.

The 25-household beta is open now at [BETA SIGNUP URL]. Source code,
documentation, and a self-hosting quickstart are at
github.com/wifihaven/wifihaven. Press kit: [PRESS KIT URL].

### About WifiHaven

WifiHaven is an open-source, router-level parental control and screen-time
system for families. It enforces per-child schedules, time limits, and
content rules at the network gateway on OpenWRT routers, with a free
self-hosted option and a hosted cloud tier. Learn more at wifihaven.net.

**Media contact:** Sameer, sameer@creativedestruction.com

---

## Fact-check ledger (internal — do not send)

| Claim in release | Source |
|---|---|
| Connection-layer nftables enforcement, DNS always resolves | AGENTS.md architectural model §1 |
| blockIpOnly closes DoH / hard-coded-IP bypass | AGENTS.md `blockIpOnly` field; pricing doc §3.1 |
| $10/mo, $96/yr, unlimited profiles/devices, 1 router | pricing doc §1 |
| $6/mo founding price, forever | pricing doc §1 (Stripe coupon duration=forever — verify basil caveat §7 before GA) |
| No card during beta | pricing doc §5.2 |
| Beta = 25 households; GA = fill + 2 months | operator instruction 2026-07-12 (supersedes pricing doc's 4-month term) |
| Self-hosted free forever, stated explicitly | pricing doc §1, §3 |
| OpenWRT routers from ~$30 | verify a current example model/price before send (e.g. GL.iNet entry models) — UNVERIFIED. If naming GL.iNet: the agent is validated on **flashed vanilla OpenWRT** only; GL.iNet **stock** firmware is not yet verified ([#2304](https://github.com/wifihaven/wifihaven/issues/2304)) — don't imply it works out of the box. |
| Per-device apps defeated by deletion/reset; DNS filters bypassed by DoH | pricing doc §3.1; general claim, safe |
