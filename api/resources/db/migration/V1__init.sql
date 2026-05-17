-- V1__init.sql
-- FamilyDNS full schema

CREATE TABLE users (
  id            BIGSERIAL PRIMARY KEY,
  username      TEXT NOT NULL UNIQUE,
  password_hash TEXT NOT NULL,
  role          TEXT NOT NULL DEFAULT 'child' CHECK (role IN ('admin','adult','child')),
  created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE profiles (
  id                  BIGSERIAL PRIMARY KEY,
  name                TEXT NOT NULL,
  blocked_categories  TEXT[] NOT NULL DEFAULT '{}',
  extra_blocked       TEXT[] NOT NULL DEFAULT '{}',
  extra_allowed       TEXT[] NOT NULL DEFAULT '{}',
  paused              BOOLEAN NOT NULL DEFAULT FALSE,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE schedules (
  id           BIGSERIAL PRIMARY KEY,
  profile_id   BIGINT NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  name         TEXT NOT NULL,
  days         TEXT[] NOT NULL,
  block_from   TEXT NOT NULL,
  block_until  TEXT NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_schedules_profile ON schedules(profile_id);

-- Daily total time limit per profile (None = no limit)
CREATE TABLE time_limits (
  id             BIGSERIAL PRIMARY KEY,
  profile_id     BIGINT NOT NULL UNIQUE REFERENCES profiles(id) ON DELETE CASCADE,
  daily_minutes  INT NOT NULL CHECK (daily_minutes > 0),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Per-site time limits (tracked separately from total)
CREATE TABLE site_time_limits (
  id              BIGSERIAL PRIMARY KEY,
  profile_id      BIGINT NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  domain_pattern  TEXT NOT NULL,   -- e.g. "youtube.com" or "*.youtube.com"
  daily_minutes   INT NOT NULL CHECK (daily_minutes > 0),
  label           TEXT NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE(profile_id, domain_pattern)
);
CREATE INDEX idx_site_time_limits_profile ON site_time_limits(profile_id);

CREATE TABLE devices (
  id            BIGSERIAL PRIMARY KEY,
  mac           TEXT NOT NULL UNIQUE,
  name          TEXT NOT NULL,
  profile_id    BIGINT REFERENCES profiles(id) ON DELETE SET NULL,
  last_seen_ip  TEXT,
  last_seen_at  TIMESTAMPTZ,
  location      TEXT,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_devices_mac ON devices(mac);

CREATE TABLE blocklist_domains (
  id        BIGSERIAL PRIMARY KEY,
  domain    TEXT NOT NULL,
  category  TEXT NOT NULL,
  UNIQUE(domain, category)
);
CREATE INDEX idx_blocklist_domain   ON blocklist_domains(domain);
CREATE INDEX idx_blocklist_category ON blocklist_domains(category);

-- Time usage: accumulated per (mac, domain, date), updated by traffic monitor
CREATE TABLE time_usage (
  id           BIGSERIAL PRIMARY KEY,
  device_mac   TEXT NOT NULL,
  domain       TEXT NOT NULL,       -- apex domain e.g. "youtube.com"
  date         DATE NOT NULL,       -- local date for reset-at-midnight
  minutes_used INT NOT NULL DEFAULT 0,
  last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE(device_mac, domain, date)
);
CREATE INDEX idx_time_usage_mac_date   ON time_usage(device_mac, date);
CREATE INDEX idx_time_usage_domain     ON time_usage(domain);

-- Admin-granted time extensions
CREATE TABLE time_extensions (
  id             BIGSERIAL PRIMARY KEY,
  device_mac     TEXT NOT NULL,
  date           DATE NOT NULL,
  extra_minutes  INT NOT NULL CHECK (extra_minutes > 0),
  granted_by     TEXT NOT NULL,
  note           TEXT,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_time_extensions_mac_date ON time_extensions(device_mac, date);

CREATE TABLE query_logs (
  id           BIGSERIAL PRIMARY KEY,
  mac          TEXT,
  device_name  TEXT,
  profile_id   BIGINT,
  profile_name TEXT,
  domain       TEXT NOT NULL,
  qtype        INT NOT NULL DEFAULT 1,
  blocked      BOOLEAN NOT NULL,
  reason       TEXT NOT NULL,
  location     TEXT,
  ts           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_query_logs_ts      ON query_logs(ts DESC);
CREATE INDEX idx_query_logs_mac     ON query_logs(mac);
CREATE INDEX idx_query_logs_blocked ON query_logs(blocked);
CREATE INDEX idx_query_logs_domain  ON query_logs(domain);

-- Seed default profiles
INSERT INTO profiles (name, blocked_categories, paused) VALUES
  ('Kids',   ARRAY['adult','gambling','social_media','proxy'], FALSE),
  ('Adults', ARRAY[]::TEXT[], FALSE);

-- Seed bedtime schedule on Kids profile
INSERT INTO schedules (profile_id, name, days, block_from, block_until)
SELECT id, 'Bedtime',
  ARRAY['mon','tue','wed','thu','fri','sat','sun'],
  '21:00', '07:00'
FROM profiles WHERE name = 'Kids';

-- Seed default admin (password: changeme — must be changed on first login)
INSERT INTO users (username, password_hash, role) VALUES
  ('admin', '$2a$12$ldVwQxE6A.5oyXaMiu3bvuACgvwnN8wgDL5FAZ8s.81Yp9w0HHZ6.', 'admin');

-- Many-to-many: which profiles each user can see / manage
CREATE TABLE user_profiles (
  user_id    BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  profile_id BIGINT NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  PRIMARY KEY (user_id, profile_id)
);
CREATE INDEX idx_user_profiles_profile ON user_profiles(profile_id);

-- Link the seeded admin to all seeded profiles
INSERT INTO user_profiles (user_id, profile_id)
SELECT u.id, p.id FROM users u CROSS JOIN profiles p WHERE u.username = 'admin';
