import { defineBadge, at, f } from '../packages/core/src/kit.js';
import { disc, pop, INK, INKS } from './_disc.js';

export default defineBadge({
  name: 'clock',
  zh: '定时',
  tone: '#0284C7',
  tags: ['time', 'clock', 'schedule', 'pending', 'history', 'recent'],
  // The minute hand sweeps one full turn on hover.
  svg: (u) => disc(u, `<path d="M0 0L-1.7 -1.4" fill="none" stroke-width="2" stroke-linecap="round" ${INKS}/>
    <path data-n="kbM" d="M0 0V-3" transform="rotate(48)" fill="none" stroke-width="1.8" stroke-linecap="round" ${INKS}/><circle r="1.2" ${INK}/>`),
  target: (h) => ({ s: h ? 1.14 : 1, r: h ? 408 : 48 }),
  draw(p, E) { at(E.kbG, { transform: pop(p.s) }); at(E.kbM, { transform: `rotate(${f(p.r)})` }); }
});
