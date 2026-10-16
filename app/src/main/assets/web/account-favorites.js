/* Read the account's original, rendered favorites UI. No API calls or credentials. */
(function () {
  'use strict';
  if (window.top !== window || location.protocol !== 'https:' || location.hostname !== 'm.dcinside.com' || location.pathname !== '/aside') return;
  if (window.MoaAccountFavorites && window.MoaAccountFavorites.version === '1.0.2') return;
  var observedIdentity = null;
  var identityChanged = false;
  var pendingRender = null;
  function renderedState() {
    var container = document.querySelector('#swipe-favorite');
    return {container:container,nodes:container ? Array.from(container.querySelectorAll('.bookmark-lst a.fx-elp[href],.no-bookmark-lst')) : []};
  }
  function awaitingRender() {
    if (!pendingRender) return false;
    var next = renderedState();
    if (next.container === pendingRender.container && next.nodes.length === pendingRender.nodes.length
        && next.nodes.every(function (node, index) { return node === pendingRender.nodes[index]; })) return true;
    pendingRender = null;
    return false;
  }
  function getIdentity() {
    var identity = document.querySelector('#user_id');
    return identity ? String(identity.value || '').trim() : '';
  }
  function identityState() {
    var identity = getIdentity();
    if (observedIdentity === null) observedIdentity = identity;
    else if (identity !== observedIdentity) identityChanged = true;
    if (!identity) return 'signedOut';
    // Once the document changes account, old rendered bookmarks cannot be
    // attributed safely. The host must load a fresh account page to retry.
    return identityChanged ? 'error' : null;
  }
  function read() {
    var state = identityState();
    if (state) return {state:state,items:[]};
    if (awaitingRender()) return {state:'pending',items:[]};
    var items = [];
    var seen = new Set();
    document.querySelectorAll('#swipe-favorite .bookmark-lst a.fx-elp[href]').forEach(function (link) {
      try {
        var url = new URL(link.getAttribute('href'), location.href);
        if (url.protocol !== 'https:' || url.hostname !== 'm.dcinside.com' || url.username || url.password || url.port) return;
        if (!/^\/(board|mini|person)\/[A-Za-z0-9_]{1,80}\/?$/.test(url.pathname)) return;
        var title = link.querySelector('.fx-elpin');
        var name = String((title || link).textContent || '').trim().slice(0,200);
        var canonicalPath = url.pathname.replace(/\/$/, '');
        if (!name || seen.has(canonicalPath) || items.length >= 300) return;
        seen.add(canonicalPath);
        items.push({name:name,url:url.origin+canonicalPath});
      } catch (ignored) { }
    });
    if (items.length) return {state:'ready',items:items};
    if (document.querySelector('#swipe-favorite .no-bookmark-lst')) return {state:'emptyOrUnavailable',items:[]};
    return {state:'pending',items:[]};
  }
  function begin() {
    var state = identityState();
    if (state) return {state:state,items:[]};
    if (awaitingRender()) return {state:'pending',items:[]};
    if (typeof window.callBookmark !== 'function') return {state:'error',items:[]};
    // The original async UI keeps old rows/empty markers until the response.
    // Wait for its rendered nodes to change, without clearing site-owned DOM.
    pendingRender = renderedState();
    try { window.callBookmark(); }
    catch (ignored) { pendingRender = null; return {state:'error',items:[]}; }
    return read();
  }
  window.MoaAccountFavorites = Object.freeze({version:'1.0.2',begin:begin,read:read,getIdentity:getIdentity});
})();
