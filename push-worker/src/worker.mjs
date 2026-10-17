import { WatchError, canonicalWatchUrl, validWatchId, watchRoute, runWatchJob, resumeWatchDelivery } from './public-watches.mjs';
import { RegistrationError, validRegistrationId, registrationRoute, cancelRegistrationDevice, cleanupRegistrations } from './registration.mjs';
import { dispatchScheduledWatches, verifyJobRequest } from './job-dispatch.mjs';

const MAX_BODY_BYTES = 8192;
const PAIR_TTL_SECONDS = 15 * 60;
const OAUTH_URL = 'https://oauth2.googleapis.com/token';
const FCM_SCOPE = 'https://www.googleapis.com/auth/firebase.messaging';
const ID_PATTERN = /^[A-Za-z0-9_-]{16,80}$/;
const SECRET_PATTERN = /^[A-Za-z0-9_-]{43,128}$/;
const CODE_PATTERN = /^[A-Za-z0-9_-]{27}$/;
const textEncoder = new TextEncoder();
const oauthCache = new Map();

class HttpError extends Error {
  constructor(status, code, retryAfter = 0) {
    super(code);
    this.status = status;
    this.code = code;
    this.retryAfter = retryAfter;
  }
}

function reply(status, value, retryAfter = 0) {
  const headers = {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
  };
  if (retryAfter) headers['Retry-After'] = String(retryAfter);
  return new Response(JSON.stringify(value), { status, headers });
}

function base64url(bytes) {
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '');
}

async function digest(value) {
  return base64url(new Uint8Array(await crypto.subtle.digest('SHA-256', textEncoder.encode(value))));
}

function equalDigest(left, right) {
  let diff = left.length ^ right.length;
  for (let i = 0; i < Math.max(left.length, right.length); i++) {
    diff |= (left.charCodeAt(i) || 0) ^ (right.charCodeAt(i) || 0);
  }
  return diff === 0;
}

function bearer(request) {
  const value = request.headers.get('Authorization') || '';
  const match = /^Bearer ([A-Za-z0-9_-]{43,128})$/.exec(value);
  return match ? match[1] : '';
}

async function bodyObject(request, allowedKeys) {
  if (!/^application\/json(?:\s*;\s*charset=utf-8)?$/i.test(request.headers.get('Content-Type') || '')) {
    throw new HttpError(415, 'json_required');
  }
  const encoding = request.headers.get('Content-Encoding');
  if (encoding && encoding.toLowerCase() !== 'identity') throw new HttpError(415, 'unsupported_encoding');
  const length = request.headers.get('Content-Length');
  if (length !== null && (!/^\d+$/.test(length) || Number(length) > MAX_BODY_BYTES)) {
    throw new HttpError(413, 'body_too_large');
  }
  const reader = request.body?.getReader();
  let lengthRead = 0;
  const chunks = [];
  if (reader) {
    for (;;) {
      const chunk = await reader.read();
      if (chunk.done) break;
      lengthRead += chunk.value.byteLength;
      if (lengthRead > MAX_BODY_BYTES) {
        await reader.cancel();
        throw new HttpError(413, 'body_too_large');
      }
      chunks.push(chunk.value);
    }
  }
  const joined = new Uint8Array(lengthRead);
  let cursor = 0;
  for (const chunk of chunks) { joined.set(chunk, cursor); cursor += chunk.byteLength; }
  let value;
  try { value = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(joined)); }
  catch { throw new HttpError(400, 'invalid_json'); }
  if (!value || Array.isArray(value) || typeof value !== 'object'
      || Object.keys(value).some(key => !allowedKeys.includes(key))) {
    throw new HttpError(400, 'invalid_payload');
  }
  return value;
}

function validToken(token) {
  return typeof token === 'string' && token.length >= 32 && token.length <= 4096
    && /^[A-Za-z0-9_:.-]+$/.test(token);
}

function nowSeconds() { return Math.floor(Date.now() / 1000); }

async function budget(db, key, limit, duration, now) {
  const start = now - now % duration;
  const result = await db.prepare(`INSERT INTO rate_limits (bucket_key, window_start, expires_at, hits)
    VALUES (?, ?, ?, 1)
    ON CONFLICT (bucket_key, window_start) DO UPDATE SET hits = hits + 1
      WHERE hits < ?
    RETURNING hits`).bind(key, start, start + duration * 2, limit).first();
  if (!result || result.hits > limit) throw new HttpError(429, 'rate_limited', start + duration - now);
}

