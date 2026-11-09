import { defineIcon, f, WHITE, rp, at } from '../packages/core/src/kit.js';
import { card } from './_files-text.js';

// A capital A as outer outline plus counter; `b` widens the stems from regular to bold.
const glyph = (b) => {
  const s = 2.4 + b, base = 19.4, top = 5.4, cx = 15.5, half = 6.6;
  const outer = [[cx - s / 2 - 0.4, top], [cx + s / 2 + 0.4, top], [cx + half, base], [cx + half - s, base], [cx + half - s - 1.1, base - 3.2], [cx - half + s + 1.1, base - 3.2], [cx - half + s, base], [cx - half, base]];
  const ct = 9.6 + b * 0.6, cb = 13.6, cw = 1.8 - b * 0.35;
  const counter = [[cx, ct], [cx + cw, cb], [cx - cw, cb]];
  return rp(outer, [0.5, 0.5, 0.4, 0.4, 0.2, 0.2, 0.4, 0.4]) + rp(counter, 0.3);
};

export default defineIcon({
  name: 'type',
  zh: '字体',
  category: 'text',
  tags: ['font', 'typography', 'text', 'letter', 'typeface', '文字'],
  // A letter A on a card; on hover it is set in bold italic.
  svg: (u) => card(u, `<path data-n="a" fill-rule="evenodd" ${WHITE}/>`),
  target: (h) => ({ b: h ? 1.2 : 0, k: h ? -11 : 0 }),
  draw(p, E) { at(E.a, { d: glyph(p.b), transform: `translate(15.5 19.4) skewX(${f(p.k)}) translate(-15.5 -19.4)` }); }
});
