import { defineIcon, CR, f, DEEPS, WHITE, rr, at } from '../packages/core/src/kit.js';
import { card, bar } from './_files-text.js';

const Y = [8.4, 16.6], TICK = 'M-1.5 0.1L-0.4 1.2L1.6 -1.1', L = 4.6;

export default defineIcon({
  name: 'list-checks',
  zh: '任务清单',
  category: 'text',
  tags: ['checklist', 'todo', 'tasks', 'done', 'checkbox', '待办'],
  // A checklist with one item done; on hover the second item gets ticked and its box pops.
  svg: (u) => card(u, Y.map((y, i) => `<path data-n="b${i}" d="${rr(8, y - 2.3, 4.6, 4.6, CR)}" fill="#fff"/>
    <path data-n="k${i}" d="${TICK}" transform="translate(10.3 ${y})" fill="none" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" stroke-dasharray="${L}" ${DEEPS}/>
    <path d="${bar(14.8, y, i ? 6.8 : 8.6)}" ${WHITE}/>`).join('')),
  target: (h) => ({ k: h ? 1 : 0 }),
  draw(p, E) {
    at(E.k0, { 'stroke-dashoffset': 0 });
    const o = Math.max(0, Math.min(1, p.k)), k = Math.min(1, o / 0.55), s = 1 + 0.4 * Math.sin(Math.PI * Math.max(0, o - 0.3) / 0.7), y = Y[1];
    at(E.b1, { d: rr(10.3 - 2.3 * s, y - 2.3 * s, 4.6 * s, 4.6 * s, CR * s) });
    at(E.k1, { transform: s === 1 ? `translate(10.3 ${y})` : `translate(10.3 ${y}) scale(${f(s)})`, 'stroke-dashoffset': f(L * (1 - k)), opacity: k > 0.02 ? 1 : 0 });
  }
});
