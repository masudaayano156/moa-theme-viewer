const TTL = 300;
const CANCEL_TTL = 600;
const MAX_PENDING = 2000;
const MAX_CANCELLATIONS = 4000;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const INSTALLATION = /^[A-Za-z0-9_-]{16,80}$/;
const SECRET = /^[A-Za-z0-9_-]{43,128}$/;
const CHALLENGE = /^[A-Za-z0-9_-]{43}$/;

export class RegistrationError extends Error {
  constructor(status, code, retryAfter = 0) { super(code); this.status = status; this.code = code; this.retryAfter = retryAfter; }
}

export function validRegistrationId(value) { return typeof value === 'string' && UUID.test(value); }

function info(row) {
  return { registrationId: row.registration_id, installationId: row.installation_id,
    state: row.state, expiresAt: row.expires_at, ...(row.state === 'confirmed' ? { paired: true } : {}) };
}

async function getRegistration(env, id) {
  return env.DB.prepare('SELECT * FROM public_registrations WHERE registration_id = ?').bind(id).first();
}

async function currentConfirmedDevice(env, id, secretHash, tokenHash, now) {
  return env.DB.prepare(`SELECT installation_id FROM devices WHERE installation_id = ? AND secret_hash = ?
    AND token_hash = ? AND fcm_token IS NOT NULL AND NOT EXISTS (SELECT 1 FROM registration_cancellations
      WHERE installation_id = ? AND secret_hash = ? AND expires_at > ?)`)
    .bind(id, secretHash, tokenHash, id, secretHash, now).first();
}

async function authenticatedRegistration(request, env, id, now, helpers) {
  const secret = helpers.bearer(request);
  if (!secret) throw new RegistrationError(401, 'unauthorized');
  const row = await getRegistration(env, id);
  if (!row || !helpers.equalDigest(row.secret_hash, await helpers.digest(secret))) throw new RegistrationError(401, 'unauthorized');
  if (row.expires_at <= now) throw new RegistrationError(410, 'registration_expired');
  if (row.state === 'superseded') throw new RegistrationError(409, 'registration_superseded');
  return row;
}

async function checkedInfo(env, row, now) {
  if (row.state === 'confirmed' && !await currentConfirmedDevice(env, row.installation_id, row.secret_hash, row.token_hash, now)) {
    throw new RegistrationError(409, 'registration_superseded');
  }
  return info(row);
}

async function notifyConfirmed(env, installationId, now, helpers) {
  if (helpers.onRegistered) await helpers.onRegistered(env, installationId, now);
}

async function dispatchChallenge(env, row, challenge, now, helpers) {
  // OFF or a newer attempt can retire the pending row while HTTP is returning.
  const active = await env.DB.prepare(`SELECT registration_id FROM public_registrations
    WHERE registration_id = ? AND state = 'pending' AND challenge_hash = ? AND expires_at > ?
      AND NOT EXISTS (SELECT 1 FROM registration_cancellations c WHERE c.installation_id = public_registrations.installation_id
        AND c.secret_hash = public_registrations.secret_hash AND c.expires_at > ?)`)
    .bind(row.registration_id, row.challenge_hash, now, now).first();
  if (!active) return;
  try {
    await helpers.sendRegistration(env, row.fcm_token, row.registration_id, row.installation_id, challenge, row.token_hash, now);
  } catch {
    // A lost upstream response may still have delivered the challenge. A valid
    // proof may complete this failed attempt until expiry; it is never resent.
    await env.DB.prepare(`UPDATE public_registrations SET state = 'failed'
      WHERE registration_id = ? AND challenge_hash = ? AND state = 'pending'`)
      .bind(row.registration_id, row.challenge_hash).run();
  }
}

