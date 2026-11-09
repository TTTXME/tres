import { defineIcon, f } from '../kubai/packages/core/src/kit.js';
import { viewfinder, SCREEN, at } from './_camera.js';

const [x, y, w, h] = SCREEN, g = 0.382;
// the golden lines and a quarter of the spiral round the lower right golden point
const lines = (s) => {
  const cx = x + w / 2, cy = y + h / 2, P = (px, py) => `${f(cx + (px - cx) * s)} ${f(cy + (py - cy) * s)}`;
  const xs = [x + w * g, x + w * (1 - g)], ys = [y + h * g, y + h * (1 - g)];
  return xs.map((v) => `M${P(v, y + 1.4)}L${P(v, y + h - 1.4)}`).join('') + ys.map((v) => `M${P(x + 1.4, v)}L${P(x + w - 1.4, v)}`).join('');
};
export default defineIcon({
  name: 'vf-grid-golden',
  zh: '黄金分割',
  category: 'camera',
  tags: ['grid', 'golden ratio', 'phi', 'composition'],
  // The viewfinder with the golden-ratio lines; on press they close in on the subject.
  svg: (u) => `${viewfinder(u)}<path data-n="ln" fill="none" stroke-width="1.6" stroke-linecap="round" data-t="stroke:#5393FF"/>`
    + `<circle data-n="pt" r="1.6" data-t="fill:#2F6FE0"/>`,
  target: (hv) => ({ s: hv ? 0.82 : 1 }),
  draw(p, E) {
    at(E.ln, { d: lines(p.s) });
    const cx = x + w / 2, cy = y + h / 2;
    at(E.pt, { cx: f(cx + (x + w * (1 - g) - cx) * p.s), cy: f(cy + (y + h * (1 - g) - cy) * p.s) });
  }
});
