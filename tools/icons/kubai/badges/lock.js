import { defineBadge, at, f, rr } from '../packages/core/src/kit.js';
import { disc, pop, INK, INKS } from './_disc.js';

export default defineBadge({
  name: 'lock',
  zh: '锁定',
  tone: '#475569',
  tags: ['lock', 'private', 'secure', 'protected', 'password'],
  // The shackle springs open on hover.
  svg: (u) => disc(u, `<path data-n="kbM" d="M-1.5 -0.4V-1.4A1.5 1.5 0 0 1 1.5 -1.4V-0.4" fill="none" stroke-width="1.6" stroke-linecap="round" ${INKS}/>
    <path d="${rr(-2.7, -0.6, 5.4, 3.8, 0.9)}" ${INK}/>`),
  target: (h) => ({ s: h ? 1.14 : 1, y: h ? -1.3 : 0 }),
  draw(p, E) { at(E.kbG, { transform: pop(p.s) }); at(E.kbM, { transform: `translate(0 ${f(p.y)})` }); }
});
