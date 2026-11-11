-- V95__drop_support_press_tables.sql
-- #2961 (part of #2954): drop the tables that only the removed support and
-- press responders used. The code that read and wrote them was removed in
-- #2968 (e3219cdd); prod was confirmed serving a later SHA before this was
-- written, and no source under api/, web/ or scripts/ references any of them.
--
-- SCHEMA-ONLY PR: per docs/process/migrations.md#migrations-back-compat this
-- ships alone. The existing feature suite applying V95 is the gate: the
-- current code never references these tables.
--
-- ── Prod data volume (docs/process/migrations.md#migrations-prod-data-volume) ─
-- Prod sizes measured read-only before writing this:
--   press_messages            12 rows, 96 kB total relation size
--   support_thread_consent     4 rows, 48 kB
--   support_consent_link_use   5 rows, 48 kB
-- None is an unbounded-growth table. DROP TABLE takes a brief ACCESS EXCLUSIVE
-- lock on tables nothing queries; runtime is milliseconds.
--
-- Dropping a table also drops its indexes and constraints:
--   press_messages_created_at_idx (V71), uq_support_thread_consent (V84),
--   support_consent_link_use_outstanding_idx (V89), and the BIGSERIAL sequences
--   press_messages_id_seq and support_thread_consent_id_seq (owned by their id
--   columns). No other table has a foreign key into any of the three.
--
-- Deliberately kept: V86's one-admin-per-household constraint on users. It was
-- motivated by the Plain customer mapping but is a data-integrity rule on
-- users that the rest of the app relies on.

-- press_messages has a self-referencing FK (in_reply_to), which DROP TABLE
-- handles itself. The other two reference households/users, not each other.
DROP TABLE IF EXISTS support_consent_link_use;
DROP TABLE IF EXISTS support_thread_consent;
DROP TABLE IF EXISTS press_messages;
