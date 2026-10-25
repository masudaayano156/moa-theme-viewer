(function () {
  'use strict';
  if (window.top !== window || location.protocol !== 'https:' || location.hostname !== 'm.dcinside.com') return;
  // Re-injection on a theme change must not turn the current draft into its own baseline.
  if (window.MoaDrafts && window.MoaDrafts.version === 3) return;
  var selectors = ['#writeForm #subject', '#writeForm #textbox', '#comment_write #comment_memo'];
  var baseline = null, interacted = false, startedEarly = document.readyState === 'loading';
  var originals = new WeakMap(), activeComment = null, requestedComment = '', keyboardFocus = null;
  function textValue(element) {
    return String(element.isContentEditable ? element.innerText : element.value || '');
  }
  function editorTarget(target) {
    if (!target || !target.closest) return null;
    return target.closest(selectors.join(','));
  }
  function originalFor(element, refresh) {
    var context = fieldContext(element), original = originals.get(element);
    if (!original || original.context !== context) {
      if (original && activeComment && activeComment.element === element) { activeComment = null; requestedComment = ''; }
      original = {context:context, text:textValue(element), authored:false};
      originals.set(element, original);
    } else if (refresh && !original.authored) original.text = textValue(element);
    return original;
  }
  function activateComment(element, recheck) {
    if (!element || element.id !== 'comment_memo' || !element.getClientRects().length) return;
    var context = fieldContext(element);
    activeComment = {element:element, context:context};
    // Reading a post is not an invitation to restore its comment. Ask the native
    // host to recheck only when the user actually enters this comment editor.
    if (!recheck && requestedComment === context) return;
    requestedComment = context;
    setTimeout(function () {
      if (!element.isConnected || fieldContext(element) !== context) return;
      try {
        if (window.MoaLocalActions) window.MoaLocalActions.postMessage(JSON.stringify({action:'draft-editor',url:location.href}));
      } catch (_) { }
    }, 0);
  }
  function editorSnapshot() {
    var fields = [], present = false;
    selectors.forEach(function (selector) {
      var element = document.querySelector(selector);
      if (!element || !element.getClientRects().length) return;
      present = true;
      // Read only. Never rewrite the site's editor or its upload/serialization fields.
      fields.push([selector, element.isContentEditable ? element.innerHTML : String(element.value || '')]);
    });
    var files = [];
    if (document.querySelector('#writeForm, #comment_write')) {
      document.querySelectorAll('input[type="file"]').forEach(function (input) {
        Array.prototype.forEach.call(input.files || [], function (file) {
          files.push([input.id || input.name, file.name, file.size, file.type, file.lastModified]);
        });
      });
    }
    return {present:present, signature:JSON.stringify([fields, files]), files:files.length};
  }
  function establishBaseline() {
    if (!interacted) baseline = editorSnapshot();
    selectors.forEach(function (selector) {
      var element = document.querySelector(selector);
      if (element) originalFor(element, true);
    });
  }
  function trackInteraction(event) {
    var target = event.target;
    // Site initialization and synthetic input events must not claim ownership of
    // prefilled article text, a template, or the site's own comment state.
    if (!event.isTrusted) return;
    var editor = editorTarget(target);
    if (event.type === 'keydown') {
      keyboardFocus = event.key === 'Tab' ? event : null;
      if (keyboardFocus) {
        var navigation = keyboardFocus;
        setTimeout(function () { if (keyboardFocus === navigation) keyboardFocus = null; }, 0);
      } else if (editor) activateComment(editor);
      return;
    }
    var tab = null;
    // Consume keyboard authorization on the very next focus transition, even
    // outside a form. A modal's trapped Tab must not authorize its later focus
    // restoration into a comment editor.
    if (event.type === 'focusin') { tab = keyboardFocus; keyboardFocus = null; }
    if (event.type === 'pointerdown') keyboardFocus = null;
    if (!target || !target.closest || !target.closest('#writeForm, #comment_write') && target.type !== 'file') return;
    if (event.type === 'focusin') {
      if (!interacted && startedEarly) baseline = editorSnapshot();
      if (editor) {
        originalFor(editor, true);
        if (tab) {
          var context = fieldContext(editor);
          setTimeout(function () {
            if (!tab.defaultPrevented && document.activeElement === editor && editor.isConnected && fieldContext(editor) === context) activateComment(editor, true);
          }, 0);
        }
      }
      return;
    }
    if (event.type === 'pointerdown') {
      if (editor) originalFor(editor, true);
      return;
    }
    // A previous request may have raced an Activity pause. Explicit new entry
    // can retry; native code suppresses only a prompt actually shown here.
    if (event.type === 'click' && editor) { activateComment(editor, true); return; }
    if (event.type === 'click' && !target.closest('button,a,[role="button"],input[type="button"],input[type="submit"],input[type="file"]')) return;
    // Capture the settled original immediately before the user's first edit or
    // toolbar action. The site's late initialization is not an unsaved change.
    if (!interacted) {
      if (/^(beforeinput|paste|drop|click)$/.test(event.type)) baseline = editorSnapshot();
      else if (!baseline) baseline = editorSnapshot();
    }
    interacted = true;
    if (editor && /^(beforeinput|input|change|paste|drop)$/.test(event.type)) {
      var edited = originalFor(editor, /^(beforeinput|paste|drop)$/.test(event.type));
      edited.authored = true; edited.toolbarOnly = false;
    } else if (event.type === 'click' && target.closest('#writeForm') && !target.closest('input[type="submit"],button[type="submit"]')) {
      var body = document.querySelector('#writeForm #textbox');
      if (body) {
        var originalBody = originalFor(body, true);
        if (!originalBody.authored) originalBody.toolbarOnly = true;
        originalBody.authored = true;
      }
    } else if (event.type === 'click' && target.closest('[data-moa-community-owned="tools"] .moa-community-affix')) {
      // The user's affix button changes the main comment through synthetic
      // input events. Retain the settled original before its programmatic focus
      // event, which would otherwise replace the original with the new text.
      var comment = target.closest('#comment_write').querySelector('#comment_memo');
      if (comment) {
        var original = originalFor(comment, true), wasAuthored = original.authored;
        original.authored = true;
        setTimeout(function () {
          // A cancelled or over-limit insertion must preserve any pending draft.
          if (originals.get(comment) === original && textValue(comment) === original.text) original.authored = wasAuthored;
        }, 0);
      }
    }
  }
  ['focusin', 'pointerdown', 'keydown', 'beforeinput', 'input', 'change', 'paste', 'drop', 'click'].forEach(function (name) {
    document.addEventListener(name, trackInteraction, true);
  });
  if (startedEarly) document.addEventListener('DOMContentLoaded', establishBaseline, {once:true});
  else establishBaseline();
  window.addEventListener('load', establishBaseline, {once:true});
  function navigationState(expected) {
    if (pageKey(location.href) !== pageKey(expected)) return {state:'unknown'};
    if (!/^\/(?:write|modify|board|mini|person)\//.test(location.pathname)) return {state:'clean'};
    var current = editorSnapshot();
    if (!current.present) {
      return {state:/^\/(?:write|modify)\//.test(location.pathname) ? 'unknown' : 'clean'};
    }
    var edit = /(?:^\/modify\/|\/modify\/)/.test(location.pathname) || /(?:^|[?&])(?:mode|action)=modify(?:&|$)/.test(location.search);
    if (current.files) return {state:'dirty'};
    if (edit && startedEarly && !interacted) baseline = current;
    if (edit && baseline && current.signature !== baseline.signature) return {state:'dirty'};
    // A late installation cannot prove that an existing edit form was untouched.
    if (edit && !startedEarly) return {state:'unknown'};
    if (edit) return {state:'clean'};
    var text = capture(expected);
    if (text.fields.length || current.files) return {state:'dirty'};
    var body = document.querySelector('#writeForm #textbox');
    if (body && body.querySelector('img,video,audio,iframe,canvas,object')) return {state:'dirty'};
    // Older WebView providers may install this helper after the user could type.
    // Do not call pre-existing text user-authored, but retain the navigation guard.
    if (!startedEarly && !text.eligible && /^\/(?:write|modify)\//.test(location.pathname) && selectors.some(function (selector) {
      var element = document.querySelector(selector);
      return element && element.getClientRects().length && textValue(element).trim();
    })) return {state:'unknown'};
    return {state:'clean'};
  }
  function pageKey(value) {
    try { var url = new URL(value); return url.origin + url.pathname; } catch (_) { return ''; }
  }
  function onExpectedPage(expected) {
    return pageKey(location.href) === pageKey(expected) && /^\/(?:write|modify|board|mini|person)\//.test(location.pathname);
  }
  function fieldContext(element) {
    var form = element.closest('form');
    if (!form) return '';
    var parent = form.closest('li[id]');
    var replyFields = ['reply_no', 'parent_no', 're_no', 'c_no', 'r_no', 'comment_no'];
    var documentReply = form.id === 'comment_write' ? ['comment_no', 'reple_id'].map(function (id) {
      var field = document.getElementById(id);
      return field ? String(field.value || '') : '';
    }) : [];
    return JSON.stringify([form.id, form.getAttribute('onsubmit') || '', parent ? parent.id : '', documentReply, replyFields.map(function (name) {
      var field = form.querySelector('input[name="' + name + '"]');
      return field ? String(field.value || '') : '';
    })]);
  }
  function capture(expected) {
    if (!onExpectedPage(expected)) return {eligible:false, fields:[]};
    var authored = false, fields = [];
    selectors.forEach(function (selector) {
      var element = document.querySelector(selector);
      if (!element || element.getAttribute('type') === 'password' || !element.getClientRects().length) return;
      var original = originalFor(element, false), value = textValue(element);
      if (!original.authored) return;
      // A toolbar can open a picker or settings without changing the body.
      // Keep its original until a delayed insertion arrives, but do not export
      // an unchanged empty snapshot that would erase a pending saved draft.
      if (original.toolbarOnly && value === original.text) return;
      original.toolbarOnly = false;
      authored = true;
      if (value.trim() && value !== original.text) fields.push({selector:selector,text:value.slice(0,100000),context:original.context});
    });
    // An untouched empty editor must preserve a pending saved draft. A user who
    // clears their own text instead produces an eligible empty snapshot, removing it.
    return {eligible:authored, fields:fields};
  }
  function restorable(fields, expected) {
    if (!onExpectedPage(expected) || !Array.isArray(fields)) return [];
    return fields.filter(function (field) {
      if (!field || selectors.indexOf(field.selector) < 0 || typeof field.text !== 'string' || !field.text.trim()) return false;
      var element = document.querySelector(field.selector);
      if (!element || !element.getClientRects().length || element.getAttribute('type') === 'password') return false;
      if (field.context !== fieldContext(element)) return false;
      if (element.id === 'comment_memo' && (!activeComment || activeComment.element !== element || activeComment.context !== field.context)) return false;
      var current = element.isContentEditable ? element.innerText : element.value;
      if (current && String(current).trim()) return false;
      return !element.isContentEditable || !element.querySelector('img,video,audio,iframe,canvas,object,input');
    });
  }
  function restore(fields, expected) {
    var ready = restorable(fields, expected);
    if (ready.length && !interacted) { baseline = editorSnapshot(); interacted = true; }
    ready.forEach(function (field) {
      var element = document.querySelector(field.selector);
      var original = originalFor(element, false);
      original.text = textValue(element);
      original.authored = true; original.toolbarOnly = false;
      if (element.isContentEditable) element.innerText = field.text;
      else element.value = field.text;
      element.dispatchEvent(new Event('input', {bubbles:true}));
    });
    return ready.length;
  }
  window.MoaDrafts = {version:3,capture:capture,canRestore:function(fields,expected){return restorable(fields,expected).length>0;},restore:restore,
    navigationState:navigationState};
})();
