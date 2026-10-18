import { commentTarget, laterCommentId, readPublicReplySnapshot, ReplyReadError } from './reply-parser.mjs';

const WATCH_TTL = 3 * 86400;
const CHECK_INTERVAL = 300;
const LIST_LIMIT = 20;
const FANOUT_CHUNK = 16;
const DAY_JOBS = 20000;
const DAY_WRITES = 70000;
const DAY_READS = 3000000;
const JOB_COST = { check: [8, 128], deliver: [14, 160], fanout: [144, 256] };
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
      || !/^https:\/\/m\.dcinside\.com\/(?:board|mini|person)\/[A-Za-z0-9_]{1,80}\/[1-9][0-9]{0,18}(?:\?comment=[1-9][0-9]{0,18})?$/.test(value)) return null;
  try {
    const url = new URL(value);
    return url.origin === 'https://m.dcinside.com' && !url.username && !url.password
      && !url.port && (!url.search || commentTarget(value)) && !url.hash && url.href === value ? url.href : null;
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
  const result = await env.DB.prepare(`WITH recent AS (SELECT * FROM watch_subscriptions
    WHERE installation_id = ? ORDER BY created_at DESC, watch_id DESC LIMIT ?)
    SELECT s.watch_id, t.url,
    CASE WHEN t.poll_seq >= s.start_poll_seq THEN t.baseline_count ELSE NULL END AS baseline_count,
    t.last_checked, CASE WHEN e.attempts > 0 THEN 'delivery_error' ELSE t.status END AS status,
    s.expires_at AS expires_at, d.fcm_token
    FROM recent s JOIN watch_targets t USING(target_id)
    JOIN devices d USING(installation_id)
    LEFT JOIN watch_events e USING(watch_id)
    WHERE s.expires_at > ? ORDER BY s.created_at DESC, s.watch_id DESC`)
    .bind(installationId, LIST_LIMIT, now).all();
  return result.results.map(publicWatch);
}

async function ownWatchAtUrl(env, installationId, url, now) {
  const row = await env.DB.prepare(`SELECT s.watch_id, t.url,
    CASE WHEN t.poll_seq >= s.start_poll_seq THEN t.baseline_count ELSE NULL END AS baseline_count,
    t.last_checked, CASE WHEN e.attempts > 0 THEN 'delivery_error' ELSE t.status END AS status,
    s.expires_at AS expires_at, d.fcm_token
    FROM watch_targets t JOIN watch_subscriptions s USING(target_id) JOIN devices d USING(installation_id)
    LEFT JOIN watch_events e USING(watch_id)
    WHERE t.url = ? AND s.installation_id = ? AND s.expires_at > ? LIMIT 1`)
    .bind(url, installationId, now).first();
  return row ? publicWatch(row) : null;
}

