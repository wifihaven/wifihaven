-- #385: align FailureMode on three modes (BlockAll / AllowAll / LastKnownGood).
--
-- The original V10 column was a binary 'open' / 'closed' that collapsed two
-- semantically distinct modes — "pass everything" and "keep enforcing the
-- cached snapshot" — into one ('open'). The agent (render.lua + policy.lua)
-- picked the LastKnownGood interpretation (keep enforcing) for 'open', while
-- the design doc shipped both names. This migration aligns wire/storage on
-- the three-mode vocabulary so the doc, code, and agent agree.
--
-- Mapping (interpretation of CURRENT behaviour, not original intent):
--   'closed' → 'block-all'        (agent drops all forwarded traffic — #311)
--   'open'   → 'last-known-good'  (agent keeps the cached snapshot — #311)
--
-- Verified against the production DB (2026-05-14) before merge:
--   Kids   (no users)      failure_mode='closed'  → 'block-all'
--   Adults (admin user)    failure_mode='open'    → 'last-known-good'
--
-- The new explicit 'allow-all' mode has no pre-image in the binary column —
-- it is only reachable by an admin who deliberately picks it on the new UI.
--
-- Column default switches to 'last-known-good': it is the safe pick when the
-- API layer omits the field on profile insert (it preserves whatever
-- enforcement the cached snapshot already had rather than silently clearing
-- it). Role-aware defaulting (child → block-all, adult/admin →
-- last-known-good) is a UI concern; the server just persists whatever value
-- the admin selects, falling back to this column default when the field is
-- absent.

ALTER TABLE profiles
  ALTER COLUMN failure_mode DROP DEFAULT;

ALTER TABLE profiles
  DROP CONSTRAINT IF EXISTS profiles_failure_mode_check;

UPDATE profiles
   SET failure_mode = CASE failure_mode
                        WHEN 'closed' THEN 'block-all'
                        WHEN 'open'   THEN 'last-known-good'
                        ELSE failure_mode
                      END;

ALTER TABLE profiles
  ADD CONSTRAINT profiles_failure_mode_check
  CHECK (failure_mode IN ('block-all', 'allow-all', 'last-known-good'));

ALTER TABLE profiles
  ALTER COLUMN failure_mode SET DEFAULT 'last-known-good';
