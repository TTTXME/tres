import { defineBadge, at, f, rr, circ } from '../packages/core/src/kit.js';
import { disc, pop, INK } from './_disc.js';

export default defineBadge({
  name: 'info',
  zh: '信息',
  tone: '#0284C7',
  tags: ['info', 'about', 'details', 'information'],
  // The i hops up on hover.
  svg: (u) => disc(u, `<g data-n="kbM"><path d="${circ(0, -2.3, 1.2) + rr(-1.1, -0.5, 2.2, 3.8, 1.1)}" ${INK}/></g>`),
  target: (h) => ({ s: h ? 1.14 : 1, y: h ? -0.9 : 0 }),
  draw(p, E) { at(E.kbG, { transform: pop(p.s) }); at(E.kbM, { transform: `translate(0 ${f(p.y)})` }); }
});
