/* Device-local viewing preferences. No requests, submission or account access. */
(function () {
  'use strict';
  if (window.top !== window) return;
  if (window.MoaCommunityTools && window.MoaCommunityTools.version === 1) return;

  var config = {}, active = false, enabled = false, observer = null, timer = null, style = null;
  var blocked = new Set(), hiddenImages = new Set(), notes = new Map(), tools = new Map();
  var gifs = new Map();
  var insertions = new WeakMap();
  var owned = 'data-moa-community-owned';
  var blockedMark = 'data-moa-community-blocked';
  var imageMark = 'data-moa-community-image-hidden';
  var gifMark = 'data-moa-community-gif-hidden';
  var commentRowSelector = '.all-comment-lst > li.comment, .all-comment-lst > li.comment-add';
  var rowSelector = '.gall-detail-lst > li, ' + commentRowSelector;
  var editorSelector = 'form,[contenteditable],#textbox';
  var identityKinds = ['id', 'ip', 'nickname'];

  function supported() {
    return location.protocol === 'https:' && location.hostname === 'm.dcinside.com' &&
      (!location.port || location.port === '443') && !location.username && !location.password &&
      /^\/(?:board|mini|person|write|modify)\/[A-Za-z0-9_]+(?:\/(?:modify\/)?\d+)?\/?$/.test(location.pathname);
  }
  function clean(value, max, multiline) {
    if (typeof value !== 'string' || value.length > max ||
        (multiline ? /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/ : /[\u0000-\u001f\u007f]/).test(value)) return '';
    return multiline ? value : value.trim();
  }
  function normalise(input) {
    input = input || {};
    var valid = function (items, note) {
      if (!Array.isArray(items)) return [];
      return items.slice(0, 200).filter(function (item) {
        return item && (note ? identityKinds : ['word'].concat(identityKinds)).indexOf(item.kind) >= 0 &&
          clean(item.value, 120, false) && (!note || clean(item.text, 200, false));
      }).map(function (item) { return {kind: item.kind, value: clean(item.value, 120, false), text: note ? clean(item.text, 200, false) : ''}; });
    };
    return {rules: valid(input.rules, false), notes: valid(input.notes, true),
      postPrefix: clean(input.postPrefix, 2000, true), postSuffix: clean(input.postSuffix, 2000, true),
      commentPrefix: clean(input.commentPrefix, 2000, true), commentSuffix: clean(input.commentSuffix, 2000, true),
      imagesHidden: input.imagesHidden === true, gifTapToPlay: input.gifTapToPlay === true};
  }
  function plain(node) {
    if (!node) return '';
    // Memo labels and icon accessibility text are not the person's nickname.
    // Walk text in place: cloning an IMG-containing comment can cause another
    // image load in some WebViews, even when the clone is never attached.
    var walker = document.createTreeWalker(node, NodeFilter.SHOW_TEXT), part, value = '';
    while ((part = walker.nextNode())) {
      if (!part.parentElement || part.parentElement.closest('[' + owned + '],.sp-nick,.blind,.blockInfo,.blockCommentId,.blockCommentIp,script,style')) continue;
      value += part.nodeValue || '';
    }
    return value.trim();
  }
  function attr(node, name) { return node ? clean(node.getAttribute(name), 120, false) : ''; }
  function ip(value) { return /^(?:\d{1,3}\.){1,3}\d{1,3}$/.test(value) ? value : ''; }
  function identity(scope, nick) {
    var metadata = scope.matches(commentRowSelector) ? scope.querySelector('.ginfo-area') : scope;
    var block = metadata && metadata.querySelector('.blockInfo[data-info]');
    var uid = metadata && metadata.querySelector('.blockCommentId[data-info],[data-uid]');
    var address = metadata && metadata.querySelector('.blockCommentIp[data-info],[data-ip]');
    var info = attr(block, 'data-info');
    return {
      nickname: attr(block, 'data-name') || attr(nick, 'data-nick') || clean(plain(nick), 120, false),
      id: attr(scope, 'data-uid') || attr(uid, 'data-uid') || attr(uid, 'data-info') || (info && !ip(info) ? info : ''),
      ip: attr(scope, 'data-ip') || attr(address, 'data-ip') || attr(address, 'data-info') || ip(info)
    };
  }
  function entry(row) {
    if (!row.isConnected || row.closest(editorSelector) || row.querySelector('form,[contenteditable],textarea,' + commentRowSelector)) return null;
    if (row.matches(commentRowSelector)) {
      var nick = row.querySelector('.ginfo-area .nick');
      var body = row.querySelector('p.txt,.comment-txt,.reply-txt');
      if (!nick || !body) return null;
      return {identity: identity(row, nick), text: plain(body), targets: [nick]};
    }
    var source = Array.from(row.children).find(function (child) { return child.matches('.gall-detail-lnktb'); });
    if (!source || row.matches('.adv-inner') || source.querySelector('.ntc-line-ad')) return null;
    var title = source.querySelector('a.lt .subjectin'), author = source.querySelector('.list-nick');
    if (!title || !author) return null;
    return {identity: identity(row, author), text: plain(title),
      targets: [author].concat(Array.from(row.querySelectorAll('.moa-entry .moa-entry-name')))};
  }
  function match(data, rule) {
    return rule.kind === 'word' ? data.text.indexOf(rule.value) >= 0 :
      Boolean(data.identity[rule.kind]) && data.identity[rule.kind] === rule.value;
  }
  function mark(node, name, set) {
    if (node.getAttribute(name) !== '1') node.setAttribute(name, '1');
    set.add(node);
  }
  function restoreUnused(set, wanted, name) {
    set.forEach(function (node) {
      if (!wanted.has(node)) { if (node.getAttribute(name) === '1') node.removeAttribute(name); set.delete(node); }
    });
  }
  function noteFor(data) {
    // A stable ID takes priority over a nickname that several people may share.
    for (var k = 0; k < identityKinds.length; k++) {
      var kind = identityKinds[k];
      var found = config.notes.find(function (note) { return note.kind === kind && data.identity[kind] && note.value === data.identity[kind]; });
      if (found) return found.text;
    }
    return '';
  }
  function putNote(target, value, wanted) {
    if (!target || !target.isConnected || !value) return;
    wanted.add(target);
    var label = notes.get(target);
    if (!label || !label.isConnected) {
      label = document.createElement('span'); label.className = 'moa-community-note'; label.setAttribute(owned, 'note');
      label.setAttribute('aria-label', '나만 보는 이용자 메모');
      // The list renderer reads direct .ginfo children as date/view metadata.
      // Keep the original nickname and metadata container entirely unchanged;
      // generated theme names can carry their label directly beside the name.
      var anchor = target.matches('.list-nick') && target.parentElement && target.parentElement.matches('.ginfo') ? target.parentElement : target;
      anchor.insertAdjacentElement('afterend', label); notes.set(target, label);
    }
    if (label.textContent !== value) label.textContent = value;
  }
  function scanRows() {
    var wanted = new Set(), wantedNotes = new Set();
    document.querySelectorAll(rowSelector).forEach(function (row) {
      var data = entry(row); if (!data) return;
      if (config.rules.some(function (rule) { return match(data, rule); })) { mark(row, blockedMark, blocked); wanted.add(row); }
      var memo = noteFor(data); data.targets.forEach(function (target) { putNote(target, memo, wantedNotes); });
    });
    // The article itself remains readable; an identity memo is still useful.
    document.querySelectorAll('.gallview-tit-box').forEach(function (header) {
      if (header.closest(editorSelector)) return;
      var nick = header.querySelector('.ginfo2 .nick,.btm .nick');
      if (nick) putNote(nick, noteFor({identity: identity(header, nick)}), wantedNotes);
    });
    restoreUnused(blocked, wanted, blockedMark);
    notes.forEach(function (label, target) { if (!wantedNotes.has(target)) { label.remove(); notes.delete(target); } });
  }
  function contentImages() {
    return Array.from(document.querySelectorAll('.thum-txtin img,.moa-entry img.moa-entry-image,.gall-detail-lnktb .thumb img,.gall-detail-lnktb .thum img,.gall-detail-lnktb .thum-img img,.gall-detail-lnktb .thumbnail img,.all-comment-lst > li.comment p.txt img,.all-comment-lst > li.comment-add p.txt img'))
      .filter(function (image) { return !image.closest(editorSelector); });
  }
  function isGif(image) {
    return [image.currentSrc, image.getAttribute('src'), image.getAttribute('data-src')].some(function (source) {
      if (!source) return false;
      if (/^data:image\/gif(?:;[^,]*)?,/i.test(source)) return true;
      try { var url = new URL(source, location.href); return /^https?:$/.test(url.protocol) && /\.gif$/i.test(url.pathname); }
      catch (_) { return false; }
    });
  }
  function gifKey(image) { return JSON.stringify([image.getAttribute('src'), image.getAttribute('srcset'), image.getAttribute('data-src')]); }
  function removeGif(image, state) {
    if (image.getAttribute(gifMark) === '1') image.removeAttribute(gifMark);
    if (state && state.wrapper && state.wrapper.getAttribute(gifMark) === '1') state.wrapper.removeAttribute(gifMark);
    if (state && state.button) state.button.remove();
    gifs.delete(image);
  }
  function gifPlaceholder(image, state) {
    var button = document.createElement('button'); button.type = 'button'; button.className = 'moa-community-gif';
    button.setAttribute(owned, 'gif'); button.textContent = 'GIF 눌러서 보기'; button.setAttribute('aria-label', '움짤 표시');
    button.addEventListener('click', function (event) {
      // Programmatic site clicks must not silently turn the user's preference off.
      if (!event.isTrusted || !enabled || !config.gifTapToPlay || config.imagesHidden || !supported() || !image.isConnected || gifs.get(image) !== state || gifKey(image) !== state.key) return;
      event.preventDefault(); event.stopPropagation();
      state.revealed = true; if (image.getAttribute(gifMark) === '1') image.removeAttribute(gifMark);
      if (state.wrapper && state.wrapper.getAttribute(gifMark) === '1') state.wrapper.removeAttribute(gifMark);
      button.remove(); state.button = null;
    });
    var target = image, container = image.closest('.thum-txtin,.moa-entry,.gall-detail-lnktb,p.txt');
    var anchor = image.closest('a'), picture = image.closest('picture');
    if (anchor && container && container.contains(anchor)) target = anchor;
    else if (picture && container && container.contains(picture)) target = picture;
    if (target.matches('.moa-entry-media') && target.querySelectorAll('img').length === 1) {
      state.wrapper = target; if (target.getAttribute(gifMark) !== '1') target.setAttribute(gifMark, '1');
    }
    target.insertAdjacentElement('beforebegin', button); state.button = button;
  }
  function scanImages() {
    var wanted = new Set();
    var images = contentImages(), wantedGifs = new Set();
    if (config.imagesHidden) images.forEach(function (image) {
      mark(image, imageMark, hiddenImages); wanted.add(image);
      var media = image.closest('.moa-entry-media');
      if (media && media.querySelectorAll('img').length === 1) { mark(media, imageMark, hiddenImages); wanted.add(media); }
    });
    restoreUnused(hiddenImages, wanted, imageMark);
    if (config.gifTapToPlay && !config.imagesHidden) images.forEach(function (image) {
      if (!isGif(image)) return;
      wantedGifs.add(image); var state = gifs.get(image), key = gifKey(image);
      if (!state || state.key !== key) { if (state) removeGif(image, state); state = {key:key, revealed:false, button:null}; gifs.set(image, state); }
      if (state.revealed) return;
      if (image.getAttribute(gifMark) !== '1') image.setAttribute(gifMark, '1');
      if (!state.button || !state.button.isConnected) gifPlaceholder(image, state);
    });
    gifs.forEach(function (state, image) { if (!wantedGifs.has(image)) removeGif(image, state); });
  }

  function identityForTarget(target) {
    if (!enabled || !supported() || !target || target.nodeType !== 1 || target.closest(editorSelector)) return null;
    var nick = target.closest('.nick,.list-nick,.moa-entry-name');
    if (!nick) notes.forEach(function (label, candidate) { if (label === target || label.contains(target)) nick = candidate; });
    if (!nick) return null;
    var row = nick.closest(rowSelector), data;
    if (row) {
      data = entry(row); if (!data || data.targets.indexOf(nick) < 0) return null;
    } else {
      var header = nick.closest('.gallview-tit-box');
      if (!header || header.querySelector('.ginfo2 .nick,.btm .nick') !== nick) return null;
      data = {identity: identity(header, nick)};
    }
    return data.identity.nickname || data.identity.id || data.identity.ip ? data.identity : null;
  }
  function identityAt(x, y) {
    if (!Number.isFinite(x) || !Number.isFinite(y) || x < 0 || y < 0 || x > window.innerWidth || y > window.innerHeight) return null;
    return identityForTarget(document.elementFromPoint(x, y));
  }
  document.addEventListener('contextmenu', function (event) {
    if (!event.isTrusted || !window.MoaLocalActions || typeof window.MoaLocalActions.postMessage !== 'function') return;
    var person = identityForTarget(event.target); if (!person) return;
    try {
      window.MoaLocalActions.postMessage(JSON.stringify({action:'user', nickname:person.nickname, id:person.id, ip:person.ip}));
      event.preventDefault(); event.stopPropagation();
    } catch (_) { /* Retain the site's normal menu if the native connection is unavailable. */ }
  }, true);
  function replyContext(form) {
    if (form.closest(commentRowSelector)) return true;
    var names = ['reply_no', 'parent_no', 're_no', 'c_no', 'r_no', 'comment_no', 'reple_id'];
    return names.some(function (name) {
      var field = form.querySelector('input[name="' + name + '"]') || (form.id === 'comment_write' ? document.getElementById(name) : null);
      return field && String(field.value || '').trim() && String(field.value).trim() !== '0';
    });
  }
  function eligible(editor, kind) {
    var form = editor && editor.closest('form');
    if (!editor || !editor.isConnected || !form || editor.disabled || editor.readOnly || replyContext(form)) return false;
    if (/(?:^\/modify\/|\/modify\/)/.test(location.pathname) || /(?:^|[?&])(?:mode|action)=modify(?:&|$)/.test(location.search)) return false;
    return kind === 'post' ? /^\/write\/[A-Za-z0-9_]+\/?$/.test(location.pathname) && form.id === 'writeForm' && editor.isContentEditable :
      /^\/(?:board|mini|person)\/[A-Za-z0-9_]+\/\d+\/?$/.test(location.pathname) && form.id === 'comment_write' && editor.tagName === 'TEXTAREA';
  }
  function draftText(editor) { return editor.isContentEditable ? editor.innerText : String(editor.value || ''); }
  function insertionPresent(editor, state) {
    if (!state) return false;
    if (editor.isContentEditable) return state.nodes.some(function (node) { return editor.contains(node); });
    var value = draftText(editor);
    return (state.prefix && value.indexOf(state.prefix) === 0) || (state.suffix && value.endsWith(state.suffix));
  }
  function insert(editor, kind, status) {
    if (!active || !eligible(editor, kind)) { status.textContent = '현재 작성 화면에는 넣을 수 없어요.'; return; }
    if (insertionPresent(editor, insertions.get(editor))) { status.textContent = '이미 넣은 문구가 있어요.'; return; }
    var prefix = config[kind + 'Prefix'], suffix = config[kind + 'Suffix'], current = draftText(editor);
    if (prefix && current.startsWith(prefix)) prefix = '';
    if (suffix && current.endsWith(suffix)) suffix = '';
    if (!prefix && !suffix) { status.textContent = '이미 문구가 들어 있어요.'; return; }
    var before = prefix ? prefix + '\n' : '', after = suffix ? '\n' + suffix : '';
    if (current.length + before.length + after.length > 100000 || (!editor.isContentEditable && editor.maxLength >= 0 && current.length + before.length + after.length > editor.maxLength)) {
      status.textContent = '글자 수 제한을 넘어서 넣지 않았어요.'; return;
    }
    var change = new Event('beforeinput', {bubbles: true, cancelable: true});
    if (!editor.dispatchEvent(change)) return;
    var state = {prefix: prefix, suffix: suffix, nodes: []};
    if (editor.isContentEditable) {
      var selection = window.getSelection(), saved = selection && selection.rangeCount && editor.contains(selection.anchorNode) && editor.contains(selection.focusNode) ?
        [selection.anchorNode, selection.anchorOffset, selection.focusNode, selection.focusOffset] : null;
      var appendText = function (text, first) {
        if (!text) return;
        var paragraph = document.createElement('div'); paragraph.style.whiteSpace = 'pre-wrap'; paragraph.textContent = text;
        paragraph.setAttribute('data-moa-community-inserted', first ? 'prefix' : 'suffix');
        if (first) editor.insertBefore(paragraph, editor.firstChild); else editor.appendChild(paragraph);
        state.nodes.push(paragraph);
      };
      appendText(prefix, true); appendText(suffix, false); editor.focus();
      if (saved) { try { selection.setBaseAndExtent(saved[0], saved[1], saved[2], saved[3]); } catch (_) { } }
    } else {
      var start = editor.selectionStart, end = editor.selectionEnd, direction = editor.selectionDirection;
      editor.value = before + current + after; editor.focus(); editor.setSelectionRange(start + before.length, end + before.length, direction);
    }
    insertions.set(editor, state); editor.dispatchEvent(new Event('input', {bubbles: true})); status.textContent = '문구를 넣었어요. 내용을 확인하고 직접 등록해주세요.';
  }
  function scanTools() {
    var wanted = new Set();
    [['post', '#writeForm #textbox'], ['comment', '#comment_write #comment_memo']].forEach(function (pair) {
      var kind = pair[0], editor = document.querySelector(pair[1]);
      if (!eligible(editor, kind) || (!config[kind + 'Prefix'] && !config[kind + 'Suffix'])) return;
      wanted.add(editor);
      var existing = tools.get(editor); if (existing && existing.isConnected) return;
      var wrap = document.createElement('div'), button = document.createElement('button'), status = document.createElement('span');
      wrap.className = 'moa-community-tools'; wrap.setAttribute(owned, 'tools');
      button.type = 'button'; button.className = 'moa-community-affix'; button.textContent = '머리말·꼬리말 넣기';
      button.addEventListener('mousedown', function (event) { event.preventDefault(); });
      button.addEventListener('click', function (event) { event.preventDefault(); insert(editor, kind, status); });
      status.className = 'moa-community-status'; status.setAttribute('role', 'status');
      wrap.append(button, status); editor.insertAdjacentElement('afterend', wrap); tools.set(editor, wrap);
    });
    tools.forEach(function (wrap, editor) { if (!wanted.has(editor)) { wrap.remove(); tools.delete(editor); } });
  }
  function installStyle() {
    if (style && style.isConnected) return;
    style = document.createElement('style'); style.setAttribute(owned, 'style');
    style.textContent = typeof window.__MOA_COMMUNITY_CSS === 'string' ? window.__MOA_COMMUNITY_CSS :
      '[' + blockedMark + '="1"],[' + imageMark + '="1"],[' + gifMark + '="1"]{display:none!important;}';
    (document.head || document.documentElement).appendChild(style);
  }
  function observe() {
    if (!observer) observer = new MutationObserver(function (records) {
      var relevant = records.some(function (record) {
        var target = record.target.nodeType === 1 ? record.target : record.target.parentElement;
        if (!target || target.closest('[' + owned + ']')) return false;
        return record.type === 'childList' || target.closest('.gall-detail-lst,.all-comment-lst,.gallview-tit-box,.thum-txtin,#writeForm,#comment_write');
      });
      if (relevant) schedule();
    });
    observer.observe(document.documentElement, {subtree: true, childList: true, characterData: true, attributes: true,
      attributeFilter: ['data-info', 'data-name', 'data-uid', 'data-ip', 'data-nick', 'src', 'srcset', 'data-src', 'class', 'id']});
  }
  function scan() {
    timer = null; if (!active) return;
    if (!supported()) { disable(); return; }
    if (observer) observer.disconnect();
    try { installStyle(); scanRows(); scanImages(); scanTools(); }
    finally { if (active) observe(); }
  }
  function schedule() { if (active && timer === null) timer = setTimeout(scan, 120); }
  function disable() {
    active = false; enabled = false; if (timer !== null) clearTimeout(timer); timer = null;
    if (observer) observer.disconnect();
    restoreUnused(blocked, new Set(), blockedMark); restoreUnused(hiddenImages, new Set(), imageMark);
    notes.forEach(function (label) { label.remove(); }); notes.clear();
    tools.forEach(function (wrap) { wrap.remove(); }); tools.clear();
    gifs.forEach(function (state, image) { removeGif(image, state); });
    if (style) style.remove(); style = null;
    // Inserted draft text belongs to the user and remains untouched on disable.
  }
  function apply(options) {
    if (!supported()) { disable(); return false; }
    config = normalise(options);
    if (!config.rules.length && !config.notes.length && !config.imagesHidden && !config.gifTapToPlay && !config.postPrefix && !config.postSuffix && !config.commentPrefix && !config.commentSuffix) {
      disable(); enabled = true; return true;
    }
    active = true; enabled = true;
    if (timer !== null) clearTimeout(timer);
    scan(); return true;
  }
  window.MoaCommunityTools = {version: 1, apply: apply, disable: disable, identityAt: identityAt};
})();
