# Bounded /tmp writes on the router agent

This was originally in AGENTS.md §"Every router-agent write to /tmp must be bounded"; see AGENTS.md for the TOC.

## Every router-agent write to `/tmp` must be bounded {#bounded-tmp-writes}

**On the OpenWRT target `/tmp` is `tmpfs` — RAM, not disk.** A log, spool, or
cache the agent appends to without a cap grows until it exhausts router memory,
at which point allocations start failing fleet-wide: dnsmasq, the agent, and
the kernel all compete for a few hundred MB of RAM on the smallest supported
device. An unbounded `/tmp` writer is therefore not a disk-hygiene nicety —
it is a latent **router-wedge / OOM** bug. (This rule comes out of the #573
SNI sidecar, whose first cut appended to `/tmp/wifihaven-sni.log` forever.)

So: **any agent file under `/tmp` that grows with traffic, time, or event
volume must ship with a rotation/truncation path in the SAME change that
introduces it.** Concretely:

- **Add it to the existing rotation cron, don't invent a parallel one.**
  [`wifihaven-rotate-dnsmasq-log`](../../openwrt/files/usr/sbin/wifihaven-rotate-dnsmasq-log)
  runs every 10 minutes and is the canonical place — it caps each file at a
  fixed `MAX_BYTES` using the **copytruncate** pattern (`cp` to `*.1`, then
  `: > file`). New spools join its file list; they do not get a second cron
  job or a bespoke shell script.
- **Copytruncate, never rename**, for any file a long-lived process holds an
  open fd on (dnsmasq, or a `tail -F` follower like `wifihaven-dns-tail`). A
  rename leaves the writer appending to an invisible, unrotated inode and the
  follower stranded; truncation keeps both on the same inode. The follower's
  brief reseek window is acceptable and bounded — see the header comment in
  the rotate script for the full rationale.
- **A bounded in-memory ring that is only ever *snapshotted* to `/tmp`
  (atomic write + rename of a whole file, like `paths.dns_cache`) is already
  bounded** — its size is the cache size, not cumulative. Those don't need
  cron rotation. The rule targets **append/grow** writers (logs, spools,
  metrics journals), not fixed-size snapshots.
- **State the cap and worst-case `/tmp` footprint** in the script/header so a
  reviewer can sanity-check it against the smallest device's RAM.

The same reasoning applies to anything else the agent persists under `/tmp`
(JSON spools, NDJSON event journals, debug dumps). If it can grow, it gets a
bound — in the same PR.

## Enforcement-record spool (`paths.nflog_drops`) {#nflog-spool}

The `wifihaven-nflog-tail` sidecar runs the blocking `logread -f`, keeps the
lines carrying a wifihaven enforcement record, and appends them to a tmpfs
spool the agent drains on its tick (`nflog.drain_file`). Two record prefixes
share that one spool:

- `wh_drop:<mac>:<reason>` — forward-hook drop (`chain wifihaven_block`).
- `wh_dnat:<mac>:<reason>` — block-page DNAT/redirect
  (`chain wifihaven_block_nat`, prerouting), added in
  [#2647](https://github.com/wifihaven/wifihaven/issues/2647).

**This spool is bounded by the sidecar itself, not by the rotation cron.** The
writer holds the fd open and is the only writer, so it caps the file at
`nflog_spool_max_bytes` (default 256 KiB, UCI-overridable) and truncates in
place by reopening with mode `"w"`; the agent detects the shrink (file shorter
than its saved offset) and rewinds its cursor. At most the lines written since
the agent's last drain are lost, which is acceptable for a best-effort,
agent-deduped visibility stream. `nflog_tail_bounded_spec.sh` pins the cap and
the truncate path.

**A second record source does not need a second bound**, but it does need the
volume argument re-made. `wh_dnat:` records are the COMMON case (TCP 80 and 443
are both redirected), so they are rate-limited router-side before they ever
reach `logread`: each dnat/redirect rule is fronted by a log rule gated on a
per-flow limiter set (`wh_dnat_log4` / `wh_dnat_log6`, keyed on `(mac, dst)` at
`1/minute burst 5`) — the same per-flow shape #1915 gave the drop path, and the
same reason: a flat per-rule budget starves event synthesis for the next
distinct flow once a storm drains it. Worst case per flow is therefore
unchanged in order of magnitude, and both sources land under the one cap above.

## Per-blocklist dnsmasq conf shards (`/tmp/dnsmasq.d/blocklists/wifihaven-blocklist-<id>.conf`) {#blocklist-shards}

Added in [#1782/#1783](https://github.com/wifihaven/wifihaven/issues/1782).
One file per blocklist id in the current snapshot.

**How they work.** `blocklists.render_shards` reads each list's on-disk cache
file (`/etc/wifihaven/blocklists/<id>-<version>.txt`) line-by-line and writes
one `nftset=/<host>/4#inet#wifihaven#bl_<id>,6#inet#wifihaven#bl6_<id>` line
per host — never accumulating the full list in a Lua table. `render.dnsmasq`
emits `conf-file=/tmp/dnsmasq.d/blocklists/wifihaven-blocklist-<id>.conf` for
each id so dnsmasq picks up the host set at startup and on reload.

**Shards MUST live inside the dnsmasq confdir** (`/tmp/dnsmasq.d`), not bare
`/tmp` — [#1812](https://github.com/wifihaven/wifihaven/issues/1812). OpenWRT
runs dnsmasq in a procd ujail (`/etc/init.d/dnsmasq`'s
`procd_add_jail_mount $dnsmasqconfdir …`) that bind-mounts ONLY the confdir and
a few known files. A `conf-file=` directive pointing at a shard outside the
confdir is unreadable inside the jail: dnsmasq aborts with `cannot read …`,
procd crash-loops it and gives up, and `:53` goes dark (connection refused, no
DHCP leases). `dnsmasq --test` passes only because it runs unjailed. The shard
dir is a *subdir* of the confdir (`…/blocklists/`) so dnsmasq's
non-recursive `conf-dir=/tmp/dnsmasq.d` does not auto-load the shards — they
load only via the explicit, shard-existence-gated `conf-file=` directives.
`render.SHARD_DIR` is the single source of truth (the agent's
`BLOCKLIST_SHARD_DIR` derives from it).

**Atomic write.** Each shard is written to `<id>.conf.tmp`, then renamed onto
`<id>.conf`. A partial shard is never visible to dnsmasq.

**Sizing.** ~80 bytes per host × list member count. The two large StevenBlack
lists are ~7 MB each; three curated lists total under 100 KB. A per-shard cap
(default 10 MB, UCI `blocklist_max_list_bytes`) prevents a runaway list from
filling `/tmp`.

**Lifecycle (GC, NOT log rotation).** These are config files, not append-only
logs — `copytruncate` is wrong here. The lifecycle is:

- **On every render:** `blocklists.gc_shards` removes shards whose id is no
  longer in the current snapshot (list unassigned or deleted). Stale
  `.conf.tmp` files are also removed (crash-during-render defense).
- **On agent startup:** `gc_shards` runs against the cached snapshot (or an
  empty placeholder) before the first apply, so a crash-during-render from a
  previous run can never leave a stale shard visible to the newly-started
  dnsmasq.

**NOT added to `wifihaven-rotate-dnsmasq-log`.** The rotate cron is for
append-only logs. Shard lifecycle is precise removal of stale ids, not
copytruncate — adding these to the cron would silently truncate a live shard
mid-use.
