-- V14__connection_events_event_id.sql
-- Add a client-supplied UUID per connection_events row so a duplicate POST
-- (#330 retry queue replay, response-path failure, etc.) collapses on insert
-- rather than landing as a new BIGSERIAL row (#338).
--
-- Schema choice:
--   * DEFAULT gen_random_uuid() — older agents that don't carry an eventId
--     still get a unique value, so the table stays NOT NULL UNIQUE and the
--     ON CONFLICT clause is universal. Pre-#376 capability fallback.
--   * Unique index, not table-level UNIQUE, to mirror traffic_reports (#309).
ALTER TABLE connection_events
  ADD COLUMN event_id UUID NOT NULL DEFAULT gen_random_uuid();

CREATE UNIQUE INDEX idx_conn_events_event_id ON connection_events(event_id);
