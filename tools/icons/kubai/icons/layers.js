import { defineIcon, f, OL, rp, at } from '../packages/core/src/kit.js';

const sheet = (y) => rp([[4, y], [15.5, y - 5.4], [27, y], [15.5, y + 5.4]], [1.6, 2, 1.6, 2]);

export default defineIcon({
  name: 'layers',
  zh: '图层',
  category: 'development',
  tags: ['stack', 'layer', 'levels', 'overlay', 'arrange', '叠放'],
  variants: ['plus', 'minus', 'check', 'x', 'search', 'lock'],
  // Three stacked sheets; on hover they spread apart.
  svg: (u) => [2, 1, 0].map((i) => `<g filter="${u.sh}"><g filter="${u.hi}"><path data-n="s${i}" fill="${i ? u.loS : u.upS}"/></g></g><path data-n="s${i}o" ${OL}/>`).join(''),
  target: (h) => ({ g: h ? 5.6 : 4.2 }),
  draw(p, E) {
    [0, 1, 2].forEach((i) => { const d = sheet(12.5 + (i - 1) * p.g); at(E['s' + i], { d }); at(E['s' + i + 'o'], { d }); });
  }
});
