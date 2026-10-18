import { readPublicArticleSnapshot } from './reply-parser.mjs';

export const ARTICLE_CHECK_INTERVAL = 150;
const MAX_TARGETS = 512;
const MAX_WRITES = 24;
const MAX_SNAPSHOT_BYTES = 65536;
const UUID = /^[0-9a-f-]{36}$/;

function rotate(ids, cursor) {
  const sorted = [...new Set(ids)].sort();
  return sorted.filter(id => id > cursor).concat(sorted.filter(id => id <= cursor));
}

/** A check invocation collects one article, then applies a bounded change batch.
 * Quiet targets share the article's poll clock instead of being rewritten.
 * The short-lived snapshot contains IDs/counts only, never HTML or login data. */
export async function checkArticleGroup(env, now, applyTarget, needsUpdate) {
  const token = crypto.randomUUID();
  const group = await env.DB.prepare(`UPDATE watch_articles SET lease_until = ?, lease_token = ?, dirty = CASE WHEN snapshot IS NULL THEN 0 ELSE dirty END
    WHERE article_url = (SELECT article_url FROM watch_articles WHERE next_check_at <= ? AND lease_until <= ?
      ORDER BY next_check_at, article_url LIMIT 1)
    RETURNING *`).bind(now + 90, token, now, now).first();
  if (!group) return { state: 'idle' };
  let records, collected = null;
  if (group.snapshot !== null) {
    try { records = JSON.parse(group.snapshot); }
    catch { records = null; }
    if (!Array.isArray(records) || records.length > MAX_TARGETS || group.snapshot.length > MAX_SNAPSHOT_BYTES
        || records.some(row => !Array.isArray(row) || row.length !== 3 || !UUID.test(row[0])
          || row[1] !== null && (!Number.isInteger(row[1]) || row[1] < 0 || row[1] > 1000000)
          || row[2] !== null && !/^(0|[1-9][0-9]{0,18})$/.test(row[2]))) {
      await env.DB.prepare(`UPDATE watch_articles SET snapshot = NULL, snapshot_cursor = 0, lease_until = 0, lease_token = NULL,
        next_check_at = ? WHERE article_url = ? AND lease_token = ?`).bind(now + 60, group.article_url, token).run();
      return { state: 'retry' };
    }
  } else {
    const selection = await env.DB.prepare(`SELECT t.* FROM watch_targets t
      WHERE t.article_url = ? AND t.target_id > ? AND EXISTS
        (SELECT 1 FROM watch_subscriptions s JOIN devices d USING(installation_id)
         WHERE s.target_id = t.target_id AND s.expires_at > ? AND d.fcm_token IS NOT NULL)
      ORDER BY t.target_id LIMIT ?`).bind(group.article_url, group.scan_cursor, now, MAX_TARGETS + 1).all();
    if (!selection.results.length) {
      if (group.scan_cursor) {
        await env.DB.prepare(`UPDATE watch_articles SET scan_cursor = '', next_check_at = ?, lease_until = 0, lease_token = NULL
          WHERE article_url = ? AND lease_token = ?`).bind(now, group.article_url, token).run();
        return { state: 'retry' };
      }
      const keep = await env.DB.prepare('SELECT 1 AS found FROM watch_targets WHERE article_url = ? LIMIT 1').bind(group.article_url).first();
      if (!keep) await env.DB.prepare('DELETE FROM watch_articles WHERE article_url = ? AND lease_token = ?').bind(group.article_url, token).run();
      else await env.DB.prepare(`UPDATE watch_articles SET paused_no_device = 1, next_check_at = ?, lease_until = 0, lease_token = NULL
        WHERE article_url = ? AND lease_token = ?`).bind(now + 86400, group.article_url, token).run();
      return { state: 'inactive' };
    }
    const targets = selection.results.slice(0, MAX_TARGETS);
    collected = new Map(targets.map(target => [target.target_id,target]));
    const fromSeq = Math.max(group.poll_seq, ...targets.map(target => target.poll_seq));
    // An old deployment may finish a target poll after migration. Normalize
    // the shared clock before network I/O so a concurrent manual subscription
    // still starts strictly after this in-flight sample.
    if (fromSeq > group.poll_seq) {
      const normalized = await env.DB.prepare(`UPDATE watch_articles SET poll_seq = ?
        WHERE article_url = ? AND lease_token = ? AND snapshot IS NULL`)
        .bind(fromSeq, group.article_url, token).run();
      if (normalized.meta?.changes !== 1) return { state: 'retry' };
      group.poll_seq = fromSeq;
    }
    group.next_scan_cursor = selection.results.length > MAX_TARGETS ? targets.at(-1).target_id : '';
    let sampled;
    try {
      const wanted = targets.filter(row => row.url.includes('?comment=')).map(row => row.url.split('?comment=')[1]);
      sampled = await readPublicArticleSnapshot(group.article_url, rotate(wanted, group.focus_cursor));
    } catch (error) {
      const status = ['blocked','rate_limited','parse_error'].includes(error?.code) ? error.code : 'unavailable';
      const delay = status === 'blocked' ? 21600 : status === 'rate_limited' ? 3600
        : Math.min(21600, ARTICLE_CHECK_INTERVAL * 2 ** Math.min(group.failures + 1, 6));
      await env.DB.prepare(`UPDATE watch_articles SET status = ?, last_checked = ?, next_check_at = ?,
        failures = MIN(failures + 1,16), lease_until = 0, lease_token = NULL WHERE article_url = ? AND lease_token = ?`)
        .bind(status, now, now + delay, group.article_url, token).run();
      return { state: status };
    }
    const replies = new Map(sampled.replies.map(row => [row.comment, row]));
    records = targets.map(target => {
      const comment = target.url.split('?comment=')[1], reply = comment ? replies.get(comment) : null;
      return [target.target_id, comment ? reply ? reply.count : null : sampled.totalCount, comment && reply ? reply.latestReplyNo : null];
    });
    const json = JSON.stringify(records);
    if (new TextEncoder().encode(json).length > MAX_SNAPSHOT_BYTES) throw new Error('Bounded article snapshot exceeded');
    const saved = await env.DB.prepare(`UPDATE watch_articles SET snapshot = ?, snapshot_at = ?, snapshot_from_seq = ?,
      snapshot_cursor = 0, next_scan_cursor = ?, focus_cursor = ?, next_check_at = ? WHERE article_url = ? AND lease_token = ?`)
      .bind(json, now, fromSeq, group.next_scan_cursor, sampled.lastFocus || group.focus_cursor, now, group.article_url, token).run();
    if (saved.meta?.changes !== 1) return { state: 'retry' };
    group.snapshot_at = now; group.snapshot_from_seq = fromSeq; group.snapshot_cursor = 0;
  }
  let cursor = group.snapshot_cursor, changed = 0;
  const remaining = records.slice(cursor);
  if (remaining.length) {
    // The collection SELECT already read baseline state. A quiet target does
    // not need a second read; every prospective mutation is still re-read
    // after network I/O, including a fresh publication's initial opt-in flag.
    const candidates = collected ? remaining.filter(record => {
      const target = collected.get(record[0]);
      return target && needsUpdate(target,record[1],record[2],group.snapshot_at,group.snapshot_from_seq);
    }) : remaining;
    const statements = [];
    // D1 permits at most 100 bound parameters per statement. Exact membership
    // also prevents concurrent registrations from expanding this snapshot.
    for (let start = 0; start < candidates.length; start += 80) {
      const ids = candidates.slice(start, start + 80).map(row => row[0]);
      statements.push(env.DB.prepare(`SELECT t.* FROM watch_targets t WHERE t.article_url = ?
        AND t.target_id IN (${ids.map(() => '?').join(',')}) AND EXISTS
        (SELECT 1 FROM watch_subscriptions s JOIN devices d USING(installation_id)
         WHERE s.target_id = t.target_id AND s.expires_at > ? AND d.fcm_token IS NOT NULL)`)
        .bind(group.article_url, ...ids, now));
    }
    const pages = statements.length ? await env.DB.batch(statements) : [];
    const byId = new Map(pages.flatMap(page => page.results).map(row => [row.target_id, row]));
    for (const record of remaining) {
      const target = byId.get(record[0]);
      if (target) {
        const mutation = await applyTarget(env, target, record[1], record[2], group.snapshot_at, group.snapshot_from_seq, group.article_url, token);
        if (mutation) changed++;
      }
      cursor++;
      if (changed >= MAX_WRITES) break;
    }
  }
  const done = cursor >= records.length;
  if (done) {
    await env.DB.prepare(`UPDATE watch_articles SET snapshot = NULL, snapshot_cursor = 0,
      last_checked = snapshot_at, poll_seq = snapshot_from_seq + 1, status = 'active', failures = 0, paused_no_device = 0,
      scan_cursor = next_scan_cursor, next_check_at = CASE WHEN dirty = 1 OR next_scan_cursor <> '' THEN ? ELSE ? END,
      lease_until = 0, lease_token = NULL WHERE article_url = ? AND lease_token = ?`)
      .bind(now, group.snapshot_at + ARTICLE_CHECK_INTERVAL, group.article_url, token).run();
  } else {
    await env.DB.prepare(`UPDATE watch_articles SET snapshot_cursor = ?, next_check_at = ?, lease_until = 0, lease_token = NULL
      WHERE article_url = ? AND lease_token = ?`).bind(cursor, now, group.article_url, token).run();
  }
  return { state: done ? 'checked' : 'applying', changed, examined: cursor - group.snapshot_cursor };
}
