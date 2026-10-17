const WATCH_TTL = 7 * 86400;
const CHECK_INTERVAL = 300;
const DEVICE_LIMIT = 3;
const TARGET_LIMIT = 5;
const TARGET_SUBSCRIBERS = 3;
const MAX_PAGE_BYTES = 512 * 1024;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

export class WatchError extends Error {
  constructor(status, code, retryAfter = 0) {
    super(code);
    this.status = status;
    this.code = code;
    this.retryAfter = retryAfter;
  }
}

export function canonicalWatchUrl(value) {
  if (typeof value !== 'string' || value.length > 256
      || !/^https:\/\/m\.dcinside\.com\/(?:board|mini|person)\/[A-Za-z0-9_]{1,80}\/[1-9][0-9]{0,18}$/.test(value)) return null;
  try {
    const url = new URL(value);
    return url.origin === 'https://m.dcinside.com' && !url.username && !url.password
      && !url.port && !url.search && !url.hash && url.href === value ? url.href : null;
  } catch { return null; }
}

export function validWatchId(value) { return typeof value === 'string' && UUID.test(value); }

function publicWatch(row) {
  return {
    watchId: row.watch_id,
    url: row.url,
    baselineCount: row.baseline_count,
    lastChecked: row.last_checked,
    status: !row.fcm_token ? 'token_expired' : row.baseline_count === null && row.status === 'active' ? 'pending' : row.status,
    expiresAt: row.expires_at,
  };
}

async function ownWatches(env, installationId, now) {
  const result = await env.DB.prepare(`SELECT s.watch_id, t.url, s.baseline_count,
    t.last_checked, CASE WHEN e.attempts > 0 THEN 'delivery_error' ELSE t.status END AS status, s.expires_at, d.fcm_token
    FROM watch_subscriptions s JOIN watch_targets t USING(target_id)
    JOIN devices d USING(installation_id)
    LEFT JOIN watch_events e USING(watch_id)
    WHERE s.installation_id = ? AND s.expires_at > ? ORDER BY s.created_at DESC LIMIT ?`)
    .bind(installationId, now, DEVICE_LIMIT).all();
  return result.results.map(publicWatch);
}

