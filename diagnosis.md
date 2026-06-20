# #1793 diagnosis — IPv6 destinations recorded with bare literal as host

## Phase 1 — router version (resolves Hypothesis A vs B)

Prod router (`hostname=router`, LAN gateway `192.168.10.1`; `router.lan` did not
resolve from the operator workstation, `192.168.10.1` did):

```
wifihaven-0.3.12-r1   (uptime 30d)
```

`v0.3.12` → commit `1cfb3f11` (2026-06-15). Both v6-attribution merges are
ancestors:

- `a29a55bb` (#1668, "attribute v6 destinations end-to-end") — ancestor ✓
- `98e43a89` (#1688, "complete v6 attribution end-to-end") — ancestor ✓

So the router **is on post-#1668 code** → **Hypothesis A (code-side gap)**, not B.

## Phase 2 — canonicalization mismatch (the gap)

The bug only affects **blocked** v6 flows. Why: an allowed flow is confirmed at
the postrouting hook and surfaces via `conntrack -E NEW`; a **dropped** flow is
killed in the forward chain before confirm, never emits IPCT_NEW, and is instead
made visible by the **nflog path** (`render.lua` `log prefix "wh_drop:…"` →
`wifihaven-nflog-tail` spool → `nflog.run`). The two readers print IPv6
differently:

| Source | v6 form | Example |
|---|---|---|
| dnsmasq query-log → dns cache key (`/tmp/wifihaven-dns-cache.txt`) | **compressed** RFC 5952 | `2607:f8b0:400f:801::2002` |
| `conntrack -L`/`-E` `dst=` | **compressed** | `2607:f8b0:400f:805::200a` |
| kernel netfilter LOG `DST=` (nflog path) | **fully expanded, zero-padded** | `2607:f8b0:400f:0801:0000:0000:0000:2002` |

Evidence captured on prod router:

- Cache keys are compressed:
  `2607:f8b0:400f:807::2002 -> pagead2.googlesyndication.com` (555 v6 keys).
- `conntrack -L` v6 dst is compressed: `dst=2607:f8b0:400f:805::200a`.
- Live `wh_drop` LOG lines are expanded:
  `wh_drop:04:72:ef:…:category:ads … DST=2607:f8b0:400f:0801:0000:0000:0000:2002`
  — byte-identical to the issue's reported `host` literal
  `2607:f8b0:400f:0805:0000:0000:0000:2002`.

`nflog.run` does `lookup(parsed.dst_ip)` with the **expanded** `DST=`, but the
cache is keyed by the **compressed** form dnsmasq logged → exact-string
`tbl[dst_ip]` miss → `hname=nil` → `build_event` falls back to the bare
`type="ipv6"` literal. The conntrack (allowed) path never hits this because its
`dst=` already matches the compressed cache key — which is exactly why only
blocked v6 flows show the bare literal.

## Fix

Single canonicalization helper `host_norm.canon_ip(ip)` → fully-expanded
zero-padded v6 form (v4 / non-v6 pass through). Applied at every dns-cache
key-use site so compressed (dnsmasq/conntrack) and expanded (kernel LOG) inputs
collapse to one key:

- writer: `dns_log` `store`, `load_table`
- reader: `dns_log` instance `lookup`, agent `lookup_hostname`

No cache-file format change that invalidates existing routers: `load_table`
canonicalizes on read, so a file with old compressed keys still resolves.
