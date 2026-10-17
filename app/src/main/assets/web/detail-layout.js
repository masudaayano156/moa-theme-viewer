/* Live article/editor layout adapter. Owns only annotations and new UI siblings.
 * Existing content, controls, listeners, advertising and form serialization stay
 * on their original nodes. This module never fetches or submits content. */
(function () {
  'use strict';
  if (window.top !== window || window.MoaDetailLayout) return;
  var root = document.documentElement;
  var changes = new Map();
  var generated = new Set();
  var pieces = new Map();
  var actionObservers = new Map();
  var activePage = null;
  var themes = {
    mail: { article: '받은편지함', compose: '편지쓰기', destination: '받는 라벨', reply: '답장', subject: '제목' },
    post: { article: '게시물', compose: '게시물 작성', destination: '커뮤니티', reply: '답글', subject: '제목' },
    photogram: { article: '게시물', compose: '새 게시물', destination: '게시할 피드', reply: '댓글 달기', subject: '캡션 · 제목' },
    cafe: { article: '게시글', compose: '글쓰기', destination: '게시판', reply: '댓글 쓰기', subject: '제목' },
    sheet: { article: 'A1', compose: '새 행', destination: '시트', reply: '메모', subject: 'A1' },
    ebook: { article: '읽기', compose: '원고 쓰기', destination: '서가', reply: '독서 메모', subject: '장 제목' },
    magazine: { article: 'ARTICLE', compose: '새 이야기', destination: '섹션', reply: '독자 의견', subject: '헤드라인' }
  };
  function annotate(element, name, value) {
    if (!element) return;
    var edits = changes.get(element);
    if (!edits) { edits = new Map(); changes.set(element, edits); }
    var edit = edits.get(name);
    if (!edit) { edit = { before: element.getAttribute(name), value: null }; edits.set(name, edit); }
    else if (element.getAttribute(name) !== edit.value) edit.before = element.getAttribute(name);
    if (element.getAttribute(name) !== value) element.setAttribute(name, value);
    edit.value = value;
  }
  function words(element) { return element ? element.textContent.replace(/\s+/g, ' ').trim() : ''; }
  function text(element, value) { if (element && element.textContent !== value) element.textContent = value; }
  function make(tag, className) {
    var element = document.createElement(tag); element.className = className;
    element.setAttribute('data-moa-detail-owned', 'true'); generated.add(element); return element;
  }
  function piece(key, parent, tag, className, before) {
    var element = pieces.get(key);
    if (!element || !element.isConnected || element.parentElement !== parent) {
      if (element) element.remove();
      element = make(tag, className); parent.insertBefore(element, before || null); pieces.set(key, element);
    }
    return element;
  }
  function icon(kind,theme) {
    var svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('viewBox', '0 0 24 24'); svg.setAttribute('aria-hidden', 'true');
    var path = document.createElementNS(svg.namespaceURI, 'path');
    var shape = { reply: 'M9 5 3 11l6 6M4 11h9a7 7 0 0 1 7 7', like: 'M20.8 4.6a5.4 5.4 0 0 0-7.6 0L12 5.8l-1.2-1.2a5.4 5.4 0 0 0-7.6 7.6L12 21l8.8-8.8a5.4 5.4 0 0 0 0-7.6Z', share: 'm8 7 4-4 4 4M12 3v12M5 11v10h14V11', save: 'M6 3h12v18l-6-4-6 4Z' }[kind];
    if (kind === 'reply' && (theme === 'post' || theme === 'photogram')) shape = 'M21 11.5a8.5 8.5 0 0 1-8.5 8.5H4l1.7-4.2A8.5 8.5 0 1 1 21 11.5Z';
    if (kind === 'share' && theme === 'photogram') shape = 'm22 2-7 20-4-9-9-4 20-7ZM11 13 22 2';
    path.setAttribute('d',shape);
    path.setAttribute('fill','none'); path.setAttribute('stroke','currentColor'); path.setAttribute('stroke-width','1.7');
    path.setAttribute('stroke-linejoin','round'); path.setAttribute('stroke-linecap','round'); svg.appendChild(path); return svg;
  }
  function action(parent, kind, label, source, theme) {
    var key = 'action-' + kind;
    var button = pieces.get(key);
    function stopObserving() { var watcher = actionObservers.get(key); if (watcher) watcher.disconnect(); actionObservers.delete(key); }
    if (!source) { stopObserving(); if (button) button.remove(); pieces.delete(key); return; }
    if (!button || !button.isConnected || button._moaSource !== source) {
      stopObserving();
      if (button) button.remove();
      button = make('button', 'moa-detail-action moa-detail-' + kind); button.type = 'button';
      button._moaSource = source; button._moaIconTheme = theme; button.appendChild(icon(kind,theme)); button.appendChild(document.createElement('span'));
      button.addEventListener('click', function () { if (source.isConnected && !source.disabled && source.getAttribute('aria-disabled') !== 'true') source.click(); });
      parent.appendChild(button); pieces.set(key,button);
      if (kind === 'like') {
        // Observe only the real vote control's enabled state, never the new
        // action strip. This cannot loop with the page/theme DOM observers.
        var syncEnabled = function () { var disabled = !!source.disabled || source.getAttribute('aria-disabled') === 'true'; if (button.disabled !== disabled) button.disabled = disabled; };
        syncEnabled();
        var watcher = new MutationObserver(syncEnabled);
        watcher.observe(source,{attributes:true,attributeFilter:['disabled','aria-disabled']});
        actionObservers.set(key,watcher);
      }
    }
    if (button._moaIconTheme !== theme) { button.replaceChild(icon(kind,theme),button.firstChild); button._moaIconTheme = theme; }
    text(button.lastElementChild,label); button.setAttribute('aria-label',label);
    // Keep only one visible copy of an action while preserving the original
    // node and its site-owned event handler for delegation and source mode.
    if (kind === 'share' || kind === 'save') annotate(source,'data-moa-detail-delegated','true');
    if (kind === 'like') annotate(source,'data-moa-detail-vote-proxy','true');
  }
  function community() { return words(document.querySelector('.gall-tit-lnk')) || words(document.querySelector('.gall-tit')); }
  function article(config,theme) {
    var title = document.querySelector('.gallview-tit-box');
    var body = document.querySelector('.thum-txtin');
    if (!title || !body) return false;
    var heading = piece('article-kicker', title, 'div', 'moa-detail-kicker', title.firstChild);
    text(heading, config.article);
    var meta = title.querySelector('.btm');
    if (meta) {
      var name = words(meta.querySelector('.nick'));
      annotate(meta,'data-moa-author-initial',Array.from(name)[0] || '·');
      var destination = piece('article-destination',meta,'div','moa-detail-destination');
      text(destination,community());
    }
    // Only mark a source media node. We do not clone an image, download another
    // article, reorder authored paragraphs or move a nested advertising slot.
    var media = body.querySelector('img:not(.emoji),video,iframe');
    if (media && !media.closest('[class*="adv-"],ins,[id*="ad_"]')) annotate(media,'data-moa-detail-media','true');
    // A photo caption follows the untouched media/body. Only the title is
    // copied; authored paragraphs, media nodes and their order are preserved.
    var caption = piece('article-caption',body.parentElement,'div','moa-detail-caption',body.nextSibling);
    text(caption,words(title.querySelector('.tit')));
    var stats = document.querySelector('.gall-thum-btm-inner > .ginfo2');
    annotate(stats,'data-moa-detail-stats','true');
    var recommendations = document.querySelector('.reco-area');
    if (recommendations) {
      var tools = piece('article-actions',recommendations,'div','moa-detail-actions',recommendations.firstChild);
      action(tools,'reply',config.reply,document.querySelector('.btn-commentgo'),theme);
      var vote = (theme === 'post' || theme === 'photogram') ? recommendations.querySelector('button#recommend_join') : null;
      action(tools,'like',theme === 'post' ? '마음에 들어요' : '좋아요',vote,theme);
      action(tools,'share','공유',recommendations.querySelector('a.sp-share'),theme);
      action(tools,'save','저장',recommendations.querySelector('a.sp-scrap'),theme);
    }
    document.querySelectorAll('.all-comment-lst > li.comment').forEach(function (comment) {
      annotate(comment,'data-moa-comment-initial',Array.from(words(comment.querySelector('.nick')))[0] || '·');
    });
    var replyForm = document.querySelector('#comment_write');
    if (replyForm) {
      var replyHeading = piece('reply-heading',replyForm,'div','moa-detail-reply-heading',replyForm.firstChild);
      text(replyHeading,config.reply); annotate(replyForm,'data-moa-reply-surface','true');
    }
    return true;
  }
  function compose(config) {
    var form = document.querySelector('#writeForm');
    var editor = form && form.querySelector('#textbox');
    if (!form || !editor) return false;
    var title = document.querySelector('.wrt-tit-box');
    if (title) {
      var composeHeading = piece('compose-heading',title,'div','moa-detail-compose-heading',title.firstChild);
      text(composeHeading,config.compose);
      annotate(title,'data-moa-compose-heading','true');
    }
    var destination = piece('compose-destination',form,'div','moa-detail-compose-destination',form.firstChild);
    var label = destination.querySelector('span');
    var value = destination.querySelector('strong');
    if (!label) { label = document.createElement('span'); value = document.createElement('strong'); destination.appendChild(label); destination.appendChild(value); }
    text(label,config.destination); text(value,community());
    var subject = form.querySelector('li.subject');
    if (subject) {
      var subjectLabel = piece('compose-subject-label',subject,'label','moa-detail-subject-label',subject.firstChild);
      subjectLabel.htmlFor = 'subject'; text(subjectLabel,config.subject);
      annotate(subject,'data-moa-write-role','subject');
    }
    var list = form.querySelector('.wrt-lst');
    if (list) Array.prototype.forEach.call(list.children,function(row) {
      if (row.tagName !== 'LI') return;
      if (row.contains(editor)) annotate(row,'data-moa-write-role','body');
      else if (row.querySelector('.btm-wrt-btns')) annotate(row,'data-moa-write-role','tools');
      else if (row.classList.contains('malwrtline')) annotate(row,'data-moa-write-role','category');
    });
    return true;
  }
  function disable() {
    actionObservers.forEach(function (watcher) { watcher.disconnect(); }); actionObservers.clear();
    generated.forEach(function (element) { element.remove(); }); generated.clear(); pieces.clear();
    changes.forEach(function (edits,element) { edits.forEach(function(edit,key) {
      if (element.getAttribute(key) !== edit.value) return;
      if (edit.before === null) element.removeAttribute(key); else element.setAttribute(key,edit.before);
    }); }); changes.clear(); activePage = null;
  }
  function apply(options) {
    root = document.documentElement;
    if (!root) return false;
    options = options || {}; var theme = options.theme; var page = options.pageType || root.getAttribute('data-moa-page');
    if (!themes[theme] || (page !== 'view' && page !== 'write')) { disable(); return false; }
    if (location.hostname !== 'm.dcinside.com' || location.protocol !== 'https:') { disable(); return false; }
    if (activePage && activePage !== page) disable();
    var supported = page === 'view' ? article(themes[theme],theme) : compose(themes[theme]);
    if (!supported) { disable(); return false; }
    annotate(root,'data-moa-detail',theme); annotate(root,'data-moa-detail-page',page); activePage=page; return true;
  }
  window.MoaDetailLayout = Object.freeze({version:'3.1.0',apply:apply,disable:disable});
})();
