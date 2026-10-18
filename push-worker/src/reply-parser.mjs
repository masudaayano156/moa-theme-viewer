// Read public reply relationships only. No author, text, browser login or session is persisted.
const ID = /^[1-9][0-9]{0,18}$/;
const LIMIT = 512 * 1024;
const MAX_WANTED = 512;
const SNAPSHOT_LIMIT = 64 * 1024;
const MAX_ROWS = 8192;
export class ReplyReadError extends Error {
  constructor(code) { super(code); this.code = code; }
}
export function commentTarget(value) {
  if (typeof value !== 'string') return null;
  const match = /^(https:\/\/m\.dcinside\.com\/(board|mini|person)\/([A-Za-z0-9_]{1,80})\/([1-9][0-9]{0,18}))\?comment=([1-9][0-9]{0,18})$/.exec(value);
  return match ? { base: match[1], kind: match[2], board: match[3], article: match[4], comment: match[5] } : null;
}
export function laterCommentId(next, previous) {
  return typeof next === 'string' && /^(0|[1-9][0-9]{0,18})$/.test(next)
    && (!previous || next.length > previous.length || next.length === previous.length && next > previous);
}
async function checked(response) {
  async function reject(code) { try { await response.body?.cancel(); } catch {} throw new ReplyReadError(code); }
  if (response.status === 403) return reject('blocked');
  if (response.status === 429) return reject('rate_limited');
  if (!response.ok || response.redirected || !/^text\/html(?:;|$)/i.test(response.headers.get('Content-Type') || '') || !response.body)
    return reject('unavailable');
  const length = Number(response.headers.get('Content-Length') || 0);
  if (!Number.isSafeInteger(length) || length < 0 || length > LIMIT) return reject('parse_error');
}
async function parseReplyTargets(response, wantedIds, fragment = false) {
  await checked(response);
  const scope = fragment ? '' : '#comment_box ';
  let bytes = 0, root = null, row = null, invalid = false, csrf = '';
  let inputMatches = 0, inputCount = null, headingMatches = 0, heading = '';
  const wanted = new Map(wantedIds.map(id => [id, { found: false, count: 0, latestReplyNo: '0' }]));
  const seen = new Set();
  const bounded = response.body.pipeThrough(new TransformStream({ transform(chunk, control) {
    bytes += chunk.byteLength;
    if (bytes > LIMIT) throw new ReplyReadError('parse_error');
    control.enqueue(chunk);
  } }));
  const onRow = { element(element) {
    if (!wanted.size) return; // Article-only watches need the aggregate, not reply row identities.
    const no = element.getAttribute('no'), classes = (element.getAttribute('class') || '').split(/\s+/);
    if (seen.has(no) && row && row.no === no) return;
    if (!ID.test(no || '') || seen.has(no)) { invalid = true; root = null; row = null; return; }
    if (seen.size >= MAX_ROWS) throw new ReplyReadError('parse_error');
    seen.add(no);
    const reply = classes.includes('comment-add');
    if (!reply) root = no;
    const current = row = { no, reply, parent: root, mentions: [] };
    if (wanted.has(no)) wanted.get(no).found = true;
    element.onEndTag(() => {
      if (current.reply) {
        const targets = [...new Set(current.mentions)];
        if (targets.length > 1 || targets.includes(null)) {
          if (wanted.has(current.parent) || targets.some(id => wanted.has(id))) invalid = true;
        } else {
          const target = targets.length ? targets[0] : current.parent;
          if (wanted.has(target) && current.no !== target) {
            const state = wanted.get(target);
            state.count++; if (laterCommentId(current.no, state.latestReplyNo)) state.latestReplyNo = current.no;
          }
        }
      }
      if (row === current) row = null;
    });
  } };
  try {
    const transform = new HTMLRewriter()
      .on(scope + 'ul.all-comment-lst', { element(element) { root = null; element.onEndTag(() => { root = null; row = null; }); } })
      .on(scope + '.all-comment-lst > li.comment', onRow)
      .on(scope + '.all-comment-lst > li.comment-add', onRow)
      .on(scope + '.all-comment-lst > li.comment-add p.txt a.mention', { element(element) {
        if (!row) return;
        const match = /^\s*mntFocus\(\s*([1-9][0-9]{0,18})\s*,\s*false\s*\)\s*;?\s*$/.exec(element.getAttribute('onclick') || '');
        row.mentions.push(match ? match[1] : null);
      } })
      .on('meta[name="csrf-token"]', { element(element) {
        const token = element.getAttribute('content') || '';
        if (/^[A-Za-z0-9_-]{16,256}$/.test(token)) csrf = token;
      } })
      .on('#comment_box .all-comment-lst > input#reple_totalCnt', { element(element) {
        inputMatches++;
        const value = element.getAttribute('value');
        if (/^(?:0|[1-9][0-9]{0,6})$/.test(value || '') && Number(value) <= 1000000) inputCount = Number(value);
      } })
      .on('#comment_box > .all-comment-tit .tit-box > a.tit > span.ct', {
        element() { headingMatches++; },
        text(chunk) { heading += chunk.text.slice(0, Math.max(0, 32 - heading.length)); },
      })
      .transform(new Response(bounded, { headers: { 'Content-Type': 'text/html; charset=utf-8' } }));
    const reader = transform.body.getReader();
    for (;;) { if ((await reader.read()).done) break; }
  } catch (error) { throw error instanceof ReplyReadError ? error : new ReplyReadError('parse_error'); }
  if (invalid) throw new ReplyReadError('parse_error');
  const label = /^\[(0|[1-9][0-9]{0,6})\]$/.exec(heading.trim());
  const headingCount = headingMatches === 1 && label && Number(label[1]) <= 1000000 ? Number(label[1]) : null;
  let totalCount = null;
  if (inputMatches <= 1 && headingMatches <= 1 && !(inputMatches === 1 && inputCount === null)
      && !(headingMatches === 1 && headingCount === null)
      && !(inputMatches === 1 && headingMatches === 1 && inputCount !== headingCount)) {
    totalCount = inputMatches === 1 ? inputCount : headingCount;
  }
  return { wanted, csrf, totalCount };
}
export async function parseReplyPage(response, wanted, fragment = false) {
  if (typeof wanted !== 'string' || !ID.test(wanted)) throw new ReplyReadError('parse_error');
  const parsed = await parseReplyTargets(response, [wanted], fragment);
  return { ...parsed.wanted.get(wanted), csrf: parsed.csrf };
}

