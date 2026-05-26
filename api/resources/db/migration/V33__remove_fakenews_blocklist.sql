-- #1037: drop the bundled `fakenews` blocklist. Editorializing on what
-- counts as "fake news" is a value judgment that belongs to the household
-- admin, not to the bundled set we ship.
--
-- Idempotent: re-running on a deployment that never had fakenews seeded
-- (or has already had it removed) is a no-op. profiles.blocked_categories
-- is a TEXT[]; array_remove is safe on rows that don't reference the id.

DELETE FROM blocklist_domains
WHERE category = 'fakenews';

DELETE FROM blocklists
WHERE id = 'fakenews';

UPDATE profiles
SET blocked_categories = array_remove(blocked_categories, 'fakenews')
WHERE blocked_categories && ARRAY['fakenews']::TEXT[];
