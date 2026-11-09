import { defineIcon } from '../packages/core/src/kit.js';
import { liveBody, leanBody, swell, mark, place, arrow, f, at } from './_arrows.js';

// Hook arrow pointing left: a top run that bends round to the right and back.
const Y = -2, R = 2.8, A = arrow(0, Y, -5.2, Y, 3.4, 2.8);
const HOOK = `M-2.4 ${Y}H1.2A${R} ${R} 0 0 1 1.2 ${f(Y + 2 * R)}H-1.2`;

export default defineIcon({
  name: 'undo',
  zh: '撤销',
  category: 'arrows',
  tags: ['undo', 'back', 'revert', 'history', 'return', '撤回'],
  // A disc with a hooked arrow pointing back to the left; on hover it darts left with
  // the face leaning after it, then settles a little left.
  svg: (u) => liveBody(u) + mark(HOOK, A.head, 'arr'),
  target: (h) => ({ o: h ? 1 : 0 }),
  draw(p, E) {
    const w = swell(p.o), x = -0.9 * p.o - 1.6 * w;
    leanBody(E, 'disc', -0.8 * w, 0);
    at(E.arr, { transform: place(0, x, 0) });
  }
});
