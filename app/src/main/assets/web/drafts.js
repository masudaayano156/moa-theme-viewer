(function () {
  'use strict';
  if (window.top !== window || location.protocol !== 'https:' || location.hostname !== 'm.dcinside.com') return;
  var selectors = ['#writeForm #subject', '#writeForm #textbox', '#comment_write #comment_memo'];
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
    ready.forEach(function (field) {
      var element = document.querySelector(field.selector);
      if (element.isContentEditable) element.innerText = field.text;
      else element.value = field.text;
      element.dispatchEvent(new Event('input', {bubbles:true}));
    });
    return ready.length;
  }
  window.MoaDrafts = {capture:capture,canRestore:function(fields,expected){return restorable(fields,expected).length>0;},restore:restore};
})();
