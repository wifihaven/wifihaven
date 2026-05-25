-- #706 / #958: remove the dev-only test_ads / test_social seed from every
-- deployment. The original V11 migration unconditionally inserted these
-- rows, leaking dev data into prod. From v958-onward the test seed is
-- gated behind the WIFIHAVEN_SEED_TEST_BLOCKLISTS env flag and applied
-- at API startup (not via migration), so a fresh prod enrollment will
-- never carry them.
--
-- Idempotent: re-running on a deployment that has no test_* rows is a
-- no-op. profiles.blocked_categories is a TEXT[]; array_remove is safe
-- on rows that don't reference the ids.

DELETE FROM blocklist_domains
WHERE category IN ('test_ads', 'test_social');

DELETE FROM blocklists
WHERE id IN ('test_ads', 'test_social');

UPDATE profiles
SET blocked_categories = array_remove(
      array_remove(blocked_categories, 'test_ads'),
      'test_social'
    )
WHERE blocked_categories && ARRAY['test_ads', 'test_social']::TEXT[];
