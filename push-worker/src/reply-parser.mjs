// Read public reply relationships only. No author, text, browser login or session is persisted.
const ID = /^[1-9][0-9]{0,18}$/;
const LIMIT = 512 * 1024;
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
export async function parseReplyPage(response, wanted, fragment = false) {
  await checked(response);
  const scope = fragment ? '' : '#comment_box ';
  let bytes = 0, root = null, row = null, found = false, count = 0, latest = '0', invalid = false, csrf = '';
  const seen = new Set();
  const bounded = response.body.pipeThrough(new TransformStream({ transform(chunk, control) {
    bytes += chunk.byteLength;
    if (bytes > LIMIT) throw new ReplyReadError('parse_error');
    control.enqueue(chunk);
  } }));
  const onRow = { element(element) {
    const no = element.getAttribute('no'), classes = (element.getAttribute('class') || '').split(/\s+/);
    if (seen.has(no) && row && row.no === no) return;
    if (!ID.test(no || '') || seen.has(no)) { invalid = true; root = null; row = null; return; }
    seen.add(no);
    const reply = classes.includes('comment-add');
    if (!reply) root = no;
    const current = row = { no, reply, parent: root, mentions: [] };
    if (no === wanted) found = true;
    element.onEndTag(() => {
      if (current.reply) {
        const targets = [...new Set(current.mentions)];
        if (targets.length > 1 || targets.includes(null)) {
          if (current.parent === wanted || targets.includes(wanted)) invalid = true;
        } else {
          const target = targets.length ? targets[0] : current.parent;
          if (target === wanted && current.no !== wanted) {
            count++; if (laterCommentId(current.no, latest)) latest = current.no;
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
      .transform(new Response(bounded, { headers: { 'Content-Type': 'text/html; charset=utf-8' } }));
    const reader = transform.body.getReader();
    for (;;) { if ((await reader.read()).done) break; }
  } catch (error) { throw error instanceof ReplyReadError ? error : new ReplyReadError('parse_error'); }
  if (invalid || count > 1000000) throw new ReplyReadError('parse_error');
  return { found, count, latestReplyNo: latest, csrf };
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