export async function watchRoute(request, env, installationId, watchId, now, helpers) {
  const { reply, bodyObject, budget } = helpers;
  if (request.method === 'GET' && !watchId) {
    // Reading this list never extends a post's three-day checking window.
    await resumeWatchDelivery(env, installationId, now);
    const usage = await env.DB.prepare('SELECT jobs, writes_reserved, reads_reserved FROM watch_job_budget WHERE day = ?').bind(day(now)).first();
    const paused = await pendingWorkPaused(env, installationId, now, usage);
    const watches = await ownWatches(env, installationId, now);
    const delayed = watches.some(watch => watch.status === 'active' && watch.lastChecked > 0 && now - watch.lastChecked > CHECK_INTERVAL * 2);
    return reply(200, { watches,
      intervalSeconds: CHECK_INTERVAL, listLimit: LIST_LIMIT, maxWatches: null, expiryDays: 3,
      automaticRenewal: false, bestEffort: true, queueStatus: paused ? 'quota_paused' : delayed ? 'busy' : 'normal',
      retryAfterSeconds: paused ? resetAfter(now) : 60 });
  }
  if (request.method === 'DELETE' && validWatchId(watchId)) {
    const target = await env.DB.prepare('SELECT target_id FROM watch_subscriptions WHERE installation_id = ? AND watch_id = ?')
      .bind(installationId, watchId).first();
    await env.DB.batch([
      env.DB.prepare('DELETE FROM watch_subscriptions WHERE installation_id = ? AND watch_id = ?')
        .bind(installationId, watchId),
      env.DB.prepare(`DELETE FROM watch_targets WHERE target_id = ? AND NOT EXISTS
        (SELECT 1 FROM watch_subscriptions s WHERE s.target_id = watch_targets.target_id)`).bind(target?.target_id || ''),
      env.DB.prepare(`DELETE FROM watch_delivery_queue WHERE installation_id = ? AND NOT EXISTS
        (SELECT 1 FROM watch_events e WHERE e.installation_id = ?)`)
        .bind(installationId, installationId),
    ]);
    return reply(200, { removed: true });
  }
  if (request.method !== 'PUT' || watchId) throw new WatchError(404, 'not_found');
  const value = await bodyObject(request, ['url', 'newPost', 'publishedAt', 'notifyExisting']);
  if (Object.hasOwn(value, 'newPost') && typeof value.newPost !== 'boolean') throw new WatchError(400, 'invalid_payload');
  if (value.newPost === true && !Object.hasOwn(value, 'publishedAt')) throw new WatchError(400, 'invalid_payload');
  if (Object.hasOwn(value, 'notifyExisting') && (typeof value.notifyExisting !== 'boolean' || value.newPost !== true)) throw new WatchError(400, 'invalid_payload');
  if (Object.hasOwn(value, 'publishedAt') && (value.newPost !== true || !Number.isSafeInteger(value.publishedAt)
      || value.publishedAt <= 0 || value.publishedAt > 4102444800)) throw new WatchError(400, 'invalid_payload');
  const url = canonicalWatchUrl(value.url);
  if (!url) throw new WatchError(400, 'invalid_watch_url');
  const newPost = value.newPost === true;
  const initialNotification = newPost && value.notifyExisting !== false;
  // A future client clock cannot request a period beyond three days from now.
  const startedAt = newPost && value.publishedAt ? Math.min(now, value.publishedAt) : now;
  const expiresAt = startedAt + WATCH_TTL, newWatchId = crypto.randomUUID();
  if (expiresAt <= now) return reply(200, { expired: true });
  await budget(env.DB, `watch:${installationId}`, 60, 3600, now);
  // Storage has no per-device/global subscription count cap. The list remains
  // bounded independently, and daily budgets still constrain background work.
  await env.DB.batch([
    env.DB.prepare('DELETE FROM watch_subscriptions WHERE installation_id = ? AND expires_at <= ?')
      .bind(installationId, now),
    env.DB.prepare(`INSERT INTO watch_targets(target_id, url, next_check_at)
      SELECT ?, ?, ? WHERE EXISTS (SELECT 1 FROM devices WHERE installation_id = ? AND fcm_token IS NOT NULL)
      ON CONFLICT(url) DO NOTHING`).bind(crypto.randomUUID(), url, now, installationId),
    env.DB.prepare(`INSERT INTO watch_subscriptions(watch_id, installation_id, target_id, created_at, expires_at, start_poll_seq, delivered_revision)
      SELECT ?, ?, t.target_id, ?, ?, CASE WHEN ? THEN 0 ELSE t.poll_seq + 1 END, t.revision FROM watch_targets t
      WHERE t.url = ? AND EXISTS (SELECT 1 FROM devices WHERE installation_id = ? AND fcm_token IS NOT NULL)
      ON CONFLICT(installation_id, target_id) DO UPDATE SET
        expires_at = CASE WHEN ? THEN watch_subscriptions.expires_at ELSE excluded.expires_at END,
        created_at = CASE WHEN ? THEN watch_subscriptions.created_at ELSE excluded.created_at END`)
      .bind(newWatchId, installationId, startedAt, expiresAt, initialNotification ? 1 : 0, url, installationId, newPost ? 1 : 0, newPost ? 1 : 0),
    // The UUID belongs only to this insertion. A retried PUT cannot replay an
    // initial notification or silently change an existing manual subscription.
    env.DB.prepare(`UPDATE watch_targets SET initial_watch = 1 WHERE baseline_count IS NULL
      AND target_id = (SELECT target_id FROM watch_subscriptions WHERE watch_id = ? AND start_poll_seq = 0)`)
      .bind(newWatchId),
    env.DB.prepare(`INSERT INTO watch_events(watch_id, installation_id, event_id, created_at, available_at, revision)
      SELECT s.watch_id, s.installation_id, ?, ?, ?, t.revision FROM watch_subscriptions s
      JOIN watch_targets t USING(target_id) WHERE s.watch_id = ? AND s.start_poll_seq = 0 AND t.baseline_count > 0
      ON CONFLICT(watch_id) DO NOTHING`).bind(crypto.randomUUID(), now, now, newWatchId),
    env.DB.prepare(`INSERT INTO watch_delivery_queue(installation_id, ready_at)
      SELECT installation_id, ? FROM watch_events WHERE watch_id = ? ON CONFLICT(installation_id) DO NOTHING`)
      .bind(now, newWatchId),
    env.DB.prepare(`DELETE FROM watch_targets WHERE url = ? AND NOT EXISTS
      (SELECT 1 FROM watch_subscriptions s WHERE s.target_id = watch_targets.target_id)`).bind(url),
  ]);
  await resumeWatchDelivery(env, installationId, now);
  const watch = await ownWatchAtUrl(env, installationId, url, now);
  if (!watch) {
    const device = await env.DB.prepare('SELECT fcm_token FROM devices WHERE installation_id = ?')
      .bind(installationId).first();
    if (!device) throw new WatchError(401, 'unauthorized');
    if (!device.fcm_token) throw new WatchError(410, 'device_token_expired');
    throw new WatchError(503, 'watch_registration_unavailable');
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

function day(now) { return Math.floor(now / 86400); }
function resetAfter(now) { return (day(now) + 1) * 86400 - now; }
function exhausted(usage, kind = 'check') {
  const [writes, reads] = JOB_COST[kind];
  return !!usage && (usage.jobs >= DAY_JOBS || usage.writes_reserved + writes > DAY_WRITES || usage.reads_reserved + reads > DAY_READS);
}

async function pendingWorkPaused(env, installationId, now, usage) {
  if (exhausted(usage)) return true;
  if (!exhausted(usage, 'fanout')) return false;
  // Close to a row budget, collection may still fit while an already pending
  // fanout or delivery does not. Report that pause for this device as well.
  const [fanout, delivery] = await env.DB.batch([
    env.DB.prepare(`SELECT 1 FROM watch_subscriptions s JOIN watch_targets t USING(target_id)
      JOIN devices d USING(installation_id) WHERE s.installation_id = ?
      AND s.expires_at > ? AND t.fanout_pending = 1
      AND t.fanout_next_at <= ? AND t.fanout_lease_until <= ? LIMIT 1`)
      .bind(installationId, now, now, now),
    env.DB.prepare(`SELECT 1 FROM watch_delivery_queue WHERE installation_id = ?
      AND paused = 0 AND ready_at <= ? AND lease_until <= ? LIMIT 1`)
      .bind(installationId, now, now),
  ]);
  return (fanout.results.length > 0 && exhausted(usage, 'fanout'))
    || (delivery.results.length > 0 && exhausted(usage, 'deliver'));
}

async function reserveWork(env, now, writes, reads, jobs = 1) {
  const result = await env.DB.prepare(`INSERT INTO watch_job_budget(day, jobs, writes_reserved, reads_reserved)
    VALUES (?, ?, ?, ?) ON CONFLICT(day) DO UPDATE SET jobs = jobs + excluded.jobs,
    writes_reserved = writes_reserved + excluded.writes_reserved,
    reads_reserved = reads_reserved + excluded.reads_reserved
    WHERE jobs + excluded.jobs <= ? AND writes_reserved + excluded.writes_reserved <= ?
      AND reads_reserved + excluded.reads_reserved <= ? RETURNING day`)
    .bind(day(now), jobs, writes, reads, DAY_JOBS, DAY_WRITES, DAY_READS).all();
  return result.results.length ? { day: day(now), writes, reads, meta: result.meta } : null;
}

function meteredDatabase(db) {
  const totals = { writes: 0, reads: 0, known: true };
  function count(result) {
    const meta = result?.meta;
    if (!Number.isFinite(meta?.rows_written) || !Number.isFinite(meta?.rows_read)) totals.known = false;
    else { totals.writes += meta.rows_written; totals.reads += meta.rows_read; }
    return result;
  }
  function wrap(statement) {
    return { raw: statement,
      bind: (...values) => wrap(statement.bind(...values)),
      all: async () => count(await statement.all()),
      first: async () => (count(await statement.all()).results[0] || null),
      run: async () => count(await statement.run()),
    };
  }
  return { totals, db: {
    prepare: sql => wrap(db.prepare(sql)),
    batch: async statements => (await db.batch(statements.map(statement => statement.raw))).map(count),
  } };
}

async function settleWork(env, ticket, totals) {
  if (!totals.known || !Number.isFinite(ticket.meta?.rows_written) || !Number.isFinite(ticket.meta?.rows_read)) return;
  // D1 metadata includes index rows. Include the reservation and this ledger
  // update too. Failed/CPU-terminated work keeps the conservative reservation.
  const writes = totals.writes + ticket.meta.rows_written + 1;
  const reads = totals.reads + ticket.meta.rows_read + 1;
  await env.DB.prepare(`UPDATE watch_job_budget SET writes_reserved = MAX(0, writes_reserved + ?),
    reads_reserved = MAX(0, reads_reserved + ?) WHERE day = ?`)
    .bind(writes - ticket.writes, reads - ticket.reads, ticket.day).run();
}

export async function resumeWatchDelivery(env, installationId, now) {
  await env.DB.prepare(`UPDATE watch_delivery_queue SET paused = 0, ready_at = MIN(ready_at, ?)
    WHERE installation_id = ? AND paused = 1 AND EXISTS
      (SELECT 1 FROM devices d JOIN watch_subscriptions s USING(installation_id)
       WHERE d.installation_id = ? AND d.fcm_token IS NOT NULL AND s.expires_at > ?)`)
    .bind(now, installationId, installationId, now).run();
  // Only the no-active-device pause is lifted. A genuine source 403/429 or
  // parsing backoff must survive token renewal and new subscriptions.
  await env.DB.prepare(`UPDATE watch_targets SET next_check_at = MIN(next_check_at, ?), paused_no_device = 0
    WHERE paused_no_device = 1 AND target_id IN
      (SELECT s.target_id FROM watch_subscriptions s JOIN devices d USING(installation_id)
       JOIN watch_targets t USING(target_id) WHERE s.installation_id = ? AND t.paused_no_device = 1
       AND s.expires_at > ? AND d.fcm_token IS NOT NULL LIMIT 20)`)
    .bind(now, installationId, now).run();
}

async function checkOneTarget(env, now) {
  const lease = crypto.randomUUID();
  const target = await env.DB.prepare(`UPDATE watch_targets SET lease_until = ?, lease_token = ?
    WHERE target_id = (SELECT target_id FROM watch_targets WHERE next_check_at <= ? AND lease_until <= ?
      ORDER BY next_check_at, target_id LIMIT 1)
    RETURNING target_id, url, failures, poll_seq, last_reply_no`).bind(now + 90, lease, now, now).first();
  if (!target) return { state: 'idle' };
  const active = await env.DB.prepare(`SELECT 1 AS found FROM watch_subscriptions s JOIN devices d USING(installation_id)
    WHERE s.target_id = ? AND s.expires_at > ? AND d.fcm_token IS NOT NULL LIMIT 1`)
    .bind(target.target_id, now).first();
  if (!active) {
    await env.DB.batch([
      env.DB.prepare(`DELETE FROM watch_targets WHERE target_id = ? AND lease_token = ? AND NOT EXISTS
        (SELECT 1 FROM watch_subscriptions WHERE target_id = ?)`)
        .bind(target.target_id, lease, target.target_id),
      env.DB.prepare(`UPDATE watch_targets SET next_check_at = ?, paused_no_device = 1, lease_until = 0, lease_token = NULL
        WHERE target_id = ? AND lease_token = ?`).bind(now + 86400, target.target_id, lease),
    ]);
    return { state: 'inactive' };
  }
  let count, replyNo = null;
  const replyTarget = commentTarget(target.url);
  try {
    if (replyTarget) { const state = await readPublicReplySnapshot(target.url); count = state.count; replyNo = state.latestReplyNo; }
    else count = await readPublicCount(target.url);
  }
  catch (error) {
    const status = error instanceof WatchError || error instanceof ReplyReadError ? error.code : 'unavailable';
    const delay = status === 'blocked' ? 21600 : status === 'rate_limited' ? 3600
      : Math.min(21600, CHECK_INTERVAL * 2 ** Math.min(target.failures + 1, 6));
    await env.DB.prepare(`UPDATE watch_targets SET status = ?, last_checked = ?, next_check_at = ?,
      failures = MIN(failures + 1, 16), lease_until = 0, lease_token = NULL WHERE target_id = ? AND lease_token = ?`)
      .bind(status, now, now + delay, target.target_id, lease).run();
    return { state: status };
  }
  const eventId = crypto.randomUUID();
  // Manual subscriptions use the first sample only as a baseline. A newly
  // authored-post opt-in starts at zero, so its first positive count is eligible.
  // start_poll_seq keeps manual subscribers out of that initial revision.
  const increase = replyTarget
    ? '((baseline_count IS NOT NULL AND ?7 = 1) OR (baseline_count IS NULL AND initial_watch = 1 AND ?1 > 0))'
    : '((baseline_count IS NOT NULL AND baseline_count < ?1) OR (baseline_count IS NULL AND initial_watch = 1 AND ?1 > 0))';
  const newerReply = replyTarget && laterCommentId(replyNo, target.last_reply_no);
  const highWater = replyTarget ? (newerReply ? replyNo : target.last_reply_no || replyNo) : null;
  await env.DB.prepare(`UPDATE watch_targets SET
    revision = revision + CASE WHEN ${increase} THEN 1 ELSE 0 END,
    increase_from_seq = CASE WHEN ${increase} THEN poll_seq ELSE increase_from_seq END,
    latest_event_id = CASE WHEN ${increase} THEN ?2 ELSE latest_event_id END,
    fanout_revision = CASE WHEN ${increase} AND fanout_pending = 0 THEN revision + 1 ELSE fanout_revision END,
    fanout_from_seq = CASE WHEN ${increase} AND fanout_pending = 0 THEN poll_seq ELSE fanout_from_seq END,
    fanout_event_id = CASE WHEN ${increase} AND fanout_pending = 0 THEN ?2 ELSE fanout_event_id END,
    fanout_cursor = CASE WHEN ${increase} AND fanout_pending = 0 THEN '' ELSE fanout_cursor END,
    fanout_next_at = CASE WHEN ${increase} AND fanout_pending = 0 THEN ?3 ELSE fanout_next_at END,
    fanout_pending = CASE WHEN ${increase} THEN 1 ELSE fanout_pending END,
    baseline_count = ?1, poll_seq = poll_seq + 1, last_checked = ?3, next_check_at = ?4, last_reply_no = ?8,
    status = 'active', failures = 0, paused_no_device = 0, lease_until = 0, lease_token = NULL
    WHERE target_id = ?5 AND lease_token = ?6`)
    .bind(count, eventId, now, now + CHECK_INTERVAL, target.target_id, lease, newerReply ? 1 : 0, highWater).run();
  return { state: 'checked' };
}

async function fanoutOneChunk(env, now) {
  const lease = crypto.randomUUID();
  const target = await env.DB.prepare(`UPDATE watch_targets SET fanout_lease_until = ?, fanout_lease_token = ?
    WHERE target_id = (SELECT target_id FROM watch_targets WHERE fanout_pending = 1 AND fanout_next_at <= ?
      AND fanout_lease_until <= ? ORDER BY fanout_next_at, target_id LIMIT 1)
    RETURNING target_id, fanout_revision, fanout_from_seq, fanout_event_id, fanout_cursor`)
    .bind(now + 90, lease, now, now).first();
  if (!target) return { state: 'idle' };
  // Limit rows before eligibility filtering. An old/inactive subscriber cannot
  // turn a small fanout page into an unbounded scan of the whole popular topic.
  const page = await env.DB.prepare(`SELECT s.watch_id FROM watch_subscriptions s
    WHERE s.target_id = ? AND s.watch_id > ? ORDER BY s.watch_id LIMIT ?`)
    .bind(target.target_id, target.fanout_cursor, FANOUT_CHUNK).all();
  if (!page.results.length) {
    await env.DB.prepare(`UPDATE watch_targets SET
      fanout_pending = CASE WHEN revision > fanout_revision THEN 1 ELSE 0 END,
      fanout_from_seq = CASE WHEN revision > fanout_revision THEN increase_from_seq ELSE fanout_from_seq END,
      fanout_event_id = CASE WHEN revision > fanout_revision THEN latest_event_id ELSE fanout_event_id END,
      fanout_revision = CASE WHEN revision > fanout_revision THEN revision ELSE fanout_revision END,
      fanout_cursor = '', fanout_next_at = ?, fanout_lease_until = 0, fanout_lease_token = NULL
      WHERE target_id = ? AND fanout_lease_token = ?`).bind(now + 60, target.target_id, lease).run();
    return { state: 'fanout_complete' };
  }
  const ids = page.results.map(row => row.watch_id), slots = ids.map(() => '?').join(',');
  await env.DB.batch([
    env.DB.prepare(`INSERT INTO watch_events(watch_id, installation_id, event_id, created_at, available_at, revision)
      SELECT s.watch_id, s.installation_id, ?, ?, ?, ? FROM watch_subscriptions s JOIN devices d USING(installation_id)
      WHERE s.watch_id IN (${slots}) AND s.expires_at > ? AND d.fcm_token IS NOT NULL
        AND s.start_poll_seq <= ? AND s.delivered_revision < ?
        AND EXISTS (SELECT 1 FROM watch_targets WHERE target_id = ? AND fanout_lease_token = ?)
      ON CONFLICT(watch_id) DO UPDATE SET revision = excluded.revision,
        event_id = CASE WHEN watch_events.lease_until > ? THEN excluded.event_id ELSE watch_events.event_id END
      WHERE watch_events.revision < excluded.revision`)
      .bind(target.fanout_event_id, now, now, target.fanout_revision, ...ids, now,
        target.fanout_from_seq, target.fanout_revision, target.target_id, lease, now),
    env.DB.prepare(`INSERT INTO watch_delivery_queue(installation_id, ready_at)
      SELECT DISTINCT s.installation_id, ? FROM watch_subscriptions s JOIN watch_events e USING(watch_id)
      WHERE s.watch_id IN (${slots}) ON CONFLICT(installation_id) DO NOTHING`).bind(now, ...ids),
    env.DB.prepare(`UPDATE watch_targets SET fanout_cursor = ?, fanout_next_at = ?,
      fanout_lease_until = 0, fanout_lease_token = NULL WHERE target_id = ? AND fanout_lease_token = ?`)
      .bind(ids[ids.length - 1], now + 60, target.target_id, lease),
  ]);
  return { state: 'fanout', scanned: ids.length };
}

async function refreshDeliverySlot(env, installationId, lease, now, minimumDelay = 60, paused = false) {
  await env.DB.batch([
    env.DB.prepare(`DELETE FROM watch_delivery_queue WHERE installation_id = ? AND lease_token = ?
      AND NOT EXISTS (SELECT 1 FROM watch_events e WHERE e.installation_id = ?)`)
      .bind(installationId, lease, installationId),
    env.DB.prepare(`UPDATE watch_delivery_queue SET ready_at = MAX(?, COALESCE(
        (SELECT e.available_at FROM watch_events e WHERE e.installation_id = ?
         ORDER BY e.available_at, e.watch_id LIMIT 1), ?)), paused = ?, lease_until = 0, lease_token = NULL
      WHERE installation_id = ? AND lease_token = ?`)
      .bind(now + minimumDelay, installationId, now + minimumDelay, paused ? 1 : 0, installationId, lease),
  ]);
}

async function deliverOneEvent(env, now, sendComment) {
  const lease = crypto.randomUUID();
  const device = await env.DB.prepare(`UPDATE watch_delivery_queue SET lease_until = ?, lease_token = ?
    WHERE installation_id = (SELECT installation_id FROM watch_delivery_queue WHERE paused = 0
      AND ready_at <= ? AND lease_until <= ? ORDER BY ready_at, installation_id LIMIT 1)
    RETURNING installation_id`).bind(now + 90, lease, now, now).first();
  if (!device) return { state: 'idle' };
  const active = await env.DB.prepare('SELECT fcm_token FROM devices WHERE installation_id = ?')
    .bind(device.installation_id).first();
  if (!active?.fcm_token) {
    await refreshDeliverySlot(env, device.installation_id, lease, now, 60, true);
    return { state: 'idle' };
  }
  const pending = await env.DB.prepare(`SELECT e.watch_id, e.event_id, e.revision, e.attempts, t.url
    FROM watch_events e JOIN watch_subscriptions s USING(watch_id) JOIN watch_targets t USING(target_id)
    WHERE e.installation_id = ? AND e.available_at <= ? AND s.installation_id = e.installation_id AND s.expires_at > ?
    ORDER BY e.available_at, e.watch_id LIMIT 1`).bind(device.installation_id, now, now).first();
  if (!pending) { await refreshDeliverySlot(env, device.installation_id, lease, now); return { state: 'idle' }; }
  pending.fcm_token = active.fcm_token;
  const marked = await env.DB.prepare(`UPDATE watch_events SET lease_token = ?, lease_until = ?
    WHERE watch_id = ? AND event_id = ? AND revision = ?`)
    .bind(lease, now + 90, pending.watch_id, pending.event_id, pending.revision).run();
  if (marked.meta?.changes !== 1) {
    await refreshDeliverySlot(env, device.installation_id, lease, now, 1);
    return { state: 'retry' };
  }
  try {
    await sendComment(env, pending.fcm_token, pending.event_id, pending.url, now);
    await env.DB.batch([
      env.DB.prepare(`UPDATE watch_subscriptions SET delivered_revision = MAX(delivered_revision, ?)
        WHERE watch_id = ? AND installation_id = ?`).bind(pending.revision, pending.watch_id, device.installation_id),
      // A newer change arriving during FCM I/O must survive the old ACK.
      env.DB.prepare('DELETE FROM watch_events WHERE watch_id = ? AND event_id = ? AND revision = ?')
        .bind(pending.watch_id, pending.event_id, pending.revision),
      env.DB.prepare('UPDATE watch_events SET lease_token = NULL, lease_until = 0 WHERE watch_id = ? AND lease_token = ?')
        .bind(pending.watch_id, lease),
    ]);
    await refreshDeliverySlot(env, device.installation_id, lease, now);
    return { state: 'delivered' };
  } catch (error) {
    if (error?.code === 'device_token_expired') {
      await env.DB.prepare('UPDATE devices SET fcm_token = NULL, token_hash = NULL, updated_at = ? WHERE installation_id = ? AND fcm_token = ?')
        .bind(now, device.installation_id, pending.fcm_token).run();
      const current = await env.DB.prepare('SELECT fcm_token FROM devices WHERE installation_id = ?').bind(device.installation_id).first();
      await env.DB.prepare('UPDATE watch_events SET lease_token = NULL, lease_until = 0 WHERE watch_id = ? AND lease_token = ?')
        .bind(pending.watch_id, lease).run();
      await refreshDeliverySlot(env, device.installation_id, lease, now, 60, !current?.fcm_token);
      return { state: current?.fcm_token ? 'retry' : 'token_expired' };
    }
    const retry = Math.min(86400, Math.max(Number(error?.retryAfter) || 0,
      300 * 2 ** Math.min(pending.attempts, 6)));
    await env.DB.prepare(`UPDATE watch_events SET attempts = MIN(attempts + 1, 16), available_at = ?, lease_token = NULL, lease_until = 0
      WHERE watch_id = ? AND lease_token = ?`).bind(now + retry, pending.watch_id, lease).run();
    await refreshDeliverySlot(env, device.installation_id, lease, now, 60);
    return { state: 'retry' };
  }
}

async function pruneExpired(env, now) {
  const stale = await env.DB.prepare(`SELECT watch_id, target_id, installation_id FROM watch_subscriptions
    WHERE expires_at <= ? ORDER BY expires_at LIMIT 8`).bind(now).all();
  if (!stale.results.length) return;
  const ticket = await reserveWork(env, now, 160, 256, 0); if (!ticket) return;
  const meter = meteredDatabase(env.DB), db = meter.db;
  const ids = stale.results.map(row => row.watch_id), targets = [...new Set(stale.results.map(row => row.target_id))];
  const devices = [...new Set(stale.results.map(row => row.installation_id))];
  await db.batch([
    db.prepare(`DELETE FROM watch_subscriptions WHERE watch_id IN (${ids.map(() => '?').join(',')}) AND expires_at <= ?`)
      .bind(...ids, now),
    db.prepare(`DELETE FROM watch_targets WHERE target_id IN (${targets.map(() => '?').join(',')})
      AND NOT EXISTS (SELECT 1 FROM watch_subscriptions s WHERE s.target_id = watch_targets.target_id)`).bind(...targets),
    db.prepare(`DELETE FROM watch_delivery_queue WHERE installation_id IN (${devices.map(() => '?').join(',')})
      AND NOT EXISTS (SELECT 1 FROM watch_events e WHERE e.installation_id = watch_delivery_queue.installation_id)`).bind(...devices),
    db.prepare(`UPDATE devices SET watch_active_until = 0 WHERE installation_id IN (${devices.map(() => '?').join(',')})
      AND NOT EXISTS (SELECT 1 FROM watch_subscriptions s WHERE s.installation_id = devices.installation_id)`).bind(...devices),
  ]);
  await settleWork(env, ticket, meter.totals);
}

export async function planWatchJobs(env, now) {
  const usage = await env.DB.prepare('SELECT jobs, writes_reserved, reads_reserved FROM watch_job_budget WHERE day = ?').bind(day(now)).first();
  if (exhausted(usage)) return [];
  await pruneExpired(env, now);
  const [checks, deliveries, fanouts] = await env.DB.batch([
    env.DB.prepare('SELECT target_id FROM watch_targets WHERE next_check_at <= ? AND lease_until <= ? ORDER BY next_check_at, target_id LIMIT 6').bind(now, now),
    env.DB.prepare('SELECT installation_id FROM watch_delivery_queue WHERE paused = 0 AND ready_at <= ? AND lease_until <= ? ORDER BY ready_at, installation_id LIMIT 6').bind(now, now),
    env.DB.prepare('SELECT target_id FROM watch_targets WHERE fanout_pending = 1 AND fanout_next_at <= ? AND fanout_lease_until <= ? ORDER BY fanout_next_at, target_id LIMIT 2').bind(now, now),
  ]);
  const jobs = []; let writes = usage?.writes_reserved || 0, reads = usage?.reads_reserved || 0;
  const usedJobs = usage?.jobs || 0;
  function add(kind) {
    const cost = JOB_COST[kind];
    if (usedJobs + jobs.length >= DAY_JOBS || writes + cost[0] > DAY_WRITES || reads + cost[1] > DAY_READS) return;
    writes += cost[0]; reads += cost[1]; jobs.push(kind);
  }
  // Interleave existing deliveries and collection so a busy source does not
  // consume every dispatch slot. Each HTTP job performs one bounded operation.
  for (let index = 0; index < 6; index++) {
    if (index < deliveries.results.length) add('deliver');
    if (index < checks.results.length) add('check');
    if (index < fanouts.results.length) add('fanout');
  }
  return jobs;
}

export async function runWatchJob(env, now, kind, sendComment) {
  const cost = JOB_COST[kind];
  if (!cost) throw new WatchError(400, 'invalid_job');
  const ticket = await reserveWork(env, now, cost[0], cost[1]);
  if (!ticket) return { state: 'quota_paused', retryAfterSeconds: resetAfter(now) };
  const meter = meteredDatabase(env.DB), scoped = { ...env, DB: meter.db };
  try {
    const result = kind === 'check' ? await checkOneTarget(scoped, now)
      : kind === 'fanout' ? await fanoutOneChunk(scoped, now)
      : await deliverOneEvent(scoped, now, sendComment);
    await settleWork(env, ticket, meter.totals);
    return result;
  } catch (error) {
    // Keep reservation on unexpected failure. Leases expire without exposing
    // raw database errors or silently retrying within the same CPU invocation.
    throw error;
  }
}
