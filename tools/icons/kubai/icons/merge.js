import { defineIcon } from '../packages/core/src/kit.js';
import { liveBody, leanBody, swell, liveMark, place, arrow, f, at } from './_arrows.js';

export default defineIcon({
  name: 'merge',
  zh: '合并',
  category: 'arrows',
  tags: ['merge', 'join', 'combine', 'converge', 'git', '合并'],
  // A tile with two branches joining into one upward arrow; on hover the branches draw
  // together as the joined arrow darts up with the face leaning after it, then settles.
  svg: (u) => liveBody(u, 'tile') + liveMark('m'),
  target: (h) => ({ o: h ? 1 : 0 }),
  draw(p, E) {
    const w = swell(p.o), t = 4.6 + 0.8 * p.o + 1.9 * w, x = f(4.6 - 1.2 * w);
    const a = arrow(0, 1, 0, -t, 3.4, 2.9);
    leanBody(E, 'tile', 0, -0.8 * w);
    at(E.m, { transform: place(0) });
    at(E.mS, { d: `M${-x} 5.2C${-x} 2.8 0 3.2 0 1M${x} 5.2C${x} 2.8 0 3.2 0 1V${f(-t + 2.8)}` });
    at(E.mH, { d: a.head });
  }
});
