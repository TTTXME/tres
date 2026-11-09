import { defineBadge, at, f } from '../packages/core/src/kit.js';
import { disc, pop, INKS } from './_disc.js';

export default defineBadge({
  name: 'search',
  zh: '搜索',
  tone: '#475569',
  tags: ['search', 'find', 'lookup', 'zoom', 'magnifier'],
  // The lens sweeps a little to the left on hover.
  svg: (u) => disc(u, `<g data-n="kbM"><circle cx="-0.7" cy="-0.7" r="1.9" fill="none" stroke-width="1.6" ${INKS}/>
    <path d="M0.8 0.8L2.4 2.4" fill="none" stroke-width="1.8" stroke-linecap="round" ${INKS}/></g>`),
  target: (h) => ({ s: h ? 1.14 : 1, x: h ? -0.8 : 0 }),
  draw(p, E) { at(E.kbG, { transform: pop(p.s) }); at(E.kbM, { transform: `translate(${f(p.x)} ${f(p.x)})` }); }
});
