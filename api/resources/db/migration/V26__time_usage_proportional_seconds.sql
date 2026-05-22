-- V26__time_usage_proportional_seconds.sql
-- #715: per-FQDN time-on-site is bucket-max, not real time-on-site.
--
-- `seconds_used` is bucket-presence: every host the device touched in a 5-min
-- bucket gets credited with the whole bucket's duration. That's correct for
-- the per-device daily cap (which collapses across hosts) but wildly inflates
-- per-host minutes in the UI — a phone polling icloud.com once per bucket
-- while a 30-min youtube.com video plays will show both hosts at 30 minutes.
--
-- Add `proportional_seconds` as a parallel counter: each (mac, host)'s share
-- of the bucket duration is weighted by its share of bucket bytes
-- (bytes_in + bytes_out). Sum across hosts within a mac ≈ bucket duration,
-- so it's a much fairer "wall-clock attention" number for the screen-time UI.
-- `seconds_used` is preserved — daily-cap math still reads it (and the
-- bucket-deduped presence math doesn't touch this column at all).

ALTER TABLE time_usage
  ADD COLUMN proportional_seconds BIGINT NOT NULL DEFAULT 0;
