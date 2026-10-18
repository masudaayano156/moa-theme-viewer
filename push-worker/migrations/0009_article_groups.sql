-- Share public reads and quiet-poll state across every watched comment in a post.
ALTER TABLE watch_targets ADD COLUMN article_url TEXT;
UPDATE watch_targets SET article_url = CASE WHEN instr(url, '?') > 0 THEN substr(url, 1, instr(url, '?') - 1) ELSE url END;
CREATE INDEX watch_target_article ON watch_targets(article_url, target_id);
CREATE TABLE watch_articles (
  article_url TEXT PRIMARY KEY,
  next_check_at INTEGER NOT NULL,
  last_checked INTEGER NOT NULL DEFAULT 0,
  poll_seq INTEGER NOT NULL DEFAULT 0,
  status TEXT NOT NULL DEFAULT 'pending',
  failures INTEGER NOT NULL DEFAULT 0,
  paused_no_device INTEGER NOT NULL DEFAULT 0,
  lease_until INTEGER NOT NULL DEFAULT 0,
  lease_token TEXT,
  snapshot TEXT,
  snapshot_at INTEGER NOT NULL DEFAULT 0,
  snapshot_from_seq INTEGER NOT NULL DEFAULT 0,
  snapshot_cursor INTEGER NOT NULL DEFAULT 0,
  scan_cursor TEXT NOT NULL DEFAULT '',
  next_scan_cursor TEXT NOT NULL DEFAULT '',
  focus_cursor TEXT NOT NULL DEFAULT '',
  dirty INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX watch_article_due ON watch_articles(next_check_at, lease_until);
CREATE INDEX watch_article_paused ON watch_articles(paused_no_device, article_url);
INSERT INTO watch_articles(article_url, next_check_at, last_checked, poll_seq, status)
  SELECT article_url, MAX(MIN(next_check_at), MAX(CASE WHEN status IN ('blocked','rate_limited') THEN next_check_at ELSE 0 END)),
    MIN(last_checked), MAX(poll_seq),
    CASE WHEN SUM(status = 'blocked') > 0 THEN 'blocked' WHEN SUM(status = 'rate_limited') > 0 THEN 'rate_limited' ELSE 'active' END
  FROM watch_targets GROUP BY article_url;
-- Translate every old clock by the same offset, preserving eligibility for
-- already pending fanout as well as manual subscriptions awaiting a baseline.
UPDATE watch_subscriptions SET start_poll_seq = CASE WHEN start_poll_seq = 0 THEN 0 ELSE
  (SELECT a.poll_seq + watch_subscriptions.start_poll_seq - t.poll_seq FROM watch_targets t
   JOIN watch_articles a USING(article_url) WHERE t.target_id = watch_subscriptions.target_id) END;
UPDATE watch_targets SET
  increase_from_seq = increase_from_seq + (SELECT a.poll_seq FROM watch_articles a WHERE a.article_url = watch_targets.article_url) - poll_seq,
  fanout_from_seq = fanout_from_seq + (SELECT a.poll_seq FROM watch_articles a WHERE a.article_url = watch_targets.article_url) - poll_seq,
  poll_seq = (SELECT a.poll_seq FROM watch_articles a WHERE a.article_url = watch_targets.article_url);

-- Old requests can still finish while the schema and Worker versions overlap.
CREATE TRIGGER watch_target_assign_article AFTER INSERT ON watch_targets
WHEN NEW.article_url IS NULL
BEGIN
  UPDATE watch_targets SET article_url = CASE WHEN instr(NEW.url,'?') > 0 THEN substr(NEW.url,1,instr(NEW.url,'?')-1) ELSE NEW.url END
    WHERE target_id = NEW.target_id;
END;
CREATE TRIGGER watch_target_create_article AFTER UPDATE OF article_url ON watch_targets
WHEN NEW.article_url IS NOT NULL
BEGIN
  INSERT INTO watch_articles(article_url,next_check_at,poll_seq) VALUES(NEW.article_url,NEW.next_check_at,NEW.poll_seq)
    ON CONFLICT(article_url) DO NOTHING;
END;
CREATE TRIGGER watch_target_align_article_clock AFTER UPDATE OF article_url ON watch_targets
WHEN NEW.article_url IS NOT NULL AND NEW.poll_seq < COALESCE((SELECT a.poll_seq FROM watch_articles a WHERE a.article_url = NEW.article_url),NEW.poll_seq)
BEGIN
  UPDATE watch_targets SET
    increase_from_seq = increase_from_seq + (SELECT a.poll_seq FROM watch_articles a WHERE a.article_url = NEW.article_url) - poll_seq,
    fanout_from_seq = fanout_from_seq + (SELECT a.poll_seq FROM watch_articles a WHERE a.article_url = NEW.article_url) - poll_seq,
    poll_seq = (SELECT a.poll_seq FROM watch_articles a WHERE a.article_url = NEW.article_url)
    WHERE target_id = NEW.target_id;
END;
CREATE TRIGGER watch_subscription_article_clock AFTER INSERT ON watch_subscriptions
WHEN NEW.start_poll_seq <> 0
BEGIN
  UPDATE watch_subscriptions SET start_poll_seq = COALESCE((SELECT MAX(a.poll_seq,t.poll_seq) + 1 FROM watch_targets t
    JOIN watch_articles a USING(article_url) WHERE t.target_id = NEW.target_id),NEW.start_poll_seq)
    WHERE watch_id = NEW.watch_id;
END;
CREATE TRIGGER watch_target_prune_article AFTER DELETE ON watch_targets
BEGIN
  DELETE FROM watch_articles WHERE article_url = OLD.article_url
    AND NOT EXISTS(SELECT 1 FROM watch_targets t WHERE t.article_url = OLD.article_url);
END;
