-- #352: seed two test blocklists for development and integration tests.
-- test_ads: ad networks and trackers
-- test_social: social media platforms
--
-- ON CONFLICT DO NOTHING makes this migration idempotent.

INSERT INTO blocklist_domains (domain, category) VALUES
  ('adserver.example.com', 'test_ads'),
  ('doubleclick.net',      'test_ads'),
  ('googleadservices.com', 'test_ads'),
  ('facebook.com',         'test_social'),
  ('instagram.com',        'test_social'),
  ('tiktok.com',           'test_social')
ON CONFLICT DO NOTHING;
