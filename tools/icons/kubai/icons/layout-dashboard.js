import { defineIcon, CR, rr, at } from '../packages/core/src/kit.js';
import { TILE, back } from './_interface.js';

export default defineIcon({
  name: 'layout-dashboard',
  zh: '仪表盘',
  category: 'layout',
  tags: ['dashboard', 'widgets', 'panels', 'overview', 'layout', '看板'],
  variants: ['plus', 'cog'],
  // Four cards of a dashboard; on hover the tall and short cards trade places.
  svg: (u) => `${back(u, TILE)}${[0, 1, 2, 3].map((i) => `<path data-n="k${i}" fill="#fff"/>`).join('')}`,
  target: (h) => ({ l: h ? 5.2 : 8.6, r: h ? 8.6 : 5.2 }),
  draw(p, E) {
    const col = (x, top, a, b) => {
      at(E['k' + a], { d: rr(x, 4.6, 6.9, top, CR) });
      at(E['k' + b], { d: rr(x, 4.6 + top + 2, 6.9, 13.8 - top, CR) });
    };
    col(7.6, p.l, 0, 1); col(16.5, p.r, 2, 3);
  }
});
