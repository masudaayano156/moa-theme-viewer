/*
 * MOA presentation layer. The host supplies window.__MOA_SKIN_CSS from skin.css,
 * evaluates this file, then calls MoaSkin.apply(options). No network calls,
 * native bridge, form submission, page reload, or content reconstruction.
 */
(function () {
  'use strict';
  if (window.top !== window) return;
  if (window.MoaSkin && window.MoaSkin.version === '2.0.0') return;

  var themes = {
    mail: { noun: '주제함', name: '메일함' },
    post: { noun: '채널', name: '포스트' },
    photogram: { noun: '주제 피드', name: '포토그램' },
    sheet: { noun: '시트', name: '스프레드 시트' },
    cafe: { noun: '게시판', name: '카페' },
    ebook: { noun: '서가', name: 'E-book' },
    magazine: { noun: '섹션', name: '매거진' }
  };
  var current = null;
  var observer = null;
  var queued = false;
  var textEdits = new Map();
  var attributeEdits = new Map();
  var root = document.documentElement;
  var rootFontOriginal = null;
  var rootDarkOriginal = null;
  var rootDarkCurrent = null;
  var pendingOptions = null;
  var waitingForDocument = false;
  var ownedNodes = new Set();
  var rows = new Map();
  var pageHeader = null;
  var listHeading = null;
  var lastStatus = { applied: false, reason: 'not-applied', pageType: 'original' };

  // User content is never traversed for naming. Even an exact UI phrase in a
  // title, author, body, comment, editable field, or hidden token stays intact.
  var protectedSelector = [
    '.gall-detail-lst', '.gall-lst', '.detail-top-lst', '.gallview-tit-box',
    '.thum-txt', '.all-comment-lst', '.ginfo', '.ginfo2', '.nick',
    '#textbox', '[contenteditable]', 'input', 'textarea', 'select', 'option',
    'script', 'style', 'noscript', 'template'
  ].join(',');

  function pageType() {
    if (location.protocol !== 'https:' || location.hostname !== 'm.dcinside.com') return null;
    if (/^\/board\/[a-zA-Z0-9_]+(?:\/\d+)?\/?$/.test(location.pathname)) {
      return /\/\d+\/?$/.test(location.pathname) ? 'view' : 'list';
    }
    if (/^\/write\/[a-zA-Z0-9_]+(?:\/(?:modify\/)?\d+)?\/?$/.test(location.pathname)) return 'write';
    if (/^\/modify\/[a-zA-Z0-9_]+(?:\/\d+)?\/?$/.test(location.pathname)) return 'write';
    return null;
  }

  function setAttribute(node, name, value) {
    var changes = attributeEdits.get(node);
    if (!changes) { changes = new Map(); attributeEdits.set(node, changes); }
    var entry = changes.get(name);
    if (!entry) {
      entry = { original: node.getAttribute(name), current: null };
      changes.set(name, entry);
    } else if (node.getAttribute(name) !== entry.current) {
      // If the site updated the attribute independently, restore that version.
      entry.original = node.getAttribute(name);
    }
    if (node.getAttribute(name) !== value) node.setAttribute(name, value);
    entry.current = value;
  }

  function replaceText(node, value) {
    var entry = textEdits.get(node);
    if (!entry) { entry = { original: node.data, current: node.data }; textEdits.set(node, entry); }
    if (node.data !== entry.current) entry.original = node.data;
    if (node.data !== value) node.data = value;
    entry.current = value;
  }

  function restoreText() {
    textEdits.forEach(function (entry, node) {
      if (node.data === entry.current) node.data = entry.original;
    });
    textEdits.clear();
  }

  function updateUiLabels() {
    if (!current) return;
    var noun = current.groupNoun;
    var fixedLabels = {
      '갤러리': noun,
      '마이너갤': '마이너 ' + noun,
      '미니갤': '미니 ' + noun,
      '인물갤': '인물 ' + noun,
      '갤러리 & 통합검색': noun + ' & 통합검색',
      '갤러리 검색': noun + ' 검색',
      '갤러리 정보': noun + ' 정보',
      '갤러리 관리 내역': noun + ' 관리 내역',
      '현재 갤러리': '현재 ' + noun,
      '전체 갤러리': '전체 ' + noun,
      '연관 갤러리': '연관 ' + noun
    };
    // A deliberately narrow allowlist: fixed search/navigation/popup labels.
    document.querySelectorAll([
      '.header .fld-sch legend', '.nav .menu-scroll-lst > li > a', '#minor_info_pop .pp-tit2',
      '#major_info_pop .pp-tit2', '#gall_history_btn',
      '#userMemoPopup .lflbl', '.rel-group .rel-tit'
    ].join(',')).forEach(function (element) {
      if (element.closest(protectedSelector)) return;
      Array.prototype.forEach.call(element.childNodes, function (node) {
        if (node.nodeType !== 3) return;
        var record = textEdits.get(node);
        var original = record && node.data === record.current ? record.original : node.data;
        var replacement = fixedLabels[original.trim()];
        if (replacement) replaceText(node, original.replace(original.trim(), replacement));
      });
    });
    document.querySelectorAll('.header input[name="search_gall"]').forEach(function (input) {
      var edits = attributeEdits.get(input);
      var edit = edits && edits.get('placeholder');
      var original = edit && input.getAttribute('placeholder') === edit.current ? edit.original : input.getAttribute('placeholder');
      if (original && fixedLabels[original]) setAttribute(input, 'placeholder', fixedLabels[original]);
    });
    document.querySelectorAll('.gall-tit').forEach(function (title) {
      setAttribute(title, 'data-moa-noun', noun);
    });
    document.querySelectorAll('.header .search-wrap2 .logo').forEach(function (logo) {
      setAttribute(logo, 'data-moa-name', current.displayName);
      setAttribute(logo, 'aria-label', current.displayName + ' · 연결된 커뮤니티 홈');
    });
    // CSS selectors handle newly loaded comments without touching their nodes.
    // The observer only revisits the narrow UI allowlist above.
  }

  function installStyle() {
    var css = window.__MOA_SKIN_CSS;
    var style = document.getElementById('moa-skin-style');
    if (style && (style.tagName !== 'STYLE' || style.getAttribute('data-moa-owned') !== 'true')) return false;
    if (!style && typeof css !== 'string') return false;
    if (!style) {
      style = document.createElement('style');
      style.id = 'moa-skin-style';
      style.setAttribute('data-moa-owned', 'true');
      (document.head || root).appendChild(style);
    }
    if (typeof css === 'string' && style.textContent !== css) style.textContent = css;
    style.disabled = false;
    return true;
  }

  // The presentation adapter adds its own siblings; original links, editor
  // nodes, event handlers, notices and advertising inventory remain intact.
  // No content is fetched in the background and no counter is invented.
  function node(tag, className, value) {
    var element = document.createElement(tag);
    element.className = className;
    element.setAttribute('data-moa-owned', 'true');
    if (value) element.textContent = value;
    return element;
  }

  function own(element) { ownedNodes.add(element); return element; }
  function words(element) { return element ? element.textContent.replace(/\s+/g, ' ').trim() : ''; }
  function text(element, value) { if (element.textContent !== value) element.textContent = value; }

  function icon(kind) {
    var svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('viewBox', '0 0 24 24');
    svg.setAttribute('aria-hidden', 'true');
    svg.setAttribute('class', 'moa-icon');
    var path = document.createElementNS(svg.namespaceURI, 'path');
    path.setAttribute('fill', 'none'); path.setAttribute('stroke', 'currentColor');
    path.setAttribute('stroke-width', '1.7'); path.setAttribute('stroke-linecap', 'round'); path.setAttribute('stroke-linejoin', 'round');
    var paths = {
      comment: 'M21 11.5a8.5 8.5 0 0 1-8.5 8.5H4l1.8-4A8.5 8.5 0 1 1 21 11.5Z',
      heart: 'M12 20S3 14 3 8.5A4.5 4.5 0 0 1 12 7a4.5 4.5 0 0 1 9 1.5C21 14 12 20 12 20Z',
      views: 'M5 20V12M10 20V4M15 20V9M20 20V6',
      edit: 'm14 4 6 6M4 20l5-1L21 7l-5-5L4 14Z',
      back: 'm14 5-7 7 7 7'
    };
    path.setAttribute('d', paths[kind] || paths.comment); svg.appendChild(path); return svg;
  }

  function action(className, kind, label, target) {
    var link = node(target ? 'a' : 'span', className);
    if (target) {
      var href = target.getAttribute('href');
      link.href = href && !/^javascript:/i.test(href) ? target.href : '#';
      link.addEventListener('click', function (event) {
        if (event.ctrlKey || event.metaKey || event.shiftKey) return;
        event.preventDefault(); target.click();
      });
    }
    link.setAttribute('aria-label', label);
    link.appendChild(icon(kind)); link.appendChild(node('span', 'moa-action-value', label));
    return link;
  }

  function updatePresentation() {
    if (!current) return;
    var type = pageType();
    setAttribute(root, 'data-moa-layout', 'app');
    // Never hide an advertising slot as a side effect of removing duplicated
    // site navigation. If a provider adds a slot there, retain that surface.
    document.querySelectorAll('body > .header, body > .nav, body > .footer, .gall-tit-box').forEach(function (chrome) {
      var ad = chrome.querySelector('.adv-group,.adv-groupin,.adv-inner,.ntc-line-ad,ins.adsbygoogle,iframe[src*="doubleclick.net"],iframe[src*="googlesyndication.com"]');
      setAttribute(chrome, 'data-moa-contains-ad', ad ? 'true' : 'false');
    });
    var group = document.querySelector('.gall-tit-group');
    var title = document.querySelector('.gall-tit-lnk') || document.querySelector('.gall-tit');
    if (!pageHeader || !pageHeader.isConnected) {
      pageHeader = own(node('div', 'moa-page-header'));
      pageHeader.appendChild(node('div', 'moa-community-mark'));
      var heading = node('a', 'moa-page-title');
      if (title && title.href) heading.href = title.href;
      else heading.removeAttribute('href');
      pageHeader.appendChild(heading);
      pageHeader.appendChild(node('span', 'moa-page-context'));
      var tools = node('button', 'moa-page-tools', '⋯');
      tools.type = 'button'; tools.setAttribute('aria-label', '게시판 도구');
      tools.setAttribute('aria-expanded', 'false');
      tools.addEventListener('click', function () {
        var expanded = root.getAttribute('data-moa-tools') !== 'true';
        setAttribute(root, 'data-moa-tools', String(expanded));
        tools.setAttribute('aria-expanded', String(expanded));
      });
      pageHeader.appendChild(tools);
      if (type === 'list') {
        var filter = node('button', 'moa-filter-toggle', '필터');
        filter.type = 'button'; filter.setAttribute('aria-expanded','false');
        filter.addEventListener('click', function () {
          var expanded = root.getAttribute('data-moa-filters') !== 'true';
          setAttribute(root,'data-moa-filters',String(expanded));
          filter.setAttribute('aria-expanded',String(expanded));
        });
        pageHeader.appendChild(filter);
      }
      var compose = document.querySelector('.gall-lnk-box .btn-write');
      if (compose && type !== 'write') {
        var composeLink = action('moa-compose', 'edit', '글쓰기', compose);
        pageHeader.appendChild(composeLink);
      }
      (group || document.body).insertBefore(pageHeader, (group || document.body).firstChild);
    }
    var name = words(title) || current.displayName;
    text(pageHeader.querySelector('.moa-page-title'), name);
    text(pageHeader.querySelector('.moa-community-mark'), Array.from(name)[0] || '·');
    var contexts = {mail:'주제함', post:'채널', photogram:'피드', cafe:'카페', sheet:'워크시트', ebook:'서가', magazine:'섹션'};
    text(pageHeader.querySelector('.moa-page-context'), contexts[current.theme]);
    var recommendation = document.querySelector('#recom_box1');
    if (recommendation) setAttribute(recommendation,'data-moa-empty',String(!words(recommendation.querySelector('.lt'))));
    var list = document.querySelector('.gall-detail-lst');
    if (!list) return;
    if (!listHeading || !listHeading.isConnected) {
      listHeading = own(node('div', 'moa-list-heading'));
      list.parentNode.insertBefore(listHeading, list);
    }
    text(listHeading, {mail:'받은편지함', post:'게시물', photogram:'피드', cafe:'전체글', sheet:'목록', ebook:'읽을거리', magazine:'최신 이야기'}[current.theme]);
    rows.forEach(function (value, row) { if (!row.isConnected) rows.delete(row); });
    Array.prototype.forEach.call(list.children, function (row) {
      var original = row.querySelector('.gall-detail-lnktb');
      if (!original || row.classList.contains('adv-inner') || row.querySelector('.ntc-line-ad')) return;
      var titleLink = original.querySelector('a.lt');
      if (!titleLink) return;
      var info = original.querySelector('.ginfo');
      var author = words(original.querySelector('.list-nick')) || '작성자';
      var parts = info ? Array.from(info.children).map(words) : [];
      var date = parts.find(function (part) { return /^(?:\d{1,4}[.:/-])?\d{1,2}[.:/-]\d{1,2}(?:\s|$)/.test(part); }) || '';
      var views = parts.find(function (part) { return /^조회\s*\d/.test(part); }) || '';
      var recommend = parts.find(function (part) { return /^추천\s*\d/.test(part); }) || '';
      var commentLink = original.querySelector('a.rt');
      var comments = words(commentLink && commentLink.querySelector('.ct'));
      var signature = [author,date,views,recommend,comments].join('|');
      var state = rows.get(row);
      if (!state) {
        setAttribute(row, 'data-moa-row', 'true');
        state = { avatar: own(node('div','moa-avatar')), author: own(node('div','moa-author-line')), actions: own(node('div','moa-row-actions')), signature: null };
        state.avatar.setAttribute('aria-hidden','true');
        state.author.appendChild(node('span','moa-author-name'));
        state.author.appendChild(node('time','moa-row-time'));
        row.appendChild(state.avatar); row.appendChild(state.author); row.appendChild(state.actions);
        rows.set(row,state);
      }
      if (!state.media) {
        // Use only an image already provided in this row. Sprite/nickname
        // icons are not photos and are never expanded into fabricated media.
        var source = original.querySelector('img[data-src], .thumb img, .thum img, .thum-img img, .thumbnail img');
        if (source) {
          try {
            var imageUrl = new URL(source.getAttribute('data-src') || source.getAttribute('src'), location.href);
            if (imageUrl.protocol === 'https:') {
              state.media = own(node('a','moa-media')); state.media.href = titleLink.href;
              state.media.addEventListener('click', function (event) {
                if (event.ctrlKey || event.metaKey || event.shiftKey) return;
                event.preventDefault(); titleLink.click();
              });
              var picture = node('img','moa-post-image'); picture.src = imageUrl.href;
              picture.alt = source.alt || '게시물 사진'; picture.loading = 'lazy';
              picture.addEventListener('error',function () { if (state.media) state.media.hidden = true; });
              state.media.appendChild(picture); row.appendChild(state.media);
              setAttribute(source,'data-moa-source-media','true');
            }
          } catch (_) { }
        }
      }
      if (state.signature !== signature) {
        state.signature = signature;
        text(state.avatar, Array.from(author)[0] || '·');
        text(state.author.querySelector('.moa-author-name'), author);
        text(state.author.querySelector('.moa-row-time'), date);
        state.actions.textContent = '';
        if (commentLink && comments) { var commentAction = action('moa-comment-count','comment',comments,commentLink); commentAction.setAttribute('aria-label','댓글 ' + comments); state.actions.appendChild(commentAction); }
        if (recommend) { var recommendCount = action('moa-recommend-count','heart',recommend.replace(/^추천\s*/,''),null); recommendCount.setAttribute('aria-label',recommend); state.actions.appendChild(recommendCount); }
        if (views) { var viewCount = action('moa-view-count','views',views.replace(/^조회\s*/,''),null); viewCount.setAttribute('aria-label',views); state.actions.appendChild(viewCount); }
      }
    });
  }

  function removePresentation() {
    ownedNodes.forEach(function (element) { element.remove(); });
    ownedNodes.clear(); rows.clear(); pageHeader = null; listHeading = null;
  }

  function observe() {
    if (!observer) observer = new MutationObserver(function (mutations) {
      if (!current || queued || !mutations.some(function (m) {
        return m.type === 'childList' || m.type === 'characterData' || m.type === 'attributes';
      })) return;
      queued = true;
      Promise.resolve().then(function () {
        queued = false;
        if (!current) return;
        observer.disconnect();
        updateUiLabels();
        updatePresentation();
        observer.observe(root, { childList: true, subtree: true, characterData: true, attributes: true, attributeFilter: ['class','id','src','data-src'] });
      });
    });
    observer.observe(root, { childList: true, subtree: true, characterData: true, attributes: true, attributeFilter: ['class','id','src','data-src'] });
  }

  function disable(reason) {
    pendingOptions = null;
    current = null;
    if (observer) observer.disconnect();
    removePresentation();
    restoreText();
    attributeEdits.forEach(function (changes, node) {
      changes.forEach(function (entry, name) {
        if (node.getAttribute(name) !== entry.current) return;
        if (entry.original === null) node.removeAttribute(name);
        else node.setAttribute(name, entry.original);
      });
    });
    attributeEdits.clear();
    if (rootDarkOriginal !== null) {
      if (root.classList.contains('darkmode') === rootDarkCurrent) root.classList.toggle('darkmode', rootDarkOriginal);
      rootDarkOriginal = null;
      rootDarkCurrent = null;
    }
    if (rootFontOriginal !== null) {
      if (rootFontOriginal.value) root.style.setProperty('--moa-font-scale', rootFontOriginal.value, rootFontOriginal.priority);
      else root.style.removeProperty('--moa-font-scale');
      rootFontOriginal = null;
    }
    var style = document.getElementById('moa-skin-style');
    if (style && style.tagName === 'STYLE' && style.getAttribute('data-moa-owned') === 'true') style.disabled = true;
    lastStatus = { applied: false, reason: reason || 'original-mode', pageType: 'original' };
    return lastStatus;
  }

  function apply(options) {
    options = options || {};
    root = document.documentElement;
    // A document-start callback may run after <html> exists but before the
    // content has arrived. Do not theme an error/auth page just from its URL.
    if (!root || document.readyState === 'loading') {
      pendingOptions = options;
      if (!waitingForDocument) {
        waitingForDocument = true;
        document.addEventListener('DOMContentLoaded', function () {
          waitingForDocument = false;
          var next = pendingOptions;
          pendingOptions = null;
          if (next) apply(next);
        }, { once: true });
      }
      lastStatus = { applied: false, reason: 'pending-document', pageType: 'original' };
      return lastStatus;
    }
    var type = pageType();
    if (!type) return disable('unsupported-page');
    if (!document.querySelector(
      type === 'write' ? '#writeForm #textbox' : type === 'view' ? '.gallview-tit-box' : '.gall-tit-group'
    )) return disable('unsupported-layout');
    var theme = Object.prototype.hasOwnProperty.call(themes, options.theme) ? options.theme : 'mail';
    if (!installStyle()) return disable('missing-css');
    if (observer) observer.disconnect();
    current = {
      theme: theme,
      groupNoun: typeof options.groupNoun === 'string' && options.groupNoun.trim() ? options.groupNoun.slice(0, 24) : themes[theme].noun,
      displayName: typeof options.displayName === 'string' && options.displayName.trim() ? options.displayName.slice(0, 32) : themes[theme].name
    };
    setAttribute(root, 'data-moa-theme', theme);
    setAttribute(root, 'data-moa-dark', options.dark ? 'true' : 'false');
    setAttribute(root, 'data-moa-page', type);
    // The site's stored night-mode class includes !important colors and image
    // inversion. Keep it aligned with the app setting while themed, then return
    // only this token to its original state without touching other site classes.
    var siteDark = root.classList.contains('darkmode');
    if (rootDarkOriginal === null || siteDark !== rootDarkCurrent) rootDarkOriginal = siteDark;
    rootDarkCurrent = Boolean(options.dark);
    root.classList.toggle('darkmode', rootDarkCurrent);
    if (rootFontOriginal === null) rootFontOriginal = {
      value: root.style.getPropertyValue('--moa-font-scale'), priority: root.style.getPropertyPriority('--moa-font-scale')
    };
    var scale = Number(options.fontScale);
    root.style.setProperty('--moa-font-scale', String(Number.isFinite(scale) ? Math.max(0.85, Math.min(scale, 1.5)) : 1));
    updateUiLabels();
    updatePresentation();
    observe();
    lastStatus = { applied: true, reason: 'themed', pageType: type, theme: theme };
    return lastStatus;
  }

  window.MoaSkin = Object.freeze({
    version: '2.0.0', apply: apply, disable: disable,
    status: function () { return Object.assign({}, lastStatus); }
  });
})();
