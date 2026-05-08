-- V5__profile_time_extensions.sql
-- Move time extensions from per-device to per-profile.
-- device_mac is made nullable so existing rows keep their data.
-- New grants set profile_id and leave device_mac NULL.
ALTER TABLE time_extensions
  ALTER COLUMN device_mac DROP NOT NULL,
  ADD COLUMN profile_id BIGINT REFERENCES profiles(id) ON DELETE CASCADE;

CREATE INDEX idx_time_extensions_profile_date
  ON time_extensions(profile_id, date)
  WHERE profile_id IS NOT NULL;
