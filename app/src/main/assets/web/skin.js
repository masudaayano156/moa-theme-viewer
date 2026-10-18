/*
 * MOA presentation layer. The host supplies window.__MOA_SKIN_CSS from skin.css,
 * evaluates this file, then calls MoaSkin.apply(options). Original service
 * nodes remain intact. Independent list templates use only supplied page data;
 * there is no article prefetch, native bridge, or automatic form submission.
 */
(function () {
  'use strict';
  if (window.top !== window) return;
  if (window.MoaSkin && window.MoaSkin.version === '3.0.7') return;
  if (window.MoaSkin && typeof window.MoaSkin.disable === 'function') window.MoaSkin.disable();

  var themes = {
    mail: { noun: '라벨', name: '메일함' },
    post: { noun: '커뮤니티', name: 'X' },
    photogram: { noun: '피드', name: '인스타그램' },
    sheet: { noun: '시트', name: '엑셀' },
    cafe: { noun: '카페', name: '네이버 카페' },
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
  var sheetHeader = null;
  var sheetFormula = null;
  var sheetResizeListening = false;
  var lastStatus = { applied: false, reason: 'not-applied', pageType: 'original' };
  var accentStyle = null;

  function applyAccent(options) {
    var colors;
    try { colors = [options.accent, options.accentOn, options.accentTint]; }
    catch (_) { if (accentStyle) accentStyle.disabled = true; return; }
    // Native options are strings. Snapshot once so arrays, coercible objects or
    // changing property getters cannot insert unvalidated CSS into the rule.
    var valid = colors.every(function (value) { return typeof value === 'string' && value.length === 7 && /^#[0-9a-f]{6}$/i.test(value); });
    if (!valid) { if (accentStyle) accentStyle.disabled = true; return; }
    if (!accentStyle || !accentStyle.isConnected) {
      accentStyle = document.createElement('style');
      accentStyle.setAttribute('data-moa-accent-owned','true');
      (document.head || root).appendChild(accentStyle);
    }
    var rules = 'html[data-moa-theme]{--moa-accent:'+colors[0]+'!important;--moa-action-ink:'+colors[0]+'!important;--moa-action-fill:'+colors[0]+'!important;--moa-on-accent:'+colors[1]+'!important;--moa-action-on:'+colors[1]+'!important;--moa-tint:'+colors[2]+'!important;}';
    if (accentStyle.textContent !== rules) accentStyle.textContent = rules;
    accentStyle.disabled = false;
  }

  // User content is never traversed for naming. Even an exact UI phrase in a
  // title, author, body, comment, editable field, or hidden token stays intact.
  var protectedSelector = [
    '.gall-detail-lst', '.gall-lst', '.sch-lst', '.flex-gall-lst', '.detail-top-lst', '.gallview-tit-box',
    '.thum-txt', '.all-comment-lst', '.ginfo', '.ginfo2', '.nick',
    '#textbox', '[contenteditable]', 'input', 'textarea', 'select', 'option',
    'script', 'style', 'noscript', 'template'
  ].join(',');

  function pageType() {
    if (location.protocol !== 'https:' || location.hostname !== 'm.dcinside.com') return null;
    if (/^\/search(?:\/(?:search_main|gall_name|gall_content|movie))?\/?$/.test(location.pathname)) return 'search';
    if (/^\/(?:board|mini|person)\/[a-zA-Z0-9_]+(?:\/\d+)?\/?$/.test(location.pathname)) {
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
    var composeLabels = {mail:'편지 쓰기',post:'게시하기',photogram:'만들기',sheet:'새 행',cafe:'글쓰기',ebook:'새 글',magazine:'기고'};
    var listLabels = {mail:'받은편지함',post:'게시물',photogram:'피드',sheet:'워크시트',cafe:'글 목록',ebook:'목차',magazine:'기사 목록'};
    var popularLabels = {mail:'중요 글',post:'인기',photogram:'인기 게시물',sheet:'인기 행',cafe:'인기글',ebook:'추천 글',magazine:'인기 기사'};
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
      '연관 갤러리': '연관 ' + noun,
      '갤로그 가기': '프로필',
      '개념글': popularLabels[current.theme],
      '개념글보기': popularLabels[current.theme],
      '개념글 추천하기': current.theme === 'post' ? '마음에 들어요' : current.theme === 'photogram' ? '좋아요' : '추천',
      '목록보기': listLabels[current.theme],
      '글쓰기': composeLabels[current.theme]
    };
    // A deliberately narrow allowlist: fixed search/navigation/popup labels.
    document.querySelectorAll([
      '.header .fld-sch legend', '.nav .menu-scroll-lst > li > a', '#minor_info_pop .pp-tit2',
      '#major_info_pop .pp-tit2', '#gall_history_btn',
      '#userMemoPopup .lflbl', '.rel-group .rel-tit',
      '.gall-lst-group > .tab-basic .tab-lst > li > a',
      '.gallview-tit-box .btm > .rt > a.btn-line-gray',
      '.reco-circle #recommend_join', '#view_btn_area > button',
      '.gall-btns .btn-reco', '.gall-btns .btn-write'
    ].join(',')).forEach(function (element) {
      if (element.closest(protectedSelector) && !element.matches('.gallview-tit-box .btm > .rt > a.btn-line-gray')) return;
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
      back: 'm14 5-7 7 7 7',
      grid: 'M3 3h18v18H3ZM3 9h18M3 15h18M9 3v18M15 3v18',
      feed: 'M4 3h16v18H4ZM4 8h16M4 16h16'
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

  var adSelector = '.adv-group,.adv-groupin,.adv-inner,.ntc-line-ad,ins.adsbygoogle,iframe[src*="doubleclick.net"],iframe[src*="googlesyndication.com"]';

  function sourceLink(className, target, value) {
    var link = node('a', className, value);
    var href = target.getAttribute('href');
    link.href = href && !/^javascript:/i.test(href) ? target.href : '#';
    link.addEventListener('click', function (event) {
      if (event.ctrlKey || event.metaKey || event.shiftKey) return;
      event.preventDefault(); if (target.isConnected) target.click();
    });
    return link;
  }

  function readEntry(row, index) {
    var original = Array.from(row.children).find(function (child) { return child.classList.contains('gall-detail-lnktb'); });
    if (!original || row.matches(adSelector) || row.querySelector(adSelector)) return null;
    var titleLink = original.querySelector('a.lt');
    var titleNode = original.querySelector('.subjectin');
    if (!titleLink || !titleNode) return null;
    var info = original.querySelector('.ginfo');
    var parts = info ? Array.from(info.children) : [];
    var authorNode = original.querySelector('.list-nick');
    var author = words(authorNode) || '작성자';
    var date = parts.map(words).find(function (part) { return /^(?:\d{1,4}[.:/-])?\d{1,2}[.:/-]\d{1,2}(?:\s|$)/.test(part); }) || '';
    var category = parts.length && parts[0] !== authorNode ? words(parts[0]) : '';
    if (/^(조회|추천)|^\d/.test(category)) category = '';
    var count = function (prefix) {
      var part = parts.map(words).find(function (value) { return value.startsWith(prefix); });
      if (!part) return null;
      var match = part.slice(prefix.length).match(/[\d,.]+(?:[만천])?/);
      return match ? match[0] : null;
    };
    var imageSource = original.querySelector('img[data-src],.thumb img,.thum img,.thum-img img,.thumbnail img');
    var imageUrl = '';
    if (imageSource) {
      try {
        var parsed = new URL(imageSource.getAttribute('data-src') || imageSource.getAttribute('src'), location.href);
        if (parsed.protocol === 'https:') imageUrl = parsed.href;
      } catch (_) { }
    }
    var commentLink = original.querySelector('a.rt');
    var comments = words(commentLink && commentLink.querySelector('.ct'));
    return {original:original,titleLink:titleLink,title:words(titleNode),author:author,date:date,category:category,
      views:count('조회'),recommend:count('추천'),commentLink:commentLink,comments:comments,
      imageSource:imageSource,imageUrl:imageUrl,imageAlt:imageSource ? imageSource.alt : '',index:index};
  }

  function entryParts(data) {
    var avatar = node('span', 'moa-entry-avatar', Array.from(data.author)[0] || '·');
    avatar.setAttribute('aria-hidden','true');
    var name = node('span','moa-entry-name',data.author);
    var date = node('time','moa-entry-date',data.date);
    var category = node('span','moa-entry-category',data.category);
    var title = sourceLink('moa-entry-title',data.titleLink,data.title);
    var header = node('div','moa-entry-header'); header.append(name,date);
    var comments = data.commentLink && data.comments ? action('moa-entry-comments','comment',data.comments,data.commentLink) : null;
    if (comments) comments.setAttribute('aria-label','댓글 ' + data.comments);
    var recommend = data.recommend !== null ? action('moa-entry-recommend','heart',data.recommend,null) : null;
    if (recommend) recommend.setAttribute('aria-label','추천 ' + data.recommend);
    var views = data.views !== null ? action('moa-entry-views','views',data.views,null) : null;
    if (views) views.setAttribute('aria-label','조회 ' + data.views);
    var media = null;
    if (data.imageUrl && ['post','photogram','cafe','magazine'].includes(current.theme)) {
      media = sourceLink('moa-entry-media',data.titleLink);
      var image = node('img','moa-entry-image'); image.src = data.imageUrl; image.alt = data.imageAlt || '게시물 사진'; image.loading = 'lazy';
      image.addEventListener('error',function () { media.hidden = true; }); media.appendChild(image);
    }
    return {avatar:avatar,name:name,date:date,category:category,title:title,header:header,comments:comments,recommend:recommend,views:views,media:media};
  }
  function append(parent) { Array.prototype.slice.call(arguments,1).forEach(function (child) { if (child) parent.appendChild(child); }); return parent; }
  function region(className) { return node('div',className); }
  function entryRoot(className, data) { var entry = node('article','moa-entry '+className); entry.setAttribute('aria-label',data.title); return entry; }

  // Seven templates, each with its own information hierarchy. These are not
  // one shared board row recolored or reordered by the selected palette.
  var entryTemplates = {
    mail: function (d,p) {
      var main = sourceLink('moa-entry-main',d.titleLink);
      append(main,p.header,node('span','moa-entry-title',d.title),append(region('moa-entry-meta'),p.category));
      return append(entryRoot('moa-mail-message',d),p.avatar,main,p.comments);
    },
    post: function (d,p) {
      var main = append(region('moa-entry-main'),p.header,p.title,p.media,append(region('moa-entry-actions'),p.comments,p.recommend,p.views));
      return append(entryRoot('moa-post-entry',d),p.avatar,main);
    },
    photogram: function (d,p) {
      var profile = append(region('moa-entry-header'),p.avatar,p.name,p.date);
      var caption = append(region('moa-entry-caption'),node('span','moa-entry-caption-author',d.author),p.title,append(region('moa-entry-meta'),p.category));
      return append(entryRoot('moa-photo-entry',d),profile,p.media,caption,append(region('moa-entry-actions'),p.recommend,p.comments,p.views));
    },
    cafe: function (d,p) {
      var main = append(region('moa-entry-main'),p.category,p.title,append(region('moa-entry-meta'),p.name,p.date,p.views));
      return append(entryRoot('moa-cafe-entry',d),main,p.media,p.comments);
    },
    sheet: function (d,p) {
      var number = d.index + 1;
      var key = node('span','moa-entry-key',String(number)); key.setAttribute('aria-label','행 '+number);
      p.category.classList.add('moa-sheet-category-cell');
      var caption = node('span','moa-entry-category moa-sheet-category-caption',d.category);
      var titleCell = append(region('moa-sheet-title-cell'),caption,p.title);
      titleCell.setAttribute('data-moa-sheet-row',String(number));
      titleCell.setAttribute('data-moa-cell',(wideSheet() ? 'B' : 'A')+number);
      var entry = append(entryRoot('moa-sheet-entry',d),key,p.category,titleCell,p.name,p.comments || node('span','moa-entry-comments','—'),p.views || node('span','moa-entry-views','—'));
      p.title.addEventListener('focus',function () { selectSheetCell(titleCell,d.title); });
      p.title.addEventListener('pointerdown',function () { selectSheetCell(titleCell,d.title); });
      return entry;
    },
    ebook: function (d,p) {
      var key = node('span','moa-entry-key',String(d.index).padStart(2,'0'));
      var main = append(region('moa-entry-main'),p.category,p.title,append(region('moa-entry-meta'),p.name,p.date),p.comments);
      return append(entryRoot('moa-book-entry',d),key,main);
    },
    magazine: function (d,p) {
      var key = node('span','moa-entry-key',String(d.index).padStart(2,'0'));
      var main = append(region('moa-entry-main'),p.category,p.title,append(region('moa-entry-meta'),p.name,p.date),append(region('moa-entry-actions'),p.comments,p.views));
      return append(entryRoot('moa-mag-entry',d),key,main,p.media);
    }
  };

  // A real source title is previewed in the formula strip; this does not imply
  // that a community post is an editable workbook cell or install an editor.
  function selectSheetCell(cell,value) {
    if (!current || current.theme !== 'sheet' || !sheetFormula) return;
    rows.forEach(function (state) {
      state.entry.querySelectorAll('[data-moa-cell-selected]').forEach(function (selected) {
        if (selected !== cell) selected.removeAttribute('data-moa-cell-selected');
      });
    });
    if (cell.getAttribute('data-moa-cell-selected') !== 'true') cell.setAttribute('data-moa-cell-selected','true');
    text(sheetFormula.querySelector('.moa-sheet-address'),cell.getAttribute('data-moa-cell'));
    text(sheetFormula.querySelector('.moa-sheet-value'),value);
  }

  function wideSheet() { return window.matchMedia('(min-width: 600px)').matches; }
  function syncSheetLayout() {
    if (!current || current.theme !== 'sheet' || !sheetHeader) return;
    var wide = wideSheet();
    var letters = wide ? ['', 'A', 'B', 'C', 'D', 'E'] : ['', '', 'A', 'B', 'C', 'D'];
    sheetHeader.querySelectorAll('.moa-sheet-columns > span').forEach(function (column,index) { text(column,letters[index]); });
    rows.forEach(function (state) {
      var cell = state.entry.querySelector('.moa-sheet-title-cell');
      if (!cell) return;
      var address = (wide ? 'B' : 'A') + cell.getAttribute('data-moa-sheet-row');
      if (cell.getAttribute('data-moa-cell') !== address) cell.setAttribute('data-moa-cell',address);
    });
    var selected = document.querySelector('.moa-sheet-title-cell[data-moa-cell-selected="true"]') ||
      document.querySelector('.moa-sheet-title-cell');
    if (selected) selectSheetCell(selected,words(selected.querySelector('.moa-entry-title')));
    syncSheetScroll(sheetHeader.scrollLeft);
  }

  function restoreRow(row, state) {
    if (state.sheetScroll) row.removeEventListener('scroll',state.sheetScroll);
    state.entry.remove();
    [['data-moa-row',state.rowAttribute],['data-moa-renderer',state.rendererAttribute]].forEach(function (pair) {
      if (pair[1] === null) row.removeAttribute(pair[0]); else row.setAttribute(pair[0],pair[1]);
    });
    if (state.original.getAttribute('data-moa-list-source') === 'true') {
      if (state.sourceAttribute === null) state.original.removeAttribute('data-moa-list-source');
      else state.original.setAttribute('data-moa-list-source',state.sourceAttribute);
    }
  }

  function renderEntries(list) {
    rows.forEach(function (state,row) { if (!row.isConnected || row.parentNode !== list) { restoreRow(row,state); rows.delete(row); } });
    var index = 0;
    Array.from(list.children).forEach(function (row) {
      var data = readEntry(row,index+1);
      var previous = rows.get(row);
      if (!data) {
        if (previous) { restoreRow(row,previous); rows.delete(row); }
        return;
      }
      index++;
      var signature = JSON.stringify([current.theme,data.title,data.author,data.date,data.category,data.views,data.recommend,data.comments,data.imageUrl,data.imageAlt,data.index,
        data.titleLink.getAttribute('href'),data.commentLink && data.commentLink.getAttribute('href')]);
      if (previous && previous.signature === signature && previous.original === data.original
          && previous.titleLink === data.titleLink && previous.commentLink === data.commentLink) return;
      var wasSelected = previous && previous.entry.querySelector('[data-moa-cell-selected="true"]');
      var previousScroll = row.scrollLeft;
      if (previous) restoreRow(row,previous);
      var entry = entryTemplates[current.theme](data,entryParts(data));
      var state = {entry:entry,original:data.original,titleLink:data.titleLink,commentLink:data.commentLink,signature:signature,
        rowAttribute:row.getAttribute('data-moa-row'),rendererAttribute:row.getAttribute('data-moa-renderer'),sourceAttribute:data.original.getAttribute('data-moa-list-source')};
      row.setAttribute('data-moa-row','true'); row.setAttribute('data-moa-renderer','3');
      data.original.setAttribute('data-moa-list-source','true'); row.appendChild(entry); rows.set(row,state);
      if (current.theme === 'sheet') {
        if (wasSelected) entry.querySelector('.moa-sheet-title-cell').setAttribute('data-moa-cell-selected','true');
        row.scrollLeft = previousScroll;
        state.sheetScroll = function () { if (current && current.theme === 'sheet') receiveSheetScroll(row); };
        row.addEventListener('scroll',state.sheetScroll,{passive:true});
      }
    });
  }

  // Scroll events caused by synchronizing another row arrive asynchronously.
  // Do not replay those writes as a fresh drag after the user moves a new row.
  var sheetScrollWrites = new WeakMap();
  function receiveSheetScroll(source) {
    var expected = sheetScrollWrites.get(source);
    if (expected !== undefined && Math.abs(source.scrollLeft-expected) <= 1) return;
    sheetScrollWrites.delete(source);
    syncSheetScroll(source.scrollLeft);
  }

  function syncSheetScroll(left) {
    function move(target) {
      if (!target || Math.abs(target.scrollLeft-left) <= 1) return;
      target.scrollLeft = left;
      // Read back the browser-clamped position rather than assuming it accepted
      // the requested offset (a fold/resize can change the available width).
      sheetScrollWrites.set(target,target.scrollLeft);
    }
    move(sheetHeader);
    rows.forEach(function (state,row) { move(row); });
  }

  function containsUnhiddenAd(container) {
    var candidates = Array.from(container.querySelectorAll(adSelector));
    if (container.matches(adSelector)) candidates.unshift(container);
    var cleaned = root.getAttribute('data-moa-cleanup-active') === '1';
    return candidates.some(function (ad) {
      return !cleaned || !ad.closest('[data-moa-cleanup-hidden="1"]');
    });
  }

  function updatePresentation() {
    if (!current) return;
    var type = pageType();
    setAttribute(root, 'data-moa-layout', 'app');
    if (type === 'search') return;
    // The independent cleanup module owns ad hiding. Slots it has already
    // hidden must not leave an empty copy of the site's header or footer.
    document.querySelectorAll('body > .header, body > .nav, body > .footer, .gall-tit-box').forEach(function (chrome) {
      setAttribute(chrome, 'data-moa-contains-ad', String(containsUnhiddenAd(chrome)));
      chrome.querySelectorAll('.logo,#topsearch_form,.search-box,.menu-scroll-lst,.ft-top,.ft-btm').forEach(function (part) {
        setAttribute(part,'data-moa-chrome-hidden',String(!containsUnhiddenAd(part)));
      });
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
        var photoSwitch = node('div','moa-photo-switch');
        photoSwitch.setAttribute('role','group'); photoSwitch.setAttribute('aria-label','사진 피드 보기 방식');
        ['feed','grid'].forEach(function (value) {
          var choice = node('button','moa-photo-choice');
          choice.appendChild(icon(value));
          choice.appendChild(node('span','moa-photo-choice-label',value === 'feed' ? '피드' : '모아보기'));
          choice.type = 'button'; choice.setAttribute('data-view',value);
          choice.addEventListener('click',function () {
            setAttribute(root,'data-moa-photo-view',value);
            photoSwitch.querySelectorAll('button').forEach(function (button) { button.setAttribute('aria-pressed',String(button === choice)); });
          });
          photoSwitch.appendChild(choice);
        });
        pageHeader.appendChild(photoSwitch);
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
    var contexts = {mail:'라벨', post:'커뮤니티', photogram:'피드', cafe:'카페', sheet:'워크시트', ebook:'서가', magazine:'섹션'};
    text(pageHeader.querySelector('.moa-page-context'), contexts[current.theme]);
    var composeLabel = pageHeader.querySelector('.moa-compose .moa-action-value');
    if (composeLabel) {
      var composeName = {mail:'편지 쓰기',post:'게시하기',photogram:'만들기',sheet:'새 행',cafe:'글쓰기',ebook:'새 글',magazine:'기고'}[current.theme];
      text(composeLabel,composeName);
      setAttribute(composeLabel.parentNode,'aria-label',composeName);
    }
    var photoView = root.getAttribute('data-moa-photo-view');
    if (!photoView) { photoView = 'feed'; setAttribute(root,'data-moa-photo-view',photoView); }
    pageHeader.querySelectorAll('.moa-photo-choice').forEach(function (button) { button.setAttribute('aria-pressed',String(button.getAttribute('data-view') === photoView)); });
    var recommendation = document.querySelector('#recom_box1');
    if (recommendation) setAttribute(recommendation,'data-moa-empty',String(!words(recommendation.querySelector('.lt'))));
    if (window.MoaDetailLayout) window.MoaDetailLayout.apply(Object.assign({},current,{pageType:type}));
    var list = document.querySelector('.gall-detail-lst');
    if (!list) return;
    if (!listHeading || !listHeading.isConnected) {
      listHeading = own(node('div', 'moa-list-heading'));
      list.parentNode.insertBefore(listHeading, list);
    }
    text(listHeading, {mail:'받은편지함', post:'게시물', photogram:'게시물', cafe:'전체글', sheet:'워크시트', ebook:'목차', magazine:'이야기'}[current.theme]);
    if (!sheetFormula || !sheetFormula.isConnected) {
      sheetFormula = own(node('div','moa-sheet-formula'));
      sheetFormula.setAttribute('aria-label','선택한 게시물 제목');
      append(sheetFormula,node('span','moa-sheet-address','A2'),node('span','moa-sheet-fx','ƒx'),node('span','moa-sheet-value'));
      list.parentNode.insertBefore(sheetFormula,list);
    }
    if (!sheetHeader || !sheetHeader.isConnected) {
      sheetHeader = own(node('div','moa-sheet-header-scroll'));
      sheetHeader.setAttribute('aria-label','표 열 제목 · 가로 스크롤');
      var columns = node('div','moa-sheet-columns');
      ['','A','B','C','D','E'].forEach(function (label,index) { columns.appendChild(node('span',index === 1 ? 'moa-sheet-category-column' : '',label)); });
      var labels = node('div','moa-sheet-labels');
      ['1','분류','제목','작성자','댓글','조회'].forEach(function (label,index) { labels.appendChild(node('span',index === 1 ? 'moa-sheet-category-column' : '',label)); });
      append(sheetHeader,columns,labels); list.parentNode.insertBefore(sheetHeader,list);
      sheetHeader.addEventListener('scroll',function () { if (current && current.theme === 'sheet') receiveSheetScroll(sheetHeader); },{passive:true});
    }
    renderEntries(list);
    if (current.theme === 'sheet') {
      if (!sheetResizeListening) { window.addEventListener('resize',syncSheetLayout); sheetResizeListening = true; }
      syncSheetLayout();
    } else if (sheetResizeListening) {
      window.removeEventListener('resize',syncSheetLayout); sheetResizeListening = false;
    }
  }

  function removePresentation() {
    if (sheetResizeListening) { window.removeEventListener('resize',syncSheetLayout); sheetResizeListening = false; }
    if (window.MoaDetailLayout) window.MoaDetailLayout.disable();
    rows.forEach(function (state,row) { restoreRow(row,state); });
    ownedNodes.forEach(function (element) { element.remove(); });
    ownedNodes.clear(); rows.clear(); pageHeader = null; listHeading = null; sheetHeader = null; sheetFormula = null;
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
        suppressSourceDarkMode();
        updateUiLabels();
        updatePresentation();
        observer.observe(root, { childList: true, subtree: true, characterData: true, attributes: true, attributeFilter: ['class','id','src','data-src','href','alt','data-moa-cleanup-active','data-moa-cleanup-hidden'] });
      });
    });
    observer.observe(root, { childList: true, subtree: true, characterData: true, attributes: true, attributeFilter: ['class','id','src','data-src','href','alt','data-moa-cleanup-active','data-moa-cleanup-hidden'] });
  }

  function suppressSourceDarkMode() {
    var siteDark = root.classList.contains('darkmode');
    if (rootDarkOriginal === null || siteDark !== rootDarkCurrent) rootDarkOriginal = siteDark;
    rootDarkCurrent = false;
    // The source night stylesheet inverts the entire article and media again,
    // and overrides authored colors with !important. Our palette already owns
    // night mode; keep that source class inactive until original view returns.
    if (siteDark) root.classList.remove('darkmode');
  }

  function disable(reason) {
    pendingOptions = null;
    current = null;
    if (observer) observer.disconnect();
    if (accentStyle) accentStyle.disabled = true;
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
      if (root.classList.contains('darkmode') === rootDarkCurrent && rootDarkCurrent !== rootDarkOriginal)
        root.classList.toggle('darkmode', rootDarkOriginal);
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
      type === 'write' ? '#writeForm #textbox' : type === 'view' ? '.gallview-tit-box' : type === 'search' ? '.search-wrapping' : '.gall-tit-group'
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
    applyAccent(options);
    suppressSourceDarkMode();
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
    version: '3.0.7', apply: apply, disable: disable,
    presentationState: function () {
      var currentRoot = document.documentElement;
      var style = document.getElementById('moa-skin-style');
      var ready = Boolean(currentRoot && current && lastStatus.applied && !pendingOptions && !waitingForDocument && !queued &&
        document.readyState !== 'loading' && style && style.isConnected && !style.disabled &&
        style.getAttribute('data-moa-owned') === 'true' &&
        currentRoot.getAttribute('data-moa-theme') === current.theme && currentRoot.getAttribute('data-moa-layout') === 'app');
      return {
        ready: ready,
        theme: current ? current.theme : null,
        dark: Boolean(currentRoot && currentRoot.getAttribute('data-moa-dark') === 'true'),
        fontScale: currentRoot ? Number(currentRoot.style.getPropertyValue('--moa-font-scale')) || 1 : 1,
        reason: lastStatus.reason
      };
    },
    status: function () { return Object.assign({}, lastStatus); }
  });
})();