async function entryBudget(request, env, now) {
  // Cloudflare supplies this header; the original address is never persisted.
  // The server secret prevents dictionary recovery of a stored address digest.
  const address = request.headers.get('CF-Connecting-IP') || 'unavailable';
  const key = await digest(`${env.ADMIN_SECRET}:ip:${Math.floor(now / 86400)}:${address}`);
  await budget(env.DB, `entry:${key}`, 30, 60, now);
}

async function requireAdmin(request, env) {
  const provided = bearer(request);
  if (!provided || !equalDigest(await digest(provided), await digest(env.ADMIN_SECRET))) {
    throw new HttpError(401, 'unauthorized');
  }
}

async function requireDevice(request, env, installationId) {
  const secret = bearer(request);
  if (!secret) throw new HttpError(401, 'unauthorized');
  const secretHash = await digest(secret);
  const device = await env.DB.prepare(`SELECT installation_id, secret_hash, fcm_token
    FROM devices WHERE installation_id = ?`).bind(installationId).first();
  if (!device || !equalDigest(secretHash, device.secret_hash)) throw new HttpError(401, 'unauthorized');
  return { device, secretHash };
}

async function issueCode(request, env, now) {
  await requireAdmin(request, env);
  await bodyObject(request, []);
  await budget(env.DB, 'admin:pair', 10, 3600, now);
  const code = base64url(crypto.getRandomValues(new Uint8Array(20)));
  const expiresAt = now + PAIR_TTL_SECONDS;
  await env.DB.batch([
    env.DB.prepare('DELETE FROM pairing_codes WHERE expires_at <= ?').bind(now),
    env.DB.prepare('INSERT INTO pairing_codes (code_hash, expires_at) VALUES (?, ?)')
      .bind(await digest(code), expiresAt),
  ]);
  return reply(201, { code, expiresAt });
}

async function pair(request, env, now) {
  const value = await bodyObject(request, ['code', 'installationId', 'installationSecret', 'fcmToken']);
  if (typeof value.code !== 'string' || !CODE_PATTERN.test(value.code)
      || typeof value.installationId !== 'string' || !ID_PATTERN.test(value.installationId)
      || typeof value.installationSecret !== 'string' || !SECRET_PATTERN.test(value.installationSecret)
      || !validToken(value.fcmToken)) throw new HttpError(400, 'invalid_payload');
  const codeHash = await digest(value.code);
  const secretHash = await digest(value.installationSecret);
  const tokenHash = await digest(value.fcmToken);
  let results;
  try {
    // D1 batches are transactional. A code is consumed only when its device is
    // inserted, and UNIQUE token/installation constraints cannot consume a code.
    results = await env.DB.batch([
      env.DB.prepare(`INSERT INTO devices
        (installation_id, secret_hash, fcm_token, token_hash, created_at, updated_at)
        SELECT ?, ?, ?, ?, ?, ? FROM pairing_codes WHERE code_hash = ? AND expires_at > ?`)
        .bind(value.installationId, secretHash, value.fcmToken, tokenHash, now, now, codeHash, now),
      env.DB.prepare(`DELETE FROM pairing_codes WHERE code_hash = ? AND EXISTS
        (SELECT 1 FROM devices WHERE installation_id = ? AND secret_hash = ?)`)
        .bind(codeHash, value.installationId, secretHash),
    ]);
  } catch (error) {
    if (String(error?.message || '').includes('UNIQUE constraint failed')) {
      throw new HttpError(409, 'pairing_conflict');
    }
    throw new HttpError(503, 'relay_unavailable');
  }
  if (results[0].meta.changes !== 1) throw new HttpError(401, 'invalid_pairing_code');
  return reply(201, { paired: true, installationId: value.installationId });
}

function serviceAccount(env) {
  if (!/^[a-z][a-z0-9-]{4,28}[a-z0-9]$/.test(env.FIREBASE_PROJECT_ID || '')) {
    throw new HttpError(503, 'push_not_configured');
  }
  let account;
  try { account = JSON.parse(env.FCM_SERVICE_ACCOUNT_JSON); }
  catch { throw new HttpError(503, 'push_not_configured'); }
  if (!account || account.type !== 'service_account' || account.project_id !== env.FIREBASE_PROJECT_ID
      || !/^[A-Za-z0-9._-]+@[A-Za-z0-9.-]+\.iam\.gserviceaccount\.com$/.test(account.client_email || '')
      || !/^[a-f0-9]{20,80}$/.test(account.private_key_id || '')
      || typeof account.private_key !== 'string' || account.private_key.length > 16384
      || !account.private_key.startsWith('-----BEGIN PRIVATE KEY-----')) {
    throw new HttpError(503, 'push_not_configured');
  }
  return account;
}

