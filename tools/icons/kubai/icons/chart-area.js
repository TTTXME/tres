import { defineIcon, CR, rp, at } from '../packages/core/src/kit.js';
import { card } from './_commerce-charts.js';

const XS = [8.4, 12.8, 17.2, 22.6];

export default defineIcon({
  name: 'chart-area',
  zh: '面积图',
  category: 'charts',
  tags: ['chart', 'graph', 'area chart', 'volume', 'statistics', 'analytics'],
  variants: ['plus', 'x', 'search', 'sparkle'],
  // An area chart on a card; on hover the filled area swells to new values.
  svg: (u) => `${card(u)}<path data-n="ar" fill="#fff"/>`,
  target: (h) => ({ ys: h ? [11, 14.6, 7.8, 4.8] : [13.4, 9.6, 13, 8.4] }),
  draw(p, E) {
    const pts = [[8.4, 19.8], ...XS.map((x, i) => [x, p.ys[i]]), [22.6, 19.8]];
    at(E.ar, { d: rp(pts, [CR, 0.6, 0.8, 0.8, 0.6, CR]) });
  }
});
