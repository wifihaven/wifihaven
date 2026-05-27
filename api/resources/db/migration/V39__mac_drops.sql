-- #1103: surface MAC-level packet drops from nftables `@blocked_macs` (paused /
-- schedule / time_limit / manual / unmanaged) as a first-class series.
--
-- `connection_events` records per-name DNS-resolved attempts. When a MAC is
-- blocked at the firewall layer the packets never reach dnsmasq, so the
-- chart goes silent. The router agent polls per-(mac, reason) counter rules
-- and posts deltas; we store them here so /api/mac-drops/series can return
-- a second band on the Logs chart.
--
-- Unique key is (router_id, mac, reason, period_end) — replays from the
-- agent's retry queue collapse on ON CONFLICT DO NOTHING.

CREATE TABLE mac_drops (
  id           bigserial PRIMARY KEY,
  router_id    uuid NOT NULL REFERENCES routers(id) ON DELETE CASCADE,
  -- TEXT (not macaddr) to match the household-wide convention — see V4
  -- connection_events.mac and V1 devices.mac. Mixing column types makes
  -- joins clunky and Meta[MacAddress] is Meta[String].
  mac          TEXT NOT NULL,
  reason       text NOT NULL,
  packets      bigint NOT NULL CHECK (packets >= 0),
  bytes        bigint NOT NULL CHECK (bytes >= 0),
  period_start timestamptz NOT NULL,
  period_end   timestamptz NOT NULL,
  UNIQUE (router_id, mac, reason, period_end)
);

CREATE INDEX mac_drops_ts ON mac_drops (period_end);
CREATE INDEX mac_drops_mac_ts ON mac_drops (mac, period_end);
