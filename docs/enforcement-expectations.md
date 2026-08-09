# How enforcement takes effect (and what can bypass it)

> **The one-line version.** When you first enable a block, it is **not**
> instant. Blocking is a connection-layer drop keyed to the IPs a device
> resolves *through the router's DNS*, so a block becomes fully effective only
> after the policy reaches the router (seconds) **and** the device does a fresh
> DNS lookup for the host. Until then, cached DNS answers and already-open
> connections keep working. Several device-side settings (VPN, "Secure DNS",
> iCloud Private Relay) route around the router entirely and defeat filtering
> by design.

This surprised us during hardware validation
([#2334](https://github.com/wifihaven/wifihaven/issues/2334)): we enabled a
block and YouTube stayed reachable for a few minutes. Nothing was broken — the
warm-up below is how the system is built to work. This page sets expectations
so you don't chase a non-bug.

For the underlying design, see
[`docs/architecture.md`](architecture.md) §0 (enforcement model). Every claim
here traces to that model and the agent code it cites.

## Why a block isn't instant

WifiHaven never lies in DNS — the host resolves normally, and blocking happens
at the **connection layer**: nftables drops forwarded packets whose destination
IP is in a per-host block set. Those sets are populated **lazily, at DNS-resolve
time**, so there is a built-in warm-up.

1. **Policy propagation (seconds).** A newly-authored block reaches the router
   on its next policy poll. The default poll cadence is **5 s**
   (`policy_poll_interval`, [`openwrt/files/etc/config/wifihaven`](../openwrt/files/etc/config/wifihaven));
   on installs using the live WebSocket push path the snapshot arrives sooner
   (`ws.apply_interval`, default **2 s**).
   Either way the router now *knows* about the block, but the block set is still
   empty.

2. **Block sets fill on the device's next fresh lookup.** A host block is
   enforced via an nftables set named `eb_<host>` (`eb6_<host>` for IPv6) whose
   member IPs are added by dnsmasq's `nftset=` callback **only when a device
   resolves that host through the router's DNS**
   ([`openwrt/files/usr/lib/lua/wifihaven/render.lua`](../openwrt/files/usr/lib/lua/wifihaven/render.lua)).
   Until a device does a fresh lookup, the set has no IPs and nothing drops.

3. **Cached DNS and open connections keep working.** A device that already
   resolved the host (cached A/AAAA answer) or that has a live connection pool
   keeps using the cached IP — which isn't in the block set yet — until the
   cache expires and it re-resolves. iOS/iPadOS app caches and connection pools
   routinely outlive an hour, which is exactly why the agent runs a periodic
   re-resolve of every blocked host (`eb_refresh_interval`, default **1800 s**)
   to keep the sets populated ahead of the kernel's 1 h set timeout
   ([`eb_refresh.lua`](../openwrt/files/usr/lib/lua/wifihaven/eb_refresh.lua),
   [#1658](https://github.com/wifihaven/wifihaven/issues/1658)).

4. **Category blocklists warm up over time.** Curated-category lists (ads,
   adult, …) are fetched on a periodic cadence — `blocklist_refresh_interval`,
   default **3600 s** — and their member hosts resolve into the per-category
   `bl_<id>` sets the same lazy way. A freshly-enabled category won't be fully
   effective until its next fetch-and-resolve cycle.

### Test a block immediately

You don't have to wait for the natural warm-up. To make a block fire right now:

- **Flush the device's DNS cache**, then reload the site. A fresh resolution
  goes through the router, populates `eb_<host>`, and the next connection drops.
  (On macOS: `sudo dscacheutil -flushcache; sudo killall -HUP mDNSResponder`.
  On a browser, a full quit-and-reopen or clearing the browser's own DNS cache
  works too.)
- Prefer **HTTP** for a quick visual confirmation: a blocked `http://` request
  is DNAT'd to the local block page, so you *see* the block. A blocked
  `https://` request can't show a page cleanly (see below).

## What HTTPS blocks look like

The block **page** is served by redirecting blocked **HTTP/port-80** traffic to
a router-local `uhttpd` listener
([`render.lua` `wifihaven_block_nat`](../openwrt/files/usr/lib/lua/wifihaven/render.lua)).

For **HTTPS/port-443**, WifiHaven does **not** intercept TLS — that would mean
installing a custom CA on every device, which we won't do. On a standard install
the agent DNATs blocked 443 to a sibling TLS listener with a **self-signed**
cert (CN `block.wifihaven.local`,
[#383](https://github.com/wifihaven/wifihaven/issues/383)). In practice:

- **Browsers** show a certificate warning; clicking through lands on the same
  block page.
- **Apps** (which pin or strictly validate certs) just see a failed TLS
  handshake — the connection errors out. That *is* the block working; there's
  simply no page to render.

So "the site won't load / the app can't connect" is the expected outcome of an
HTTPS block. Only HTTP shows the explanatory page.

## What can bypass host-based blocking

These are inherent to network-level filtering, not bugs. Call them out to
anyone who reports "blocking doesn't work."

- **Device VPN / Cloudflare WARP / any full tunnel.** A full-tunnel VPN routes
  **all** traffic — including DNS — off the local network through the VPN
  provider. Nothing on the router can see or filter it. The only counter is to
  block the VPN's own endpoints. This is fundamental: if traffic doesn't
  traverse the gateway in the clear, the gateway can't filter it.

- **DoH / DoT / "Secure DNS" / iCloud Private Relay.** These bypass the
  router's resolver, so the device never asks our dnsmasq — and the `eb_<host>`
  set never gets the IP. Blocking still doesn't *fail open* if you turn on the
  backstop:
  - **`blockIpOnly`** (per-profile, and a network-wide global variant) drops
    forwarded traffic to any IP the device did **not** resolve through us. It is
    the DoH / hard-coded-IP backstop, but it is **off by default**
    (`block_ip_only … DEFAULT FALSE`,
    [`api/resources/db/migration/V17__profile_block_ip_only.sql`](../api/resources/db/migration/V17__profile_block_ip_only.sql));
    turn it on for a profile to close this hole. Note it has no allowlist
    carve-out — if we can't attribute an IP to a hostname, we can't check it
    against the allowlist (see [`architecture.md`](architecture.md) §0.2).
  - **`blockEncryptedDns`** is a separate, heavier network-wide toggle that
    (a) signals iOS to turn iCloud Private Relay *off* so its traffic becomes
    filterable and (b) drops DoT and DNS-to-public-resolvers at the connection
    layer ([architecture.md](architecture.md) §0.1, the one sanctioned
    DNS-negative-answer exception, [#1911](https://github.com/wifihaven/wifihaven/issues/1911)).
    Unlike `blockIpOnly` above, this one is **on by default for households
    created after [#2643](https://github.com/wifihaven/wifihaven/issues/2643)** —
    without it, a device can tunnel past every expectation on this page. Households
    that predate #2643 keep whatever they had; nothing was backfilled.

- **Shared CDN / front-end IPs.** When a blocked host and an allowed host share
  the same IP (common on large CDNs), carving the allowed host out of the drop
  necessarily lets the blocked host's traffic to that shared IP through — we
  match on destination IP, and the IP is the same. Tracked in
  [#2369](https://github.com/wifihaven/wifihaven/issues/2369).

## A device that is offline entirely, not partly blocked

Everything above is about a block that seems too weak. The opposite report —
"this device has no internet at all, and I never blocked it" — usually has a
different cause: the household's **unmanaged-device policy** is set to `block`,
and the device has not been assigned to a profile.

Check the **Devices** page first and find the device. If it has no profile, that
is the block; enrolling it into one clears it on the next snapshot.

Right now "has no profile" reads as a **No profile** pill on a row in the main
list — and only on a window at least 640px wide, since that column is hidden on
narrower screens — a narrow window hides it whatever the device. Widen the
window before you conclude anything; below the threshold the list looks the same
whether or not anything is unassigned. Look as an admin or an adult, too: a
`child` sees only devices attached to a profile they are linked to, so an
unassigned device is absent from their page entirely.

The **Unmanaged Devices** section that is supposed to collect these never
renders — the page compares `profileId` against `null` while the API omits the
field entirely for an unassigned device, so the check is never true
([#2622](https://github.com/wifihaven/wifihaven/pull/2622) fixes it,
[#2623](https://github.com/wifihaven/wifihaven/issues/2623) covers the class).
Do not read an absent Unmanaged section as "nothing is unassigned."

The block page such a device lands on does not name this case either
([#2610](https://github.com/wifihaven/wifihaven/issues/2610)). Until both land,
the profile column is the only reliable signal.

The policy, its default, and when to change it are documented as an onboarding
step in
[`install-openwrt.md` §4](install-openwrt.md#4-enroll-your-devices-then-block-unmanaged-ones).

## Verified constants (source of truth)

| Constant | Default | Source |
| --- | --- | --- |
| `policy_poll_interval` | 5 s | [`etc/config/wifihaven`](../openwrt/files/etc/config/wifihaven) |
| `blocklist_refresh_interval` | 3600 s | [`usr/sbin/wifihaven-agent`](../openwrt/files/usr/sbin/wifihaven-agent) |
| `eb_refresh_interval` | 1800 s | [`usr/sbin/wifihaven-agent`](../openwrt/files/usr/sbin/wifihaven-agent) |
| `block_ip_only` | `false` | [`V17__profile_block_ip_only.sql`](../api/resources/db/migration/V17__profile_block_ip_only.sql) |

---

_Found during beta hardware validation
([#2334](https://github.com/wifihaven/wifihaven/issues/2334)); documented per
[#2370](https://github.com/wifihaven/wifihaven/issues/2370)._
