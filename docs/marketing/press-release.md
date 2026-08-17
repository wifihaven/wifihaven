# Press release — WifiHaven beta launch

> **Final copy.** Every operator slot is filled: the dateline is Monday 2026-08-17, the
> founder is Sameer Brenn, the beta link is `https://app.wifihaven.net/beta`, and the
> press kit is `https://wifihaven.net/press`. There are no placeholders left, which is
> deliberate — this release is DISTRIBUTED BY HAND via each outlet's contact form (see
> [`press-submission-pack.md`](press-submission-pack.md)), so there is no send-time fill
> step to catch an unfilled one.
>
> **Two copies, kept in sync by CI.** This is the authored source of truth, carrying the
> fact-check ledger below. The PUBLISHED copy is
> [`web-marketing/site/press/index.html`](../../web-marketing/site/press/index.html),
> live at `https://wifihaven.net/press`. `PressReleaseSyncSpec` fails CI if the prose
> drifts between them.
>
> The API's press-outreach EMAIL sender was removed on 2026-08-15 (operator decision):
> no target has a publishable address, so every outlet is a form submission. The #2203
> press RESPONDER is untouched and still handles inbound press mail.

---

FOR IMMEDIATE RELEASE

## WifiHaven opens the beta of open-source parental controls that run on the family's own router — nothing to install on a kid's phone, free to self-host forever

**Invite-only cloud beta opens to founding households; the self-hosted version stays free forever**

August 17, 2026 — WifiHaven today opened the beta of its open-source, whole-home
parental control and screen-time system, built to run on the router a family
already owns. Unlike the app-per-device suites and DNS filters that dominate the
category, WifiHaven enforces rules at the network connection layer on OpenWrt
routers, so there is nothing to install on a child's phone, nothing to delete,
and no DNS workaround that gets past it.

Today's parental controls fall into two camps, and kids know how to beat both.
Per-device apps such as Bark, Qustodio and Net Nanny stop working the moment a
child deletes the app, factory-resets the phone, or borrows a friend's device.
DNS-based filters, including most router vendors' built-in controls, are bypassed
by the encrypted-DNS setting now built into every major browser and phone OS.
WifiHaven takes a third approach: it drops disallowed connections at the home's
gateway using the Linux kernel's nftables firewall. DNS still resolves normally,
so the lookup succeeds and the answer comes back, but the connection to that
address never leaves the house. Destinations the router cannot attribute to an
approved hostname can be dropped outright, which closes the encrypted-DNS and
hard-coded-IP routes around it.

On top of that enforcement layer, WifiHaven gives parents per-child profiles that
follow every device a child uses: bedtime and school-hours schedules, daily time
limits, per-app limits, category blocklists, and one-tap pause, all evaluated
centrally and applied across the whole network at once.

**Free forever if you run it yourself.** WifiHaven's entire stack is open source.
Families comfortable running a small server can self-host all of it at no cost,
permanently. That is an explicit commitment from the company, following the model
of projects like Home Assistant and Tailscale.

**Cloud beta for everyone else.** For families who want the same control without
running a server, WifiHaven is opening a hosted tier to a founding cohort. The
beta is invite-based: a household requests access, each request is reviewed by
hand, and approved households get an invite link. It is free and takes no credit
card. Once 25 active households are in, a 60-day countdown to general pricing
begins and runs to the end. Households see that end date in their dashboard as
soon as the countdown starts, and are told before anything changes. General
pricing is $10/month or $96/year per household, covering unlimited profiles and
devices on one router. Households that join during the
beta keep a founding price of $6/month or $57/year for as long as they stay
subscribed.

"We went looking for something that would work for our own family, and nothing on
the market gave us the coverage or the peace of mind we wanted. So we built it,"
said Sameer Brenn of WifiHaven.

WifiHaven runs on a router flashed with vanilla OpenWrt, the open-source router
firmware supported on hundreds of consumer models. Vendor stock firmware is not a
supported target, including the OpenWrt-derived firmware some router makers ship,
so flashing is part of the setup. The documented lineup starts with the GL.iNet
Flint (GL-AX1800, around $80) and treats the Flint 2 (GL-MT6000, around $150) as
reference hardware. The company is deliberately starting with the
technical-family audience, the households already running Pi-hole or Home
Assistant, before broadening router support.

One router per household is the plan limit for the beta rather than a limit of
the software. Multi-router support is already built, and the cap is expected to
rise as paid tiers roll out.

Beta access: app.wifihaven.net/beta. Source code, documentation and a self-hosting
quickstart: github.com/wifihaven/wifihaven. Press kit: wifihaven.net/press.

### About WifiHaven

