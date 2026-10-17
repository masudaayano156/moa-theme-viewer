CREATE TABLE pairing_codes (
  code_hash TEXT PRIMARY KEY,
  expires_at INTEGER NOT NULL
);
CREATE INDEX pairing_expiry ON pairing_codes(expires_at);

CREATE TABLE devices (
  installation_id TEXT PRIMARY KEY,
  secret_hash TEXT NOT NULL,
  fcm_token TEXT,
  token_hash TEXT UNIQUE,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  last_test_at INTEGER NOT NULL DEFAULT 0,
  test_window_start INTEGER NOT NULL DEFAULT 0,
  test_count INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE rate_limits (
  bucket_key TEXT NOT NULL,
  window_start INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  hits INTEGER NOT NULL,
  PRIMARY KEY(bucket_key, window_start)
);
CREATE INDEX rate_expiry ON rate_limits(expires_at);
