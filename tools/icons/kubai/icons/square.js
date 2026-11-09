import { defineIcon, KR, f, rr, at } from '../packages/core/src/kit.js';
import { turning } from './_science-edu-sports.js';

export default defineIcon({
  name: 'square',
  zh: '正方形',
  category: 'shapes',
  tags: ['box', 'rectangle', 'shape', '方形'],
  // A square with a lighter square inset; on hover the two turn a quarter in opposite directions.
  svg: (u) => turning(u, rr(-10.3, -10.3, 20.6, 20.6, KR), rr(-5, -5, 10, 10, 1.6)),
  target: (h) => ({ a: h ? 90 : 0 }),
  draw(p, E) {
    const t = (a) => `translate(15.5 12.5) rotate(${f(a)})`;
    at(E.o, { transform: t(p.a) }); at(E.oO, { transform: t(p.a) });
    at(E.i, { transform: t(-p.a) }); at(E.iO, { transform: t(-p.a) });
  }
});
