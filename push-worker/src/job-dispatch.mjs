import { planWatchJobs } from './public-watches.mjs';

const JOB_KINDS = new Set(['check', 'deliver', 'fanout']);
const MAX_JOBS = 14;
const CONCURRENCY = 4;
const encoder = new TextEncoder();
let keyCache;

function validSecret(value) { return typeof value === 'string' && /^[A-Za-z0-9_-]{43,128}$/.test(value); }
async function signingKey(secret) {
  if (!keyCache || keyCache.secret !== secret) keyCache = {
    secret,
    key: crypto.subtle.importKey('raw', encoder.encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign','verify']),
  };
  return keyCache.key;
}
function purpose(origin, minute, kind) { return encoder.encode(`moa-job-v1\n${origin}\n${minute}\n${kind}`); }
function base64url(bytes) {
  return btoa(String.fromCharCode(...new Uint8Array(bytes))).replaceAll('+','-').replaceAll('/','_').replaceAll('=','');
}

export async function verifyJobRequest(request, env, kind, now) {
  const origin = configuredRelayOrigin(env), time = request.headers.get('X-Moa-Job-Time') || '';
  const proof = request.headers.get('X-Moa-Job-Proof') || '';
  const target = new URL(request.url);
  if (!origin || !validSecret(env.ADMIN_SECRET) || !JOB_KINDS.has(kind)
      || request.method !== 'POST' || target.origin !== origin || target.pathname !== '/internal/jobs' || target.search || target.hash
      || !/^[1-9][0-9]{8,10}$/.test(time) || !/^[A-Za-z0-9_-]{43}$/.test(proof)) return false;
  const stamp = Number(time);
  if (stamp % 60 !== 0 || stamp > now + 30 || now - stamp > 120) return false;
  try {
    const bytes = Uint8Array.from(atob(proof.replaceAll('-','+').replaceAll('_','/')+'='), c=>c.charCodeAt(0));
    if (base64url(bytes) !== proof) return false;
    return await crypto.subtle.verify('HMAC', await signingKey(env.ADMIN_SECRET), bytes, purpose(origin, time, kind));
  } catch { return false; }
}

// Job payloads contain no URLs, users, tokens or content. Only a short-lived,
// origin- and purpose-bound proof is sent; the administrator secret never is.
export function configuredRelayOrigin(env) {
  const value = env.RELAY_ORIGIN;
  if (typeof value !== 'string' || value.length > 280) return null;
  try {
    const url = new URL(value);
    if (url.protocol !== 'https:' || url.username || url.password || url.port
        || ![url.origin, url.origin + '/'].includes(value)
        || url.search || url.hash || !['', '/'].includes(url.pathname)
        || !/^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.workers\.dev$/.test(url.hostname)) return null;
    return url.origin;
  } catch { return null; }
}

export async function dispatchScheduledWatches(env, now) {
  const origin = configuredRelayOrigin(env);
  if (!origin || !validSecret(env.ADMIN_SECRET)) return { dispatched: 0, failed: 0, configured: false };
  const jobs = await planWatchJobs(env, now);
  if (!Array.isArray(jobs) || jobs.length > MAX_JOBS || jobs.some(kind => !JOB_KINDS.has(kind)))
    throw new Error('Invalid bounded job plan');
  let cursor = 0, failed = 0;
  async function drain() {
    for (;;) {
      const index = cursor++;
      if (index >= jobs.length) return;
      try {
        const stamp = String(Math.floor(now / 60) * 60);
        const proof = base64url(await crypto.subtle.sign('HMAC', await signingKey(env.ADMIN_SECRET), purpose(origin, stamp, jobs[index])));
        const response = await fetch(origin + '/internal/jobs', {
          method: 'POST', redirect: 'manual', signal: AbortSignal.timeout(20000),
          headers: { 'Content-Type': 'application/json', 'User-Agent': 'Moa-Queue/1.0', 'X-Moa-Job-Time': stamp, 'X-Moa-Job-Proof': proof },
          body: JSON.stringify({ kind: jobs[index] }),
        });
        if (!response.ok || response.redirected) failed++;
        await response.body?.cancel();
      } catch { failed++; }
    }
  }
  await Promise.all(Array.from({ length: Math.min(CONCURRENCY, jobs.length) }, drain));
  return { dispatched: jobs.length, failed, configured: true };
}
