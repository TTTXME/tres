import { defineIcon, f, rp } from '../packages/core/src/kit.js';
import { rig, pose } from './_tools-dev.js';

// Two crop marks: one runs down then right, the other right then down.
const A = rp([[6, 1], [9.2, 1], [9.2, 15.2], [26, 15.2], [26, 18.4], [6, 18.4]], [1.2, 1.2, 0.8, 1.2, 1.2, 1.6]);
const B = rp([[4, 6.6], [24.8, 6.6], [24.8, 24], [21.6, 24], [21.6, 9.8], [4, 9.8]], [1.2, 1.6, 1.2, 1.2, 0.8, 1.2]);

export default defineIcon({
  name: 'crop',
  zh: '裁剪',
  category: 'tools',
  tags: ['trim', 'cut', 'frame', 'resize', 'image', '剪裁'],
  // Two crop marks; on hover they slide in to tighten the frame.
  svg: (u) => rig(u, 'a', [{ d: A, fill: u.loS }]) + rig(u, 'b', [{ d: B, fill: u.upS }]),
  target: (h) => ({ k: h ? 1.4 : 0 }),
  draw(p, E) {
    pose(E, 'a', 1, `translate(${f(p.k)} ${f(-p.k)})`);
    pose(E, 'b', 1, `translate(${f(-p.k)} ${f(p.k)})`);
  }
});
