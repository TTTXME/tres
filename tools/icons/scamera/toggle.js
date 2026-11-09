// A switch for the line style (the library's knob is the track's own end circle: as a line it vanishes into the track): the
// track's outline and a solid knob inside it; on press the knob slides across.
import { defineIcon, f, rr, circ, at } from '../kubai/packages/core/src/kit.js';
import { back } from '../kubai/icons/_interface.js';

const TRACK = rr(4, 6, 23, 13, 6.5);
export default defineIcon({
  name: 'toggle',
  zh: '开关',
  category: 'interface',
  tags: ['switch', 'toggle'],
  svg: (u) => `${back(u, TRACK)}<path data-n="knob" d="${circ(0, 0, 3.3)}" fill="${u.pure('#4389FF')}"/>`,
  target: (h) => ({ x: h ? 20.5 : 10.5 }),
  draw(p, E) { at(E.knob, { transform: `translate(${f(p.x)} 12.5)` }); }
});
