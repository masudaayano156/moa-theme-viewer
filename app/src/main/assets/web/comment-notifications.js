/* Correlate the user's successful comment action with its public ID.
 * Text/name comparisons are ephemeral and stay in this WebView; only a URL and ID leave it.
 */
(function () {
  'use strict';
  if (window.top !== window || location.protocol !== 'https:' || location.hostname !== 'm.dcinside.com' ||
      !/^\/(board|mini|person)\/[A-Za-z0-9_]{1,80}\/[1-9][0-9]{0,18}\/?$/.test(location.pathname)) return;
  if (window.MoaCommentNotifications) return;
  var pending = null, hooked = null, observer = null, restoreTimer = null, gone = false;
  var enabled = window.__moaNotificationsOn === true;
  var remembered = new Set();
  var rows = '.all-comment-lst > li.comment,.all-comment-lst > li.comment-add';
  function send(value) { try { if (window.MoaLocalActions) MoaLocalActions.postMessage(JSON.stringify(value)); } catch (_) {} }
  function normalize(value) { return String(value || '').replace(/\s+/g, ' ').trim(); }
  function number(row) { var value = row && row.getAttribute('no'); return /^[1-9][0-9]{0,18}$/.test(value || '') ? value : ''; }
  function rootOf(row) {
    for (var item = row; item; item = item.previousElementSibling) if (item.matches('li.comment')) return number(item);
    return '';
  }
  function targetOf(row) {
    if (!row.matches('li.comment-add')) return '';
    var mention = row.querySelector('p.txt a.mention');
    if (!mention) return rootOf(row);
    var match = /^\s*mntFocus\(\s*([1-9][0-9]{0,18})\s*,\s*false\s*\)\s*;?\s*$/.exec(mention.getAttribute('onclick') || '');
    return match ? match[1] : '?';
  }
  function text(row) {
    var body = row.querySelector('p.txt'); if (!body) return '';
    var walker = document.createTreeWalker(body, NodeFilter.SHOW_TEXT | NodeFilter.SHOW_ELEMENT), node, value = '';
    while ((node = walker.nextNode())) {
      if (node.nodeType === 1 && node.tagName === 'BR') value += ' ';
      else if (node.nodeType === 3 && !node.parentElement.closest('a.mention')) value += node.nodeValue;
    }
    return normalize(value);
  }
  function author(row) {
    var mark = row.querySelector('.ginfo-area .blockCommentId[data-info]');
    return mark ? String(mark.getAttribute('data-info') || '').toLowerCase() : '';
  }
  function fresh(row, at) {
    var label = normalize((row.querySelector('.date') || {}).textContent);
    var match = /(?:^|\s)(?:[12][0-9]{3}[.-])?([0-9]{1,2})[.-]([0-9]{1,2})\s+([0-9]{1,2}):([0-9]{2})(?:\s|$)/.exec(label);
    if (!match) return false;
    try {
      var format = new Intl.DateTimeFormat('en-GB', { timeZone: 'Asia/Seoul', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' });
      for (var offset = -60000; offset <= 120000; offset += 60000) {
        var parts = {}; format.formatToParts(new Date(at + offset)).forEach(function (part) { parts[part.type] = part.value; });
        if (+parts.month === +match[1] && +parts.day === +match[2] && +parts.hour === +match[3] && +parts.minute === +match[4]) return true;
      }
    } catch (_) {}
    return false;
  }
  function publicationTime(row) {
    var label = normalize((row.querySelector('.date') || {}).textContent);
    var match = /(?:^|\s)(?:([12][0-9]{3})[.-])?([0-9]{1,2})[.-]([0-9]{1,2})\s+([0-9]{1,2}):([0-9]{2})(?:\s|$)/.exec(label);
    if (!match) return 0;
    try {
      var year = match[1] ? +match[1] : +new Intl.DateTimeFormat('en',{timeZone:'Asia/Seoul',year:'numeric'}).format(new Date());
      var value = Date.UTC(year, +match[2]-1, +match[3], +match[4]-9, +match[5]);
      if (!match[1] && value > Date.now() + 86400000) value = Date.UTC(year-1,+match[2]-1,+match[3],+match[4]-9,+match[5]);
      return value > Date.now()-259200000 && value <= Date.now()+120000 ? Math.floor(value/1000) : 0;
    } catch (_) { return 0; }
  }
  function restoreOwned() {
    restoreTimer = null;
    if (!enabled || gone || pending || !window.MoaLocalActions) return;
    var user = String((document.getElementById('user_id') || {}).value || '').toLowerCase();
    if (!user) return; // A shared guest nickname cannot prove historical ownership.
    document.querySelectorAll('#comment_box ' + rows.replace(',', ',#comment_box ')).forEach(function (row) {
      var id = number(row); if (!id || remembered.has(id) || author(row) !== user) return;
      var at = publicationTime(row); if (!at) return;
      remembered.add(id); send({ action:'restore_comment', commentId:id, publishedAt:at });
    });
  }
  function scheduleRestore() {
    if (!gone && restoreTimer === null) restoreTimer = setTimeout(restoreOwned, 300);
  }
  function scan() {
    var current = pending;
    if (!current || !current.accepted || Date.now() - current.at >= 45000) return;
    var found = Array.from(document.querySelectorAll('#comment_box ' + rows.replace(',', ',#comment_box '))).filter(function (row) {
      var id = number(row);
      if (!id || current.seen.has(id) || targetOf(row) !== current.parent) return false;
      if (current.serverId && id === current.serverId) return true;
      if (current.user ? author(row) !== current.user : normalize((row.querySelector('.ginfo-area .nick') || {}).textContent) !== current.nick) return false;
      return current.memo && text(row) === current.memo || current.user && fresh(row, current.at);
    });
    if (found.length !== 1) return;
    var id = number(found[0]); remembered.add(id); pending = null;
    send({ action: 'comment_published', ticket: current.ticket, commentId: id });
  }
  function ajax() {
    var jq = window.jQuery;
    if (!jq || !jq.fn || hooked === jq) return;
    hooked = jq;
    jq(document).on('ajaxSuccess.moaCommentNotifications', function (event, xhr, settings, result) {
      var current = pending; if (!current || Date.now() - current.at >= 45000) return;
      var endpoint;
      try { endpoint = new URL(settings.url, location.href); } catch (_) { return; }
      if (endpoint.origin !== location.origin || endpoint.pathname !== '/ajax/comment-write' ||
          String(settings.type || settings.method || 'GET').toUpperCase() !== 'POST') return;
      if (!result || typeof result !== 'object' || !result.result || result.result === '0') { pending = null; return; }
      current.accepted = true;
      ['comment_no', 'commentNo'].some(function (key) {
        var value = String(result[key] || ''); if (!/^[1-9][0-9]{0,18}$/.test(value)) return false;
        current.serverId = value; return true;
      });
      scan();
    });
  }
  function arm(event, form, visual) {
    if (!enabled || !event.isTrusted || !form || !/^comment_write(?:_reple|_mention)?$/.test(form.id) || !window.MoaLocalActions) return;
    if (pending && pending.form === form && Date.now() - pending.at < 1500) { pending.visual = pending.visual || visual; return; }
    if (String(form.method).toLowerCase() !== 'post') return;
    try { var action = new URL(form.getAttribute('action') || '', location.href); if (action.origin !== location.origin || action.pathname.replace(/\/$/, '') !== location.pathname.replace(/\/$/, '')) return; } catch (_) { return; }
    var memo = form.querySelector('textarea'), user = document.getElementById('user_id');
    var nick = form.querySelector('input[name=comment_nick],input[name=comment_nick_reple]');
    var name = normalize(nick && nick.value), id = String(user && user.value || '').toLowerCase();
    var content = normalize(memo && memo.value); if (!content && !visual || !id && !name) return;
    var bytes = new Uint8Array(16); try { crypto.getRandomValues(bytes); } catch (_) { return; }
    var ticket = Array.from(bytes, function (byte) { return byte.toString(16).padStart(2, '0'); }).join('');
    var field = document.getElementById('comment_no');
    pending = { form: form, at: Date.now(), ticket: ticket, memo: content, user: id, nick: name, visual: Boolean(visual),
      parent: form.id === 'comment_write' ? '' : String(field && field.value || ''), accepted: false,
      seen: new Set(Array.from(document.querySelectorAll('#comment_box ' + rows.replace(',', ',#comment_box ')), number)) };
    ajax(); send({ action: 'submitting_comment', ticket: ticket });
    setTimeout(function () {
      if (!pending || pending.ticket !== ticket) return;
      if (pending.accepted) send({ action: 'comment_tracking_failed', ticket: ticket });
      pending = null; scheduleRestore();
    }, 45000);
  }
  document.addEventListener('click', function (event) {
    var picker = event.target && event.target.closest && event.target.closest('#dcconBoxComment,#dcconBoxReple,#txtconBoxComment,#txtconBoxReple');
    if (picker && /^(commentWrite|commentReple|commentMention)$/.test(window.dcconMode || '')) {
      var mode = window.dcconMode === 'commentWrite' ? 'comment_write' : window.dcconMode === 'commentReple' ? 'comment_write_reple' : 'comment_write_mention';
      arm(event, document.getElementById(mode), true); return;
    }
    var button = event.target && event.target.closest && event.target.closest('button,input,a');
    if (!button || !button.matches('button[type=submit],input[type=submit],#commentWrite,#commentReple,#commentMention,#txtconWrite,#txtconReple,#txtconMention')) return;
    arm(event, button.form || button.closest('form'));
  }, true);
  // A script-triggered button.click()/requestSubmit() can produce a trusted submit
  // event. Only an actual pointer click or Enter key may authorize this capture.
  document.addEventListener('keydown', function (event) {
    if (!event.isTrusted || event.isComposing || event.key !== 'Enter' || !event.target ||
        event.target.tagName === 'TEXTAREA' && !event.ctrlKey && !event.metaKey) return;
    arm(event, event.target.form || event.target.closest && event.target.closest('form'));
  }, true);
  window.addEventListener('pagehide', function () { pending = null; gone = true; if (restoreTimer !== null) clearTimeout(restoreTimer); restoreTimer = null; observer.disconnect(); });
  window.addEventListener('pageshow', function () { gone = false; observer.observe(document,{childList:true,subtree:true}); scheduleRestore(); });
  observer = new MutationObserver(function () { ajax(); scan(); scheduleRestore(); });
  observer.observe(document, { childList: true, subtree: true });
  ajax(); scheduleRestore();
  window.MoaCommentNotifications = Object.freeze({ version: '1.0.0', refresh: function (value) {
    var next = value === true;
    if (enabled !== next) { remembered.clear(); pending = null; enabled = next; }
    if (enabled) scheduleRestore();
  } });
})();
