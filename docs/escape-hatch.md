# Disable enforcement / escape hatch — turn off all blocking

**Short version:** if WifiHaven is blocking something it shouldn't, the internet
isn't working, or the WifiHaven server is down and you just need the internet
back, you can **turn off all blocking**. There are two ways to do it. One works
even when the WifiHaven server is offline.

Plain-language search terms this page covers: *turn off blocking*, *disable
enforcement*, *disable WifiHaven*, *internet not working*, *internet is broken*,
*bypass WifiHaven*, *unblock everything*, *emergency off switch*, *escape hatch*,
*stop filtering*, *pause all blocking*, *WifiHaven server is down*.

---

## Which off switch should I use?

There are **two** independent off switches. They do the same thing (turn off all
blocking) but they live in different places:

| | **Router off switch** (this page) | **Dashboard off switch** ([#2382](https://github.com/wifihaven/wifihaven/issues/2382)) |
|---|---|---|
| Where | On the router itself (LuCI or SSH) | In the WifiHaven web dashboard |
| Works when the server/API is **down**? | **Yes** — it's local to the router | No — it needs the dashboard/API to be up |
| Scope | This one router | The household |
| Best for | A server outage, or "nothing else works" | Everyday "just turn it off for now" |

**Rule of thumb:** try the dashboard toggle first (it's easier). If the
dashboard won't load or the server is down, use the **router off switch** below —
that's the whole point of it: it works offline.

---

## Router off switch (works even when the server is down)

When you turn this on, the router immediately stops **all** blocking — profiles,
schedules, time limits, blocked sites, and category blocklists — and it stops
redirecting blocked pages to the block page. Every device gets normal,
unfiltered internet within a few seconds. It works **even if the WifiHaven server
is unreachable**, because the switch lives on the router and is read locally,
independent of any server policy.

It does **not** uninstall anything and does **not** lose your settings. It's a
reversible pause. Turn it back off to restore normal blocking.

### Option A — LuCI (web UI on the router)

1. Open the router's admin page (LuCI), usually `http://192.168.1.1`.
2. Go to **Services → WifiHaven → Settings**.
3. Turn on **"Disable all WifiHaven enforcement"**.
4. Click **Save & Apply**.

Blocking stops within a few seconds. To restore blocking, turn the same toggle
off and Save & Apply again.

### Option B — command line (SSH into the router)

```sh
# Turn OFF all blocking (internet works normally, even if the server is down):
wifihaven-disable

# Restore normal blocking:
wifihaven-enable
```

Either command takes effect within about a second — no reboot or service restart
needed. `wifihaven-disable` prints a confirmation and reminds you how to undo it.

Under the hood these just set a local config flag and commit it:

```sh
# Equivalent to wifihaven-disable:
uci set wifihaven.settings.enforcement_disabled=1 && uci commit wifihaven

# Equivalent to wifihaven-enable:
uci set wifihaven.settings.enforcement_disabled=0 && uci commit wifihaven
```

The agent re-reads this flag on **every** apply cycle, so a change takes effect
on the next cycle regardless of whether the server is reachable.

### How to tell it's working

```sh
logread -f | grep wifihaven
```

You'll see a line like `enforcement DISABLED (#2381 escape hatch)` when it turns
on, and `enforcement RE-ENABLED` when you turn it back off. On the fleet
dashboard, the **"Routers with enforcement disabled"** panel shows a router in
bypass (the `enforcement_disabled` metric reads 1).

---

## Dashboard off switch (server-driven — needs the WifiHaven server up) — #2382

The easier switch, for everyday "just turn it off for now": a toggle in the
WifiHaven web dashboard. It turns off **all** blocking for the whole
household — profiles, schedules, time limits, blocked sites, and category
blocklists — so every device gets normal, unfiltered internet within seconds.
Turn it back off to restore normal blocking; nothing is uninstalled and no
settings are lost.

**Where:** dashboard → **Admin** → **"Turn off all blocking (escape hatch)"**.
You must be signed in as a household **admin** to change it (any member can see
its state).

**Important — it needs the WifiHaven server to be reachable.** Because the
decision is made on the server, this switch does **not** help during a server
outage or when the internet is down (you couldn't load the dashboard anyway).
For those cases use the **router off switch** above, which works offline.

Under the hood the server just ships a fully *permissive* policy for the
household (the same "allow everything" snapshot it already uses so a lapsed
account never bricks the network), and the router applies it blind — it never
learns *why*, it simply stops dropping traffic. So this is **not** a second
enforcement mechanism; it reuses the existing pass-through path. The flag is a
per-household setting (`household_settings.enforcement_disabled`), scoped so one
household's switch never affects another.

### How to tell it's working

While it's on, the household's devices browse normally and the block page never
appears. Server-side, the **"Permissive snapshots by reason"** panel on the
Enforcement dashboard shows a non-zero `enforcement_disabled` rate (the
`policy_permissive_snapshot_total{reason="enforcement_disabled"}` series) —
that's the signal a household is currently running with blocking off.

---

## Why this is safe and reversible

- It's a **pause**, not an uninstall. Your profiles, schedules, blocklists, and
  device assignments are untouched. Turning it back off restores everything
  exactly as it was.
- It only affects **this** router.
- It's the sanctioned, deliberate exception to WifiHaven's normal rule that "the
  router just applies whatever the server decides" — a manual escape hatch has to
  work when the server can't be reached, which is exactly when you need it most.

## How it works (for the curious)

WifiHaven blocks traffic at the **connection layer** on the router (nftables
forward-drops), not via DNS. The escape hatch short-circuits the router agent's
apply pipeline at the very top: when the flag is set, the agent renders a
*permissive* ruleset with **no** forward-drops and **no** block-page redirect, so
all forwarded traffic passes. Per-device usage accounting keeps working, so your
timeline/usage data isn't lost while blocking is off. See
[`AGENTS.md`](../AGENTS.md) (Architectural Truth #2) and
[`docs/architecture.md`](architecture.md) for the enforcement model.

## Related

- Dashboard equivalent (server-driven, needs the API up):
  [#2382](https://github.com/wifihaven/wifihaven/issues/2382)
- This router-level hatch:
  [#2381](https://github.com/wifihaven/wifihaven/issues/2381)
- Install / enrollment: [`docs/install-openwrt.md`](install-openwrt.md),
  [`docs/install-flint2.md`](install-flint2.md)