export async function watchRoute(request, env, installationId, watchId, now, helpers) {
  const { reply, bodyObject, budget } = helpers;
  if (request.method === 'GET' && !watchId) {
    return reply(200, { watches: await ownWatches(env, installationId, now),
      intervalSeconds: CHECK_INTERVAL, maxWatches: DEVICE_LIMIT });
  }
  if (request.method === 'DELETE' && validWatchId(watchId)) {
    await env.DB.batch([
      env.DB.prepare('DELETE FROM watch_subscriptions WHERE installation_id = ? AND watch_id = ?')
        .bind(installationId, watchId),
      env.DB.prepare('DELETE FROM watch_targets WHERE NOT EXISTS (SELECT 1 FROM watch_subscriptions s WHERE s.target_id = watch_targets.target_id)'),
    ]);
    return reply(200, { removed: true });
  }
  if (request.method !== 'PUT' || watchId) throw new WatchError(404, 'not_found');
  const value = await bodyObject(request, ['url']);
  const url = canonicalWatchUrl(value.url);
  if (!url) throw new WatchError(400, 'invalid_watch_url');
  await budget(env.DB, `watch:${installationId}`, 10, 3600, now);
  const expiresAt = now + WATCH_TTL;
  // Every cap is enforced by SQL in this atomic batch, including concurrent adds.
  await env.DB.batch([
    env.DB.prepare('DELETE FROM watch_subscriptions WHERE expires_at <= ?').bind(now),
    env.DB.prepare('DELETE FROM watch_targets WHERE NOT EXISTS (SELECT 1 FROM watch_subscriptions s WHERE s.target_id = watch_targets.target_id)'),
    env.DB.prepare(`INSERT INTO watch_targets(target_id, url, next_check_at)
      SELECT ?, ?, ? WHERE (SELECT count(*) FROM watch_targets) < ?
      ON CONFLICT(url) DO NOTHING`).bind(crypto.randomUUID(), url, now, TARGET_LIMIT),
    env.DB.prepare(`INSERT INTO watch_subscriptions(watch_id, installation_id, target_id, created_at, expires_at)
      SELECT ?, ?, t.target_id, ?, ? FROM watch_targets t
      WHERE t.url = ? AND EXISTS (SELECT 1 FROM devices WHERE installation_id = ? AND fcm_token IS NOT NULL)
        AND (EXISTS (SELECT 1 FROM watch_subscriptions WHERE installation_id = ? AND target_id = t.target_id)
          OR ((SELECT count(*) FROM watch_subscriptions WHERE installation_id = ?) < ?
            AND (SELECT count(*) FROM watch_subscriptions WHERE target_id = t.target_id) < ?))
      ON CONFLICT(installation_id, target_id) DO UPDATE SET expires_at = excluded.expires_at`)
      .bind(crypto.randomUUID(), installationId, now, expiresAt, url,
        installationId, installationId, installationId, DEVICE_LIMIT, TARGET_SUBSCRIBERS),
    env.DB.prepare('DELETE FROM watch_targets WHERE NOT EXISTS (SELECT 1 FROM watch_subscriptions s WHERE s.target_id = watch_targets.target_id)'),
  ]);
  const watches = await ownWatches(env, installationId, now);
  const watch = watches.find(item => item.url === url);
  if (!watch) {
    const device = await env.DB.prepare('SELECT fcm_token FROM devices WHERE installation_id = ?')
      .bind(installationId).first();
    if (!device) throw new WatchError(401, 'unauthorized');
    if (!device.fcm_token) throw new WatchError(410, 'device_token_expired');
    throw new WatchError(409, 'watch_limit_reached');
  }
  return reply(200, { watch });
}

// Parsing is restricted to a server-rendered comment count. Authored text,
// article titles, comments, scripts, cookies and response headers are not retained.
export async function readPublicCount(url) {
  if (!canonicalWatchUrl(url)) throw new WatchError(400, 'invalid_watch_url');
  let response;
  try {
    response = await fetch(url, {
      method: 'GET', redirect: 'manual', credentials: 'omit',
      headers: { Accept: 'text/html', 'User-Agent': 'Moa-Public-Comment-Watch/1.0 (Android client; Mobile)' },
      signal: AbortSignal.timeout(10000),
    });
  } catch { throw new WatchError(502, 'unavailable'); }
  if (response.status === 403) { await response.body?.cancel(); throw new WatchError(403, 'blocked'); }
  if (response.status === 429) { await response.body?.cancel(); throw new WatchError(429, 'rate_limited'); }
  if (!response.ok || response.redirected || !/^text\/html(?:;|$)/i.test(response.headers.get('Content-Type') || '')) {
    await response.body?.cancel(); throw new WatchError(502, 'unavailable');
  }
  const declared = Number(response.headers.get('Content-Length') || 0);
  if (!Number.isSafeInteger(declared) || declared < 0 || declared > MAX_PAGE_BYTES || !response.body) {
    await response.body?.cancel(); throw new WatchError(502, 'unavailable');
  }
  let size = 0;
  const bounded = response.body.pipeThrough(new TransformStream({
    transform(chunk, controller) {
      size += chunk.byteLength;
      if (size > MAX_PAGE_BYTES) { controller.error(new Error('page_limit')); return; }
      controller.enqueue(chunk);
    },
  }));
  let count = null, matches = 0, headingMatches = 0, heading = '';
  try {
    const parsed = new HTMLRewriter().on('#comment_box .all-comment-lst > input#reple_totalCnt', {
      element(element) {
        matches++;
        const value = element.getAttribute('value');
        if (typeof value === 'string' && /^(?:0|[1-9][0-9]{0,6})$/.test(value)
            && Number(value) <= 1000000) count = Number(value);
      },
    }).on('#comment_box > .all-comment-tit .tit-box > a.tit > span.ct', {
      element() { headingMatches++; },
      text(chunk) { heading += chunk.text.slice(0, Math.max(0, 32 - heading.length)); },
    }).transform(new Response(bounded, { headers: { 'Content-Type': 'text/html; charset=utf-8' } }));
    const reader = parsed.body.getReader();
    for (;;) { if ((await reader.read()).done) break; }
  } catch { throw new WatchError(502, 'parse_error'); }
  // Empty comment sections omit their list/hidden input entirely. Their visible
  // server count is still present, so zero must be a valid initial baseline.
  const label = /^\[(0|[1-9][0-9]{0,6})\]$/.exec(heading.trim());
  const headingCount = headingMatches === 1 && label && Number(label[1]) <= 1000000 ? Number(label[1]) : null;
  if (matches > 1 || headingMatches > 1 || (matches === 1 && count === null)
      || (headingMatches === 1 && headingCount === null)
      || (matches === 1 && headingMatches === 1 && count !== headingCount)) throw new WatchError(502, 'parse_error');
  if (matches === 1) return count;
  if (headingMatches === 1) return headingCount;
  throw new WatchError(502, 'parse_error');
}

