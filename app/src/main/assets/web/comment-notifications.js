/* Present DC's own foreground comment/reply popup in the selected theme.
 * The service's own notification function and single 90-second interval are
 * reused only while this pane is active. No custom HTTP, confirmation, comment
 * capture, account credentials or system notifications are introduced. The
 * optional local inbox receives only eligible foreground popup records.
 */
(function () {
  'use strict';
  if (window.top !== window || location.protocol !== 'https:' || location.hostname !== 'm.dcinside.com' ||
      location.port && location.port !== '443' || location.username || location.password) return;
  if (window.MoaForegroundNotifications) { window.MoaForegroundNotifications.refresh(); return; }

  var sourceSelector = '.alarm-popup.alarm-default';
  var sourceMark = 'data-moa-foreground-source';
  var owned = 'data-moa-foreground-owned';
  var excluded = '.thum-txt,.thum-txtin,.all-comment-lst,.moa-entry,form,[contenteditable],#textbox';
  var sources = new Set(), seen = new Set(), observer = null, style = null, panel = null, timer = null;
  var sitePoll = null, pollWrapper = null, pollStarted = false, pollRunning = false, lastPoll = 0;
  var scheduled = false, gone = false, lastActive = active(), lastAccount = null, lastPage = location.href;
  var names = {mail:'메일함',post:'X',photogram:'인스타그램',sheet:'엑셀',cafe:'네이버 카페',ebook:'E-book',magazine:'매거진'};
  var marks = {mail:'✉',post:'↗',photogram:'♡',sheet:'▦',cafe:'☕',ebook:'▱',magazine:'¶'};

  function supported() {
    return /^\/(?:board|mini|person)\/[A-Za-z0-9_]{1,80}(?:\/[1-9][0-9]{0,18})?\/?$/.test(location.pathname) ||
      /^\/(?:write|modify)\/[A-Za-z0-9_]{1,80}(?:\/(?:modify\/)?[1-9][0-9]{0,18})?\/?$/.test(location.pathname) ||
      /^\/search(?:\/(?:search_main|gall_name|gall_content|movie))?\/?$/.test(location.pathname);
  }
  function active() {
    return window.__moaInAppAlertsEnabled === true && window.__moaForegroundActive === true &&
      !gone && !document.hidden && document.visibilityState !== 'hidden';
  }
  function account() {
    var field = document.getElementById('user_id');
    return String(field && field.value || '').slice(0, 120);
  }
  function siteAlertsAllowed() {
    try {
      var settings = JSON.parse(localStorage.getItem('alarm_setting'));
      // Match alarm_notification(): the service stores its master opt-out as
      // an onoff entry. The original R/RR branch still applies its own filters.
      return !Array.isArray(settings) || !settings.some(function (item) { return item && item.setting === 'onoff'; });
    } catch (_) { return false; }
  }
  function stopSitePoll() {
    if (!sitePoll) return;
    if (window.interval_Noti !== null && window.interval_Noti !== undefined) clearInterval(window.interval_Noti);
    window.interval_Noti = null; window.set_interval_noti = 0; pollRunning = false;
  }
  function armSitePoll() {
    if (window.interval_Noti === null || window.interval_Noti === undefined) {
      window.interval_Noti = setInterval(pollWrapper, 90000);
    }
    pollRunning = true;
  }
  function syncSitePoll() {
    var candidate = window.alarm_notification_pop;
    if (typeof candidate === 'function' && candidate !== pollWrapper &&
        typeof window.set_interval_noti === 'number' && 'interval_Noti' in window) {
      var alreadyStarted = window.set_interval_noti > 0 || window.interval_Noti !== null && window.interval_Noti !== undefined;
      sitePoll = candidate;
      // A late injection can find an original request in flight. Wait a full
      // source interval before another call instead of duplicating that request.
      if (alreadyStarted) { pollStarted = true; lastPoll = Date.now(); }
      stopSitePoll();
      pollWrapper = function () {
        pollStarted = true;
        if (!active() || !supported() || !siteAlertsAllowed()) { stopSitePoll(); return false; }
        armSitePoll();
        if (Date.now() - lastPoll < 90000) return false;
        lastPoll = Date.now();
        // The original creates its interval when this increments to 1 and
        // stops after 3. We own that same single slot and retain its cadence.
        window.set_interval_noti = 1;
        return sitePoll.apply(this, arguments);
      };
      window.alarm_notification_pop = pollWrapper;
    }
    if (!sitePoll) return;
    if (!active() || !supported() || !siteAlertsAllowed()) { stopSitePoll(); return; }
    if (pollStarted && !pollRunning) { armSitePoll(); pollWrapper(); }
  }
  function plain(value) {
    return String(value || '').replace(/[\u0000-\u001f\u007f]/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 240);
  }
  function safeUrl(value) {
    try {
      var url = new URL(value, location.href);
      if (url.protocol !== 'https:' || url.hostname !== 'm.dcinside.com' || url.username || url.password ||
          url.port && url.port !== '443' || url.hash ||
          !/^\/(?:board|mini|person)\/[A-Za-z0-9_]{1,80}\/[1-9][0-9]{0,18}\/?$/.test(url.pathname)) return '';
      var valid = true, comment = url.searchParams.getAll('comment');
      url.searchParams.forEach(function (_, key) { if (key !== 'comment') valid = false; });
      if (!valid || comment.length > 1 || comment.length && !/^[1-9][0-9]{0,18}$/.test(comment[0])) return '';
      return url.origin + url.pathname.replace(/\/$/, '') + (comment.length ? '?comment=' + comment[0] : '');
    } catch (_) { return ''; }
  }
  function notification(node) {
    if (!node.isConnected || node.closest(excluded) || node.matches('.silbe-alarm')) return null;
    var link = node.querySelector('.one-ntc-box .ntc-lnk');
    var badge = link && link.querySelector('.ntc');
    var label = plain(badge && badge.textContent);
    // The official R and RR branches insert these exact badges. Promotions,
    // guestbook, repost and video notifications use other badges or popups.
    if (label !== '댓글' && label !== '답글') return null;
    var url = safeUrl(link.getAttribute('href') || '');
    if (!url) return null;
    var walker = document.createTreeWalker(link, NodeFilter.SHOW_TEXT), part, text = '';
    while ((part = walker.nextNode())) {
      if (!part.parentElement || part.parentElement.closest('.ntc,script,style,.blind')) continue;
      text += part.nodeValue || '';
    }
    text = plain(text); if (!text) return null;
    return {kind:label, url:url, text:text, key:label + '|' + url + '|' + text};
  }
  function displayed(node) {
    // Ignore visibility: our own marker hides the source without changing the
    // display/opacity properties used by the site's fadeIn/fadeOut callbacks.
    for (var item = node; item && item.nodeType === 1; item = item.parentElement) {
      if (item.hidden || getComputedStyle(item).display === 'none') return false;
    }
    return true;
  }
  function remember(item, saveHistory) {
    var key = item.key;
    seen.add(key);
    if (seen.size > 100) seen.delete(seen.values().next().value);
    if (saveHistory && window.MoaNotificationInbox && typeof window.MoaNotificationInbox.receive === 'function')
      window.MoaNotificationInbox.receive(item);
  }
  function dismiss() {
    if (timer !== null) clearTimeout(timer); timer = null;
    if (panel) panel.remove(); panel = null;
  }
  function theme() {
    var name = document.documentElement.getAttribute('data-moa-theme');
    return Object.prototype.hasOwnProperty.call(names, name) ? name : '';
  }
  function updateTheme() {
    if (!panel) return;
    var current = theme();
    if (panel.getAttribute('data-theme') !== current) panel.setAttribute('data-theme', current);
    var context = panel.querySelector('.moa-foreground-context'), icon = panel.querySelector('.moa-foreground-icon');
    if (context.textContent !== (names[current] || '알림')) context.textContent = names[current] || '알림';
    if (icon.textContent !== (marks[current] || '●')) icon.textContent = marks[current] || '●';
  }
  function show(item) {
    dismiss();
    if (!active() || !document.body) return;
    var box = document.createElement('aside'), link = document.createElement('a'), icon = document.createElement('span');
    var words = document.createElement('span'), context = document.createElement('span'), heading = document.createElement('span');
    var text = document.createElement('span'), close = document.createElement('button');
    box.id = 'moa-foreground-notification'; box.setAttribute(owned, 'popup'); box.setAttribute('aria-label', '새 ' + item.kind + ' 알림');
    link.className = 'moa-foreground-link'; link.href = item.url;
    icon.className = 'moa-foreground-icon'; icon.setAttribute('aria-hidden', 'true');
    words.className = 'moa-foreground-words'; context.className = 'moa-foreground-context';
    heading.className = 'moa-foreground-heading'; heading.textContent = '새 ' + item.kind;
    text.className = 'moa-foreground-text'; text.textContent = item.text;
    words.append(context, heading, text); link.append(icon, words);
    close.type = 'button'; close.className = 'moa-foreground-close'; close.textContent = '×'; close.setAttribute('aria-label', '알림 닫기');
    close.addEventListener('click', function (event) { event.preventDefault(); event.stopPropagation(); dismiss(); });
    link.addEventListener('click', function (event) {
      // A pause or account/history change can race the last rendered frame.
      if (!active() || !supported() || location.href !== lastPage || account() !== lastAccount) event.preventDefault();
      dismiss();
    });
    box.append(link, close); panel = box; updateTheme();
    // Announce only the short heading and subject, without moving input focus.
    words.setAttribute('role', 'status'); words.setAttribute('aria-live', 'polite'); words.setAttribute('aria-atomic', 'true');
    document.body.append(box);
    timer = setTimeout(dismiss, 7000);
  }
  function installStyle() {
    if (style && style.isConnected || !document.documentElement) return;
    style = document.createElement('style'); style.setAttribute(owned, 'style');
    style.textContent = [
      'html .alarm-popup.alarm-default[' + sourceMark + '="1"]{visibility:hidden!important;pointer-events:none!important;}',
      '#moa-foreground-notification{position:fixed!important;inset:auto!important;top:max(12px,env(safe-area-inset-top))!important;left:12px!important;right:12px!important;z-index:2147483647!important;display:flex!important;align-items:stretch!important;box-sizing:border-box!important;width:auto!important;max-width:440px!important;margin:0 auto!important;padding:0!important;overflow:hidden!important;border:1px solid var(--moa-line,#d6dbe0)!important;border-radius:14px!important;background:var(--moa-paper,#fff)!important;color:var(--moa-ink,#17202a)!important;box-shadow:0 5px 24px #0003!important;font:14px/1.4 system-ui,sans-serif!important;text-indent:0!important;text-align:left!important;}',
      '#moa-foreground-notification *{box-sizing:border-box!important;float:none!important;text-indent:0!important;}',
      '#moa-foreground-notification .moa-foreground-link{display:flex!important;align-items:center!important;gap:12px!important;min-width:0!important;flex:1!important;padding:14px!important;text-decoration:none!important;color:inherit!important;background:transparent!important;}',
      '#moa-foreground-notification .moa-foreground-icon{display:flex!important;align-items:center!important;justify-content:center!important;flex:0 0 40px!important;width:40px!important;height:40px!important;border-radius:12px!important;background:var(--moa-tint,#edf1f5)!important;color:var(--moa-accent,#0b57d0)!important;font:24px/1 system-ui,sans-serif!important;}',
      '#moa-foreground-notification .moa-foreground-words{display:block!important;min-width:0!important;flex:1!important;}',
      '#moa-foreground-notification .moa-foreground-context{display:block!important;margin:0 0 2px!important;color:var(--moa-muted,#536471)!important;font:11px/1.4 system-ui,sans-serif!important;}',
      '#moa-foreground-notification .moa-foreground-heading{display:block!important;color:var(--moa-accent,#0b57d0)!important;font:700 14px/1.4 system-ui,sans-serif!important;}',
      '#moa-foreground-notification .moa-foreground-text{display:-webkit-box!important;-webkit-line-clamp:2!important;-webkit-box-orient:vertical!important;overflow:hidden!important;overflow-wrap:anywhere!important;white-space:normal!important;margin-top:3px!important;color:var(--moa-ink,#17202a)!important;font:14px/1.45 system-ui,sans-serif!important;}',
      '#moa-foreground-notification .moa-foreground-close{display:flex!important;align-items:center!important;justify-content:center!important;flex:0 0 44px!important;min-width:44px!important;min-height:44px!important;padding:0!important;margin:0!important;border:0!important;background:transparent!important;color:var(--moa-muted,#536471)!important;font:24px/1 system-ui,sans-serif!important;cursor:pointer!important;}',
      '#moa-foreground-notification a:focus-visible,#moa-foreground-notification button:focus-visible{outline:2px solid var(--moa-accent,#0b57d0)!important;outline-offset:-3px!important;}',
      '#moa-foreground-notification[data-theme="mail"]{border-radius:16px!important;}#moa-foreground-notification[data-theme="post"]{border-radius:18px!important;border-left:4px solid var(--moa-accent,#0b57d0)!important;}',
      '#moa-foreground-notification[data-theme="photogram"] .moa-foreground-icon{border-radius:50%!important;font-size:30px!important;}#moa-foreground-notification[data-theme="sheet"]{border-radius:0!important;border-top:4px solid var(--moa-accent,#0b57d0)!important;}#moa-foreground-notification[data-theme="sheet"] .moa-foreground-icon{border-radius:0!important;}',
      '#moa-foreground-notification[data-theme="cafe"]{border-radius:8px!important;}#moa-foreground-notification[data-theme="ebook"]{border-radius:3px!important;border-left:5px solid var(--moa-accent,#845536)!important;}#moa-foreground-notification[data-theme="ebook"] .moa-foreground-text{font-family:Georgia,serif!important;}',
      '#moa-foreground-notification[data-theme="magazine"]{border-radius:0!important;border-top:3px double var(--moa-accent,#af3d2e)!important;}#moa-foreground-notification[data-theme="magazine"] .moa-foreground-heading{font-family:Georgia,serif!important;}',
      '@media(max-width:320px){#moa-foreground-notification{left:8px!important;right:8px!important;}#moa-foreground-notification .moa-foreground-link{gap:8px!important;padding:10px!important;}#moa-foreground-notification .moa-foreground-icon{flex-basis:32px!important;width:32px!important;height:36px!important;}}'
    ].join('');
    (document.head || document.documentElement).append(style);
  }
  function scan(baseline) {
    scheduled = false;
    if (!document.documentElement) return;
    installStyle();
    var currentAccount = account(), currentPage = location.href, nowActive = active() && siteAlertsAllowed();
    if (lastAccount !== null && currentAccount !== lastAccount || currentPage !== lastPage) {
      dismiss(); seen.clear(); baseline = true;
    }
    if (nowActive && !lastActive) baseline = true;
    lastAccount = currentAccount; lastPage = currentPage; lastActive = nowActive;
    syncSitePoll();
    if (!nowActive || !supported()) dismiss();
    var wanted = new Set();
    document.querySelectorAll(sourceSelector).forEach(function (node) {
      var item = supported() && notification(node); if (!item) return;
      wanted.add(node); sources.add(node);
      if (node.getAttribute(sourceMark) !== '1') node.setAttribute(sourceMark, '1');
      if (!displayed(node) || seen.has(item.key)) return;
      remember(item, !baseline && nowActive);
      if (!baseline && nowActive) show(item);
    });
    sources.forEach(function (node) {
      if (!wanted.has(node)) { node.removeAttribute(sourceMark); sources.delete(node); }
    });
    updateTheme();
  }
  function schedule() {
    if (gone || scheduled) return;
    scheduled = true; Promise.resolve().then(function () { if (!gone) scan(false); else scheduled = false; });
  }
  function observe() {
    if (!observer) observer = new MutationObserver(function (records) {
      if (records.some(function (record) {
        var node = record.target.nodeType === 1 ? record.target : record.target.parentElement;
        return !node || !node.closest('[' + owned + ']');
      })) schedule();
    });
    observer.observe(document, {subtree:true,childList:true,characterData:true,attributes:true,
      attributeFilter:['class','style','href','hidden','value','data-moa-theme','data-moa-dark']});
  }
  function refresh() { scan(false); }
  document.addEventListener('visibilitychange', refresh);
  window.addEventListener('moa-in-app-alerts-state', refresh);
  window.addEventListener('popstate', function () { scan(true); });
  window.addEventListener('hashchange', function () { scan(true); });
  window.addEventListener('pagehide', function () { gone = true; dismiss(); stopSitePoll(); observer.disconnect(); });
  window.addEventListener('pageshow', function () { gone = false; observe(); scan(true); });
  window.MoaForegroundNotifications = Object.freeze({version:'1.1.0',refresh:refresh});
  observe(); scan(false);
})();
