import { defineIcon, f, OL, rr, circ, at } from '../packages/core/src/kit.js';
import { back } from './_interface.js';

const TRACK = rr(4, 6, 23, 13, 6.5);

export default defineIcon({
  name: 'toggle',
  zh: '开关',
  category: 'interface',
  tags: ['switch', 'toggle', 'on off', 'enable', 'setting', '切换'],
  // A switch: on hover the knob slides across and the track fills, switched on.
  svg: (u) => `${back(u, TRACK)}<path data-n="on" d="${TRACK}" fill="${u.pure('#4389FF')}"/>
    <g data-n="knob"><g filter="${u.sh}"><g filter="${u.hi}"><path d="${circ(0, 0, 6.5)}" fill="${u.upS}"/></g></g><path d="${circ(0, 0, 6.5)}" ${OL}/></g>`,
  target: (h) => ({ x: h ? 20.5 : 10.5, on: h ? 1 : 0 }),
  draw(p, E) {
    at(E.knob, { transform: `translate(${f(p.x)} 12.5)` });
    at(E.on, { opacity: f(Math.max(0, Math.min(1, p.on))) });
  }
});
