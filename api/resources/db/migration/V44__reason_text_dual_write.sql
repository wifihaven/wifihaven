-- #1176 / #1179: back-compat reshape of V40 (TEXT → JSONB conversion).
--
-- V40 changed connection_events.reason and block_events.reason from TEXT to
-- JSONB in a single step. An image binding `reason` as TEXT cannot then read
-- or write the column, so a Render auto-rollback after V40 applied left prod
-- on `aa070c0` returning 503 BatchUpdateException on every connection_attempt
-- ingest. See #1176.
--
-- V44 introduces a parallel TEXT column `reason_text` that mirrors the
-- pre-V40 wire-format string (e.g. "category:porn", "blocked", "schedule").
-- Code in this PR dual-writes it from BlockReason.asWire(); a background
-- backfill populates it for rows inserted between V40 and V44. Reads still
-- come from the JSONB column — no SPA-visible change here.
--
-- This migration is schema-only: ADD COLUMN nullable, no inline UPDATE, no
-- type change. Conforms to #1179's back-compat invariant: an image that
-- predates V44 ignores the new column entirely; an image that knows about
-- reason_text degrades gracefully on NULL until the backfill completes.

ALTER TABLE connection_events
  ADD COLUMN reason_text TEXT;

ALTER TABLE block_events
  ADD COLUMN reason_text TEXT;