async function beginRegistration(request, env, now, helpers, context) {
  const value = await helpers.bodyObject(request, ['registrationId', 'installationId', 'installationSecret', 'fcmToken']);
  if (!validRegistrationId(value.registrationId) || typeof value.installationId !== 'string' || !INSTALLATION.test(value.installationId)
      || typeof value.installationSecret !== 'string' || !SECRET.test(value.installationSecret)
      || !helpers.validToken(value.fcmToken)) throw new RegistrationError(400, 'invalid_payload');
  const [secretHash, tokenHash] = await Promise.all([helpers.digest(value.installationSecret), helpers.digest(value.fcmToken)]);
  const old = await getRegistration(env, value.registrationId);
  if (old) {
    if (old.installation_id !== value.installationId || !helpers.equalDigest(old.secret_hash, secretHash)
        || !helpers.equalDigest(old.token_hash, tokenHash)) throw new RegistrationError(409, 'registration_conflict');
    if (old.expires_at <= now) throw new RegistrationError(410, 'registration_expired');
    if (old.state === 'superseded') throw new RegistrationError(409, 'registration_superseded');
    const state = await checkedInfo(env, old, now);
    if (old.state === 'confirmed') await notifyConfirmed(env, old.installation_id, now, helpers);
    return helpers.reply(old.state === 'confirmed' ? 200 : 202, state);
  }
  if (await currentConfirmedDevice(env, value.installationId, secretHash, tokenHash, now)) {
    await notifyConfirmed(env, value.installationId, now, helpers);
    return helpers.reply(200, { registrationId: value.registrationId, installationId: value.installationId,
      state: 'confirmed', paired: true, expiresAt: now + TTL });
  }
  const challenge = helpers.base64url(crypto.getRandomValues(new Uint8Array(32)));
  const challengeHash = await helpers.digest(challenge);
  const address = request.headers.get('CF-Connecting-IP') || 'unavailable';
  const ipHash = await helpers.digest(`${env.ADMIN_SECRET}:registration:${Math.floor(now / 86400)}:${address}`);
  const limits = [
    { key: 'registration:send:global', duration: 86400, limit: 2000 },
    { key: `registration:send:ip:${ipHash}`, duration: 3600, limit: 20 },
    { key: `registration:send:id:${value.installationId}`, duration: 3600, limit: 6 },
    { key: `registration:send:token:${tokenHash}`, duration: 3600, limit: 6 },
  ].map(item => ({ ...item, start: now - now % item.duration }));
  const quotaConditions = limits.map(() => 'COALESCE((SELECT hits FROM rate_limits WHERE bucket_key = ? AND window_start = ?), 0) < ?').join(' AND ');
  const quotaArgs = limits.flatMap(item => [item.key, item.start, item.limit]);
  const insert = env.DB.prepare(`INSERT INTO public_registrations
    (registration_id, installation_id, secret_hash, token_hash, fcm_token, challenge_hash, state, created_at, expires_at)
    SELECT ?, ?, ?, ?, ?, ?, 'pending', ?, ?
    WHERE NOT EXISTS (SELECT 1 FROM devices WHERE installation_id = ? AND secret_hash != ?)
      AND NOT EXISTS (SELECT 1 FROM public_registrations WHERE installation_id = ? AND secret_hash != ? AND expires_at > ?)
      AND NOT EXISTS (SELECT 1 FROM registration_cancellations WHERE installation_id = ? AND secret_hash = ? AND expires_at > ?)
      AND (SELECT count(*) FROM public_registrations WHERE state IN ('pending', 'failed') AND expires_at > ?) < ?
      AND ${quotaConditions}
    ON CONFLICT(registration_id) DO NOTHING`).bind(value.registrationId, value.installationId, secretHash, tokenHash,
      value.fcmToken, challengeHash, now, now + TTL, value.installationId, secretHash,
      value.installationId, secretHash, now, value.installationId, secretHash, now, now, MAX_PENDING, ...quotaArgs);
  // D1 executes this batch atomically: all four budgets are consumed only when
  // this exact random challenge was inserted, and no competing start can exceed a cap.
  await env.DB.batch([
    env.DB.prepare('DELETE FROM public_registrations WHERE expires_at <= ?').bind(now),
    env.DB.prepare('DELETE FROM registration_cancellations WHERE expires_at <= ?').bind(now),
    insert,
    ...limits.map(item => env.DB.prepare(`INSERT INTO rate_limits(bucket_key, window_start, expires_at, hits)
      SELECT ?, ?, ?, 1 WHERE EXISTS (SELECT 1 FROM public_registrations WHERE registration_id = ? AND challenge_hash = ?)
      ON CONFLICT(bucket_key, window_start) DO UPDATE SET hits = hits + 1`)
      .bind(item.key, item.start, item.start + item.duration * 2, value.registrationId, challengeHash)),
    env.DB.prepare(`UPDATE public_registrations SET state = 'superseded', challenge_hash = NULL, fcm_token = NULL
      WHERE installation_id = ? AND secret_hash = ? AND registration_id != ? AND state IN ('pending', 'failed')
        AND EXISTS (SELECT 1 FROM public_registrations WHERE registration_id = ? AND challenge_hash = ?)`)
      .bind(value.installationId, secretHash, value.registrationId, value.registrationId, challengeHash),
  ]);
  const row = await getRegistration(env, value.registrationId);
  if (!row) {
    const cancelled = await env.DB.prepare(`SELECT installation_id FROM registration_cancellations
      WHERE installation_id = ? AND secret_hash = ? AND expires_at > ?`)
      .bind(value.installationId, secretHash, now).first();
    if (cancelled) throw new RegistrationError(409, 'registration_cancelled');
    for (const item of limits) {
      const rate = await env.DB.prepare('SELECT hits FROM rate_limits WHERE bucket_key = ? AND window_start = ?').bind(item.key, item.start).first();
      if (rate && rate.hits >= item.limit) throw new RegistrationError(429, 'registration_rate_limited', item.start + item.duration - now);
    }
    const pending = await env.DB.prepare(`SELECT count(*) AS count FROM public_registrations WHERE state IN ('pending', 'failed') AND expires_at > ?`).bind(now).first();
    if (pending.count >= MAX_PENDING) throw new RegistrationError(429, 'registration_capacity', 60);
    throw new RegistrationError(409, 'registration_conflict');
  }
  if (row.installation_id !== value.installationId || !helpers.equalDigest(row.secret_hash, secretHash)
      || !helpers.equalDigest(row.token_hash, tokenHash)) throw new RegistrationError(409, 'registration_conflict');
  if (row.challenge_hash === challengeHash && row.state === 'pending') {
    const sending = dispatchChallenge(env, row, challenge, now, helpers).catch(() => { });
    if (context?.waitUntil) context.waitUntil(sending);
    else await sending;
  }
  return helpers.reply(row.state === 'confirmed' ? 200 : 202, await checkedInfo(env, row, now));
}