function articleTarget(value) {
  if (typeof value !== 'string' || value.length > 256) return null;
  const match = /^https:\/\/m\.dcinside\.com\/(board|mini|person)\/([A-Za-z0-9_]{1,80})\/([1-9][0-9]{0,18})$/.exec(value);
  return match ? { kind: match[1], board: match[2], article: match[3] } : null;
}
function updateAnonymousCookies(jar, response) {
  const values = typeof response.headers.getSetCookie === 'function' ? response.headers.getSetCookie()
    : [response.headers.get('Set-Cookie') || ''];
  for (const value of values) {
    const pair = /^([A-Za-z_][A-Za-z0-9_-]*)=([^\r\n;,\s]{0,4096})$/.exec(value.split(';')[0].trim());
    if (!pair) continue;
    if (!pair[2] || /(?:^|;)\s*max-age\s*=\s*0\s*(?:;|$)/i.test(value)) jar.delete(pair[1]);
    else if (jar.has(pair[1]) || jar.size < 16) jar.set(pair[1], pair[2]);
  }
}
function anonymousCookieHeader(jar) {
  let header = '';
  for (const [name, value] of jar) {
    const next = (header ? '; ' : '') + name + '=' + value;
    if (header.length + next.length <= 4096) header += next;
  }
  return header;
}

/** One public article fetch shares its parsed reply relationships across targets.
 * Only unresolved IDs use up to three anonymous focus lookups. No response body,
 * author, browser session, anonymous cookie or CSRF token leaves this function. */
