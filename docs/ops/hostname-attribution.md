# Hostname attribution — what we can and can't name

When the dashboard or `/api/logs` shows a connection event with
`host.type = "ipv4"` (or `ipv6`) instead of `host.type = "fqdn"`, the
agent saw the flow but couldn't tie it to a hostname. This is by design
(architecture Truth 1 — no DNS interception, no TLS interception) but
not all bare-IP events have the same cause. This doc enumerates the
known buckets so operators don't re-investigate the same thing.

See [architecture.md §6.4](../architecture.md) for the wire shape and
[architecture.md §7.2](../architecture.md) for the OpenWRT attribution
path.

## How attribution actually works

The agent maintains an IP → hostname cache (`/tmp/wifihaven-dns-cache.txt`
on the router) populated from dnsmasq's query log
(`/tmp/wifihaven-dnsmasq.log`). When conntrack reports a flow to IP X,
the agent looks X up in the cache and emits `{type:"fqdn", value:...}`
on a hit and `{type:"ipv4", value:X}` on a miss.

A miss has exactly one root cause: **no dnsmasq log entry maps that IP
to a name**. The interesting question is *why*.

## Known causes of misses

### 1. Hardcoded service IPs (will never have a hostname)

Some services connect to fixed IPs that the device never resolves via
DNS. The biggest offender on iOS/macOS:

- **Apple Push Notification Service (APNs) — `17.0.0.0/8`, predominantly
  `17.57.0.0/16`.** Every iOS device keeps a long-lived TLS connection
  to `courier.push.apple.com` (iMessage, mail badges, app push, FaceTime
  signaling). iOS hardcodes the IP set; no DNS lookup ever fires. You'll
  see steady low-rate traffic to a `17.57.*` IP per device, continuously.

There is no in-band fix — we don't see a name because no name was
queried. A future enhancement could ship a static label map for known
hardcoded ranges (`17.57.0.0/16 → "apple-apns"`), but a flow-event
annotation like that needs its own design.

### 2. Pre-existing long-lived connections after agent (re)start

After a fresh install or agent restart, the DNS cache is empty. Any TLS
socket the client had open *before* — to a CDN, a chat backend, a
streaming service, an OS sync endpoint — keeps flowing through the
router. The DNS lookup happened on the previous network (or the
previous dnsmasq process), so the agent has no mapping.

This is **transient**. Long-lived sockets eventually close (server-side
keepalive, app foreground/background, network change) and the next
connection does a fresh DNS through our dnsmasq. Expect bare-IP events
for the first ~1–4 hours after a clean install, tapering off.

To force the issue on a specific device, toggle Wi-Fi off/on on that
device — most apps will tear down and re-establish through dnsmasq.

### 3. Encrypted DNS bypass (DoH / DoT)

If a device uses encrypted DNS — Apple's iCloud Private Relay (`mask.icloud.com`
+ `mask.apple-dns.net`), Mozilla / Cloudflare DoH (`1.1.1.1` over 443),
Google DoH (`dns.google` / `8.8.8.8` over 443), NextDNS, etc. — the DNS
query never traverses our dnsmasq. The connection target IP will appear
unattributed.

You can detect this by:

- Probing the device for `_dns.resolver.arpa` SVCB queries in
  `/tmp/wifihaven-dnsmasq.log` — Apple devices send these as upgrade
  probes; an `NXDOMAIN` reply means the device should fall back to plain
  DNS, but it's not a guarantee.
- Counting destination IPs in dnsmasq replies vs. flow events for a MAC.
  A device with vastly more flow-event destinations than dnsmasq replies
  is likely using DoH/DoT.
- For Apple specifically: presence of `mask.icloud.com` /
  `mask.apple-dns.net` in the dnsmasq log + many bare-IP flows to
  Cloudflare and Akamai ranges = Private Relay is on.

