# Router-based enforcement architecture

Status: **partially implemented.**
- §5.1–5.5 and §9 schema in production (issues #67, #68/#98, #69/#97).
- §5.6 pending (#70).
- OpenWRT agent (#72) and OpnSense agent (#94) pending.

## 1. Why this exists

The original architecture ran a DNS server and a pcap-based traffic monitor on
the same Linux host as the API. That works only if the host sees every DNS
query and every flow — which it doesn't on a normal home LAN. Clients can:

- hard-code an upstream resolver (`1.1.1.1`, `8.8.8.8`) and bypass the local
  DNS server entirely;
- use DNS-over-HTTPS / DNS-over-TLS, which looks like ordinary HTTPS to the
  pcap monitor;
- talk peer-to-peer or to services that don't show up on the host's NIC.

Per-profile time accounting and per-device blocking are unenforceable from a
host that isn't on the data path. Moving enforcement to the **gateway** — a
router every device must traverse to reach the internet — fixes this.

## 2. Topology

```
                                                 +----------------------+
LAN devices ───┐                                 |  api server (Scala)  |
  (phones,     │   all egress traffic            |  ┌────────────────┐  |
   laptops,    │  ───────────────▶               |  │  Postgres      │  |
   TVs)        │                                 |  └────────────────┘  |
               ▼                                 |   • policy API       |
        +-------------------+   HTTP             |   • usage ingest API |
        |  gateway router   |◀──bearer token────▶|   • block-page UI    |
        |  (OpenWRT or      |                    |   • web admin UI     |
        |   OpnSense)       |                    +----------------------+
        |  • DNS server     |
        |  • packet filter  |
        |  • router agent   |
        +-------------------+
```

- **API server** is a single instance. All endpoints (admin, web, router) live
  under one HTTP port, distinguished by path prefix.
- **HTTP, not HTTPS**, for the router endpoints in the initial rollout. Switch
  to HTTPS / mTLS once the API server moves into the cloud.
- **Authentication** between router and API is a per-router bearer token
  obtained via a one-shot enrollment flow (§5.1).
- **The gateway can be any supported platform** — OpenWRT or OpnSense. Both
  speak the same wire protocol to the same API endpoints (§3).

## 3. Abstraction boundary

### 3.1 API contract (router-agnostic)

The HTTP contract is platform-neutral. Every agent speaks the same REST
protocol, regardless of router OS:

| Endpoint | Purpose | Status |
|----------|---------|--------|
| `POST /api/router/register` | Exchange enrollment token for bearer token | implemented |
| `GET /api/router/policy` | Pull enforcement snapshot | implemented |
| `GET /api/blocklists/<cat>.rpz` | Fetch RPZ blocklist | implemented |
| `POST /api/router/usage` | Push per-(mac, hostname) traffic records | implemented (#97) |
| `POST /api/router/events` | Push DHCP lease + DNS query events | implemented (#97) |
| `POST /api/router/decision` | Per-hostname fallback decision | pending #70 |
| `GET /blocked` | Public block page | pending #70 |

The policy snapshot JSON shape (§5.2), usage record shape (§5.4), and event
shape (§5.5) are identical across all agent platforms. An agent author only
needs to implement the HTTP calls and the platform-specific rendering logic.

### 3.2 Agent responsibilities (all platforms)

Every router agent must:

1. **Enroll** once: exchange a one-time enrollment token for a long-lived bearer token.
2. **Poll policy** every ~60 s, render the snapshot into the platform's native
   enforcement config, and reload atomically.
3. **Report usage** every ~5 min: scrape platform-native traffic counters,
   attribute bytes to `(mac, hostname)` pairs, POST to `/api/router/usage`.
4. **Stream events**: forward DHCP lease events and DNS query events to
   `/api/router/events`.

### 3.3 Platform divergence

| Concern | OpenWRT | OpnSense |
|---------|---------|----------|
| DNS server | dnsmasq | Unbound |
| Packet filter | nftables | pf / pfctl |
| Traffic accounting | nftables counters | pflog / pf tables |
| Per-MAC DNS tagging | dnsmasq `dhcp-host` + `address=` | Unbound `local-data` per-client views |
| Event sourcing | dnsmasq query log + `--dhcp-script` | Unbound query log + ISC dhcpd / Kea lease file |
| Package system | opkg / OpenWRT SDK | FreeBSD pkg / OPNsense plugin system |
| Config format | UCI | OPNsense XML API or conf files |
| Daemon supervisor | procd init script | rc.d service |
| Agent language | Lua | Python (TBD in #94) |

### 3.4 What is identical across platforms

- All HTTP calls target the same endpoints with the same JSON shapes.
- The policy snapshot (§5.2) is parsed identically; only the *rendering* step
  (snapshot → local config) is platform-specific.
- Usage POST body (§5.4): `active_seconds`, `bytes_in`, `bytes_out` per `(mac, hostname)` — same on all platforms.
- Events POST body (§5.5): `dhcp_lease` and `dns_query` types — same on all platforms.
- Bearer-token auth, ETag polling, and idempotency semantics are platform-neutral.

## 4. Component responsibilities

### Gateway router (any platform)

- DHCP + DNS for the LAN.
- Enforces filtering: drops or redirects blocked flows using the platform's packet filter.
- Accounts traffic per `(mac, hostname)`.
- Periodically pulls policy and pushes usage to the API.
- Serves a local block page that 302s to the API's `/blocked` URL for any
  blocked HTTP request.

### API server (this repo, `api/` module)

- Stores profiles, schedules, time limits, blocklists, devices, users,
  routers, traffic reports, block events.
- Exposes admin/web endpoints (existing) **and** router endpoints (§5).
- Renders the public `/blocked` page.
- Owns the policy decision logic (`PolicyService`).

### Components being deleted (issue #71)

- `dns/` — replaced by dnsmasq / Unbound on the router.
- `traffic/` — replaced by the agent's platform-native traffic accounting.

## 5. Decision model: policy snapshot, not per-flow round-trips

The router does **not** round-trip to the API on every connection. Instead:

1. Every ~60 s the agent pulls a **policy snapshot** containing everything
   needed to make decisions locally: device→profile assignments, blocked
   categories, schedules, time limits, `time_used_today`, paused state.
2. The agent renders that snapshot into DNS + packet-filter config and
   reloads them atomically.
3. Per-flow decisions happen in-kernel or in the DNS server — no API
   call per request.
4. `POST /api/router/decision` exists as a **fallback** for hostnames not in
   the snapshot, and is optional for v1.

Worst-case staleness is one poll interval (~60 s). That is acceptable for
parental-control use cases; an instant-block requirement can be met later by a
server-push channel (websocket, SSE) if needed.

## 6. Router HTTP API

All endpoints below are served by the existing API process under
`/api/router/*`. Bearer-token auth on every request except `/register`
(one-time enrollment token) and the public `/blocked` page.

JSON field names use camelCase (ZIO JSON default — no snake_case transform).

### 6.1 `POST /api/router/register`

Boot-time enrollment. The user creates a router record in the admin UI; the
API returns a one-time enrollment token they paste into the agent's config.
The agent calls this endpoint once to exchange the enrollment token for a
long-lived router token.

**Request**

```json
{
  "enrollmentToken": "et_5f3c9b...",
  "routerName": "home-gw",
  "platformVersion": "23.05.3",
  "agentVersion": "0.1.0"
}
```

`platformVersion` carries the router OS version (e.g. OpenWRT `23.05.3`,
OPNsense `24.1`). `routerName` overrides the name set in the admin UI (optional).

**Response 200**

```json
{
  "routerId": "9c1f2e8a-...",
  "routerToken": "rt_a7d12b..."
}
```

**Errors**: `401` on invalid/used enrollment token.

The enrollment token is single-use; the API marks it consumed on success.

### 6.2 `GET /api/router/policy?since=<etag>`

Polled every ~60 s. Returns the full enforcement snapshot, or `304 Not
Modified` if the client's ETag still matches.

**Response 200**

```json
{
  "etag": "sha256:abc123...",
  "generatedAt": "2026-05-02T14:00:00Z",
  "defaultProfileId": 1,
  "devices": [
    { "mac": "aa:bb:cc:11:22:33", "profileId": 3, "name": "kid-ipad" }
  ],
  "profiles": [
    {
      "id": 3,
      "name": "kids",
      "paused": false,
      "blockedCategories": ["ads", "adult"],
      "extraBlocked": ["tiktok.com"],
      "extraAllowed": ["khanacademy.org"],
      "schedules": [
        { "days": ["MON","TUE","WED","THU","FRI"],
          "blockFrom": "21:00", "blockUntil": "07:00" }
      ],
      "dailyMinutes": 120,
      "siteLimits": [
        { "domain": "youtube.com", "minutes": 30, "label": "YouTube" }
      ],
      "timeUsedToday": {
        "totalMinutes": 47,
        "byDomain": { "youtube.com": 12 }
      },
      "extensionsTodayMinutes": 15
    }
  ],
  "blocklists": {
    "ads":   { "version": "2026-04-29", "url": "/api/blocklists/ads.rpz" },
    "adult": { "version": "2026-04-29", "url": "/api/blocklists/adult.rpz" }
  }
}
```

**Response 304** when `If-None-Match: <etag>` (or `?since=<etag>`) matches the
current snapshot. Body is empty.

ETag is computed deterministically over snapshot content.

`timeUsedToday.totalMinutes` excludes domains covered by `siteLimits` — the
agent enforces both limits independently.

### 6.3 `GET /api/blocklists/<category>.rpz`

Returns an RPZ-formatted blocklist for the named category. Versioned and
ETagged. The agent caches by `version` from the policy snapshot and only
refetches when the version changes.

### 6.4 `POST /api/router/usage`

Sent every 5 minutes. Idempotent on `(routerId, periodStart, mac, hostname)`
so retries are safe.

**Request**

```json
{
  "routerId": "9c1f2e8a-...",
  "periodStart": "2026-05-02T14:00:00Z",
  "periodEnd":   "2026-05-02T14:05:00Z",
  "records": [
    {
      "mac": "aa:bb:cc:11:22:33",
      "ip":  "192.168.1.42",
      "hostname": "youtube.com",
      "activeSeconds": 240,
      "bytesIn":  38123412,
      "bytesOut": 921000
    }
  ]
}
```

`hostname` is the forward-lookup hostname the DNS server resolved for the
client — not a reverse-DNS lookup of the destination IP (see §7.2 and §8.2
for why per-platform).

**Response 200**: empty body.

Server actions: insert into `traffic_reports`, increment `time_usage`, update
`devices.last_seen_ip` / `last_seen_at`.

### 6.5 `POST /api/router/events`

Out-of-band events (DHCP leases, DNS query log lines). Used to populate the
unknown-device list and feed device autodetection.

**Request**

```json
{
  "routerId": "9c1f2e8a-...",
  "events": [
    { "type": "dhcp_lease",
      "mac": "aa:bb:cc:11:22:33", "ip": "192.168.1.42",
      "hostname": "kid-ipad", "ts": "2026-05-02T14:01:13Z" },
    { "type": "dns_query",
      "mac": "aa:bb:cc:11:22:33",
      "qname": "youtube.com", "qtype": "A",
      "blocked": false, "ts": "2026-05-02T14:01:14Z" }
  ]
}
```

`type` values (`dhcp_lease`, `dns_query`) are the same regardless of platform.

**Response 200**: empty body.

### 6.6 `POST /api/router/decision`  *(optional fallback, pending #70)*

For hostnames not in the most recent snapshot.

**Request**: `{ "mac": "aa:bb:...", "hostname": "..." }`

**Response 200**: `{ "allow": false, "reason": "category:adult", "expiresAt": "..." }`

### 6.7 `GET /blocked`  *(public, no auth, pending #70)*

Query params: `mac`, `host`, `reason`. Renders a page showing why the request
was blocked and offering a "request extension" button gated on parent login.
This is the URL the router's local block page redirects to.

## 7. OpenWRT agent design

This section describes the OpenWRT-specific rendering layer. It is implementation
guidance, not part of the wire contract.

### 7.1 Capabilities used

- **Per-MAC nftables sets** — `ether saddr @profile3_macs` matches packets
  from any MAC in the named set. One set per profile.
- **Per-domain IP sets via dnsmasq `--ipset=`** — dnsmasq populates named
  nftables sets with the IPs a hostname resolves to; nftables matches destination
  IP against the set.
- **Per-MAC dnsmasq tagging** — `dhcp-host=...,set:profileN` applies different
  `address=` / `server=` rules per MAC tag so blocked domains return NXDOMAIN
  for kids' devices and resolve normally for parents'.
- **nftables counter objects** keyed on `ether saddr . ip daddr` for per-MAC,
  per-IP byte counts.
- **dnsmasq query log** (`--log-queries=extra`) maps `(mac, time) → hostname`
  so byte counts can be attributed to the hostname the client actually resolved.
- **uhttpd** on a loopback port serves the local block page; nftables `dnat`
  redirects blocked HTTP/80 to it.

### 7.2 Forward-lookup hostnames, not reverse DNS

Reverse DNS of a destination IP often returns generic CDN PTRs
(`lb-13.akamai.net`) unrelated to the user's intent. dnsmasq's `--ipset=`
populates the nftables set *at lookup time*, so the hostname is known
definitively. HTTPS connections that bypass dnsmasq get attributed to an
`unknown` bucket.

### 7.3 Package layout

```
openwrt/
├── Makefile                            # opkg metadata, builds via OpenWRT SDK
├── files/
│   ├── etc/init.d/familydns            # procd init script
│   ├── etc/config/familydns            # UCI: api_url, router_token, poll_interval
│   ├── usr/sbin/familydns-agent        # main daemon (Lua)
│   ├── usr/lib/familydns/policy.lua    # snapshot fetcher, atomic apply
│   ├── usr/lib/familydns/usage.lua     # nftables counter scraper, reporter
│   ├── usr/lib/familydns/render.lua    # writes dnsmasq + nft fragments
│   └── www/familydns/block.html        # local block page → 302 to api /blocked
└── README.md
```

### 7.4 Daemon loop (three timers)

- **Policy timer (60 s)** — fetch snapshot, atomically rewrite
  `/tmp/dnsmasq.d/familydns.conf` and `/tmp/nftables.d/familydns.nft`,
  then reload dnsmasq + nft. On `304`: do nothing.
- **Usage timer (5 min)** — scrape nftables counters, correlate with dnsmasq
  query log + DHCP leases, POST to `/api/router/usage`. On 200, reset counters;
  on failure, retain and retry (endpoint is idempotent).
- **Event watcher** — dnsmasq `--dhcp-script` hook for DHCP events; log tail
  for query events; batched to `/api/router/events`.

### 7.5 Time-limit enforcement

The snapshot includes `timeUsedToday.totalMinutes` and `dailyMinutes`. The agent
computes `remaining = limit - used + extensions` and, when `≤ 0`, drops all
egress for that profile's MAC set until the next poll (~60 s worst-case bonus).

### 7.6 Block-page redirect

nftables `dnat` on TCP/80 to `127.0.0.1:8081` (uhttpd). A tiny CGI script
reads the original destination from conntrack and 302s to
`http://<api>/blocked?mac=…&host=…&reason=…`. Blocked HTTPS times out (no
MITM without a CA install — same behavior as commercial parental-control boxes).

## 8. OpnSense agent design  *(pending #94)*

This section describes the OpnSense-specific rendering layer. The wire protocol
(§6) is unchanged; only the platform primitives differ.

### 8.1 Capabilities used

- **Unbound DNS** for per-client DNS overrides via `local-data` directives or
  Unbound's access-control + RPZ support.
- **pf firewall** for per-MAC blocking via `<table>` entries and anchor rules
  (`pass/block from <table>`).
- **pflog / pf counters** for traffic accounting — pflog captures firewall
  decisions with source MAC and destination; pf per-rule byte counters provide
  aggregate stats.
- **DHCP server** (ISC dhcpd or Kea, depending on OPNsense version) for
  MAC→IP mapping; lease file or commit script provides events.
- **OPNsense plugin system** for packaging and rc.d integration.

### 8.2 Forward-lookup hostnames on OpnSense

Unbound's query log provides `(client IP, qname)`. The agent correlates the
client IP to a MAC via the DHCP lease table to get `(mac, hostname)` — the
same attribution the OpenWRT agent derives from dnsmasq. Connections that
bypass Unbound get attributed to `unknown`.

### 8.3 Package layout  *(TBD in #94)*

```
opnsense/
├── pkg-descr
├── Makefile
├── files/
│   ├── usr/local/etc/rc.d/familydns     # rc.d service
│   ├── usr/local/etc/familydns.conf     # api_url, router_token, poll_interval
│   └── usr/local/sbin/familydns-agent   # main daemon (Python)
└── README.md
```

### 8.4 Daemon loop

Same three-timer structure as the OpenWRT agent (§7.4), with platform-specific
rendering:
- **Policy timer**: renders Unbound `local-data` overrides and pf table entries,
  reloads both atomically via `unbound-control reload` and `pfctl -T replace`.
- **Usage timer**: reads pf per-rule counters or parses pflog, attributes bytes
  via Unbound query log, POSTs to `/api/router/usage`.
- **Event watcher**: tails dhcpd lease file and Unbound query log, batches to
  `/api/router/events`.

### 8.5 Block-page redirect

pf `rdr` rule redirects TCP/80 for blocked MACs to a local web server (nginx or
lighttpd). The block page 302s to the API's `/blocked` URL. Blocked HTTPS times
out (same limitation as OpenWRT, same rationale).

## 9. Schema

New tables (landed in #67):

- `routers` — registered gateways and their tokens.
- `traffic_reports` — raw audit log of usage POSTs.
- `block_events` — record of decisions returned by `/decision` and redirects
  served by `/blocked`.

New columns (landed in #67):

- `time_usage.bytes_in bigint default 0`
- `time_usage.bytes_out bigint default 0`

No schema changes are needed to support OpnSense — the tables are platform-neutral.
The `query_logs` table is fed by `/api/router/events` (replacing the deleted
DNS server as the source).

## 10. Rollout sequence

| Issue | Description | Status |
|-------|-------------|--------|
| #67 | V2 migration + repos | done |
| #68 | `GET /api/router/policy`, blocklists, enrollment, admin UI | done (#98) |
| #69 | `POST /api/router/usage` + `POST /api/router/events` | done (#97) |
| #70 | `POST /api/router/decision` + public `/blocked` page | pending |
| #71 | Delete `dns/` and `traffic/` modules | pending |
| #72 | OpenWRT agent (Lua, opkg) | pending |
| #73 | e2e fake-router in staging compose | pending |
| #88 | Single-host deploy plan | pending |
| #89 | Cloud deploy plan | pending |
| #93 | (see issue) | pending |
| #94 | OpnSense agent (Python, OPNsense plugin) | pending |

Steps #67–#70 land before #71: the old enforcement stack is not deleted until
the new API surface is addressable.
