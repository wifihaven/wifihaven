-- Drop query_logs table. The dns/ module (its sole writer) was removed in #71.
-- The dashboard and log routes now read from connection_events (#95).
DROP TABLE IF EXISTS query_logs CASCADE;
