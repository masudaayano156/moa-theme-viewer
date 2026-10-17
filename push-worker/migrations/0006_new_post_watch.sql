-- New-post opt-in affects only the initial sample, never ownership verification.
ALTER TABLE watch_targets ADD COLUMN initial_watch INTEGER NOT NULL DEFAULT 0;
CREATE INDEX watch_device_recent ON watch_subscriptions(installation_id, created_at DESC, watch_id DESC);

-- Device queues stay indexed after removing the stored-subscription cap.
ALTER TABLE watch_events ADD COLUMN installation_id TEXT;
UPDATE watch_events SET installation_id =
  (SELECT s.installation_id FROM watch_subscriptions s WHERE s.watch_id = watch_events.watch_id);
CREATE INDEX watch_event_device_due ON watch_events(installation_id, available_at, watch_id);
-- Covers old Worker invocations still finishing during the migration/deploy gap.
CREATE TRIGGER watch_event_assign_device AFTER INSERT ON watch_events
WHEN NEW.installation_id IS NULL
BEGIN
  UPDATE watch_events SET installation_id =
    (SELECT s.installation_id FROM watch_subscriptions s WHERE s.watch_id = NEW.watch_id)
    WHERE watch_id = NEW.watch_id;
END;
