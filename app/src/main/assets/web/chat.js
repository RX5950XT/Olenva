// Chat transcript: Kotlin pushes the full message list via Olenva.setMessages(); we diff per message
// and morph only what changed, so streaming never re-lays out earlier messages or drops a text selection.
(function () {
  'use strict';
  var R = window.OlenvaRender;
  var esc = R.esc;
  var bridge = window.Android || { copy: function () {}, action: function () {}, openImage: function () {}, openLink: function () {}, onMath: function () {}, ready: function () {} };
  var list = document.getElementById('list');
  var jump = document.getElementById('jump');
  var last = new Map();      // id -> serialized message last rendered
  var queued = null, forceScroll = false, rafPending = false;

  var ICON = {
    copy: '<path d="M9 9h10v10H9z"/><path d="M5 15V5h10"/>',
    regen: '<path d="M20 11a8 8 0 1 0-2.3 5.7"/><path d="M20 5v6h-6"/>',
    more: '<circle cx="5" cy="12" r="1.2"/><circle cx="12" cy="12" r="1.2"/><circle cx="19" cy="12" r="1.2"/>',
    edit: '<path d="M4 20h4L19 9l-4-4L4 16z"/>',
    file: '<path d="M7 3h7l5 5v13H7z"/><path d="M14 3v5h5"/>'
  };
  function icon(name) {
    return '<svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">' + ICON[name] + '</svg>';
  }
  function btn(act, name, label) {
    return '<button type="button" class="act" data-act="' + act + '" aria-label="' + esc(label) + '">' + icon(name) + '</button>';
  }

  function attachmentsHtml(m) {
    if (!m.attachments || !m.attachments.length) return '';
    var imgs = '', files = '';
    m.attachments.forEach(function (a, i) {
      if (a.kind === 'IMAGE') {
        imgs += '<button type="button" class="thumb" data-act="image" data-index="' + i + '"><img src="' + esc(a.url) + '" alt="' + esc(a.name) + '" loading="lazy"></button>';
      } else {
        files += '<button type="button" class="file" data-act="image" data-index="' + i + '">' + icon('file') + '<span><b>' + esc(a.name) + '</b><small>' + esc(a.meta || '') + '</small></span></button>';
      }
    });
    return '<div class="atts">' + imgs + files + '</div>';
  }

  function messageHtml(m) {
    var S = R.strings;
    var streaming = m.status === 'streaming';
    if (m.role === 'user') {
      return '<article class="msg user" id="m-' + m.id + '" data-id="' + m.id + '">' + attachmentsHtml(m) +
        (m.content ? '<div class="bubble">' + esc(m.content) + '</div>' : '') +
        '<div class="actions">' + btn('copy', 'copy', S.copy) + btn('edit', 'edit', S.edit) + btn('more', 'more', S.more) + '</div></article>';
    }
    var h = '<article class="msg ai" id="m-' + m.id + '" data-id="' + m.id + '">';
    if (m.reasoning) {
      var thinking = streaming && !m.content;
      h += '<details class="think"' + (thinking ? ' open' : '') + '><summary>' + esc(thinking ? S.thinking : S.thoughts) + '</summary><div class="think-body md">' + R.render(m.reasoning, thinking) + '</div></details>';
    }
    if (m.content) h += '<div class="md">' + R.render(m.content, streaming) + '</div>';
    else if (streaming && !m.reasoning) h += '<p class="waiting"><span></span><span></span><span></span></p>';
    if (m.notice) h += '<p class="notice">' + esc(m.notice) + '</p>';
    if (m.status === 'error') {
      h += '<div class="error" role="alert"><p>' + esc(m.error || '') + '</p><button type="button" class="retry" data-act="retry">' + esc(S.retry) + '</button></div>';
    }
    if (!streaming) {
      h += '<div class="actions">' + btn('copy', 'copy', S.copy) + btn('regen', 'regen', S.regenerate) + btn('more', 'more', S.more) +
        (m.meta ? '<span class="meta">' + esc(m.meta) + '</span>' : '') + '</div>';
    }
    return h + '</article>';
  }

  function atBottom() {
    return window.innerHeight + window.scrollY >= document.documentElement.scrollHeight - 48;
  }

  function apply(msgs) {
    var stick = forceScroll || atBottom();
    var seen = new Set();
    var prev = null;
    msgs.forEach(function (m) {
      seen.add(m.id);
      var key = JSON.stringify(m);
      var el = document.getElementById('m-' + m.id);
      if (!el || last.get(m.id) !== key) {
        var tpl = document.createElement('template');
        tpl.innerHTML = messageHtml(m);
        var fresh = tpl.content.firstElementChild;
        if (el) {
          window.morphdom(el, fresh, {
            onBeforeElUpdated: function (from, to) { return !from.isEqualNode(to); }
          });
        } else {
          el = fresh;
        }
        last.set(m.id, key);
      }
      var anchor = prev ? prev.nextSibling : list.firstChild;
      if (el !== anchor) list.insertBefore(el, anchor);
      prev = el;
    });
    Array.prototype.slice.call(list.children).forEach(function (el) {
      if (!seen.has(el.dataset.id)) { last.delete(el.dataset.id); el.remove(); }
    });
    if (stick) window.scrollTo(0, document.documentElement.scrollHeight);
    forceScroll = false;
    updateJump();
    markOverflow();
  }

  // Wide formulas, tables and code scroll sideways; fade the cut edge so it is clear there is more.
  var SCROLLERS = '.math-block, .table-wrap, .code pre, .math-raw.block';
  function edgeState(el) {
    var more = el.scrollWidth - el.clientWidth > 2;
    el.classList.toggle('more-right', more && el.scrollLeft + el.clientWidth < el.scrollWidth - 2);
    el.classList.toggle('more-left', more && el.scrollLeft > 2);
  }
  function markOverflow() { list.querySelectorAll(SCROLLERS).forEach(edgeState); }
  document.addEventListener('scroll', function (e) {
    var t = e.target;
    if (t.nodeType === 1 && t.matches(SCROLLERS)) edgeState(t);
  }, { capture: true, passive: true });

  // A quick flick right opens history. Only a fast, clearly sideways flick counts, so scrolling or a slow
  // sideways pan never opens it, and nothing started on a wide formula/table/code block does.
  var sx = 0, sy = 0, st = 0, swipe = false;
  document.addEventListener('touchstart', function (e) {
    swipe = e.touches.length === 1;
    if (!swipe) return;
    sx = e.touches[0].clientX; sy = e.touches[0].clientY; st = e.timeStamp;
    var s = e.target.closest && e.target.closest(SCROLLERS);
    if (s && s.scrollWidth - s.clientWidth > 2) swipe = false;
  }, { passive: true });
  document.addEventListener('touchmove', function (e) {
    if (!swipe) return;
    var dx = e.touches[0].clientX - sx, dy = Math.abs(e.touches[0].clientY - sy);
    if (dy > 24 || dx < -16 || e.timeStamp - st > 140) { swipe = false; return; }
    if (dx > 100 && dx > 3 * dy) { swipe = false; bridge.openDrawer(); }
  }, { passive: true });

  function flush() {
    rafPending = false;
    if (queued) { var q = queued; queued = null; apply(q); }
  }

  function updateJump() { jump.hidden = atBottom(); }
  window.addEventListener('scroll', updateJump, { passive: true });
  window.addEventListener('resize', function () { if (!jump.hidden) return; window.scrollTo(0, document.documentElement.scrollHeight); });
  jump.addEventListener('click', function () { window.scrollTo({ top: document.documentElement.scrollHeight, behavior: 'smooth' }); });

  document.addEventListener('click', function (e) {
    var a = e.target.closest('a[href]');
    if (a) { e.preventDefault(); bridge.openLink(a.getAttribute('href')); return; }
    var math = e.target.closest('[data-tex]');
    if (math) { bridge.onMath(math.getAttribute('data-tex')); return; }
    var b = e.target.closest('[data-act]');
    if (!b) return;
    var act = b.getAttribute('data-act');
    if (act === 'copy-code') {
      var code = b.closest('.code').querySelector('code');
      bridge.copy(code.textContent);
      b.textContent = R.strings.copied || '✓';
      setTimeout(function () { b.textContent = R.strings.copy; }, 1400);
      return;
    }
    var msg = b.closest('.msg');
    if (!msg) return;
    if (act === 'image') bridge.openImage(msg.dataset.id, +b.getAttribute('data-index'));
    else bridge.action(act, msg.dataset.id);
  });

  window.Olenva = {
    setMessages: function (msgs, scroll) {
      queued = msgs;
      forceScroll = forceScroll || !!scroll;
      if (!rafPending) { rafPending = true; requestAnimationFrame(flush); }
    },
    setAppearance: function (o) {
      var root = document.documentElement;
      root.dataset.theme = o.dark ? 'dark' : 'light';
      root.style.fontSize = (16 * o.scale) + 'px';
      root.classList.toggle('compact', !!o.compact);
      root.lang = o.lang || 'zh-Hant';
      if (o.strings) R.strings = o.strings;
      last.clear();   // labels may have changed language; re-render on next setMessages
      jump.setAttribute('aria-label', R.strings.jump || 'Latest');
    },
    scrollToBottom: function () { window.scrollTo(0, document.documentElement.scrollHeight); }
  };
  bridge.ready();
})();
