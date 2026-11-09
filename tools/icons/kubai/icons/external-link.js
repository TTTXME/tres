import { defineIcon, KR, OL, DEEP, DEEPS, f, rr, arrow, at } from '../packages/core/src/kit.js';
import { back, swell } from './_arrows.js';

const BOX = rr(4, 5.6, 18.4, 18.4, KR), front = (dx, dy) => rr(7 + dx, 8.6 + dy, 12.4, 12.4, 1.6);
const A = arrow(12.8, 14.8, 26.4, 1.2, 3.8, 3.1);

export default defineIcon({
  name: 'external-link',
  zh: '外部链接',
  category: 'arrows',
  tags: ['external', 'open', 'new window', 'new tab', 'link', 'outside', '新窗口'],
  // A box with an arrow leaving its upper right corner; on hover the arrow darts out as
  // the box's face gives a little the other way, then settles a little further out.
  svg: (u) => `${back(u, BOX)}<g filter="${u.hi}"><path data-n="fc" d="${front(0, 0)}" fill="${u.upS}"/></g><path data-n="fcO" d="${front(0, 0)}" ${OL}/>
    <g data-n="arr"><path d="${A.shaft}" fill="none" stroke-width="2.2" stroke-linecap="round" ${DEEPS}/><path d="${A.head}" ${DEEP}/></g>`,
  target: (h) => ({ o: h ? 1 : 0 }),
  draw(p, E) {
    const w = swell(p.o), s = 0.5 * p.o + 2.3 * w, d = front(-0.6 * w, 0.6 * w);
    at(E.fc, { d }); at(E.fcO, { d });
    at(E.arr, { transform: `translate(${f(s)} ${f(-s)})` });
  }
});
