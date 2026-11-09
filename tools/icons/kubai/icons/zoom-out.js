import { defineIcon, f, DEEPS, circ, at, scaleAt } from '../packages/core/src/kit.js';
import { back, bar } from './_interface.js';

export default defineIcon({
  name: 'zoom-out',
  zh: '缩小',
  category: 'interface',
  tags: ['zoom', 'reduce', 'shrink', 'magnifier', 'minus', '放大镜'],
  // A magnifier with a minus; on hover the lens draws back and the minus shortens.
  svg: (u) => `${back(u, bar(19.2, 16.2, 24.8, 21.8, 4.6))}
    <g data-n="lens">${back(u, circ(13.4, 10.4, 9.2))}<path d="${circ(13.4, 10.4, 6.8)}" fill="#fff"/></g>
    <path data-n="m" fill="none" stroke-width="2.2" stroke-linecap="round" ${DEEPS}/>`,
  target: (h) => ({ s: h ? 0.95 : 1, a: h ? 2.2 : 3.4 }),
  draw(p, E) {
    at(E.lens, { transform: scaleAt(p.s, 13.4, 10.4) });
    at(E.m, { d: `M${f(13.4 - p.a)} 10.4H${f(13.4 + p.a)}` });
  }
});
