-- #771: router agent reports its package version on each policy fetch.
-- Optional field — legacy routers (and a router that just enrolled but
-- hasn't polled yet) leave this NULL.
ALTER TABLE routers ADD COLUMN agent_version TEXT NULL;
