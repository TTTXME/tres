import { defineIcon, f, at, clamp01, bump } from '../packages/core/src/kit.js';
import { DISC, back, MARK, plusPath } from './_interface.js';

// The plus turned by deg about its centre; at 0 it keeps the plain H/V form.
const turnedPlus = (a, deg) => {
  if (!deg) return plusPath(a);
  const t = deg * Math.PI / 180, x = a * Math.cos(t), y = a * Math.sin(t);
  return `M${f(15.5 - x)} ${f(12.5 - y)}L${f(15.5 + x)} ${f(12.5 + y)}M${f(15.5 + y)} ${f(12.5 - x)}L${f(15.5 - y)} ${f(12.5 + x)}`;
};

export default defineIcon({
  name: 'circle-plus',
  zh: '添加',
  category: 'interface',
  tags: ['add', 'new', 'create', 'plus', '新建'],
  // A round add button whose plus hops and swells on hover, then lands a little larger.
  svg: (u) => `${back(u, DISC)}<path data-n="m" ${MARK}/>`,
  target: (h) => ({ o: h ? 1 : 0 }),
  draw(p, E) {
    const o = clamp01(p.o), b = bump(o);
    at(E.m, { d: turnedPlus(5.2 + 0.8 * o + 1.4 * b, 0) });
    // The plus hops up and lands; no transform at rest keeps the rest markup plain.
    if (b > 0.001) E.m.setAttribute('transform', `translate(0 ${f(-1.8 * b)})`); else E.m.removeAttribute('transform');
  }
});
