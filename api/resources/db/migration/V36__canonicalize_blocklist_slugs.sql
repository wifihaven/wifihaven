-- #1040: canonicalize stale blocklist slugs in profiles.blocked_categories.
--
-- V1__init.sql seeded the default Kids profile with
--   ARRAY['adult','gambling','social_media','proxy']
-- but the post-#1009 bundled set uses hyphenated slugs and has no
-- `proxy` entry. Any deployment that ran V1 still carries 'social_media'
-- and 'proxy' references in profiles.blocked_categories — silent no-ops
-- under the current snapshot expansion (BlocklistRepo looks up by current
-- slug), so those categories aren't being enforced.
--
-- Fix: rewrite 'social_media' -> 'social-media' (operator intent
-- preserved), and strip 'proxy' (no current blocklist matches; document
-- in #1040 whether it should be re-added as a future bundled list).
--
-- Idempotent: array_replace and array_remove are no-ops when the target
-- value isn't present, so re-running this migration produces no diff.

UPDATE profiles
SET blocked_categories = array_replace(blocked_categories, 'social_media', 'social-media')
WHERE 'social_media' = ANY(blocked_categories);

UPDATE profiles
SET blocked_categories = array_remove(blocked_categories, 'proxy')
WHERE 'proxy' = ANY(blocked_categories);
