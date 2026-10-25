/* A local, account-scoped inbox for the service's R/RR history and foreground
 * popups. No requests, credentials, source-history writes or notification text
 * cross the native bridge. Read/delete state belongs only to this inbox. */
(function () {
  'use strict';
  if (window.top !== window || location.protocol !== 'https:' || location.hostname !== 'm.dcinside.com' ||
      location.port && location.port !== '443' || location.username || location.password) return;
  if (window.MoaNotificationInbox) { window.MoaNotificationInbox.refresh(); return; }

  var prefix = 'moa_notification_inbox_v1:', owned = 'data-moa-inbox-owned';
  var currentAccount = null, currentPage = location.href, data = empty(), memory = new Map(), dirty = new Set();
  var panel = null, style = null, list = null, count = null, readButton = null, clearButton = null;
  var observer = null, interval = null, gone = false, scheduled = false, rendered = '', lastState = '';
  var returnFocus = null, panelAccount = '', panelPage = '';
  var lastReceipt = 0;

  function empty() { return {version:1, items:[], deleted:[], clearedBefore:0}; }
  function supported() {
    return /^\/(?:board|mini|person)\/[A-Za-z0-9_]{1,80}(?:\/[1-9][0-9]{0,18})?\/?$/.test(location.pathname) ||
      /^\/(?:write|modify)\/[A-Za-z0-9_]{1,80}(?:\/(?:modify\/)?[1-9][0-9]{0,18})?\/?$/.test(location.pathname) ||
      /^\/search(?:\/(?:search_main|gall_name|gall_content|movie))?\/?$/.test(location.pathname) || location.pathname === '/aside';
  }
  function active() {
    return !gone && supported() && window.__moaForegroundActive === true &&
      !document.hidden && document.visibilityState !== 'hidden';
  }
  function account() {
    var field = document.getElementById('user_id'), value = String(field && field.value || '');
    return /^[A-Za-z0-9_-]{1,120}$/.test(value) ? value : '';
  }
  function plain(value) {
    if (typeof value !== 'string') return '';
    // Template contents are inert: markup cannot execute or fetch media.
    var template = document.createElement('template'); template.innerHTML = value.slice(0, 2048);
    template.content.querySelectorAll('script,style,iframe,object').forEach(function (node) { node.remove(); });
    return (template.content.textContent || '').replace(/[\u0000-\u001f\u007f]/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 240);
  }
  function safeUrl(value) {
    if (typeof value !== 'string' || !value || value.length > 512) return '';
    try {
      var url = new URL(value, location.href), valid = true, comments = url.searchParams.getAll('comment');
      if (url.protocol !== 'https:' || url.hostname !== 'm.dcinside.com' || url.username || url.password ||
          url.port && url.port !== '443' || url.hash ||
          !/^\/(?:board|mini|person)\/[A-Za-z0-9_]{1,80}\/[1-9][0-9]{0,18}\/?$/.test(url.pathname)) return '';
      url.searchParams.forEach(function (_, name) { if (name !== 'comment') valid = false; });
      if (!valid || comments.length > 1 || comments.length && !/^[1-9][0-9]{0,18}$/.test(comments[0])) return '';
      return url.origin + url.pathname.replace(/\/$/, '') + (comments.length ? '?comment=' + comments[0] : '');
    } catch (_) { return ''; }
  }
  function kind(value) { return value === 'R' || value === '댓글' ? 'R' : value === 'RR' || value === '답글' ? 'RR' : ''; }
  function timestamp(value) {
    if (typeof value !== 'number' && !(typeof value === 'string' && /^[0-9]{12,14}$/.test(value))) return 0;
    var number = Number(value);
    return Number.isSafeInteger(number) && number >= 946684800000 && number <= Date.now() + 300000 ? number : 0;
  }
  function fingerprint(text) {
    var hash = 2166136261;
    for (var index = 0; index < text.length; index++) hash = Math.imul(hash ^ text.charCodeAt(index), 16777619) >>> 0;
    return ('00000000' + hash.toString(16)).slice(-8) + ':' + text.length;
  }
  function tombstone(item) {
    return {key:item.key, kind:item.kind, url:item.url, date:item.date, origin:item.origin, stamp:fingerprint(item.text)};
  }
  function deletedRecord(value) {
    if (!value || typeof value !== 'object') return null;
    var type = kind(value.kind), url = safeUrl(value.url), date = timestamp(value.date);
    var stamp = typeof value.stamp === 'string' ? value.stamp : fingerprint(plain(value.text));
    if (!type || !url || !date || !/^[a-f0-9]{8}:[0-9]{1,3}$/.test(stamp)) return null;
    return {key:type + '|' + url + '|' + date, kind:type, url:url, date:date, origin:value.origin === 'popup' ? 'popup' : 'site', stamp:stamp};
  }
  function record(value, receiving) {
    if (!value || typeof value !== 'object') return null;
    try {
      var type = kind(value.kind), url = safeUrl(value.url), date = receiving ? Math.max(Date.now(), lastReceipt + 1) : timestamp(value.date);
      var text = plain(value.text);
      if (!type || !url || !date || !text) return null;
      if (receiving) lastReceipt = date;
      return {key:type + '|' + url + '|' + date, kind:type, url:url, text:text, date:date, read:!receiving && value.read === true, origin:receiving || value.origin === 'popup' ? 'popup' : 'site'};
    } catch (_) { return null; }
  }
  function decode(raw) {
    var result = empty();
    if (typeof raw !== 'string' || raw.length > 524288) return result;
    try {
      var value = JSON.parse(raw); if (!value || value.version !== 1 || !Array.isArray(value.items)) return result;
      var seen = new Set();
      value.items.slice(0, 100).forEach(function (item) { var clean = record(item, false); if (clean && !seen.has(clean.key)) { seen.add(clean.key); result.items.push(clean); } });
      if (Array.isArray(value.deleted)) value.deleted.slice(0, 200).forEach(function (item) { var clean = deletedRecord(item); if (clean && !result.deleted.some(function (old) { return old.key === clean.key; })) result.deleted.push(clean); });
      result.clearedBefore = timestamp(value.clearedBefore);
      result.items = result.items.filter(function (item) { return item.date > result.clearedBefore && !result.deleted.some(function (old) { return old.key === item.key; }); }).sort(function (a, b) { return b.date - a.date; });
    } catch (_) {}
    return result;
  }
  function load(owner) {
    if (!owner) return empty();
    if (dirty.has(owner)) return memory.get(owner) || empty();
    try { var raw = localStorage.getItem(prefix + owner); return raw === null ? memory.get(owner) || empty() : decode(raw); }
    catch (_) { return memory.get(owner) || empty(); }
  }
  function save() {
    if (!currentAccount || account() !== currentAccount || !active()) return;
    memory.set(currentAccount, data);
    if (memory.size > 8) { var oldest = memory.keys().next().value; memory.delete(oldest); dirty.delete(oldest); }
    try { localStorage.setItem(prefix + currentAccount, JSON.stringify(data)); dirty.delete(currentAccount); }
    catch (_) { dirty.add(currentAccount); }
  }
  function add(item) {
    if (!item) return false;
    var exact = data.items.find(function (old) { return old.key === item.key; }) || data.deleted.find(function (old) { return old.key === item.key; });
    if (exact) {
      if (item.origin === 'site' && exact.origin === 'popup') { exact.origin = 'site'; return true; }
      return false;
    }
    // A source history entry is written shortly after its popup. Match that
    // receipt once, then retain the exact site occurrence timestamp. Distinct
    // later site timestamps remain independent events, including at one URL.
    var nearby = function (old) { return old.kind === item.kind && old.url === item.url && (old.stamp ? old.stamp === fingerprint(item.text) : old.text === item.text) && Math.abs(old.date - item.date) <= 20000; };
    if (item.origin === 'site') {
      var candidates = [];
      data.items.forEach(function (old, index) { if (old.origin === 'popup' && nearby(old)) candidates.push({old:old,index:index,deleted:false}); });
      data.deleted.forEach(function (old, index) { if (old.origin === 'popup' && nearby(old)) candidates.push({old:old,index:index,deleted:true}); });
      candidates.sort(function (a, b) { return a.old.date - b.old.date; });
      if (candidates.length) {
        var match = candidates[0]; item.read = match.old.read;
        if (match.deleted || item.date <= data.clearedBefore) {
          if (match.deleted) data.deleted.splice(match.index, 1, tombstone(item));
          else { data.items.splice(match.index, 1); data.deleted.unshift(tombstone(item)); data.deleted = data.deleted.slice(0, 200); }
        } else data.items.splice(match.index, 1, item);
        data.items.sort(function (a, b) { return b.date - a.date; }); return true;
      }
    } else if (data.items.some(function (old) { return !old.read && nearby(old); })) return false;
    if (item.date <= data.clearedBefore) return false;
    data.items.push(item); data.items.sort(function (a, b) { return b.date - a.date; }); data.items = data.items.slice(0, 100); return true;
  }
  function importSite() {
    if (!currentAccount) return false;
    var changed = false;
    try {
      var raw = localStorage.getItem('alarm_notification_' + currentAccount);
      if (!raw || raw.length > 262144) return false;
      var values = JSON.parse(raw); if (!Array.isArray(values)) return false;
      var incoming = [];
      values.slice(0, 20).forEach(function (value) {
        if (!value || typeof value !== 'object' || value.type !== 'R' && value.type !== 'RR' || typeof value.id !== 'string') return;
        var id = /^(?:(mi|pr)\$)?([A-Za-z0-9_]{1,80})$/.exec(value.id), number = String(value.no || '');
        if (!id || !/^[1-9][0-9]{0,18}$/.test(number)) return;
        var route = id[1] === 'mi' ? 'mini' : id[1] === 'pr' ? 'person' : 'board';
        var url = '/' + route + '/' + id[2] + '/' + number;
        if (value.comment_no !== undefined && value.comment_no !== null && value.comment_no !== '') {
          var comment = String(value.comment_no); if (!/^[1-9][0-9]{0,18}$/.test(comment)) return;
          url += '?comment=' + comment;
        }
        var item = record({kind:value.type, url:url, text:value.subject, date:value.reg_date, read:false}, false); if (item) incoming.push(item);
      });
      incoming.sort(function (a, b) { return a.date - b.date; }).forEach(function (item) { changed = add(item) || changed; });
    } catch (_) {}
    return changed;
  }
  function unread() { return data.items.reduce(function (number, item) { return number + (item.read ? 0 : 1); }, 0); }
  function notify() {
    if (!active()) { lastState = ''; return; }
    var value = {action:'notification-state', url:location.href, unread:currentAccount ? unread() : 0, open:!!panel};
    var message = JSON.stringify(value); if (message === lastState) return;
    try { if (window.MoaLocalActions && typeof window.MoaLocalActions.postMessage === 'function') { window.MoaLocalActions.postMessage(message); lastState = message; } } catch (_) {}
  }
  function detach(restore) {
    var wasOpen = !!panel, focus = returnFocus;
    if (panel) panel.remove(); panel = null; list = null; count = null; readButton = null; clearButton = null; rendered = ''; returnFocus = null;
    if (wasOpen && restore && active() && panelAccount === account() && panelPage === location.href && focus && focus.isConnected) {
      try { focus.focus({preventScroll:true}); } catch (_) {}
    }
    panelAccount = ''; panelPage = ''; return wasOpen;
  }
  function close() { var wasOpen = detach(true); refresh(); notify(); return wasOpen; }
  function allowed() {
    if (!active() || panelAccount !== account() || panelPage !== location.href) { detach(false); refresh(); return false; }
    return true;
  }
  function button(text, label, action, cls) {
    var node = document.createElement('button'); node.type = 'button'; node.className = cls || ''; node.textContent = text;
    if (label) node.setAttribute('aria-label', label); node.addEventListener('click', action); return node;
  }
  function removeItem(key) {
    if (!allowed()) return;
    var removed = data.items.find(function (item) { return item.key === key; });
    data.items = data.items.filter(function (item) { return item.key !== key; });
    if (removed && !data.deleted.some(function (item) { return item.key === key; })) data.deleted.unshift(tombstone(removed)); data.deleted = data.deleted.slice(0, 200);
    save(); render(); notify();
  }
  function render() {
    if (!panel || !list) return;
    var signature = JSON.stringify(data) + '|' + !!currentAccount; if (signature === rendered) return; rendered = signature;
    list.replaceChildren(); count.textContent = '읽지 않은 알림 ' + unread() + '개';
    readButton.disabled = unread() === 0; clearButton.disabled = data.items.length === 0;
    if (!currentAccount || !data.items.length) {
      var emptyRow = document.createElement('li'); emptyRow.className = 'moa-inbox-empty';
      emptyRow.textContent = currentAccount ? '댓글·답글 알림이 없습니다.' : '로그인하면 댓글·답글 알림을 볼 수 있습니다.'; list.append(emptyRow); return;
    }
    data.items.forEach(function (item) {
      var row = document.createElement('li'), link = document.createElement('a'), badge = document.createElement('span');
      var title = document.createElement('span'), date = document.createElement('time');
      row.className = 'moa-inbox-item'; row.setAttribute('data-unread', item.read ? 'false' : 'true');
      link.className = 'moa-inbox-link'; link.href = item.url;
      badge.className = 'moa-inbox-kind'; badge.textContent = item.kind === 'R' ? '댓글' : '답글';
      title.className = 'moa-inbox-title'; title.textContent = item.text;
      date.className = 'moa-inbox-date'; date.dateTime = new Date(item.date).toISOString();
      date.textContent = new Date(item.date).toLocaleString('ko-KR', {month:'numeric',day:'numeric',hour:'2-digit',minute:'2-digit'});
      link.append(badge, title, date);
      link.addEventListener('click', function (event) {
        event.preventDefault(); if (!allowed()) return;
        var url = safeUrl(item.url); if (!url) return;
        data.items.forEach(function (current) { if (current.key === item.key) current.read = true; });
        save(); detach(false); notify(); location.assign(url);
      });
      var remove = button('×', '이 알림 삭제', function () { removeItem(item.key); }, 'moa-inbox-remove');
      row.append(link, remove); list.append(row);
    });
  }
  function installStyle() {
    if (style && style.isConnected || !document.documentElement) return;
    style = document.createElement('style'); style.setAttribute(owned, 'style');
    style.textContent = [
      '#moa-notification-inbox{position:fixed!important;inset:0!important;z-index:2147483647!important;display:flex!important;align-items:center!important;justify-content:center!important;padding:12px!important;box-sizing:border-box!important;background:#0006!important;color:var(--moa-ink,#202124)!important;font:14px/1.5 system-ui,sans-serif!important;text-align:left!important;}',
      '#moa-notification-inbox *{box-sizing:border-box!important;float:none!important;text-indent:0!important;}',
      '#moa-notification-inbox .moa-inbox-card{display:flex!important;flex-direction:column!important;width:100%!important;max-width:600px!important;min-width:0!important;max-height:calc(100vh - 32px)!important;margin:0!important;padding:0!important;border:1px solid var(--moa-line,#dadce0)!important;border-radius:16px!important;background:var(--moa-paper,#fff)!important;color:inherit!important;overflow:hidden!important;box-shadow:0 12px 40px #0004!important;}',
      '#moa-notification-inbox .moa-inbox-header{display:flex!important;align-items:center!important;gap:8px!important;flex:none!important;padding:12px 12px 4px 18px!important;}',
      '#moa-notification-inbox .moa-inbox-heading{flex:1!important;min-width:0!important;margin:0!important;padding:0!important;color:var(--moa-ink,#202124)!important;font:700 20px/1.4 system-ui,sans-serif!important;}',
      '#moa-notification-inbox button{min-width:44px!important;min-height:44px!important;margin:0!important;border:0!important;border-radius:8px!important;background:transparent!important;color:var(--moa-ink,#202124)!important;font:500 13px/1.4 system-ui,sans-serif!important;cursor:pointer!important;white-space:normal!important;}',
      '#moa-notification-inbox .moa-inbox-close,#moa-notification-inbox .moa-inbox-remove{display:flex!important;align-items:center!important;justify-content:center!important;flex:0 0 44px!important;width:44px!important;padding:0!important;color:var(--moa-muted,#5f6368)!important;font:24px/1 system-ui,sans-serif!important;}',
      '#moa-notification-inbox .moa-inbox-count{flex:none!important;margin:0!important;padding:0 18px 10px!important;color:var(--moa-muted,#5f6368)!important;font:12px/1.5 system-ui,sans-serif!important;}',
      '#moa-notification-inbox .moa-inbox-tools{display:grid!important;grid-template-columns:repeat(2,minmax(0,1fr))!important;gap:8px!important;flex:none!important;padding:0 12px 12px!important;border-bottom:1px solid var(--moa-line,#dadce0)!important;}',
      '#moa-notification-inbox .moa-inbox-tools button{background:var(--moa-tint,#edf2fa)!important;color:var(--moa-action-ink,var(--moa-accent,#185abc))!important;padding:8px!important;}',
      '#moa-notification-inbox button:disabled{opacity:.45!important;cursor:default!important;}',
      '#moa-notification-inbox .moa-inbox-list{display:block!important;min-height:0!important;overflow:auto!important;overscroll-behavior:contain!important;list-style:none!important;margin:0!important;padding:0!important;}',
      '#moa-notification-inbox .moa-inbox-item{display:flex!important;align-items:stretch!important;gap:0!important;margin:0!important;padding:0 4px 0 0!important;border-bottom:1px solid var(--moa-line,#dadce0)!important;}',
      '#moa-notification-inbox .moa-inbox-link{display:block!important;flex:1!important;min-width:0!important;min-height:72px!important;margin:0!important;padding:14px 8px 14px 18px!important;text-decoration:none!important;background:transparent!important;color:var(--moa-ink,#202124)!important;}',
      '#moa-notification-inbox .moa-inbox-kind{display:block!important;margin:0 0 3px!important;color:var(--moa-muted,#5f6368)!important;font:500 11px/1.4 system-ui,sans-serif!important;}',
      '#moa-notification-inbox .moa-inbox-title{display:-webkit-box!important;-webkit-line-clamp:3!important;-webkit-box-orient:vertical!important;overflow:hidden!important;overflow-wrap:anywhere!important;white-space:normal!important;color:var(--moa-ink,#202124)!important;font:400 14px/1.5 system-ui,sans-serif!important;}',
      '#moa-notification-inbox [data-unread="true"] .moa-inbox-title{color:var(--moa-action-ink,var(--moa-accent,#185abc))!important;font-weight:700!important;}',
      '#moa-notification-inbox .moa-inbox-date{display:block!important;margin-top:6px!important;color:var(--moa-muted,#5f6368)!important;font:11px/1.4 system-ui,sans-serif!important;}',
      '#moa-notification-inbox .moa-inbox-empty{display:block!important;margin:0!important;padding:36px 18px!important;color:var(--moa-muted,#5f6368)!important;font:14px/1.6 system-ui,sans-serif!important;text-align:center!important;overflow-wrap:anywhere!important;}',
      '#moa-notification-inbox a:focus-visible,#moa-notification-inbox button:focus-visible{outline:2px solid var(--moa-accent,#185abc)!important;outline-offset:-3px!important;}',
      '@supports(height:100dvh){#moa-notification-inbox .moa-inbox-card{max-height:calc(100dvh - 32px)!important;}}'
    ].join('');
    (document.head || document.documentElement).append(style);
  }
  function open() {
    refresh(); if (!active() || !document.body) return false;
    if (panel) return true;
    installStyle(); returnFocus = document.activeElement; panelAccount = account(); panelPage = location.href;
    panel = document.createElement('div'); panel.id = 'moa-notification-inbox'; panel.setAttribute(owned, 'dialog');
    var card = document.createElement('section'), header = document.createElement('div'), heading = document.createElement('h2'), tools = document.createElement('div');
    card.className = 'moa-inbox-card'; card.setAttribute('role', 'dialog'); card.setAttribute('aria-modal', 'true'); card.setAttribute('aria-labelledby', 'moa-inbox-heading');
    header.className = 'moa-inbox-header'; heading.id = 'moa-inbox-heading'; heading.className = 'moa-inbox-heading'; heading.textContent = '알림';
    var dismiss = button('×', '알림함 닫기', close, 'moa-inbox-close'); header.append(heading, dismiss);
    count = document.createElement('p'); count.className = 'moa-inbox-count'; count.setAttribute('aria-live', 'polite');
    tools.className = 'moa-inbox-tools';
    readButton = button('모두 읽음', '', function () { if (!allowed()) return; data.items.forEach(function (item) { item.read = true; }); save(); render(); notify(); }, 'moa-inbox-read-all');
    clearButton = button('전체 삭제', '', function () { if (!allowed()) return; data.clearedBefore = Date.now(); data.items.forEach(function (item) { if (!data.deleted.some(function (old) { return old.key === item.key; })) data.deleted.unshift(tombstone(item)); }); data.deleted = data.deleted.slice(0, 200); data.items = []; save(); render(); notify(); }, 'moa-inbox-clear');
    tools.append(readButton, clearButton); list = document.createElement('ul'); list.className = 'moa-inbox-list';
    card.append(header, count, tools, list); panel.append(card);
    panel.addEventListener('click', function (event) { if (event.target === panel) close(); });
    document.body.append(panel); render(); dismiss.focus({preventScroll:true}); notify(); return true;
  }
  function refresh(forceNative) {
    scheduled = false;
    if (forceNative === true) lastState = '';
    var owner = account(), page = location.href;
    if (!active()) { detach(false); if (interval !== null) clearInterval(interval); interval = null; lastState = ''; return false; }
    if (owner !== currentAccount || page !== currentPage) { detach(false); currentAccount = owner; currentPage = page; rendered = ''; lastState = ''; }
    data = load(owner);
    if (importSite()) save();
    if (interval === null) interval = setInterval(refresh, 1500);
    render(); notify(); return true;
  }
  function receive(value) {
    if (!active() || window.__moaInAppAlertsEnabled !== true) return false;
    refresh(); if (!currentAccount) return false;
    var item = record(value, true); if (!add(item)) return false;
    save(); render(); notify(); return true;
  }
  function schedule() {
    if (gone || scheduled) return; scheduled = true;
    Promise.resolve().then(function () { if (!gone) refresh(); else scheduled = false; });
  }
  function observe() {
    if (!observer) observer = new MutationObserver(function (records) {
      if (records.some(function (change) { var node = change.target.nodeType === 1 ? change.target : change.target.parentElement; return !node || !node.closest('[' + owned + ']'); })) schedule();
    });
    observer.observe(document, {subtree:true,childList:true,attributes:true,attributeFilter:['id','value','data-moa-theme','data-moa-dark']});
  }
  document.addEventListener('keydown', function (event) {
    if (!panel) return;
    if (!allowed()) return;
    if (event.key === 'Escape') { event.preventDefault(); close(); }
    else if (event.key === 'Tab') {
      var nodes = Array.prototype.filter.call(panel.querySelectorAll('button,a[href]'), function (node) { return !node.disabled && node.getClientRects().length > 0 && getComputedStyle(node).visibility !== 'hidden'; });
      var first = nodes[0], last = nodes[nodes.length - 1];
      if (!nodes.length) { event.preventDefault(); return; }
      if (!panel.contains(document.activeElement)) { event.preventDefault(); (event.shiftKey ? last : first).focus(); }
      else if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    }
  }, true);
  document.addEventListener('visibilitychange', refresh);
  window.addEventListener('moa-in-app-alerts-state', refresh);
  window.addEventListener('popstate', refresh); window.addEventListener('hashchange', refresh);
  window.addEventListener('storage', function (event) { if (!event.key || event.key === prefix + account() || event.key === 'alarm_notification_' + account()) refresh(); });
  window.addEventListener('pagehide', function () { gone = true; detach(false); if (interval !== null) clearInterval(interval); interval = null; lastState = ''; observer.disconnect(); });
  window.addEventListener('pageshow', function () { gone = false; observe(); refresh(); });
  window.MoaNotificationInbox = Object.freeze({version:'1.0.0', open:open, close:close, refresh:refresh, receive:receive});
  observe(); refresh();
})();
