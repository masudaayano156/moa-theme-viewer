(function () {
  'use strict';
  if (window.top !== window || location.protocol !== 'https:' || location.hostname !== 'm.dcinside.com') return;
  // Re-injection on a theme change must not turn the current draft into its own baseline.
  if (window.MoaDrafts && window.MoaDrafts.version === 2) return;
  var selectors = ['#writeForm #subject', '#writeForm #textbox', '#comment_write #comment_memo'];
  var baseline = null, interacted = false, startedEarly = document.readyState === 'loading';
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
  }
  function trackInteraction(event) {
    var target = event.target;
    if (!target || !target.closest || !target.closest('#writeForm, #comment_write') && target.type !== 'file') return;
    if (event.type === 'focusin') {
      if (!interacted && startedEarly) baseline = editorSnapshot();
      return;
    }
    if (event.type === 'click' && !target.closest('button,a,[role="button"],input[type="button"],input[type="submit"],input[type="file"]')) return;
    // Capture the settled original immediately before the user's first edit or
    // toolbar action. The site's late initialization is not an unsaved change.
    if (!interacted) {
      if (/^(beforeinput|paste|drop|click)$/.test(event.type)) baseline = editorSnapshot();
      else if (!event.isTrusted && startedEarly) return;
      else if (!baseline) baseline = editorSnapshot();
    }
    interacted = true;
  }
  ['focusin', 'beforeinput', 'input', 'change', 'paste', 'drop', 'click'].forEach(function (name) {
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
    var present = false, fields = [];
    selectors.forEach(function (selector) {
      var element = document.querySelector(selector);
      if (!element || element.getAttribute('type') === 'password' || !element.getClientRects().length) return;
      present = true;
      var value = element.isContentEditable ? element.innerText : element.value;
      if (typeof value === 'string' && value.trim()) fields.push({selector:selector,text:value.slice(0,100000),context:fieldContext(element)});
    });
    return {eligible:present, fields:fields};
  }
  function restorable(fields, expected) {
    if (!onExpectedPage(expected) || !Array.isArray(fields)) return [];
    return fields.filter(function (field) {
      if (!field || selectors.indexOf(field.selector) < 0 || typeof field.text !== 'string') return false;
      var element = document.querySelector(field.selector);
      if (!element || !element.getClientRects().length || element.getAttribute('type') === 'password') return false;
      if (field.context !== fieldContext(element)) return false;
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
      if (element.isContentEditable) element.innerText = field.text;
      else element.value = field.text;
      element.dispatchEvent(new Event('input', {bubbles:true}));
    });
    return ready.length;
  }
  window.MoaDrafts = {version:2,capture:capture,canRestore:function(fields,expected){return restorable(fields,expected).length>0;},restore:restore,
    navigationState:navigationState};
})();
