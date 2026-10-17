-- Stop legacy device-wide renewal from keeping old posts active.
-- Per-subscription deadlines are authoritative in the new Worker.
UPDATE watch_subscriptions SET expires_at = MIN(expires_at, created_at + 259200);
UPDATE devices SET watch_active_until = 0, watch_renewed_day = -1;
