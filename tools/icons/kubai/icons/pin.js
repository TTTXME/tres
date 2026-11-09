import { defineIcon, f, DEEPS, rp, rr, at } from '../packages/core/src/kit.js';
import { back, front } from './_interface.js';

export default defineIcon({
  name: 'pin',
  zh: '固定',
  category: 'interface',
  tags: ['pin', 'pushpin', 'stick', 'keep', 'attach', '图钉', '置顶'],
  off: true,
  // A push pin; on hover it is pressed in and its needle sinks out of sight.
  svg: (u) => {
    const body = rp([[12.2, 4.4], [18.8, 4.4], [18.8, 9.6], [22, 14], [9, 14], [12.2, 9.6]], [0, 0, 1.2, 0, 0, 1.2]);
    return `<path data-n="nd" fill="none" stroke-width="2.2" stroke-linecap="round" ${DEEPS}/>
    <g data-n="pin">${back(u, body)}${front(u, rr(9.8, 1.4, 11.4, 4.4, 2.2))}${front(u, rr(7.2, 13, 16.6, 4.4, 2.2))}</g>`;
  },
  target: (h) => ({ y: h ? 1.8 : 0 }),
  draw(p, E) {
    at(E.pin, { transform: `translate(0 ${f(p.y)})` });
    at(E.nd, { d: `M15.5 ${f(16.4 + p.y)}V23.6` });
  }
});
