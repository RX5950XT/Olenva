// Markdown + LaTeX rendering shared by the chat transcript and the settings preview.
// Math is cut out before markdown-it sees it (so `_`, `*`, `\\` inside formulas survive),
// rendered by KaTeX, and pasted back. A formula KaTeX rejects falls back to its raw source.
(function () {
  'use strict';

  var MATH_ENVS = new Set([
    'equation', 'equation*', 'align', 'align*', 'aligned', 'alignat', 'alignat*', 'gather', 'gather*',
    'gathered', 'multline', 'multline*', 'split', 'cases', 'eqnarray', 'eqnarray*', 'array',
    'matrix', 'pmatrix', 'bmatrix', 'Bmatrix', 'vmatrix', 'Vmatrix', 'smallmatrix', 'CD'
  ]);

  function esc(s) {
    return String(s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  var md = window.markdownit({ html: false, linkify: true, breaks: true, typographer: false });
  md.validateLink = function (url) { return !/^\s*(javascript|vbscript|file|data):/i.test(url); };

  md.renderer.rules.fence = function (tokens, idx) {
    var t = tokens[idx];
    var lang = (t.info || '').trim().split(/\s+/)[0] || '';
    var body;
    try {
      body = lang && hljs.getLanguage(lang)
        ? hljs.highlight(t.content, { language: lang, ignoreIllegals: true }).value
        : esc(t.content);
    } catch (e) { body = esc(t.content); }
    return '<div class="code"><div class="code-head"><span>' + esc(lang || 'text') +
      '</span><button type="button" class="code-copy" data-act="copy-code">' + esc(R.strings.copy || 'Copy') +
      '</button></div><pre><code class="hljs">' + body + '</code></pre></div>';
  };
  md.renderer.rules.table_open = function () { return '<div class="table-wrap"><table>'; };
  md.renderer.rules.table_close = function () { return '</table></div>'; };

  // --- math extraction -------------------------------------------------------

  function isSpace(c) { return c === undefined || c === ' ' || c === '\t' || c === '\n' || c === '\r'; }

  // Closing `$` for an inline formula starting after `from`; -1 if none in the same paragraph.
  function inlineClose(src, from) {
    for (var k = from; k < src.length; k++) {
      var c = src[k];
      if (c === '\\') { k++; continue; }
      if (c === '\n' && src[k + 1] === '\n') return -1;
      if (c === '$') return k;
    }
    return -1;
  }

  function protect(src, streaming) {
    var maths = [];
    var out = '';
    var pending = null;
    var i = 0, n = src.length;
    function push(tex, display, raw) {
      maths.push({ tex: tex, display: display, raw: raw });
      out += '' + (maths.length - 1) + '';
    }
    while (i < n) {
      var ch = src[i];
      if (i === 0 || src[i - 1] === '\n') {
        var lineEnd = src.indexOf('\n', i);
        var line = src.slice(i, lineEnd === -1 ? n : lineEnd);
        var fm = /^[ \t]{0,3}(`{3,}|~{3,})/.exec(line);
        if (fm) {
          var fence = fm[1];
          var re = new RegExp('\\n[ \\t]{0,3}' + (fence[0] === '`' ? '`' : '~') + '{' + fence.length + ',}[ \\t]*(?=\\n|$)', 'g');
          re.lastIndex = i + line.length;
          var close = re.exec(src);
          var end = close ? close.index + close[0].length : n;
          out += src.slice(i, end);
          i = end;
          continue;
        }
      }
      if (ch === '`') {
        var j = i;
        while (src[j] === '`') j++;
        var run = src.slice(i, j);
        var k = src.indexOf(run, j);
        while (k !== -1 && src[k + run.length] === '`') k = src.indexOf(run, k + run.length + 1);
        if (k !== -1) { out += src.slice(i, k + run.length); i = k + run.length; continue; }
        out += run; i = j; continue;
      }
      if (ch === '\\') {
        var nx = src[i + 1];
        if (nx === '[' || nx === '(') {
          var closer = nx === '[' ? '\\]' : '\\)';
          var c2 = src.indexOf(closer, i + 2);
          if (c2 !== -1) { push(src.slice(i + 2, c2), nx === '[', src.slice(i, c2 + 2)); i = c2 + 2; continue; }
          if (streaming) { pending = src.slice(i); break; }
        } else if (src.startsWith('\\begin{', i)) {
          var bm = /^\\begin\{([a-zA-Z]+\*?)\}/.exec(src.slice(i, i + 40));
          if (bm && MATH_ENVS.has(bm[1])) {
            var endTag = '\\end{' + bm[1] + '}';
            var e2 = src.indexOf(endTag, i);
            if (e2 !== -1) { var raw = src.slice(i, e2 + endTag.length); push(raw, true, raw); i = e2 + endTag.length; continue; }
            if (streaming) { pending = src.slice(i); break; }
          }
        }
        out += src.slice(i, i + 2); i += 2; continue;
      }
      if (ch === '$') {
        if (src[i + 1] === '$') {
          var d = src.indexOf('$$', i + 2);
          if (d !== -1) { push(src.slice(i + 2, d), true, src.slice(i, d + 2)); i = d + 2; continue; }
          if (streaming) { pending = src.slice(i); break; }
          out += '\\$\\$'; i += 2; continue;
        }
        var ic = isSpace(src[i + 1]) ? -1 : inlineClose(src, i + 1);
        if (ic !== -1 && !isSpace(src[ic - 1]) && !/[0-9]/.test(src[ic + 1] || '')) {
          push(src.slice(i + 1, ic), false, src.slice(i, ic + 1)); i = ic + 1; continue;
        }
        // An unfinished `$x^2…` on the line being streamed: hold it back instead of flashing raw markup.
        if (streaming && ic === -1 && !isSpace(src[i + 1]) && !/[0-9]/.test(src[i + 1] || '') && src.indexOf('\n', i) === -1) {
          pending = src.slice(i); break;
        }
        out += '\\$'; i++; continue;
      }
      out += ch; i++;
    }
    return { text: out, maths: maths, pending: pending };
  }

  // --- KaTeX with cache ------------------------------------------------------

  var cache = new Map();
  function katexHtml(m) {
    var key = (m.display ? 'D' : 'I') + m.tex;
    var hit = cache.get(key);
    if (hit !== undefined) return hit;
    var html;
    try {
      html = katex.renderToString(m.tex, {
        displayMode: m.display, throwOnError: true, strict: 'ignore', trust: false, output: 'html', maxExpand: 500
      });
    } catch (e) {
      html = null;
    }
    if (cache.size > 3000) cache.clear();
    cache.set(key, html);
    return html;
  }

  function mathHtml(m) {
    var html = katexHtml(m);
    var tex = esc(m.tex.trim());
    if (html === null) {
      return '<code class="math-raw' + (m.display ? ' block' : '') + '" data-tex="' + tex + '">' + esc(m.raw) + '</code>';
    }
    return m.display
      ? '<span class="math-block" data-tex="' + tex + '">' + html + '</span>'
      : '<span class="math-inline" data-tex="' + tex + '">' + html + '</span>';
  }

  function render(src, streaming) {
    var p = protect(src || '', !!streaming);
    var html = md.render(p.text);
    html = html.replace(/(\d+)/g, function (_, i) { return mathHtml(p.maths[+i]); });
    if (p.pending) html += '<p class="math-pending">' + esc(p.pending) + '</p>';
    return html;
  }

  var R = window.OlenvaRender = { render: render, esc: esc, strings: {}, _protect: protect };
})();
