import { defineIcon, poly, at, clamp01 } from '../packages/core/src/kit.js';
import { card } from './_commerce-charts.js';

export default defineIcon({
  name: 'activity',
  zh: '动态',
  category: 'charts',
  tags: ['pulse', 'heartbeat', 'monitor', 'health', 'status', 'live', '心率', '监控'],
  variants: ['plus', 'check', 'alert', 'search'],
  // A pulse trace on a card; on hover the beat jumps much higher, overshoots and settles.
  svg: (u) => `${card(u)}<path data-n="ln" fill="none" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" stroke="#fff" data-w="stroke"/>`,
  target: (h) => ({ o: h ? 1 : 0 }),
  draw(p, E) {
    const o = clamp01(p.o), k = o + 2 * o * (1 - o), up = 8.4 - 4 * k, dn = 16.8 + 3.8 * k;
    at(E.ln, { d: poly([[8.2, 12.6], [11.4, 12.6], [13.4, up], [16.6, dn], [18.6, 12.6], [22.8, 12.6]]) });
  }
});
