-- #586: force admin password change on first login.
--
-- Adds must_change_password flag to users. While true, every authenticated
-- API request (other than POST /api/auth/change-password and POST
-- /api/auth/logout) returns 403 {"error":"password_change_required"}.
--
-- The flag is set true for the seeded admin row (password: changeme) so
-- that a freshly-deployed Render env cannot serve any real request until
-- the operator rotates the password. It is cleared by AuthService on a
-- successful change-password call.
--
-- Default false: existing non-admin rows and any new users created via the
-- API start with the flag off (they were already given a real password).

ALTER TABLE users
  ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT false;

-- The seeded admin row has the well-known default password that must be changed.
UPDATE users
  SET must_change_password = true
  WHERE username = 'admin';
