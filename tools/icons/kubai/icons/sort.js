import { defineIcon, f, at, arrow, rp } from '../packages/core/src/kit.js';
import { TILE, back, MARK } from './_interface.js';

const DOWN = arrow(9.8, 6.4, 9.8, 19);
const MID = 12.7;
// The down arrow flipped about its middle by sy (1 down, -1 up), as the head turns over.
const flip = (sy) => {
  if (sy === 1) return DOWN;
  const y = (v) => MID + (v - MID) * sy;
  return { shaft: `M9.8 ${f(y(6.4))}L9.8 ${f(y(16.2))}`, head: rp([[6.9, y(15.6)], [9.8, y(19)], [12.7, y(15.6)]], [0.5, 0.6, 0.5]) };
};
const ROWS = [7.6, 12.5, 17.4];

export default defineIcon({
  name: 'sort',
  zh: '排序',
  category: 'interface',
  tags: ['sort', 'order', 'ascending', 'descending', 'arrange', '升序'],
  // A sorted list beside a down arrow; on hover the bars swap lengths and the arrow flips to point
  // up, the order reversed.
  svg: (u) => `${back(u, TILE)}<path data-n="as" d="${DOWN.shaft}" ${MARK}/><path data-n="ah" d="${DOWN.head}" fill="#fff" data-w="fill"/>
    ${ROWS.map((y, i) => `<path data-n="r${i}" ${MARK}/>`).join('')}`,
  target: (h) => ({ w: h ? [7.6, 5, 2.4] : [2.4, 5, 7.6], sy: h ? -1 : 1 }),
  draw(p, E) {
    // Never quite flat, so the head keeps a shape while it turns over.
    const sy = Math.abs(p.sy) < 0.08 ? (p.sy < 0 ? -0.08 : 0.08) : p.sy, a = flip(sy);
    at(E.as, { d: a.shaft }); at(E.ah, { d: a.head });
    ROWS.forEach((y, i) => at(E['r' + i], { d: `M15 ${y}H${f(15 + p.w[i])}` }));
  }
});
