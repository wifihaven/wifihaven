-- #706: prod cloud DB had `test_ads` / `test_social` rows from the V11 dev
-- seed running unconditionally. Remove the rows everywhere; dev re-seeds
-- after migrations via the in-app DevSeeder (runs only when
-- WIFIHAVEN_ENV=dev), so this is safe on dev too.

DELETE FROM blocklist_domains
 WHERE category IN ('test_ads', 'test_social');

-- Strip any profile references too. blocked_categories is TEXT[]; no prod
-- profile is known to reference these, but be defensive.
UPDATE profiles
   SET blocked_categories = array_remove(
                              array_remove(blocked_categories, 'test_ads'),
                              'test_social'
                            )
 WHERE 'test_ads'    = ANY(blocked_categories)
    OR 'test_social' = ANY(blocked_categories);
