-- #960: unify device_alerts (#711) and the in-flight access-request rows
-- into a single `alerts` table.
--
-- Both surfaces are "something the admin needs to decide about" — a row in
-- a banner, a pending status that the admin resolves by approve / deny.
-- The state machine is the same; what differs is the side-effect on
-- approve (new_device → admit + assign profile; access_request → apply a
-- grant via existing primitives).
--
-- access_request rows weren't in any prior migration (the earlier V33
-- proposal didn't ship), so this lands as a single table from day one.
-- device_alerts rows already exist on installs that took V29 — we copy
-- them across before dropping the old table:
--
--   dismissed_at IS NULL      → status='pending'   (still on the banner)
--   dismissed_at IS NOT NULL  → status='approved'  (admin acknowledged;
--                                                   device stayed on the
--                                                   network, which is
--                                                   the historical
--                                                   semantic of dismiss)

CREATE TABLE alerts (
  id              BIGSERIAL PRIMARY KEY,
  -- Discriminator. `request_kind` is non-null only when kind='access_request'.
  kind            TEXT NOT NULL CHECK (kind IN ('new_device', 'access_request')),
  status          TEXT NOT NULL DEFAULT 'pending'
                    CHECK (status IN ('pending', 'approved', 'denied')),
  -- Common payload. mac is the device this alert is about; FK keeps the
  -- row from outliving its device (matches the old device_alerts shape).
  mac             TEXT NOT NULL REFERENCES devices(mac) ON DELETE CASCADE,
  -- Snapshot of the device's profile at create time, so the review UI can
  -- show the kid's profile name even if the device is reassigned later.
  profile_id      BIGINT REFERENCES profiles(id) ON DELETE SET NULL,
  -- access_request-only payload.
  host            TEXT,
  request_kind    TEXT CHECK (request_kind IN ('extension', 'exemption', 'unpause')),
  note            TEXT,
  granted_minutes INT,
  -- Lifecycle.
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  decided_at      TIMESTAMPTZ,
  decided_by      TEXT,
  -- Shape invariants per kind. new_device rows have no host/request_kind/
  -- note/granted_minutes; access_request rows must carry host + request_kind.
  CHECK (
    (kind = 'new_device'
       AND host IS NULL
       AND request_kind IS NULL
       AND note IS NULL
       AND granted_minutes IS NULL)
    OR
    (kind = 'access_request'
       AND host IS NOT NULL
       AND request_kind IS NOT NULL)
  )
);

-- Banner-feed path: pending rows, newest first.
CREATE INDEX idx_alerts_pending
  ON alerts(created_at DESC)
  WHERE status = 'pending';

-- Debounce path for access_request creates: "is there a recent pending
-- row for this (mac, host)?"
CREATE INDEX idx_alerts_access_request_recent
  ON alerts(mac, host, created_at DESC)
  WHERE kind = 'access_request';

-- Migrate legacy device_alerts rows. The old table dropped the alert when
-- the device was deleted (ON DELETE CASCADE), so we can rely on the mac
-- still being present in devices for every surviving row.
INSERT INTO alerts (kind, status, mac, created_at, decided_at)
SELECT
  'new_device',
  CASE WHEN dismissed_at IS NULL THEN 'pending' ELSE 'approved' END,
  mac,
  first_seen_at,
  dismissed_at
FROM device_alerts;

DROP TABLE device_alerts;