WifiHaven is an open-source, router-level parental control and screen-time system
for families. It enforces per-child schedules, time limits and content rules at
the network gateway on OpenWrt routers, with a free self-hosted option and a
hosted cloud tier. More at wifihaven.net.

**Media contact:** press@wifihaven.net

---

## Fact-check ledger (internal — do not send)

Every claim below was re-verified against the code/config on 2026-08-15 for the #2233
staging pass (`docs/process/verify-and-cite.md`). Where the previous draft asserted
something the product does not do, the correction is called out.

| Claim in release | Source | Status |
|---|---|---|
| Connection-layer nftables enforcement; DNS always resolves | `AGENTS.md` architectural model §1 | verified |
| Unattributable destinations can be dropped (DoH / hard-coded IP) | `AGENTS.md` `blockIpOnly` field | verified |
| $10/month or $96/year, unlimited profiles + devices, 1 router | `docs/design/pricing-analysis.md` §1 | verified |
| $6/month or $57/year founding price, for as long as they stay subscribed | `docs/design/pricing-analysis.md` §1 (Stripe coupon `duration=forever`) | verified |
| Beta is free, no credit card | `docs/design/pricing-analysis.md` §5.2; marketing site pricing section | verified |
| Beta is invite-based (request → hand review → invite link) | `BetaConfig.inviteUrl` (`api/src/Config.scala`); `web-marketing/site/index.html` | verified |
| "Once 25 active households are in, a 60-day countdown begins and runs to the end" | `FlipConfig` defaults — `thresholdHouseholds = 25`, `windowDays = 60`; `FlipService` starts the clock at the threshold and the window is measured from the persisted start, so a later dip never resets it (#2137) | verified |
| ~~"a founding cohort of 25 households" / "the 25-household beta"~~ | **CORRECTED.** 25 is the flip TRIGGER, not an enforced signup cap — nothing in `FlipService` or the beta-request path rejects household 26, and the founding price is reserved for beta households generally, not the first 25. The old wording promised a cap the product does not enforce. | corrected |
| ~~"General availability begins two months after the beta cohort fills"~~ | **SHARPENED** to the mechanism above: the clock starts at 25 active households and latches for 60 days. | corrected |
| ~~"every household is shown its cohort's date when it signs up"~~ | **CORRECTED.** `FlipService.windowOf` returns no flip date while `beta_cohort.clock_started_at` is null, and `BillingPage.tsx` renders the date only when non-null — so households 1–24, the whole founding cohort on launch day, see no date at signup. Restated to when the date actually appears. | corrected |
| Self-hosted free forever, stated explicitly | `docs/design/pricing-analysis.md` §1, §3; marketing site | verified |
| Vanilla OpenWrt only; vendor stock firmware unsupported | #2334 (beta hardware validation: stock GL.iNet cannot install — #2363/#2304) and #2364 (per-router flash guides; "the supported path is flashed vanilla OpenWrt") | verified |
| Flint (GL-AX1800) ~$80 low tier; Flint 2 (GL-MT6000) ~$150 reference | `README.md` hardware lineup; `web/src/pages/RouterInstallPage.tsx` | verified |
| One router is a PLAN limit, not a technical ceiling | #2499; `households.router_cap INT NOT NULL DEFAULT 1` (V66) with the cap raisable per household | verified |
| Media contact is `press@wifihaven.net` | **CHANGED** from the operator's personal address. `press@` is the monitored inbox that routes to the #2203 responder, validated end-to-end in prod by #2527, and it is the address every form submission gives — so a journalist replying to the release lands where one replying to a submission does. **Approved by the operator 2026-08-16.** | approved |
| Founder named as Sameer Brenn | Operator, 2026-08-15. Not derivable from the code — the one claim in the release with no in-repo source, which is why it gets a row of its own. | operator-supplied |
| The founder quote | Operator's own sentence, lightly edited for length. Operator-supplied, so not verifiable here — **read and approved by the operator 2026-08-16**, which is the only check this claim can get. | approved |
| Dateline August 17, 2026 | The announced launch date. Now literal in both copies rather than a fill token, because the hand-submission path has no send-time fill step to resolve one. If the launch slips, edit BOTH copies — `PressReleaseSyncSpec` fails if only one is changed. | operator-supplied |

### Open items

None outstanding. The copy has no unfilled placeholders, and the two claims that could
not be checked against the repo — the media contact address and the founder quote — were
both read and approved by the operator on 2026-08-16 (ledger rows above).

Two things to re-check on launch morning, both cheap:

1. **`https://wifihaven.net/press` renders** before the first form submission — every
   submission links to it.
2. **The dateline** says August 17, 2026. If the launch slips, it has to be changed in
   BOTH copies; `PressReleaseSyncSpec` will fail CI if only one is edited, which is the
   guard that replaced the send-time unresolved-token refusal.
