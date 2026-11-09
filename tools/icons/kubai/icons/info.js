import { defineIcon, f, at, bump } from '../packages/core/src/kit.js';
import { DISC, back, MARK } from './_interface.js';

const LEAN = 12, HOP = 1.2, DOT = 2.8;

export default defineIcon({
  name: 'info',
  zh: '信息',
  category: 'interface',
  tags: ['information', 'details', 'about', 'help', 'circle-info', '说明'],
  // A round info mark that hops on hover: it leans over as it jumps and lands upright, the dot
  // springing higher, swelling and settling a little raised.
  svg: (u) => `${back(u, DISC)}<path data-n="st" d="M15.5 11.4V18.2" ${MARK}/><circle data-n="dot" cx="15.5" r="1.6" fill="#fff" data-w="fill"/>`,
  target: (h) => ({ o: h ? 1 : 0 }),
  draw(p, E) {
    // A hop that leans over and lands upright: points turned about the foot of the stem.
    const h = bump(p.o), t = -LEAN * h * Math.PI / 180, c = Math.cos(t), s = Math.sin(t);
    const pt = (x, y) => [15.5 + (x - 15.5) * c - (y - 18.2) * s, 18.2 + (x - 15.5) * s + (y - 18.2) * c];
    const [ax, ay] = pt(15.5, 11.4 - HOP * h), [bx, by] = pt(15.5, 18.2 - HOP * h), [dx, dy] = pt(15.5, 7.2 - 1.2 * p.o - DOT * h);
    at(E.st, { d: t ? `M${f(ax)} ${f(ay)}L${f(bx)} ${f(by)}` : `M15.5 ${f(ay)}V${f(by)}` });
    at(E.dot, { cx: f(dx), cy: f(dy), r: f(1.6 + 0.5 * h) });
  }
});
