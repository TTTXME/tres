import { defineIcon, OL, f, rp, at, clamp01 } from '../kubai/packages/core/src/kit.js';

// A lightning bolt on the portrait keyline (18.4 x 23): the back piece, and its upper half as the lighter front piece
const BOLT = rp([[18.2, 1], [8.4, 14.2], [14.6, 14.2], [12.4, 24], [22.6, 10.2], [16.4, 10.2]], [1.4, 1.2, 0.6, 1.4, 1.2, 0.6]);
const UPPER = rp([[18.2, 1], [9.9, 12.2], [16.9, 12.2], [17.9, 10.2], [16.4, 10.2]], [1.4, 1.2, 0.4, 0.4, 0.6]);
// rays round the bolt (accent strokes), from the centre outward
const RAYS = [[-90, 11.6], [-30, 12.6], [30, 12.6], [150, 12.6], [210, 12.6]];
const ray = (a, r, k) => {
  const c = [15.5, 12.5], t = a * Math.PI / 180, r0 = r, r1 = r + 2.6 * k;
  return `M${f(c[0] + r0 * Math.cos(t))} ${f(c[1] + r0 * Math.sin(t))}L${f(c[0] + r1 * Math.cos(t))} ${f(c[1] + r1 * Math.sin(t))}`;
};
export default defineIcon({
  name: 'flash',
  zh: '闪光灯',
  category: 'camera',
  tags: ['flash', 'bolt', 'lightning', 'strobe'],
  // A lightning bolt; on press it flashes: it swells a little and throws short rays.
  svg: (u) => `<g data-n="bt"><g filter="${u.sh}"><g filter="${u.hi}"><path d="${BOLT}" fill="${u.loS}"/></g></g><path d="${BOLT}" ${OL}/>`
    + `<g filter="${u.hi}"><path d="${UPPER}" fill="${u.upS}"/></g></g>`
    + `<path data-n="ry" fill="none" stroke-width="1.6" stroke-linecap="round" data-t="stroke:#5393FF"/>`,
  target: (h) => ({ o: h ? 1 : 0 }),
  draw(p, E) {
    const o = clamp01(p.o), s = 1 + 0.08 * o;
    at(E.bt, { transform: `translate(15.5 12.5) scale(${f(s)}) translate(-15.5 -12.5)` });
    at(E.ry, { d: RAYS.map(([a, r]) => ray(a, r, Math.max(0.001, o))).join(''), opacity: f(Math.min(1, o * 2)) });
  }
});
