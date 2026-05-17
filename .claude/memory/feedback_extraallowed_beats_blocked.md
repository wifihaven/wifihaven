---
name: extraAllowed beats blocked (router enforcement)
description: BlockRules.extraAllowed must override BlockRules.blocked at the router — a fully-blocked device still reaches its admin-allowed carve-outs
type: feedback
---
extraAllowed beats blocked, in addition to beating extraBlocked and blocklists.

**Why:** An admin allow is the strongest signal. A device that is fully
blocked (e.g. paused, schedule-blocked, time-limit-exhausted) should still
be able to reach the explicitly-allowed hosts in its profile's
extraAllowed list (e.g. Khan Academy still works on a paused kid iPad).
This came up on the #421 PR — initial implementation only applied the ea
exception to eb_/bl_ drops, missing the @blocked_macs drop.

**How to apply:** Anywhere the router drops based on `blocked` (the
`@blocked_macs` forward-chain drop, the @blocked_macs DNAT in v4 and v6,
plus any future "fully-blocked" enforcement path), if the MAC has a
non-empty effective extraAllowed list, gate the drop/DNAT with `ip daddr
!= @ea_<m>_<a>` (resp. `ip6 daddr != @ea6_<m>_<a>`) per host a. The
predicate doc at the top of render.lua reflects this — `m ∈ blocked_macs ∧
¬ea_hit(m, d)`.