async function checkOneTarget(env, now) {
  const lease = crypto.randomUUID();
  const target = await env.DB.prepare(`UPDATE watch_targets SET lease_until = ?, lease_token = ?
    WHERE target_id = (SELECT t.target_id FROM watch_targets t
      WHERE t.next_check_at <= ? AND t.lease_until <= ?
        AND EXISTS (SELECT 1 FROM watch_subscriptions s JOIN devices d USING(installation_id)
          WHERE s.target_id = t.target_id AND s.expires_at > ? AND d.fcm_token IS NOT NULL)
      ORDER BY t.next_check_at, t.target_id LIMIT 1)
    RETURNING target_id, url, failures`).bind(now + 90, lease, now, now, now).first();
  if (!target) return;
  let count;
  try { count = await readPublicCount(target.url); }
  catch (error) {
    const status = error instanceof WatchError ? error.code : 'unavailable';
    const delay = status === 'blocked' ? 21600 : status === 'rate_limited' ? 3600
      : Math.min(21600, CHECK_INTERVAL * 2 ** Math.min(target.failures + 1, 6));
    await env.DB.prepare(`UPDATE watch_targets SET status = ?, last_checked = ?, next_check_at = ?,
      failures = MIN(failures + 1, 16), lease_until = 0, lease_token = NULL
      WHERE target_id = ? AND lease_token = ?`)
      .bind(status, now, now + delay, target.target_id, lease).run();
    return;
  }
  const eventId = crypto.randomUUID();
  await env.DB.batch([
    env.DB.prepare(`INSERT INTO watch_events(watch_id, event_id, created_at, available_at)
      SELECT s.watch_id, ?, ?, ? FROM watch_subscriptions s JOIN devices d USING(installation_id)
      WHERE s.target_id = ? AND s.expires_at > ? AND d.fcm_token IS NOT NULL
        AND s.baseline_count IS NOT NULL AND s.baseline_count < ?
        AND EXISTS (SELECT 1 FROM watch_targets WHERE target_id = ? AND lease_token = ?)
      ON CONFLICT(watch_id) DO NOTHING`)
      .bind(eventId, now, now, target.target_id, now, count, target.target_id, lease),
    env.DB.prepare(`UPDATE watch_subscriptions SET baseline_count = ? WHERE target_id = ?
      AND expires_at > ? AND EXISTS (SELECT 1 FROM watch_targets WHERE target_id = ? AND lease_token = ?)`)
      .bind(count, target.target_id, now, target.target_id, lease),
    env.DB.prepare(`UPDATE watch_targets SET baseline_count = ?, last_checked = ?, next_check_at = ?,
      status = 'active', failures = 0, lease_until = 0, lease_token = NULL
      WHERE target_id = ? AND lease_token = ?`)
      .bind(count, now, now + CHECK_INTERVAL, target.target_id, lease),
  ]);
}