export async function readPublicArticleSnapshot(baseUrl, wantedCommentIds) {
  const target = articleTarget(baseUrl);
  if (!target || !Array.isArray(wantedCommentIds) || wantedCommentIds.length > MAX_WANTED
      || wantedCommentIds.some(id => typeof id !== 'string' || !ID.test(id))) throw new ReplyReadError('parse_error');
  const ordered = [...new Set(wantedCommentIds)], resolved = new Map(), attempted = new Set(), jar = new Map();
  const deadline = Date.now() + 15000;
  const headers = { Accept: 'text/html', 'User-Agent': 'Moa-Public-Comment-Watch/1.0 (Android client; Mobile)' };
  async function request(url, options = {}, started) {
    const remaining = deadline - Date.now();
    if (remaining <= 0) throw new ReplyReadError('unavailable');
    if (started) started();
    try { return await fetch(url, { headers, ...options, redirect: 'manual', credentials: 'omit',
      signal: AbortSignal.timeout(Math.min(10000, remaining)) }); }
    catch { throw new ReplyReadError('unavailable'); }
  }
  function merge(parsed) {
    for (const id of ordered) {
      const state = parsed.wanted.get(id);
      const previous = resolved.get(id);
      if (state?.found && (!previous || laterCommentId(state.latestReplyNo, previous.latestReplyNo)))
        resolved.set(id, { comment: id, count: state.count, latestReplyNo: state.latestReplyNo });
    }
  }
  const response = await request(baseUrl);
  updateAnonymousCookies(jar, response);
  const first = await parseReplyTargets(response, ordered);
  if (!ordered.length && first.totalCount === null) throw new ReplyReadError('parse_error');
  merge(first);
  let csrf = first.csrf, lastFocus = null;
  const board = (target.kind === 'mini' ? 'mi$' : target.kind === 'person' ? 'pr$' : '') + target.board;
  for (let extra = 0; extra < 3 && csrf && Date.now() < deadline; extra++) {
    const focus = ordered.find(id => !resolved.has(id) && !attempted.has(id));
    if (!focus) break;
    attempted.add(focus);
    try {
      const cookies = anonymousCookieHeader(jar);
      const focused = await request('https://m.dcinside.com/ajax/response-comment', {
        method: 'POST', headers: { ...headers, 'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
          'X-CSRF-TOKEN': csrf, 'X-Requested-With': 'XMLHttpRequest', Referer: baseUrl,
          ...(cookies ? { Cookie: cookies } : {}) },
        body: new URLSearchParams({ id: board, no: target.article, cpage: '1', csort: '', managerskill: '', permission_pw: '', focus_no: focus }).toString(),
      }, () => { lastFocus = focus; });
      updateAnonymousCookies(jar, focused);
      const page = await parseReplyTargets(focused, ordered, true);
      merge(page);
      if (page.csrf) csrf = page.csrf;
    } catch (error) {
      if (error instanceof ReplyReadError && ['blocked', 'rate_limited'].includes(error.code)) throw error;
      // Deleted/missing/malformed focused pages remain unresolved; never write
      // zero over the previous known state. Other targets may still be readable.
    }
  }
  const result = { totalCount: first.totalCount, replies: ordered.filter(id => resolved.has(id)).map(id => resolved.get(id)),
    unresolved: ordered.filter(id => !resolved.has(id)), lastFocus };
  if (new TextEncoder().encode(JSON.stringify(result)).byteLength > SNAPSHOT_LIMIT) throw new ReplyReadError('parse_error');
  return result;
}
export async function readPublicReplySnapshot(value) {
  const target = commentTarget(value);
  if (!target) throw new ReplyReadError('parse_error');
  const headers = { Accept: 'text/html', 'User-Agent': 'Moa-Public-Comment-Watch/1.0 (Android client; Mobile)' };
  let response;
  try { response = await fetch(value, { headers, redirect: 'manual', credentials: 'omit', signal: AbortSignal.timeout(10000) }); }
  catch { throw new ReplyReadError('unavailable'); }
  const cookies = (typeof response.headers.getSetCookie === 'function' ? response.headers.getSetCookie()
    : [response.headers.get('Set-Cookie') || '']).map(part => part.split(';')[0].trim())
    .filter(part => /^[A-Za-z_][A-Za-z0-9_-]*=[^\r\n;,\s]{1,4096}$/.test(part)).join('; ').slice(0, 4096);
  let state = await parseReplyPage(response, target.comment);
  if (!state.found) {
    if (!state.csrf) throw new ReplyReadError('unavailable');
    // The mobile site's own read-only focus lookup locates comments beyond page one.
    // This is a fresh anonymous web session, never a browser/account cookie from the app.
    const board = (target.kind === 'mini' ? 'mi$' : target.kind === 'person' ? 'pr$' : '') + target.board;
    try { response = await fetch('https://m.dcinside.com/ajax/response-comment', {
      method: 'POST', redirect: 'manual', credentials: 'omit', signal: AbortSignal.timeout(10000),
      headers: { ...headers, 'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
        'X-CSRF-TOKEN': state.csrf, 'X-Requested-With': 'XMLHttpRequest', Referer: value,
        ...(cookies ? { Cookie: cookies } : {}) },
      body: new URLSearchParams({ id: board, no: target.article, cpage: '1', csort: '', managerskill: '', permission_pw: '', focus_no: target.comment }).toString(),
    }); } catch { throw new ReplyReadError('unavailable'); }
    state = await parseReplyPage(response, target.comment, true);
  }
  if (!state.found) throw new ReplyReadError('unavailable');
  return { count: state.count, latestReplyNo: state.latestReplyNo };
}
