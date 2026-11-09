import { defineIcon } from '../packages/core/src/kit.js';
import { tile, mark, place, arrow, swell, at } from './_arrows.js';

const TOP = arrow(-4.8, -2.9, 4.4, -2.9, 3.2, 2.6), BOT = arrow(4.8, 2.9, -4.4, 2.9, 3.2, 2.6);

export default defineIcon({
  name: 'arrow-left-right',
  zh: '左右互换',
  category: 'arrows',
  tags: ['swap', 'exchange', 'switch', 'transfer', 'horizontal', '交换'],
  // A tile with two opposed arrows, right above and left below; on hover each darts the
  // way it points, sliding past the other, then settles a little out.
  svg: (u) => tile(u) + mark(TOP.shaft, TOP.head, 'a1') + mark(BOT.shaft, BOT.head, 'a2'),
  target: (h) => ({ o: h ? 1 : 0 }),
  draw(p, E) {
    const s = 0.9 * p.o + 1.6 * swell(p.o);
    at(E.a1, { transform: place(0, s, 0) });
    at(E.a2, { transform: place(0, -s, 0) });
  }
});
