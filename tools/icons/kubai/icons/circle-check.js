import { defineIcon, at, bump } from '../packages/core/src/kit.js';
import { DISC, back, MARK, tick } from './_interface.js';

export default defineIcon({
  name: 'circle-check',
  zh: '完成',
  category: 'interface',
  tags: ['done', 'success', 'complete', 'ok', 'confirm', '成功'],
  // A round done mark whose tick springs up and settles a little larger on hover.
  svg: (u) => `${back(u, DISC)}<path data-n="m" ${MARK}/>`,
  target: (h) => ({ o: h ? 1 : 0 }),
  draw(p, E) {
    const o = Math.max(0, Math.min(1, p.o));
    at(E.m, { d: tick(1 + 0.14 * o, -1.6 * bump(o)) });
  }
});
