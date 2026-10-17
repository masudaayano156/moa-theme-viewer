-- Shared revisions avoid rewriting every subscriber after unchanged polls.
ALTER TABLE devices ADD COLUMN watch_active_until INTEGER NOT NULL DEFAULT 0;
ALTER TABLE devices ADD COLUMN watch_renewed_day INTEGER NOT NULL DEFAULT -1;
CREATE INDEX watch_device_activity ON devices(watch_active_until);
UPDATE devices SET watch_active_until = COALESCE((SELECT MAX(expires_at) FROM watch_subscriptions s WHERE s.installation_id = devices.installation_id), 0);

ALTER TABLE watch_targets ADD COLUMN poll_seq INTEGER NOT NULL DEFAULT 0;
ALTER TABLE watch_targets ADD COLUMN paused_no_device INTEGER NOT NULL DEFAULT 0;
ALTER TABLE watch_targets ADD COLUMN revision INTEGER NOT NULL DEFAULT 0;
ALTER TABLE watch_targets ADD COLUMN increase_from_seq INTEGER NOT NULL DEFAULT 0;
ALTER TABLE watch_targets ADD COLUMN latest_event_id TEXT;
ALTER TABLE watch_targets ADD COLUMN fanout_pending INTEGER NOT NULL DEFAULT 0;
ALTER TABLE watch_targets ADD COLUMN fanout_revision INTEGER NOT NULL DEFAULT 0;
ALTER TABLE watch_targets ADD COLUMN fanout_from_seq INTEGER NOT NULL DEFAULT 0;
ALTER TABLE watch_targets ADD COLUMN fanout_event_id TEXT;
ALTER TABLE watch_targets ADD COLUMN fanout_cursor TEXT NOT NULL DEFAULT '';
ALTER TABLE watch_targets ADD COLUMN fanout_next_at INTEGER NOT NULL DEFAULT 0;
ALTER TABLE watch_targets ADD COLUMN fanout_lease_until INTEGER NOT NULL DEFAULT 0;
ALTER TABLE watch_targets ADD COLUMN fanout_lease_token TEXT;
UPDATE watch_targets SET poll_seq = 1 WHERE baseline_count IS NOT NULL;
CREATE INDEX watch_fanout_due ON watch_targets(fanout_pending, fanout_next_at);

ALTER TABLE watch_subscriptions ADD COLUMN start_poll_seq INTEGER NOT NULL DEFAULT 1;
ALTER TABLE watch_subscriptions ADD COLUMN delivered_revision INTEGER NOT NULL DEFAULT 0;
UPDATE watch_subscriptions SET start_poll_seq = CASE WHEN baseline_count IS NULL THEN 2 ELSE 1 END
  WHERE target_id IN (SELECT target_id FROM watch_targets WHERE poll_seq = 1);
CREATE INDEX watch_subscriber_cursor ON watch_subscriptions(target_id, watch_id);

ALTER TABLE watch_events ADD COLUMN revision INTEGER NOT NULL DEFAULT 0;
CREATE TABLE watch_delivery_queue (
  installation_id TEXT PRIMARY KEY REFERENCES devices(installation_id) ON DELETE CASCADE,
  ready_at INTEGER NOT NULL,
  paused INTEGER NOT NULL DEFAULT 0,
  lease_until INTEGER NOT NULL DEFAULT 0,
  lease_token TEXT
);
CREATE INDEX watch_delivery_due ON watch_delivery_queue(paused, ready_at);
INSERT INTO watch_delivery_queue(installation_id, ready_at)
  SELECT s.installation_id, MIN(e.available_at) FROM watch_events e JOIN watch_subscriptions s USING(watch_id)
  GROUP BY s.installation_id;

CREATE TABLE watch_job_budget (
  day INTEGER PRIMARY KEY,
  jobs INTEGER NOT NULL DEFAULT 0,
  writes_reserved INTEGER NOT NULL DEFAULT 0,
  reads_reserved INTEGER NOT NULL DEFAULT 0
);
