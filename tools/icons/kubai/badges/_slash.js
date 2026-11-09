// The slash for "-off" variants: a light bar with its own outline, laid across the icon
// from top left to bottom right.
import { OL, at, f, rr, clamp01, rattle } from '../packages/core/src/kit.js';

const BAR = rr(-13.6, -1.4, 27.2, 2.8, 1.4);
export default {
  name: 'off',
  zh: '关闭',
  tags: ['off', 'disabled', 'disable', 'mute', 'slash', 'none'],
  svg: (u) => `<g data-n="kbS" transform="translate(15.5 12.5) rotate(45)">
    <g filter="${u.sh}"><g filter="${u.hi}"><path d="${BAR}" fill="${u.upS}"/></g></g><path d="${BAR}" ${OL}/></g>`,
  target: (h) => ({ o: h ? 1 : 0 }),
  // On hover the bar gives a short damped shake and settles where it was.
  draw(p, E) {
    const o = clamp01(p.o);
    at(E.kbS, { transform: `translate(15.5 12.5) rotate(${f(45 + rattle(o, 7, 3))})` });
  }
};