async function jsonUpstream(response) {
  // Upstream bodies are never returned or logged. They can contain credentials
  // or registration tokens in error messages.
  const text = await response.text();
  if (text.length > 65536) throw new HttpError(502, 'push_service_unavailable');
  try { return JSON.parse(text); }
  catch { throw new HttpError(502, 'push_service_unavailable'); }
}

async function fetchGoogle(url, init) {
  try {
    // Workers supports manual/follow redirects, not the browser-only error mode.
    // Reject redirects explicitly so credentials cannot follow a different URL.
    const response = await fetch(url, { ...init, redirect: 'manual', signal: AbortSignal.timeout(12000) });
    if (response.status >= 300 && response.status < 400) {
      await response.body?.cancel();
      throw new HttpError(502, 'push_service_unavailable');
    }
    return response;
  } catch {
    throw new HttpError(502, 'push_service_unavailable');
  }
}

async function mintToken(account, now) {
  const header = base64url(textEncoder.encode(JSON.stringify({ alg: 'RS256', typ: 'JWT', kid: account.private_key_id })));
  const claims = base64url(textEncoder.encode(JSON.stringify({
    iss: account.client_email, scope: FCM_SCOPE, aud: OAUTH_URL, iat: now, exp: now + 3600,
  })));
  let signature;
  try {
    const encodedKey = account.private_key.replace(/-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\s/g, '');
    const bytes = Uint8Array.from(atob(encodedKey), char => char.charCodeAt(0));
    const key = await crypto.subtle.importKey('pkcs8', bytes,
      { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['sign']);
    signature = new Uint8Array(await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, textEncoder.encode(`${header}.${claims}`)));
  } catch { throw new HttpError(503, 'push_not_configured'); }
  const assertion = `${header}.${claims}.${base64url(signature)}`;
  const response = await fetchGoogle(OAUTH_URL, {
    method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion }).toString(),
  });
  const payload = await jsonUpstream(response);
  if (!response.ok || !payload || typeof payload.access_token !== 'string' || !payload.access_token
      || payload.access_token.length > 8192
      || payload.token_type?.toLowerCase() !== 'bearer'
      || !Number.isFinite(payload.expires_in) || payload.expires_in < 120) {
    throw new HttpError(502, 'push_service_unavailable');
  }
  return { value: payload.access_token, until: now + Math.min(payload.expires_in, 3600) - 60 };
}

async function accessToken(account, now) {
  const cacheKey = `${account.project_id}:${account.private_key_id}`;
  const cached = oauthCache.get(cacheKey);
  if (cached?.token && cached.token.until > now) return cached.token.value;
  if (cached?.pending) return (await cached.pending).value;
  // A cache is isolated to one Worker instance. Concurrent requests share one
  // mint operation; key rotation selects a new cache entry.
  if (oauthCache.size > 2) oauthCache.clear();
  const pending = mintToken(account, now);
  oauthCache.set(cacheKey, { pending });
  try {
    const token = await pending;
    oauthCache.set(cacheKey, { token });
    return token.value;
  } catch (error) { oauthCache.delete(cacheKey); throw error; }
}

