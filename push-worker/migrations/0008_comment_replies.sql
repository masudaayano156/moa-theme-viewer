-- Focused public-comment targets share the existing bounded work and delivery queues.
-- Only the highest public reply number is retained; no comment text or author is stored.
ALTER TABLE watch_targets ADD COLUMN last_reply_no TEXT;
