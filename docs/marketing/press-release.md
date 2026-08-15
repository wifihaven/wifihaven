# Press release (draft)

> Draft for review. Bracketed items need operator input before send:
> [DATE], [FOUNDER NAME], [BETA SIGNUP URL], [PRESS KIT URL].
>
> **Operator decisions folded in on 2026-08-15:** the dateline carries no city (this is
> an internet product, not local news); the launch date is the announced send date,
> Monday August 17, 2026; the founder quote is the operator's own words, lightly edited,
> and still needs a final read before it goes out.
>
> **This is the authored source of truth (human-readable, with the fact-check ledger
> below).** The MACHINE-SENDABLE copy the #2233 press-outreach tool actually emails lives at
> [`api/resources/press/release.md`](../../api/resources/press/release.md) — same prose, stripped of
> this note + the ledger, with `{{date}}` / `{{founderName}}` / `{{betaSignupUrl}}` /
> `{{pressKitUrl}}` fill tokens the operator supplies at send time. `PressReleaseSyncSpec` fails CI if the two drift.
> The send path is operator-gated and dry-run-by-default; see the
> [send runbook](press-outreach-runbook.md).

---

FOR IMMEDIATE RELEASE

## WifiHaven opens the beta of open-source parental controls that run on the family's own router — nothing to install on a kid's phone, free to self-host forever

**Invite-only cloud beta opens to founding households; the self-hosted version stays free forever**

[DATE] — WifiHaven today opened the beta of its open-source, whole-home
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
said [FOUNDER NAME] of WifiHaven.

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

Beta access: [BETA SIGNUP URL]. Source code, documentation and a self-hosting
quickstart: github.com/wifihaven/wifihaven. Press kit: [PRESS KIT URL].

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
| Media contact is `press@wifihaven.net` | **CHANGED** from the operator's personal address. `press@` is the monitored inbox that routes to the #2203 responder and matches the outreach Reply-To, so a journalist replying to the release lands in the same place as one replying to a pitch. **Operator to confirm.** | needs sign-off |

### Open items for the operator

1. **[PRESS KIT URL]** is now `https://wifihaven.net/press` — this PR adds
   `web-marketing/site/press/index.html`, which carries quick facts, brand assets and
   this release, and is linked from every page footer. It publishes when the PR merges
   (Cloudflare Pages deploys `web-marketing/**` on push to `main`).
2. **[BETA SIGNUP URL]** is live at `https://app.wifihaven.net/beta` (linked from the
   marketing site). Supply it at send time.
3. **[FOUNDER NAME]** — not invented here. Supply it in the send request's `fill` map.
   The published press page carries no placeholder: it attributes the quote to
   "WifiHaven's founder", which is true and needs no input, so the page can ship and
   the marketing pipeline stays green. Swap in the name whenever you like — the
   marketing CD pipeline **fails the deploy** while any `[PLACEHOLDER]` remains in the
   published site, so an unfilled slot can never go live.
5. **[DATE]** — `August 17, 2026` for the launch send. It stays a token deliberately:
   it is the one field whose correctness is time-dependent, so a send that slips a day
   is stopped by the unresolved-token guard instead of carrying a stale dateline.
4. **The founder quote** is the operator's own sentence, lightly edited. Final read before send.
