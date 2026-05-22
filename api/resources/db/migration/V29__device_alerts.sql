-- #711: new-device alerts. One row per auto-discovered MAC. The admin
-- dismisses each one explicitly via /api/device-alerts/{id}/dismiss
-- (no implicit "last reviewed" timestamp — explicit per-alert state keeps
-- the SPA banner unambiguous when several arrive in a burst).
--
-- Single-household install, so no household_id. Foreign-key on mac so the
-- alert disappears if the device row is deleted; ON DELETE CASCADE because
-- a stale alert against a deleted device has no actionable target.
CREATE TABLE device_alerts (
  id              BIGSERIAL PRIMARY KEY,
  mac             TEXT        NOT NULL REFERENCES devices(mac) ON DELETE CASCADE,
  first_seen_at   TIMESTAMPTZ NOT NULL,
  dismissed_at    TIMESTAMPTZ,
  UNIQUE (mac)
);

CREATE INDEX idx_device_alerts_pending ON device_alerts(first_seen_at DESC) WHERE dismissed_at IS NULL;
