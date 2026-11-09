import { defineIcon, f, DEEP, WHITES, rp, at } from '../packages/core/src/kit.js';
import { button } from './_media.js';

const RIM = 9.6;

export default defineIcon({
  name: 'aperture',
  zh: '光圈',
  category: 'media',
  tags: ['iris', 'lens', 'shutter', 'camera', 'exposure', 'f-stop'],
  // A lens iris of six blades; on hover the blades turn and the opening closes down.
  svg: (u) => `${button(u)}
    <path data-n="hole" ${DEEP}/>
    <path data-n="bl" fill="none" stroke-width="1.6" stroke-linecap="round" ${WHITES}/>`,
  target: (h) => ({ r: h ? 2.6 : 4.2, rot: h ? 40 : 0 }),
  draw(p, E) {
    const V = [];
    for (let i = 0; i < 6; i++) {
      const a = (p.rot + i * 60) * Math.PI / 180;
      V.push([p.r * Math.cos(a), p.r * Math.sin(a)]);
    }
    // Each blade edge carries a hexagon side on out to the rim.
    let d = '';
    for (let i = 0; i < 6; i++) {
      const v = V[i], w = V[(i + 5) % 6], l = Math.hypot(v[0] - w[0], v[1] - w[1]);
      const dx = (v[0] - w[0]) / l, dy = (v[1] - w[1]) / l, b = v[0] * dx + v[1] * dy;
      const t = -b + Math.sqrt(b * b - (p.r * p.r - (RIM - 0.6) ** 2));
      d += `M${f(15.5 + v[0])} ${f(12.5 + v[1])}L${f(15.5 + v[0] + dx * t)} ${f(12.5 + v[1] + dy * t)}`;
    }
    at(E.bl, { d });
    at(E.hole, { d: rp(V.map(([x, y]) => [15.5 + x, 12.5 + y]), 0.3) });
  }
});
