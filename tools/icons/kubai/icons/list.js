import { defineIcon, f, WHITE, at } from '../packages/core/src/kit.js';
import { card, bar, ROWS } from './_files-text.js';
import { stagger } from './_interface.js';

const W = [9.4, 7.6, 8.6];

export default defineIcon({
  name: 'list',
  zh: '列表',
  category: 'text',
  tags: ['list', 'bullets', 'unordered', 'items', 'menu', '项目符号'],
  // A bulleted list; on hover the lower two items indent one level under the first, one after the other.
  svg: (u) => card(u, ROWS.map((_, i) => `<g data-n="r${i}"><circle cx="9.8" r="1.5" ${WHITE}/><path data-n="t${i}" ${WHITE}/></g>`).join('')),
  target: (h) => ({ in: h ? 1 : 0 }),
  draw(p, E) {
    const o = Math.max(0, Math.min(1, p.in));
    ROWS.forEach((y, i) => {
      const t = i ? stagger(o, i - 1, 2, 0.75) : 0, dx = 3.6 * t + 0.8 * Math.sin(Math.PI * t);
      at(E['r' + i], { transform: `translate(${f(dx)} ${y})` });
      at(E['t' + i], { d: bar(12.8, 0, W[i] - dx / 2) });
    });
  }
});
