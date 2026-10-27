const fs = require('fs'), path = require('path'), vm = require('vm');
const A = process.argv[2] || path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'web');
const ctx = { console }; ctx.window = ctx; ctx.self = ctx; ctx.globalThis = ctx; vm.createContext(ctx);
for (const f of ['lib/katex.min.js', 'lib/mhchem.min.js', 'lib/markdown-it.min.js', 'lib/highlight.min.js', 'render.js'])
  vm.runInContext(fs.readFileSync(path.join(A, f), 'utf8'), ctx);
const R = ctx.OlenvaRender; let fail = 0;
function t(name, src, streaming, checks) {
  const h = R.render(src, streaming);
  for (const [re, want] of checks) if (re.test(h) !== want) { fail++; console.log('FAIL', name, re, '\n', h); }
}
t('inline', String.raw`能量 $E=mc^2$ 守恆`, false, [[/class="math-inline"/, true], [/\$/, false]]);
t('display', String.raw`$$\int_0^1 x_i^2\,dx$$`, false, [[/math-block/, true], [/<em>/, false]]);
t('brackets', String.raw`\[\frac{a}{b}\] and \(\alpha_1\)`, false, [[/math-block/, true], [/math-inline/, true]]);
t('align', String.raw`\begin{align} a&=b\\ c&=d \end{align}`, false, [[/math-block/, true]]);
t('matrix', String.raw`$$\begin{pmatrix}1&2\\3&4\end{pmatrix}$$`, false, [[/math-block/, true]]);
t('currency', '價格 $5 和 $10 元', false, [[/math-inline/, false], [/\$5/, true]]);
t('bad', String.raw`$$\frac{a}{$$`, false, [[/math-raw/, true], [/katex-error/, false], [/cc0000/, false]]);
t('code', '```py\nx = "$a$"\n```\n`$b$`', false, [[/math-inline/, false], [/hljs/, true]]);
t('stream-pending', String.raw`推導如下：$$\int_0^\infty e^{-x`, true, [[/math-pending/, true], [/math-block/, false]]);
t('stream-inline', '令 $x^2', true, [[/math-pending/, true]]);
t('stream-done', '令 $x^2$ 為', true, [[/math-inline/, true], [/math-pending/, false]]);
t('stream-env', String.raw`\begin{aligned} a &= b`, true, [[/math-pending/, true]]);
t('table', '|a|b|\n|-|-|\n|$x_1$|2|', false, [[/table-wrap/, true], [/math-inline/, true]]);
t('underscore', '$a_1 * b_2 * c$ and *it*', false, [[/<em>it<\/em>/, true], [/math-inline/, true]]);
t('cases', String.raw`$$f(x)=\begin{cases}1&x>0\\0&\text{else}\end{cases}$$`, false, [[/math-block/, true], [/math-raw/, false]]);
t('ce', String.raw`$\ce{H2O}$`, false, [[/math-inline/, true]]);
t('vec-partial', String.raw`$\vec F = -\nabla U$, $\frac{\partial^2 u}{\partial t^2}$, $\iint_D f\,dA$, $\lim_{x\to0}\frac{\sin x}{x}$, $\sum_{n=1}^\infty \frac1{n^2}$, $\det\begin{vmatrix}a&b\\c&d\end{vmatrix}$`, false, [[/math-raw/, false]]);
t('xss', '<script>alert(1)</script> [x](javascript:alert(1))', false, [[/<script>/, false], [/href="javascript/, false]]);
t('escaped', String.raw`費用 \$5`, false, [[/\$5/, true], [/math/, false]]);
t('list-math', String.raw`1. 先求 $f'(x)$
2. 再算
   $$\int f$$`, false, [[/<ol>/, true], [/math-block/, true]]);
console.log(fail ? fail + ' failures' : 'all render checks passed');
