# Operations

Runbooks for operating a deployed FamilyDNS test or production setup.

## Developer access to the test router (WAN-side SSH)

### Why this exists

The test router enforces policy on every device that sits behind its LAN
ports. When a developer's Mac sits behind the test router, any policy
state that drops that Mac (a paused profile assignment, a schedule block,
a failover BlockAll mode, an `extraBlocked` entry that accidentally
matches, etc.) will sever the developer's SSH session mid-debug.

The robust development model is to **keep the developer Mac off the
test router's LAN entirely**: put it on the upstream network alongside
the API server, and reach the router via its WAN-side interface. WAN-side
SSH is unaffected by per-MAC enforcement on the LAN forward chain, so no
policy state on the router can lock the developer out.

This is a development-only access path. It is not a product feature —
end-user routers should not expose SSH on WAN.

### Topology

```
                +-------------------+
                |  Upstream router  |  (home internet gateway)
                |  192.168.10.0/24  |
                +---------+---------+
                          |
        +-----------------+-----------------+
        |                 |                 |
   Developer Mac    API server         Test router WAN
                    192.168.10.43      192.168.10.42 (eth1)
                                              |
                                       +------+------+
                                       | Test router |
                                       |  br-lan     |
                                       | 192.168.1.1 |
                                       +------+------+
                                              |
                                       Devices under test
                                       192.168.1.0/24
```

The developer Mac and the API server share the upstream `192.168.10.0/24`
network. The test router's WAN interface (`eth1`) lives on that same
network at `192.168.10.42`. SSH to the test router uses that WAN address.

### Open WAN-side SSH

These are the one-time setup commands on the test router. Run them while
you still have working LAN access (or via serial console).

```sh
# 1. Make dropbear listen on all interfaces (no Interface allowlist).
#    On a fresh OpenWRT image dropbear already binds to all interfaces;
#    this is a no-op unless someone previously restricted it.
uci -q delete dropbear.main.Interface
uci commit dropbear
/etc/init.d/dropbear restart

# 2. Add a firewall rule allowing TCP 22 inbound on the WAN zone,
#    scoped to the upstream management subnet. Do NOT leave src_ip
#    unset — that would expose SSH to the entire WAN side.
uci add firewall rule
uci set firewall.@rule[-1].name='Dev-SSH-from-mgmt'
uci set firewall.@rule[-1].src='wan'
uci set firewall.@rule[-1].src_ip='192.168.10.0/24'
uci set firewall.@rule[-1].proto='tcp'
uci set firewall.@rule[-1].dest_port='22'
uci set firewall.@rule[-1].target='ACCEPT'
uci set firewall.@rule[-1].family='ipv4'
uci commit firewall
/etc/init.d/firewall reload
```

**The two changes are independent.** Dropbear's interface allowlist
(System → Administration in LuCI) controls which interfaces the SSH
daemon binds to. The firewall rule (Network → Firewall) controls which
packets the kernel allows to reach the daemon. Toggling only one is the
common gotcha — dropbear can listen on WAN all day, but if the WAN zone
rejects the SYN you never reach it.

### Verify

From the router:

```sh
nft list chain inet fw4 input_wan | grep 'dport 22'
# expect:  tcp dport 22 ... accept comment "!fw4: Dev-SSH-from-mgmt"

netstat -tlnp | grep ':22 '
# expect dropbear listening on 0.0.0.0:22 (and :::22 for v6)
```

From the developer Mac (on the `192.168.10.0/24` network):

```sh
ssh root@192.168.10.42
```

### Close WAN-side SSH

When you no longer need WAN SSH (e.g. moving the router toward a
production-like configuration, or running the security audit in #369),
remove the firewall rule. Find the rule's index — the name we set above
makes it easy to spot:

```sh
uci show firewall | grep -B1 "Dev-SSH-from-mgmt"
# example output:
#   firewall.@rule[9].name='Dev-SSH-from-mgmt'

# Delete by index, then commit and reload. Substitute the index you saw.
uci delete firewall.@rule[9]
uci commit firewall
/etc/init.d/firewall reload
```

Confirm the rule is gone:

```sh
nft list chain inet fw4 input_wan | grep 'dport 22' || echo "closed"
```

If you also tightened the dropbear binding earlier and want to restore
LAN-only listening:

```sh
uci set dropbear.main.Interface='lan'
uci commit dropbear
/etc/init.d/dropbear restart
```

### Troubleshooting

- **`ssh: connect to host 192.168.10.42 port 22: Operation timed out`** —
  the firewall rule is missing or the rule's `src` is not `wan`. Re-run
  `uci show firewall | grep -A5 Dev-SSH-from-mgmt` and check the rule
  is on `src='wan'`. If it shows on `src='lan'`, LuCI placed it in the
  wrong zone; delete and re-add with the commands above.
- **`Connection refused`** — packet reached the router but dropbear
  isn't listening on that interface. Check `netstat -tlnp | grep ':22 '`
  and `uci show dropbear`. If dropbear shows `Interface='lan'`, clear
  it as in step 1.
- **`Permission denied (publickey)`** — firewall and dropbear are fine;
  this is an SSH-key problem, not a network problem. Copy the dev Mac's
  public key into `/etc/dropbear/authorized_keys` on the router.