Mitigations: see [#572](https://github.com/wifihaven/wifihaven/issues/572)
(block DoH/DoT egress) and [#573](https://github.com/wifihaven/wifihaven/issues/573)
(recover hostname from TLS SNI).

The SNI sidecar (`wifihaven-sni-tail`) can be disabled per-router on
constrained or misconfigured hardware via the `wifihaven.sni.enabled`
UCI option (default `1`):

```
uci set wifihaven.sni.enabled=0
uci commit wifihaven
/etc/init.d/wifihaven restart
```

This is local agent config, not a policy-snapshot field — the API and
other routers are unaffected. ([#1654](https://github.com/wifihaven/wifihaven/issues/1654))

### 4. Encrypted ClientHello (ECH)

When a client negotiates TLS with Encrypted ClientHello (RFC
draft-ietf-tls-esni, extension type `0xfe0d`), the real `server_name`
travels inside the encrypted **inner** ClientHello and the sidecar
cannot recover it. We do not — and will not — attempt to defeat ECH.

What the sidecar does instead ([#1650](https://github.com/wifihaven/wifihaven/issues/1650)):

- Detects the ECH extension in the outer ClientHello and buckets the
  capture as `sni_clienthellos_total{result="ech"}` so an operator can
  see the ECH-attributed fraction of the fleet's TCP/443 flows.
- Returns the **outer/public** `server_name` when present — the
  gateway hostname (e.g. `cloudflare-ech.com`, `use.tls-ech.dev`) the
  TLS server uses to terminate the outer handshake. That is the most
  honest attribution we can offer: it correctly names the gateway the
  flow is bound to, even though it does not name the inner host the
  user actually requested.
- When the outer ClientHello carries no `server_name` extension at
  all, the counter still ticks under `result="ech"` but no hostname is
  attributed.

The ECH share is visible in the `SNI ClientHello rate by result` panel
on the router-fleet Grafana dashboard.

### 5. Connection-reuse / QUIC sessions

HTTP/3 (QUIC) maintains a single UDP "connection" that the app reuses
across multiple host fetches without re-resolving DNS. If the first
fetch in a session predates the agent (cause #2) or used DoH (cause
#3), every subsequent fetch on that same connection inherits the
missing attribution. New connections to the same name *will* be named
correctly.

## Operator triage flow

When `host.type` is bare-IP and you want to know why:

1. **Look up the IP's ASN/owner.** A quick `nslookup <ip>` from the
   router resolves PTR records via the WAN resolver. Apple = `17.0.0.0/8`,
   Google = `*.1e100.net`, Cloudflare = `*.cloudflare.com`, AWS = `*.amazonaws.com`.

2. **Is it in `17.0.0.0/8`?** Almost certainly Apple APNs or another
   Apple hardcoded service. Cause #1, no fix needed.

3. **Was the agent recently (re)started?** Check the dnsmasq log start
   time:
   ```sh
   ssh root@$ROUTER 'head -1 /tmp/wifihaven-dnsmasq.log'
   ```
   If the bare-IP events postdate the start by less than a few hours,
   cause #2 is the likely culprit; wait it out.

4. **Does the device send `_dns.resolver.arpa` SVCB queries or have a
   ton of unmapped Cloudflare IPs?** Cause #3 (DoH / Private Relay).
   Mitigation: turn on **Block encrypted DNS & relays** in Settings
   (household-wide; shipped in
   [#1911](https://github.com/wifihaven/wifihaven/issues/1911) /
   [#1912](https://github.com/wifihaven/wifihaven/issues/1912)) — the
   router then answers the relay/DoH hostnames authoritatively-empty
   (`local=/<host>/` → **NODATA**, per
   [`encrypted_dns.lua`](../../openwrt/files/usr/lib/lua/wifihaven/encrypted_dns.lua)),
   which is the negative answer Apple's Private-Relay disable requires,
   and relay setup fails. Expect NOERROR-with-no-answer in the dnsmasq
   log, **not** NXDOMAIN and not a `0.0.0.0` sinkhole — the lookup still
   succeeds, which is the usual reason this looks like it "didn't work".
   Households created after
   [#2643](https://github.com/wifihaven/wifihaven/issues/2643) already
   have it on, so if this is the cause on such a household, check
   whether the setting was turned off. Failing that, turn off encrypted
   DNS on the device itself (iOS Settings → iCloud → Private Relay;
   macOS / Android equivalents). Several other docs and comments still say NXDOMAIN for this feature — tracked in #2661.

5. **None of the above?** That's worth investigating — file an issue
   with the MAC, the bare IP, the dnsmasq log for that window, and
   `/tmp/wifihaven-dns-cache.txt`.

## Reference

- [`docs/architecture.md`](../architecture.md) §0 (Truths), §6.4
  (`POST /api/router/usage` wire shape), §7.2 (OpenWRT attribution).
- `/tmp/wifihaven-dnsmasq.log` — dnsmasq query/reply log on the router.
- `/tmp/wifihaven-dns-cache.txt` — current IP → hostname map (TTLs in
  the third column).
