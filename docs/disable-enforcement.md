# Disable enforcement / escape hatch

> **The one-line version.** WifiHaven has two ways to **turn off all blocking**
> for your household when a setting is getting in the way or you just need the
> internet to work right now: a **dashboard toggle** (easy, works from any
> device — but needs the WifiHaven server to be reachable) and an **on-router
> toggle** (works even when the server or internet is down). Turn either one on
> and every device on the network gets the open internet; turn it back off and
> filtering resumes.

Search terms this page answers: *turn off blocking*, *disable enforcement*,
*disable WifiHaven*, *bypass*, *internet not working*, *unblock everything*,
*escape hatch*, *emergency off switch*.

There are two hatches because they fail in opposite situations. Pick by whether
the **WifiHaven server (API)** is reachable:

| | Dashboard hatch (this page's first section) | On-router hatch |
| --- | --- | --- |
| Where | WifiHaven web dashboard → **Admin** | Your router: LuCI admin **or** SSH command |
| Ease | Easiest — any device, no SSH | Needs router access |
| Works when the server/API is **down**? | **No** — it is server-driven | **Yes** — it lives on the router |
| Scope | Whole household | Whole router |
| Who can change it | Household **admin** only | Anyone with router access |

Rule of thumb: **try the dashboard toggle first.** If the dashboard won't load
or the internet is fully down, use the on-router toggle.

---

## Dashboard toggle (server-level, easiest) — #2382

**Where:** WifiHaven dashboard → **Admin** → **"Turn off all blocking (escape
hatch)"**. Admin login required (only a household admin can flip it).

**What it does:** when you switch it on, the WifiHaven server ships a fully
*permissive* policy for your household — schedules, time limits, blocked sites,
category blocklists, and encrypted-DNS enforcement all stop, and every device
passes straight through. Nothing on your network is filtered while it is on.
Switch it back off and your normal policy resumes within seconds (the next
policy refresh reaches the router).

Under the hood this is the same "permissive snapshot" the system already uses to
never brick a network — the router just receives "allow everything" and applies
it blind. Your profiles, schedules, and blocklists are **not deleted**; they are
simply not enforced until you turn the hatch back off.

**Important limitation — it needs the server to be up.** Because the decision is
made on the WifiHaven server, this toggle does **not** help during a server
outage or when the internet is down (you couldn't reach the dashboard anyway).
For those cases, use the on-router hatch below.

---

## On-router toggle (local, works offline) — #2381

**Where:** on the router itself, so it keeps working even when the WifiHaven
server or the wider internet is unreachable. Two equivalent ways:

- **LuCI** (the router's web admin): a clearly-labeled **"Disable enforcement"**
  toggle.
- **SSH / CLI:** a one-word helper — `wifihaven-disable` to turn enforcement
  off, `wifihaven-enable` to turn it back on (backed by the UCI flag
  `wifihaven.settings.enforcement_disabled`).

**What it does:** the router agent skips *all* enforcement on its next apply —
every nftables block (per-device blocks, blocked sites, category blocklists,
IP-only blocks) and the block-page redirect — so all traffic passes. It is
checked every apply cycle and is independent of the server: it works with a
stale or absent policy (i.e. while the API is down). Turn it off and enforcement
is restored on the next apply.

> The exact LuCI location and CLI details are owned by
> [#2381](https://github.com/wifihaven/wifihaven/issues/2381); if anything here
> differs from your router's UI, the router's own toggle label is authoritative.

---

## Which one should I use?

- **"A block is getting in the way and I can reach the dashboard."** → Dashboard
  toggle (Admin page). Easiest, and you can flip it from your phone.
- **"The internet is down / the dashboard won't load / the WifiHaven server is
  unreachable."** → On-router toggle (LuCI or `wifihaven-disable` over SSH).
- **"I'm not sure."** → Try the dashboard first; fall back to the router.

Remember to **turn the hatch back off** when you're done — while either is on,
no device on your network is filtered.

## See also

- [`enforcement-expectations.md`](enforcement-expectations.md) — why a block
  isn't instant, and what device settings (VPN, "Secure DNS", iCloud Private
  Relay) can bypass filtering on their own.
- [`architecture.md`](architecture.md) §0 — the enforcement model (blocking is a
  connection-layer drop, never a DNS trick).