async function sendMessage(env, token, data, now) {
  const registration = data?.kind === 'registration';
  if (!data || (registration
      ? !validRegistrationId(data.registration_id) || !ID_PATTERN.test(data.installation_id || '')
        || !/^[A-Za-z0-9_-]{43}$/.test(data.challenge || '') || !/^[A-Za-z0-9_-]{43}$/.test(data.token_hash || '')
      : !/^[0-9a-f-]{36}$/.test(data.event_id || '') || !['test', 'comment'].includes(data.kind)
        || (data.kind === 'comment' && !canonicalWatchUrl(data.url)))) {
    throw new HttpError(400, 'invalid_notification');
  }
  const account = serviceAccount(env);
  const oauth = await accessToken(account, now);
  const response = await fetchGoogle(`https://fcm.googleapis.com/v1/projects/${env.FIREBASE_PROJECT_ID}/messages:send`, {
    method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${oauth}` },
    body: JSON.stringify({ message: {
      token,
      data,
      android: { priority: registration ? 'NORMAL' : 'HIGH',
        ttl: registration ? '300s' : data.kind === 'comment' ? '86400s' : '60s', restricted_package_name: 'app.moa.client' },
    } }),
  });
  if (response.ok) { await response.body?.cancel(); return; }
  const failure = await jsonUpstream(response);
  if (Array.isArray(failure?.error?.details) && failure.error.details.some(detail => detail?.['@type'] === 'type.googleapis.com/google.firebase.fcm.v1.FcmError'
      && detail.errorCode === 'UNREGISTERED')) throw new HttpError(410, 'device_token_expired');
  if (response.status === 401) oauthCache.delete(`${account.project_id}:${account.private_key_id}`);
  throw new HttpError(502, 'push_service_unavailable');
}

async function sendTest(env, token, eventId, now) {
  return sendMessage(env, token, { kind: 'test', event_id: eventId }, now);
}

async function sendComment(env, token, eventId, url, now) {
  return sendMessage(env, token, { kind: 'comment', event_id: eventId, url }, now);
}

async function sendRegistration(env, token, registrationId, installationId, challenge, tokenHash, now) {
  return sendMessage(env, token, { kind: 'registration', registration_id: registrationId,
    installation_id: installationId, challenge, token_hash: tokenHash }, now);
}

function registrationHelpers() {
  return { reply, bodyObject, bearer, digest, equalDigest, base64url, validToken, sendRegistration, onRegistered: resumeWatchDelivery };
}

async function testDevice(request, env, installationId, secretHash, now) {
  await bodyObject(request, []);
  serviceAccount(env);
  const device = await env.DB.prepare(`UPDATE devices SET last_test_at = ?,
    test_window_start = CASE WHEN test_window_start <= ? THEN ? ELSE test_window_start END,
    test_count = CASE WHEN test_window_start <= ? THEN 1 ELSE test_count + 1 END
    WHERE installation_id = ? AND secret_hash = ? AND fcm_token IS NOT NULL
      AND last_test_at <= ? AND (test_window_start <= ? OR test_count < 5)
    RETURNING fcm_token`).bind(now, now - 3600, now, now - 3600,
    installationId, secretHash, now - 60, now - 3600).first();
  if (!device) {
    const current = await env.DB.prepare(`SELECT fcm_token, last_test_at, test_window_start, test_count
      FROM devices WHERE installation_id = ? AND secret_hash = ?`)
      .bind(installationId, secretHash).first();
    if (!current) throw new HttpError(401, 'unauthorized');
    if (!current.fcm_token) throw new HttpError(410, 'device_token_expired');
    const readyAt = Math.max(current.last_test_at + 60,
      current.test_count >= 5 ? current.test_window_start + 3600 : 0);
    throw new HttpError(429, 'test_rate_limited', Math.max(1, readyAt - now));
  }
  const eventId = crypto.randomUUID();
  try { await sendTest(env, device.fcm_token, eventId, now); }
  catch (error) {
    if (error instanceof HttpError && error.code === 'device_token_expired') {
      await env.DB.prepare(`UPDATE devices SET fcm_token = NULL, token_hash = NULL, updated_at = ?
        WHERE installation_id = ? AND secret_hash = ? AND fcm_token = ?`)
        .bind(now, installationId, secretHash, device.fcm_token).run();
    }
    throw error;
  }
  return reply(202, { queued: true, eventId });
}

async function deviceRoute(request, env, match, now) {
  const [, installationId, action, watchId] = match;
  if (request.method === 'DELETE' && !action) {
    return cancelRegistrationDevice(request, env, installationId, now, registrationHelpers());
  }
  const { secretHash, device } = await requireDevice(request, env, installationId);
  if (action === 'watches') {
    return watchRoute(request, env, installationId, watchId, now, { reply, bodyObject, budget });
  }
  if (request.method === 'POST' && action === 'test') {
    return testDevice(request, env, installationId, secretHash, now);
  }
  if (request.method === 'PUT' && action === 'token') {
    const value = await bodyObject(request, ['fcmToken']);
    if (!validToken(value.fcmToken)) throw new HttpError(400, 'invalid_payload');
    // Device authentication alone cannot redirect notifications to somebody
    // else's FCM token. Every new token requires the registration challenge.
    if (!device.fcm_token || !equalDigest(await digest(device.fcm_token), await digest(value.fcmToken))) {
      throw new HttpError(409, 'token_verification_required');
    }
    try {
      const result = await env.DB.prepare(`UPDATE devices SET fcm_token = ?, token_hash = ?, updated_at = ?
        WHERE installation_id = ? AND secret_hash = ? AND fcm_token = ?`)
        .bind(value.fcmToken, await digest(value.fcmToken), now, installationId, secretHash, value.fcmToken).run();
      if (result.meta.changes !== 1) throw new HttpError(409, 'token_verification_required');
    } catch (error) {
      if (error instanceof HttpError) throw error;
      if (String(error?.message || '').includes('UNIQUE constraint failed')) {
        throw new HttpError(409, 'token_conflict');
      }
      throw new HttpError(503, 'relay_unavailable');
    }
    return reply(200, { updated: true });
  }
  throw new HttpError(404, 'not_found');
}

export default {
  async fetch(request, env, context) {
    try {
      const url = new URL(request.url);
      if (url.protocol !== 'https:') throw new HttpError(400, 'https_required');
      if (url.search) throw new HttpError(400, 'query_not_allowed');
      if (request.method === 'GET' && url.pathname === '/health') {
        return reply(200, { ok: true, service: 'moa-push-relay', protocol: 1 });
      }
      if (request.method === 'POST' && url.pathname === '/internal/jobs') {
        const value = await bodyObject(request, ['kind']);
        const now = nowSeconds();
        if (!await verifyJobRequest(request, env, value.kind, now)) throw new HttpError(401, 'unauthorized');
        if (!env.DB) throw new HttpError(503, 'relay_not_configured');
        await runWatchJob(env, now, value.kind, sendComment);
        return reply(200, { ok: true });
      }
      const admin = request.method === 'POST' && url.pathname === '/v1/admin/pairing-codes';
      const pairing = request.method === 'POST' && url.pathname === '/v1/pair';
      const registrationMatch = /^\/v1\/registrations(?:\/([0-9a-f-]{36})(?:\/(confirm))?)?$/.exec(url.pathname);
      const registration = registrationMatch && ((!registrationMatch[1] && request.method === 'POST')
        || (validRegistrationId(registrationMatch[1]) && ((request.method === 'GET' && !registrationMatch[2])
          || (request.method === 'POST' && registrationMatch[2] === 'confirm'))));
      const match = /^\/v1\/devices\/([A-Za-z0-9_-]{16,80})(?:\/(test|token|watches)(?:\/([0-9a-f-]{36}))?)?$/.exec(url.pathname);
      const device = match && ((request.method === 'POST' && match[2] === 'test')
        || (request.method === 'PUT' && match[2] === 'token')
        || (request.method === 'DELETE' && !match[2])
        || (match[2] === 'watches' && ((!match[3] && ['GET', 'PUT'].includes(request.method))
          || (request.method === 'DELETE' && validWatchId(match[3])))))
        && (!match[3] || match[2] === 'watches');
      // Invalid routes never use the database. Rate-limit counters also saturate
      // so rejected requests cannot keep consuming the daily D1 write quota.
      if (!admin && !pairing && !device && !registration) throw new HttpError(404, 'not_found');
      if (!env.DB || typeof env.ADMIN_SECRET !== 'string' || !SECRET_PATTERN.test(env.ADMIN_SECRET)) {
        throw new HttpError(503, 'relay_not_configured');
      }
      const now = nowSeconds();
      await entryBudget(request, env, now);
      if (admin) return await issueCode(request, env, now);
      if (pairing) return await pair(request, env, now);
      if (registration) return await registrationRoute(request, env, registrationMatch[1], !!registrationMatch[2], now, registrationHelpers(), context);
      if (device) return await deviceRoute(request, env, match, now);
      throw new HttpError(404, 'not_found');
    } catch (error) {
      if (error instanceof HttpError || error instanceof WatchError || error instanceof RegistrationError) return reply(error.status, { error: error.code }, error.retryAfter);
      return reply(503, { error: 'relay_unavailable' });
    }
  },
  async scheduled(_event, env) {
    const now = nowSeconds();
    await env.DB.batch([
      env.DB.prepare('DELETE FROM pairing_codes WHERE expires_at <= ?').bind(now),
      env.DB.prepare('DELETE FROM rate_limits WHERE expires_at <= ?').bind(now),
    ]);
    await cleanupRegistrations(env, now);
    await dispatchScheduledWatches(env, now);
  },
};
