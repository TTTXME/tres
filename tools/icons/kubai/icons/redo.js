import { defineIcon } from '../packages/core/src/kit.js';
import { liveBody, leanBody, swell, mark, place, arrow, f, at } from './_arrows.js';

// Hook arrow drawn pointing left, then mirrored to point right.
const Y = -2, R = 2.8, A = arrow(0, Y, -5.2, Y, 3.4, 2.8);
const HOOK = `M-2.4 ${Y}H1.2A${R} ${R} 0 0 1 1.2 ${f(Y + 2 * R)}H-1.2`;

export default defineIcon({
  name: 'redo',
  zh: '重做',
  category: 'arrows',
  tags: ['redo', 'forward', 'repeat', 'again', '恢复'],
  // A disc with a hooked arrow pointing forward to the right; on hover it darts right
  // with the face leaning after it, then settles a little right.
  svg: (u) => liveBody(u) + mark(HOOK, A.head, 'arr'),
  target: (h) => ({ o: h ? 1 : 0 }),
  draw(p, E) {
    const w = swell(p.o), x = 0.9 * p.o + 1.6 * w;
    leanBody(E, 'disc', 0.8 * w, 0);
    at(E.arr, { transform: `${place(0, x, 0)} scale(-1 1)` });
  }
});
