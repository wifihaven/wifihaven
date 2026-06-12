# #1666 — phantom per-app engagement inflation

## Symptom

Prima iPad (`04:72:ef:d6:e4:5a`, profile 6) hit Khan Academy per-app limit
33/30 today; 21 phantom minutes accumulated 01:00–05:00 PDT while asleep.
`connection_events` for `khanacademy.org` / `kastatic.org` / `kasandbox.org`
on this MAC had zero rows before 14:00 UTC.

## Root cause

`Presence.appSpansForProfile` ([api/src/presence/Presence.scala:601](api/src/presence/Presence.scala:601))
stitches the union of an app's host-set as ONE stream per device on the
`effectiveGap = max(continuationSeconds, 2·R)` idle gap (default 120s).

Two reinforcing effects:

1. **Row-as-span:** every non-heartbeat `traffic_reports` row contributes its
   full `[period_start, period_start + period_seconds]` window — typically 60s —
   as evidence (design §4.1, deliberately, to fight the activeSeconds undercount).
2. **Attribution beats suppression (#1506):** any row whose host matches an
   active app's host-set is NEVER classifiable as a heartbeat — neither the
   InfraHosts identity check nor the byte-floor filter applies. By design,
   so an app's own low-byte CDN/asset poll counts.

Combined: a sparse keepalive cadence on `khanacademy.org` (e.g. one DNS-driven
~80-byte poll every 60–90s overnight) produces a row every 60–90s, each row
gets a 60s window, the gap (≤120s) bridges every adjacent pair, and the result
is one continuous synthetic span for the whole overnight stretch. Khan is not
on InfraHosts so the #1503/#1525 suppression doesn't catch it (and `#1629`'s
direction extends InfraHosts coverage — orthogonal, won't close this case).

The per-app counter is the surface this inflates because per-app uses the
host-set-unioned `appSpansForProfile`; the per-profile daily total uses
`totalSecondsByMac` which stitches per `(mac, host)` and ALSO suffers the
same row-as-span hazard but Khan being `exemptFromDaily=true` masks the
daily-total reading. The fix below addresses the gap-stitch credit itself,
not just the per-app projection.

## Candidates considered

- **(A) Per-session activity anchor inside `appSpansForProfile`** — require a
  stitched session to contain at least one *anchor* row (≥ N bytes) before
  crediting any of its span. **Chosen.**
- **(B) Snap quiet-windows out (paused/scheduled) of stitched spans** —
  doesn't close this case: Prima was not under any schedule overnight,
  and dropping only the paused windows would still credit normal idle.
- **(C) Per-app heartbeat predicate (extend InfraHosts to apps)** — too
  invasive; conflicts with the #1506 attribution-beats-suppression contract;
  forces operators to curate per-app keepalive lists.

## Chosen fix — anchor-row requirement

A per-`(mac, app)` session is dropped unless at least one of its
contributing rows has `bytes >= Presence.AppSessionAnchorBytes` (256 bytes).
Rationale: a TCP keepalive is ~60 bytes and an HTTP/2 PING is a few hundred
at most; any *substantive* HTTP request — even just a JSON poll or a 304 —
clears 256 bytes easily. So:

- **Phantom pattern** (sparse 60–120-byte polls): no row clears the anchor →
  session dropped → 0 credited.
- **Real usage** (article load, asset fetch, video chunk): the first
  substantive request anchors the session and bridges work around it.
- The check is *per-session*, not per-row, so attribution still wins at the
  row level (an attributed low-byte row still contributes to a session
  anchored elsewhere). The #1506 contract is preserved.

Single-source-of-truth: implemented inside `appSpansForProfile`, the one
canonical per-app primitive (#1514). Every callsite — the rollup, the cap,
the dashboard — inherits the fix.

No schema change. Metric: `presence_app_sessions_dropped_total{reason="no_anchor"}`.
