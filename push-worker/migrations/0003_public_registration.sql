CREATE TABLE public_registrations (
  registration_id TEXT PRIMARY KEY,
  installation_id TEXT NOT NULL,
  secret_hash TEXT NOT NULL,
  token_hash TEXT NOT NULL,
  fcm_token TEXT,
  challenge_hash TEXT,
  state TEXT NOT NULL CHECK(state IN ('pending', 'confirmed', 'failed', 'superseded')),
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE INDEX public_registration_expiry ON public_registrations(expires_at);
CREATE INDEX public_registration_owner ON public_registrations(installation_id, expires_at);

-- An OFF request can arrive before an earlier registration POST reaches D1.
-- Brief owner-bound tombstones prevent that request from recreating its device.
CREATE TABLE registration_cancellations (
  installation_id TEXT NOT NULL,
  secret_hash TEXT NOT NULL,
  expires_at INTEGER NOT NULL,
  PRIMARY KEY(installation_id, secret_hash)
);
CREATE INDEX registration_cancellation_expiry ON registration_cancellations(expires_at);
