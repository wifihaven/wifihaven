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