async function confirmRegistration(request, env, id, now, helpers) {
  const value = await helpers.bodyObject(request, ['challenge']);
  if (typeof value.challenge !== 'string' || !CHALLENGE.test(value.challenge)) throw new RegistrationError(400, 'invalid_payload');
  const row = await authenticatedRegistration(request, env, id, now, helpers);
  if (row.state === 'confirmed') {
    await checkedInfo(env, row, now);
    await notifyConfirmed(env, row.installation_id, now, helpers);
    return helpers.reply(200, { paired: true, installationId: row.installation_id });
  }
  const hash = await helpers.digest(value.challenge);
  if (!row.challenge_hash || !helpers.equalDigest(hash, row.challenge_hash)) throw new RegistrationError(401, 'unauthorized');
  await env.DB.batch([
    env.DB.prepare(`INSERT INTO devices(installation_id, secret_hash, fcm_token, token_hash, created_at, updated_at)
      SELECT p.installation_id, p.secret_hash, p.fcm_token, p.token_hash, ?, ? FROM public_registrations p
      WHERE p.registration_id = ? AND p.secret_hash = ? AND p.challenge_hash = ? AND p.expires_at > ?
        AND p.state IN ('pending', 'failed') AND p.fcm_token IS NOT NULL
        AND NOT EXISTS (SELECT 1 FROM registration_cancellations c WHERE c.installation_id = p.installation_id AND c.secret_hash = p.secret_hash AND c.expires_at > ?)
        AND NOT EXISTS (SELECT 1 FROM devices d WHERE d.installation_id = p.installation_id AND d.secret_hash != p.secret_hash)
        AND NOT EXISTS (SELECT 1 FROM devices d WHERE d.token_hash = p.token_hash AND d.installation_id != p.installation_id)
      ON CONFLICT(installation_id) DO UPDATE SET fcm_token = excluded.fcm_token, token_hash = excluded.token_hash,
        updated_at = excluded.updated_at WHERE devices.secret_hash = excluded.secret_hash`)
      .bind(now, now, id, row.secret_hash, hash, now, now),
    env.DB.prepare(`UPDATE public_registrations SET state = 'confirmed', challenge_hash = NULL, fcm_token = NULL
      WHERE registration_id = ? AND secret_hash = ? AND challenge_hash = ? AND expires_at > ? AND state IN ('pending', 'failed')
        AND EXISTS (SELECT 1 FROM devices d WHERE d.installation_id = public_registrations.installation_id
          AND d.secret_hash = public_registrations.secret_hash AND d.token_hash = public_registrations.token_hash)
        AND NOT EXISTS (SELECT 1 FROM registration_cancellations c WHERE c.installation_id = public_registrations.installation_id
          AND c.secret_hash = public_registrations.secret_hash AND c.expires_at > ?)`)
      .bind(id, row.secret_hash, hash, now, now),
  ]);
  const current = await getRegistration(env, id);
  if (!current || current.state !== 'confirmed') throw new RegistrationError(409, 'registration_conflict');
  await checkedInfo(env, current, now);
  await notifyConfirmed(env, current.installation_id, now, helpers);
  return helpers.reply(200, { paired: true, installationId: row.installation_id });
}