async function deliverOneEvent(env, now, sendComment) {
  const lease = crypto.randomUUID();
  const pending = await env.DB.prepare(`UPDATE watch_events SET lease_until = ?, lease_token = ?
    WHERE watch_id = (SELECT e.watch_id FROM watch_events e
      JOIN watch_subscriptions s USING(watch_id) JOIN devices d USING(installation_id)
      WHERE e.available_at <= ? AND e.lease_until <= ? AND s.expires_at > ? AND d.fcm_token IS NOT NULL
      ORDER BY e.created_at, e.watch_id LIMIT 1) RETURNING watch_id, event_id, attempts`)
    .bind(now + 90, lease, now, now, now).first();
  if (!pending) return;
  // Recheck ownership/expiry/token after claiming; deletes cascade to this queue.
  const row = await env.DB.prepare(`SELECT t.url, s.installation_id, d.fcm_token
    FROM watch_events e JOIN watch_subscriptions s USING(watch_id)
    JOIN watch_targets t USING(target_id) JOIN devices d USING(installation_id)
    WHERE e.watch_id = ? AND e.lease_token = ? AND s.expires_at > ? AND d.fcm_token IS NOT NULL`)
    .bind(pending.watch_id, lease, now).first();
  if (!row) return;
  try {
    await sendComment(env, row.fcm_token, pending.event_id, row.url, now);
    await env.DB.prepare('DELETE FROM watch_events WHERE watch_id = ? AND lease_token = ?')
      .bind(pending.watch_id, lease).run();
  } catch (error) {
    if (error?.code === 'device_token_expired') {
      await env.DB.batch([
        env.DB.prepare('UPDATE devices SET fcm_token = NULL, token_hash = NULL, updated_at = ? WHERE installation_id = ? AND fcm_token = ?')
          .bind(now, row.installation_id, row.fcm_token),
        env.DB.prepare(`DELETE FROM watch_events WHERE watch_id IN
          (SELECT watch_id FROM watch_subscriptions WHERE installation_id = ?)
          AND EXISTS (SELECT 1 FROM devices WHERE installation_id = ? AND fcm_token IS NULL)`)
          .bind(row.installation_id, row.installation_id),
        // A concurrent token rotation must keep its pending event for retry.
        env.DB.prepare('UPDATE watch_events SET available_at = ?, lease_until = 0, lease_token = NULL WHERE watch_id = ? AND lease_token = ?')
          .bind(now + 60, pending.watch_id, lease),
      ]);
      return;
    }
    await env.DB.prepare(`UPDATE watch_events SET attempts = MIN(attempts + 1, 16),
      available_at = ?, lease_until = 0, lease_token = NULL WHERE watch_id = ? AND lease_token = ?`)
      .bind(now + Math.min(21600, 300 * 2 ** Math.min(pending.attempts, 6)), pending.watch_id, lease).run();
  }
}

export async function scheduledWatches(env, now, sendComment) {
  await env.DB.batch([
    env.DB.prepare('DELETE FROM watch_subscriptions WHERE expires_at <= ?').bind(now),
    env.DB.prepare('DELETE FROM watch_targets WHERE NOT EXISTS (SELECT 1 FROM watch_subscriptions s WHERE s.target_id = watch_targets.target_id)'),
  ]);
  // One public HTML fetch and at most one FCM send per invocation. No recursive
  // fetching, credentials, login, article crawling or automatic subscription.
  await checkOneTarget(env, now);
  await deliverOneEvent(env, now, sendComment);
}
