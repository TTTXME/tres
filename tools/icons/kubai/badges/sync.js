import { defineBadge, at, f, arcArrow } from '../packages/core/src/kit.js';
import { disc, pop, INK, INKS } from './_disc.js';

const A = arcArrow(200, 310, 2.4, 1.5, 1.8), B = arcArrow(20, 130, 2.4, 1.5, 1.8);
export default defineBadge({
  name: 'sync',
  zh: '同步',
  tone: '#0D9488',
  tags: ['sync', 'refresh', 'update', 'reload', 'recurring'],
  // The arrows turn half a revolution on hover.
  svg: (u) => disc(u, `<g data-n="kbM"><path d="${A.arc + B.arc}" fill="none" stroke-width="1.6" stroke-linecap="round" ${INKS}/><path d="${A.head + B.head}" ${INK}/></g>`),
  target: (h) => ({ s: h ? 1.14 : 1, r: h ? 180 : 0 }),
  draw(p, E) { at(E.kbG, { transform: pop(p.s) }); at(E.kbM, { transform: `rotate(${f(p.r)})` }); }
});
