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
  var activePage = null;
  var themes = {
    mail: { article: '대화', compose: '새 메시지', destination: '받는 주제함', reply: '답장', subject: '제목' },
    post: { article: '포스트', compose: '포스트 작성', destination: '게시할 채널', reply: '답글', subject: '첫 문장 · 제목' },
    photogram: { article: '게시물', compose: '새 게시물', destination: '게시할 피드', reply: '댓글 달기', subject: '캡션 · 제목' },
    cafe: { article: '게시글', compose: '글쓰기', destination: '게시판', reply: '댓글 쓰기', subject: '제목' },
    sheet: { article: '레코드', compose: '새 레코드', destination: '시트', reply: '메모', subject: 'A · 제목' },
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
  function icon(kind) {
    var svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('viewBox', '0 0 24 24'); svg.setAttribute('aria-hidden', 'true');
    var path = document.createElementNS(svg.namespaceURI, 'path');
    path.setAttribute('d', { reply: 'M9 5 3 11l6 6M4 11h9a7 7 0 0 1 7 7', share: 'm8 7 4-4 4 4M12 3v12M5 11v10h14V11', save: 'M6 3h12v18l-6-4-6 4Z' }[kind]);
    path.setAttribute('fill','none'); path.setAttribute('stroke','currentColor'); path.setAttribute('stroke-width','1.7');
    path.setAttribute('stroke-linejoin','round'); path.setAttribute('stroke-linecap','round'); svg.appendChild(path); return svg;
  }
  function action(parent, kind, label, source) {
    var key = 'action-' + kind;
    var button = pieces.get(key);
    if (!source) { if (button) button.remove(); pieces.delete(key); return; }
    if (!button || !button.isConnected || button._moaSource !== source) {
      if (button) button.remove();
      button = make('button', 'moa-detail-action moa-detail-' + kind); button.type = 'button';
      button._moaSource = source; button.appendChild(icon(kind)); button.appendChild(document.createElement('span'));
      button.addEventListener('click', function () { if (source.isConnected && !source.disabled) source.click(); });
      parent.appendChild(button); pieces.set(key,button);
    }
    text(button.lastElementChild,label); button.setAttribute('aria-label',label);
  }
  function community() { return words(document.querySelector('.gall-tit-lnk')) || words(document.querySelector('.gall-tit')); }
  function article(config) {
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
    var stats = document.querySelector('.gall-thum-btm-inner > .ginfo2');
    annotate(stats,'data-moa-detail-stats','true');
    var recommendations = document.querySelector('.reco-area');
    if (recommendations) {
      var tools = piece('article-actions',recommendations,'div','moa-detail-actions',recommendations.firstChild);
      action(tools,'reply',config.reply,document.querySelector('.btn-commentgo'));
      action(tools,'share','공유',recommendations.querySelector('a.sp-share'));
      action(tools,'save','저장',recommendations.querySelector('a.sp-scrap'));
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
    var supported = page === 'view' ? article(themes[theme]) : compose(themes[theme]);
    if (!supported) { disable(); return false; }
    annotate(root,'data-moa-detail',theme); annotate(root,'data-moa-detail-page',page); activePage=page; return true;
  }
  window.MoaDetailLayout = Object.freeze({version:'3.0.0',apply:apply,disable:disable});
})();
