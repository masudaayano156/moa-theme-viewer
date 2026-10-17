CREATE TABLE watch_targets (
  target_id TEXT PRIMARY KEY,
  url TEXT NOT NULL UNIQUE,
  baseline_count INTEGER,
  last_checked INTEGER NOT NULL DEFAULT 0,
  next_check_at INTEGER NOT NULL,
  status TEXT NOT NULL DEFAULT 'pending',
  failures INTEGER NOT NULL DEFAULT 0,
  lease_until INTEGER NOT NULL DEFAULT 0,
  lease_token TEXT
);
CREATE INDEX watch_targets_due ON watch_targets(next_check_at, lease_until);

CREATE TABLE watch_subscriptions (
  watch_id TEXT PRIMARY KEY,
  installation_id TEXT NOT NULL REFERENCES devices(installation_id) ON DELETE CASCADE,
  target_id TEXT NOT NULL REFERENCES watch_targets(target_id) ON DELETE CASCADE,
  baseline_count INTEGER,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  UNIQUE(installation_id, target_id)
);
CREATE INDEX watch_subscriptions_expiry ON watch_subscriptions(expires_at);
CREATE INDEX watch_subscriptions_target ON watch_subscriptions(target_id, expires_at);

-- At most one coalesced notification is retained for each subscription.
CREATE TABLE watch_events (
  watch_id TEXT PRIMARY KEY REFERENCES watch_subscriptions(watch_id) ON DELETE CASCADE,
  event_id TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  available_at INTEGER NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0,
  lease_until INTEGER NOT NULL DEFAULT 0,
  lease_token TEXT
);
CREATE INDEX watch_events_due ON watch_events(available_at, lease_until);
