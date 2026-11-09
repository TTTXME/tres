import { defineIcon, rp, rr, f, at } from '../packages/core/src/kit.js';
import { rig, pose } from './_tools-dev.js';

const HANDLE = rp([[1.6, 11.2], [14.4, 10.3], [14.4, 14.7], [1.6, 13.8]], [1.3, 0.4, 0.4, 1.3]);
const TUFT = 'M18 9.6H21.2C25 9.6 27.4 11.5 29.2 12.5C27.4 13.5 25 15.4 21.2 15.4H18Z';
const FERRULE = rr(13.4, 9.2, 5.6, 6.6, 0.9);

export default defineIcon({
  name: 'paintbrush',
  zh: '画笔',
  category: 'tools',
  tags: ['paint', 'brush', 'art', 'color', 'draw', '刷子'],
  // A paintbrush; on hover it sweeps sideways and leaves a stroke of paint.
  svg: (u) => `<path data-n="pt" fill="none" stroke-width="2.2" stroke-linecap="round" data-t="stroke:#2F6FE0"/>`
    + rig(u, 'p', [{ d: HANDLE, fill: u.loS }, { d: TUFT, fill: u.pure('#4389FF') }, { d: FERRULE, fill: u.upS }]),
  target: (h) => ({ x: h ? 1.2 : 0, ink: h ? 1 : 0 }),
  draw(p, E) {
    pose(E, 'p', 3, `translate(${f(p.x)} 0) rotate(135 15.5 12.5)`);
    const k = p.ink, d = `M5.2 23.6C${f(5.2 + 3 * k)} ${f(23.6 - 1.6 * k)} ${f(5.2 + 6 * k)} ${f(23.6 + 0.8 * k)} ${f(5.2 + 9 * k)} ${f(23.6 - 0.6 * k)}`;
    at(E.pt, { d, opacity: k > 0.05 ? 1 : 0 });
  }
});
