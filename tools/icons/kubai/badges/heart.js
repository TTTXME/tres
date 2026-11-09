import { defineBadge, at, f } from '../packages/core/src/kit.js';
import { disc, pop, INK } from './_disc.js';

const HEART = 'M0 3C-1.7 1.8 -3.3 0.5 -3.3 -1C-3.3 -2.1 -2.5 -2.9 -1.5 -2.9C-0.9 -2.9 -0.3 -2.5 0 -2C0.3 -2.5 0.9 -2.9 1.5 -2.9C2.5 -2.9 3.3 -2.1 3.3 -1C3.3 0.5 1.7 1.8 0 3Z';
export default defineBadge({
  name: 'heart',
  zh: '喜欢',
  tone: '#DB2777',
  tags: ['like', 'love', 'favorite', 'heart'],
  // The heart beats on hover.
  svg: (u) => disc(u, `<path data-n="kbM" d="${HEART}" ${INK}/>`),
  target: (h) => ({ s: h ? 1.14 : 1, b: h ? 1.18 : 1 }),
  draw(p, E) { at(E.kbG, { transform: pop(p.s) }); at(E.kbM, { transform: `translate(0 0.2) scale(${f(p.b)})` }); }
});
