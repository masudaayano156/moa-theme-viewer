/* Hide verified service advertising and unrelated footer discovery modules.
 * This local presentation preference does not fetch pages, click ads, intercept
 * requests, or rewrite user content. It also applies in the original view.
 */
(function () {
  'use strict';
  if (window.top !== window) return;
  if (window.MoaPageCleanup && window.MoaPageCleanup.version === '1.0.1') {
    window.MoaPageCleanup.apply();
    return;
  }
  if (window.MoaPageCleanup && typeof window.MoaPageCleanup.disable === 'function') {
    window.MoaPageCleanup.disable();
  }

  var marker = 'data-moa-cleanup-hidden';
  var rootMarker = 'data-moa-cleanup-active';
  var protectedAreas = [
    '.thum-txt', '.thum-txtin', '.all-comment-lst', '.gall-detail-lnktb',
    '.moa-entry', '.gallview-tit-box', '#auto_picture_area', '#textbox',
    '[contenteditable]', 'form', '.capcha-code-box', '.upload-wrap',
    '.upload-img-lst', '.voice-player', '.video-player', 'audio', 'video'
  ];
  var protectedSelector = protectedAreas.join(',');
  // Exact service classes and slot IDs, including IDs that survive the site's
  // own fallback from a power link to a banner. adv-groupno is deliberately not
  // an ad selector: the source also uses it for an author's automatic picture.
  var adSelectors = [
    '.adv-group', '.adv-groupin', '.adv-grouptop',
    '.gall-detail-lst > li.adv-inner', '.gall-thum-lst > li.adv-inner',
    '#ads_list_top', '#ads_list_bottom', '#adv_group_pwlink',
    '#view_middle_area', '#list_top', '#view_top', '#pwlink_a',
    '#bottom_pwlink_a', '#content_middle_pwlink_a', '#pwlink_view_bottom',
    '#footer_bottom_overlay', '#kakao_backup_slot_viewtop',
    '#kakao_backup_slot_viewbottom', '#kakao_backup_slot_bottom',
    '#kakao_backup_slot_anchor', '#kakao_backup_slot_contentmiddle',
    '#kakao_backup_slot_mid'
  ];
  // AdFit inserts its own INS container and nested iframe, including inside
  // the article wrapper. Hide that exact advertising unit, never its article
  // parent or an arbitrary iframe (which may be the author's video).
  var embeddedAdSelector = 'ins.kakao_ad_area[data-ad-unit]';
  var editorSelector = '#textbox,[contenteditable],form,.capcha-code-box,' +
    '.upload-wrap,.upload-img-lst';
  var extraSelectors = ['.view-btm-con', '.outside-search-box'];
  var hidden = new Map();
  var style = null;
  var observer = null;
  var active = false;
  var scheduled = false;
  var timer = null;
  var listening = false;

  function supported() {
    return location.protocol === 'https:' && location.hostname === 'm.dcinside.com' &&
      (!location.port || location.port === '443') && !location.username && !location.password &&
      (/^\/(?:board|mini|person)\/[a-zA-Z0-9_]+(?:\/\d+)?\/?$/.test(location.pathname) ||
       /^\/write\/[a-zA-Z0-9_]+(?:\/(?:modify\/)?\d+)?\/?$/.test(location.pathname) ||
       /^\/modify\/[a-zA-Z0-9_]+(?:\/\d+)?\/?$/.test(location.pathname) ||
       /^\/search\/?$/.test(location.pathname));
  }

  function safe(node) {
    return node && node.nodeType === 1 && node.ownerDocument === document &&
      node.isConnected && !node.closest(protectedSelector) &&
      !node.querySelector(protectedSelector);
  }

  function embeddedAd(node) {
    return node && node.nodeType === 1 && node.ownerDocument === document &&
      node.isConnected && node.matches(embeddedAdSelector) &&
      /^DAN-[a-zA-Z0-9_-]+$/.test(node.getAttribute('data-ad-unit') || '') &&
      !node.closest(editorSelector) && !node.querySelector(editorSelector);
  }

  function valid(node, kind) {
    return kind === 'external' ? externalSection(node) :
      kind === 'embedded-ad' ? embeddedAd(node) : safe(node);
  }

  function css() {
    // Install at document start. The marker rule works on older WebViews too;
    // the structural rules avoid a first paint of known slots on current ones.
    var exclusions = protectedAreas.map(function (selector) {
      return ':not(' + selector + '):not(' + selector + ' *)';
    }).join('') + ':not(:has(' + protectedSelector + '))';
    var selectors = adSelectors.concat(extraSelectors).map(function (selector) {
      return 'html[' + rootMarker + '="1"] ' + selector + exclusions;
    });
    return 'html[' + rootMarker + '="1"] [' + marker + '="1"]{' +
      'display:none!important;min-height:0!important;height:0!important;' +
      'margin:0!important;padding:0!important;border:0!important;}' +
      selectors.join(',') + '{display:none!important;}';
  }

  function installStyle() {
    if (!document.documentElement) return;
    // The site's Swiper observes ancestor attributes. Even writing the same
    // value triggers it, then its class updates would trigger our observer.
    if (document.documentElement.getAttribute(rootMarker) !== '1') {
      document.documentElement.setAttribute(rootMarker, '1');
    }
    if (!style || !style.isConnected) {
      style = document.createElement('style');
      style.setAttribute('data-moa-page-cleanup', 'true');
      style.textContent = css();
      (document.head || document.documentElement).appendChild(style);
    }
  }

  function restore(node, record) {
    if (node.style.getPropertyValue('display') === 'none' &&
        node.style.getPropertyPriority('display') === 'important') {
      if (record.display) node.style.setProperty('display', record.display, record.priority);
      else node.style.removeProperty('display');
    }
    if (node.getAttribute(marker) === '1') {
      if (record.marker === null) node.removeAttribute(marker);
      else node.setAttribute(marker, record.marker);
    }
    hidden.delete(node);
  }

  function hide(node, kind) {
    if (!valid(node, kind)) return;
    var record = hidden.get(node);
    var display = node.style.getPropertyValue('display');
    var priority = node.style.getPropertyPriority('display');
    if (!record) {
      record = { display: display, priority: priority, marker: node.getAttribute(marker), kind: kind };
      hidden.set(node, record);
    } else if (display !== 'none' || priority !== 'important') {
      // Remember a legitimate source update so disable never rolls it back.
      record.display = display;
      record.priority = priority;
    }
    if (node.getAttribute(marker) !== '1') node.setAttribute(marker, '1');
    if (display !== 'none' || priority !== 'important') node.style.setProperty('display', 'none', 'important');
  }

  function inert(node) {
    return node.nodeType === 8 || (node.nodeType === 3 && !node.data.trim()) ||
      (node.nodeType === 1 && /^(SCRIPT|STYLE|NOSCRIPT|TEMPLATE)$/.test(node.tagName));
  }

  function collapseSection(node) {
    var parent = node.parentElement;
    if (!parent || !parent.matches('section.grid') || !safe(parent)) return;
    var children = Array.prototype.slice.call(parent.childNodes);
    if (children.every(function (child) {
      return inert(child) || child === node || (child.nodeType === 1 && hidden.has(child));
    })) hide(parent, 'wrapper');
  }

  function scan() {
    if (timer !== null) clearTimeout(timer);
    timer = null;
    scheduled = false;
    if (!active) return;
    if (!supported()) { disable(); return; }
    if (observer) observer.disconnect();
    try {
      installStyle();
      // If the source moves a previously recognized slot into authored content,
      // stop styling it. Disconnected nodes are released rather than retained.
      hidden.forEach(function (record, node) {
        var keep = valid(node, record.kind);
        if (keep && record.kind === 'wrapper') {
          keep = Array.prototype.every.call(node.childNodes, function (child) {
            return inert(child) || (child.nodeType === 1 && hidden.has(child));
          });
        }
        if (!keep) restore(node, record);
        else hide(node, record.kind);
      });
      document.querySelectorAll(adSelectors.join(',')).forEach(function (node) {
        hide(node);
        if (hidden.has(node)) collapseSection(node);
      });
      document.querySelectorAll(embeddedAdSelector).forEach(function (node) {
        hide(node, 'embedded-ad');
      });
      document.querySelectorAll('#notice_list.detail-top-lst > li').forEach(function (row) {
        if (row.querySelector('.detail-top-lnk > a > .ntc-line-ad')) hide(row);
      });
      document.querySelectorAll('.view-btm-con').forEach(function (node) {
        if (!safe(node) || !node.querySelector('#bottom_contentbox')) return;
        hide(node);
        collapseSection(node);
      });
      document.querySelectorAll('.outside-search-box').forEach(function (node) {
        // The external search has its own source form, unlike an ad slot.
        // Do not treat the community search (.con-search-box) as an extra.
        var section = node.parentElement;
        if (externalSection(section)) hide(section, 'external');
      });
    } finally {
      if (active && observer) observe();
    }
  }

  function externalSection(section) {
    if (!section || !section.isConnected || !section.matches('section.grid') ||
        section.closest(protectedSelector)) return false;
    var node = section.querySelector(':scope > .outside-search-box');
    var title = section.querySelector(':scope > .md-tit-box');
    if (!node || !title || !title.querySelector('.daumtxt') || !node.querySelector('.sp-daum-search')) return false;
    // The verified external-search form is intentionally removable; a login,
    // upload, editor, or community-search form in that section is not.
    var forbidden = '.con-search-box,#writeForm,#comment_write,input[type="password"],' +
      'input[type="file"],textarea,[contenteditable],.capcha-code-box,.thum-txt,.all-comment-lst';
    if (section.querySelector(forbidden)) return false;
    // Use an exact wrapper with only the source heading and external search.
    return Array.prototype.every.call(section.childNodes, function (child) {
      return inert(child) || child === title || child === node;
    });
  }

  function queue() {
    if (!active || scheduled) return;
    scheduled = true;
    // Yield to input and rendering even if a source script keeps refreshing
    // an advertising slot; never form an unbounded microtask chain.
    timer = setTimeout(scan, 0);
  }

  function observe() {
    observer.observe(document, { childList: true, subtree: true, attributes: true,
      attributeFilter: ['class', 'id', 'style', marker, 'data-ad-unit'] });
  }

  function apply() {
    if (!supported()) { disable(); return false; }
    active = true;
    if (!observer) {
      observer = new MutationObserver(queue);
    }
    if (!listening) {
      window.addEventListener('popstate', apply);
      window.addEventListener('hashchange', apply);
      listening = true;
    }
    scan();
    return true;
  }

  function disable() {
    active = false;
    if (timer !== null) clearTimeout(timer);
    timer = null;
    scheduled = false;
    if (observer) observer.disconnect();
    observer = null;
    if (listening) {
      window.removeEventListener('popstate', apply);
      window.removeEventListener('hashchange', apply);
      listening = false;
    }
    hidden.forEach(function (record, node) { restore(node, record); });
    if (style) style.remove();
    style = null;
    if (document.documentElement) document.documentElement.removeAttribute(rootMarker);
  }

  window.MoaPageCleanup = Object.freeze({ version: '1.0.1', apply: apply, disable: disable });
  apply();
})();
