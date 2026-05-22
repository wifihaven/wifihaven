-- #751: per-profile knob controlling how the screen-time total treats the
-- case where two devices on the same profile are active in the same 5-min
-- bucket. `sum` (default, current behavior) adds per-device totals; `dedup`
-- unions the per-device active-bucket sets so overlap counts once.
ALTER TABLE profiles
  ADD COLUMN cross_device_overlap_mode VARCHAR NOT NULL DEFAULT 'sum'
    CHECK (cross_device_overlap_mode IN ('sum', 'dedup'));