export async function registrationRoute(request, env, id, confirm, now, helpers, context) {
  if (!id && request.method === 'POST') return beginRegistration(request, env, now, helpers, context);
  if (!validRegistrationId(id)) throw new RegistrationError(404, 'not_found');
  if (request.method === 'POST' && confirm) return confirmRegistration(request, env, id, now, helpers);
  if (request.method === 'GET' && !confirm) {
    const row = await authenticatedRegistration(request, env, id, now, helpers);
    const state = await checkedInfo(env, row, now);
    if (row.state === 'confirmed') await notifyConfirmed(env, row.installation_id, now, helpers);
    return helpers.reply(200, state);
  }
  throw new RegistrationError(404, 'not_found');
}

export async function cancelRegistrationDevice(request, env, installationId, now, helpers) {
  const secret = helpers.bearer(request);
  if (!secret) throw new RegistrationError(401, 'unauthorized');
  const secretHash = await helpers.digest(secret);
  const results = await env.DB.batch([
    env.DB.prepare('DELETE FROM registration_cancellations WHERE expires_at <= ?').bind(now),
    env.DB.prepare(`INSERT INTO registration_cancellations(installation_id, secret_hash, expires_at)
      SELECT ?, ?, ? WHERE NOT EXISTS (SELECT 1 FROM devices WHERE installation_id = ? AND secret_hash != ?)
        AND NOT EXISTS (SELECT 1 FROM public_registrations WHERE installation_id = ? AND secret_hash != ? AND expires_at > ?)
        AND ((SELECT count(*) FROM registration_cancellations) < ?
          OR EXISTS (SELECT 1 FROM devices WHERE installation_id = ? AND secret_hash = ?)
          OR EXISTS (SELECT 1 FROM public_registrations WHERE installation_id = ? AND secret_hash = ?)
          OR EXISTS (SELECT 1 FROM registration_cancellations WHERE installation_id = ? AND secret_hash = ?))
      ON CONFLICT(installation_id, secret_hash) DO UPDATE SET expires_at = excluded.expires_at`)
      .bind(installationId, secretHash, now + CANCEL_TTL, installationId, secretHash,
        installationId, secretHash, now, MAX_CANCELLATIONS, installationId, secretHash, installationId, secretHash, installationId, secretHash),
    env.DB.prepare(`DELETE FROM watch_targets WHERE target_id IN
      (SELECT s.target_id FROM watch_subscriptions s JOIN devices d USING(installation_id)
        WHERE s.installation_id = ? AND d.secret_hash = ?)
      AND NOT EXISTS (SELECT 1 FROM watch_subscriptions other WHERE other.target_id = watch_targets.target_id AND other.installation_id != ?)`)
      .bind(installationId, secretHash, installationId),
    env.DB.prepare('DELETE FROM public_registrations WHERE installation_id = ? AND secret_hash = ?').bind(installationId, secretHash),
    env.DB.prepare('DELETE FROM devices WHERE installation_id = ? AND secret_hash = ?').bind(installationId, secretHash),
  ]);
  if (!(results[1].meta?.changes > 0)) {
    const device = await env.DB.prepare('SELECT secret_hash FROM devices WHERE installation_id = ?').bind(installationId).first();
    if (device && !helpers.equalDigest(device.secret_hash, secretHash)) throw new RegistrationError(401, 'unauthorized');
    const pending = await env.DB.prepare('SELECT secret_hash FROM public_registrations WHERE installation_id = ? AND expires_at > ? LIMIT 1').bind(installationId, now).first();
    if (pending && !helpers.equalDigest(pending.secret_hash, secretHash)) throw new RegistrationError(401, 'unauthorized');
    throw new RegistrationError(429, 'registration_capacity', 60);
  }
  return helpers.reply(200, { unpaired: true });
}

export async function cleanupRegistrations(env, now) {
  await env.DB.batch([
    env.DB.prepare('DELETE FROM public_registrations WHERE expires_at <= ?').bind(now),
    env.DB.prepare('DELETE FROM registration_cancellations WHERE expires_at <= ?').bind(now),
  ]);
}
