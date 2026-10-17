-- Only an opaque test event identifier, timestamps and bounded result codes.
-- Never store message text, source URLs or upstream error responses here.
ALTER TABLE devices ADD COLUMN last_test_event_id TEXT;
ALTER TABLE devices ADD COLUMN last_test_result_code TEXT NOT NULL DEFAULT 'none';
ALTER TABLE devices ADD COLUMN last_test_accepted_at INTEGER NOT NULL DEFAULT 0;
UPDATE devices SET last_test_result_code = 'unknown' WHERE last_test_at > 0;
