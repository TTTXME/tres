import { defineIcon, KR, f, rr, ellipse, DEEPS, SOFT, at } from '../packages/core/src/kit.js';
import { rig, pose } from './_tools-dev.js';

const BAR = rr(2.6, 7.8, 25.8, 9.4, KR), VIAL = rr(9.2, 9.8, 12.6, 5.4, 2.7);

export default defineIcon({
  name: 'spirit-level',
  zh: '水平仪',
  category: 'tools',
  tags: ['spirit level', 'bubble level', 'level', 'align', 'straight', 'diy', '水平尺'],
  // A spirit level resting at a slant; on hover it settles level and the bubble runs to the
  // middle.
  svg: (u) => rig(u, 'l', [{ d: BAR, fill: u.loS }, `<path d="${VIAL}" fill="#fff"/>
    <path d="M13 10.8V14.2M18 10.8V14.2" fill="none" stroke-width="1.6" stroke-linecap="round" ${DEEPS}/>
    <path data-n="bb" ${SOFT}/>`]),
  target: (h) => ({ a: h ? 0 : -6, x: h ? 15.5 : 19.6 }),
  draw(p, E) {
    pose(E, 'l', 2, `rotate(${f(p.a)} 15.5 12.5)`);
    at(E.bb, { d: ellipse(p.x, 12.5, 1.6, 1.3) });
  }
});
