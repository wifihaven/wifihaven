# WifiHaven OPNsense agent

Python agent for OPNsense routers.  Streams `connection_attempt` events to
the WifiHaven API by tailing pflog0 (via `tcpdump`) and attributing hostnames
from the Unbound query log.

## Scope for this release (#94)

| Feature | Status |
|---------|--------|
| pflog tail → `connection_attempt` events → `POST /api/router/events` | ✅ implemented |
| Hostname attribution via Unbound query log + `(mac, dest_ip)` LRU cache | ✅ implemented |
| Enrollment (`POST /api/router/register`) | deferred (#94 follow-up) |
| Policy polling + pf/Unbound config rendering | deferred (#94 follow-up) |
| Usage reporting (`POST /api/router/usage`) | deferred (#94 follow-up) |

## Requirements

- OPNsense 23.x or 24.x (FreeBSD 13/14 base)
- Python 3.9+ (available via `pkg install python3`)
- `tcpdump` (standard OPNsense install)
- Unbound DNS resolver (standard OPNsense install)
- Unbound query logging enabled (see Configuration)

## Installation

```sh
# 1. Install Python if needed
pkg install python3

# 2. Copy agent files
cp wifihaven-agent          /usr/local/sbin/wifihaven-agent
chmod +x                    /usr/local/sbin/wifihaven-agent
cp -r wifihaven/            /usr/local/lib/wifihaven/
cp rc.d/wifihaven           /usr/local/etc/rc.d/wifihaven
chmod +x                    /usr/local/etc/rc.d/wifihaven

# 3. Create config file
cp wifihaven.conf.example   /usr/local/etc/wifihaven.conf
# Edit /usr/local/etc/wifihaven.conf — see section below.

# 4. Enable and start
sysrc wifihaven_enable=YES
service wifihaven start
```

## Configuration

`/usr/local/etc/wifihaven.conf`:

```ini
[wifihaven]
api_url            = http://192.168.1.100:8080
router_token       = rt_xxxxxxxxxxxxxxxx   ; from POST /api/router/register
router_id          = 9c1f2e8a-...          ; from POST /api/router/register
lan_prefix         = 192.168.1.
unbound_log        = /var/log/unbound/unbound.log
event_batch_size   = 50
event_flush_interval = 10
```

## Enabling Unbound query logging

In OPNsense: **Services → Unbound DNS → Advanced → Log level: 1** and enable
**Log queries**.  The query log is written to `/var/log/unbound/unbound.log`.

Alternatively, add to `/var/unbound/unbound.conf`:

```
server:
    verbosity: 1
    log-queries: yes
    logfile: "/var/log/unbound/unbound.log"
```

Then restart Unbound: `service unbound restart`.

## Enrollment

Before starting the agent, obtain a `router_token` and `router_id` by
enrolling with the API:

```sh
curl -X POST http://<api_host>:8080/api/router/register \
  -H "Content-Type: application/json" \
  -d '{"enrollmentToken":"et_xxx","routerName":"home-gw",
       "platformVersion":"24.1","agentVersion":"0.1.0"}'
# → {"routerId":"...","routerToken":"rt_..."}
```

Paste the returned values into `/usr/local/etc/wifihaven.conf`.

## Running tests

```sh
pkg install python3
pip install pytest
sh opnsense/test/run_tests.sh
```

## Design notes

### Why pflog0 and not conntrack?

OPNsense is FreeBSD-based; Linux conntrack is not available.  pf logs flows
to the `pflog0` pseudo-interface.  `tcpdump -l -n -e -i pflog0` provides
structured, line-oriented output with the pf decision (pass/block) included.

### Hostname attribution: `(mac, dest_ip)` cache

A device may have concurrent connections to different hosts (YouTube, Netflix,
background update server).  The cache key is `(mac, dest_ip)` so each
connection is attributed to the correct hostname independently.

Population flow:
1. Unbound logs `client_ip → qname` (A/AAAA query)
2. Agent resolves `qname` → `[ip, ...]` via the system resolver
3. Stores `(mac_of_client_ip, ip) → qname` for each resolved IP
4. pflog event `(src_ip, dst_ip)` → ARP lookup → `mac` → cache lookup →
   hostname (falls back to `dst_ip` if no cache entry)

### Best-effort, never blocking

The event watcher is fire-and-forget.  Failed POSTs are retried with
exponential back-off (max 3 attempts) then dropped with a log message.  Flows
are never blocked or delayed waiting for the API.
