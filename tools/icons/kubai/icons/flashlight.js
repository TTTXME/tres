import { defineIcon, f, rp, rr, at, WHITE } from '../packages/core/src/kit.js';
import { rig, pose } from './_tools-dev.js';

const HANDLE = rr(2, 9.3, 12.6, 6.4, 2);
const HEAD = rp([[12.6, 9.3], [16.6, 6], [22.6, 6], [22.6, 19], [16.6, 19], [12.6, 15.7]], [0.4, 1, 1.2, 1.2, 1, 0.4]);

export default defineIcon({
  name: 'flashlight',
  zh: '手电筒',
  category: 'tools',
  tags: ['torch', 'light', 'beam', 'lamp', '照明'],
  // A torch pointing up and right; on hover it switches on and throws a beam.
  svg: (u) => rig(u, 'f', [
    `<path data-n="bm" d="${rp([[22.6, 7.2], [29.6, 4.8], [29.6, 20.2], [22.6, 17.8]], [0, 1.4, 1.4, 0])}" data-t="fill:#B2CEFF" opacity=".9"/>`,
    { d: HANDLE, fill: u.loS },
    `<path d="${rr(5.8, 11.3, 5, 2.4, 1.2)}" ${WHITE}/>`,
    { d: HEAD, fill: u.upS }
  ]),
  target: (h) => ({ b: h ? 1 : 0 }),
  draw(p, E) {
    pose(E, 'f', 4, 'translate(-1.4 1.4) rotate(-45 15.5 12.5)');
    at(E.bm, { transform: `translate(22.6 12.5) scale(${f(Math.max(0.001, p.b))} ${f(0.6 + 0.4 * p.b)}) translate(-22.6 -12.5)`, opacity: f(0.9 * Math.min(1, p.b * 3)) });
  }
});
