-- #958: promote `blocklist_domains.category` to a first-class row with
-- display metadata (name, description, bundled flag, source URL, freshness).
-- See docs/design/blocklists.md.
--
-- The wire shape (policy_snapshot.blocklists) is unchanged; this table
-- backs the SPA management page and lets the bundled-list seeder track
-- provenance + last build time.
--
-- Backfill: for every distinct category already present in
-- blocklist_domains, seed a row in `blocklists` with the id as the name
-- placeholder. The startup bundled-list seeder will overwrite display
-- metadata for ids it owns; operator-or-test-created categories keep
-- the placeholder until someone curates them.

CREATE TABLE blocklists (
  id            TEXT PRIMARY KEY,
  display_name  TEXT NOT NULL,
  description   TEXT,
  bundled       BOOLEAN NOT NULL DEFAULT FALSE,
  source_url    TEXT,
  last_built_at TIMESTAMPTZ
);

INSERT INTO blocklists (id, display_name, bundled)
SELECT DISTINCT category, category, FALSE
FROM blocklist_domains
ON CONFLICT (id) DO NOTHING;
