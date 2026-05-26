-- #962: convert block_events.reason and connection_events.reason from TEXT
-- to JSONB using the kind-tagged BlockReason shape. Forward-only migration:
-- known string forms emitted by PolicyService / the router agent are
-- normalized to typed kinds; anything we don't recognize is preserved as
-- {"kind":"unknown","raw":<original>} so no historical event is dropped.
--
-- Coordinates with #1040 (V36 canonicalize_blocklist_slugs): this migration
-- (V40) runs after slug canonicalization, so any "category:<slug>" reasons
-- converted here already see canonical hyphenated slugs ("social-media",
-- not "social_media").

CREATE OR REPLACE FUNCTION wh_block_reason_from_wire(s TEXT) RETURNS JSONB AS $$
BEGIN
  IF s IS NULL THEN
    RETURN jsonb_build_object('kind', 'unknown', 'raw', '');
  END IF;
  RETURN CASE
    WHEN s IN ('allow', 'allowed')                  THEN jsonb_build_object('kind', 'allow')
    WHEN s = 'blocked'                              THEN jsonb_build_object('kind', 'blocked')
    WHEN s = 'extra_allowed'                        THEN jsonb_build_object('kind', 'extraAllowed')
    WHEN s IN ('extra_blocked', 'ExtraBlocked', 'host') THEN jsonb_build_object('kind', 'extraBlocked')
    WHEN s = 'no_profile'                           THEN jsonb_build_object('kind', 'noProfile')
    WHEN s IN ('paused', 'Paused')                  THEN jsonb_build_object('kind', 'paused')
    WHEN s IN ('schedule', 'Schedule')              THEN jsonb_build_object('kind', 'schedule')
    WHEN s IN ('time_limit', 'TimeLimit')           THEN jsonb_build_object('kind', 'timeLimit')
    WHEN s IN ('manual', 'Manual')                  THEN jsonb_build_object('kind', 'manual')
    WHEN s IN ('unmanaged_mac', 'Unmanaged', 'device_not_enrolled') THEN jsonb_build_object('kind', 'unmanaged')
    WHEN s LIKE 'category:%' AND substr(s, 10) ~ '^[a-z0-9][a-z0-9_-]{0,63}$'
                                                    THEN jsonb_build_object('kind', 'category', 'slug', substr(s, 10))
    WHEN s LIKE 'site_time_limit:%'                 THEN jsonb_build_object('kind', 'siteTimeLimit', 'label', substr(s, 17))
    WHEN s LIKE 'app:%'                             THEN jsonb_build_object('kind', 'appBlocked', 'appId', substr(s, 5))
    ELSE jsonb_build_object('kind', 'unknown', 'raw', s)
  END;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

ALTER TABLE block_events
  ADD COLUMN reason_json JSONB;

UPDATE block_events
  SET reason_json = wh_block_reason_from_wire(reason);

ALTER TABLE block_events
  ALTER COLUMN reason_json SET NOT NULL,
  DROP COLUMN reason;

ALTER TABLE block_events
  RENAME COLUMN reason_json TO reason;

ALTER TABLE connection_events
  ADD COLUMN reason_json JSONB;

UPDATE connection_events
  SET reason_json = wh_block_reason_from_wire(reason);

ALTER TABLE connection_events
  ALTER COLUMN reason_json SET NOT NULL,
  DROP COLUMN reason;

ALTER TABLE connection_events
  RENAME COLUMN reason_json TO reason;

DROP FUNCTION wh_block_reason_from_wire(TEXT);
