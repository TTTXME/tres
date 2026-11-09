import { defineIcon, f, DEEPS, circ, at, scaleAt } from '../packages/core/src/kit.js';
import { back, bar, plusPath } from './_interface.js';

export default defineIcon({
  name: 'zoom-in',
  zh: '放大',
  category: 'interface',
  tags: ['zoom', 'magnify', 'enlarge', 'magnifier', 'plus', '放大镜'],
  // A magnifier with a plus; on hover the lens grows and so does the plus.
  svg: (u) => `${back(u, bar(19.2, 16.2, 24.8, 21.8, 4.6))}
    <g data-n="lens">${back(u, circ(13.4, 10.4, 9.2))}<path d="${circ(13.4, 10.4, 6.8)}" fill="#fff"/></g>
    <path data-n="m" fill="none" stroke-width="2.2" stroke-linecap="round" ${DEEPS}/>`,
  target: (h) => ({ s: h ? 1.06 : 1, a: h ? 4 : 3.2 }),
  draw(p, E) {
    at(E.lens, { transform: scaleAt(p.s, 13.4, 10.4) });
    at(E.m, { d: plusPath(p.a, 13.4, 10.4) });
  }
});
