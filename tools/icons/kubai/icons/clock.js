import { defineIcon, CLOCK_MINUTES, f, OL, circ, sector, at } from '../packages/core/src/kit.js';

export default defineIcon({
  name: 'clock',
  zh: '时钟',
  category: 'time',
  tags: ['time', 'hour', 'watch', 'history'],
  variants: ['plus', 'minus', 'check', 'x', 'alert'],
  svg: (u) => `
    <g filter="${u.sh}"><g filter="${u.hi}"><path d="${circ(15.5, 12.5, 11.5)}" fill="${u.loS}"/></g></g>
    <path d="${circ(15.5, 12.5, 11.5)}" ${OL}/>
    <path d="${circ(15.5, 12.5, 8.2)}" fill="#fff"/>
    <g filter="${u.hi}"><path data-n="sweep" fill="${u.upS}"/></g>
    <path data-n="hh" d="M15.5 12.5V8.3" stroke-width="2.6" stroke-linecap="round" data-t="stroke:#89B5FF"/>
    <path data-n="mh" d="M15.5 12.5V6.2" stroke-width="2" stroke-linecap="round" data-t="stroke:#2F6FE0"/>
    <circle cx="15.5" cy="12.5" r="1.7" data-t="fill:#2F6FE0"/>`,
  target: (h) => {
    // Time moves on: the hands run 25 minutes forward (10:08 to 10:33, still well apart)
    // and the face marks the stretch passed.
    const m = CLOCK_MINUTES, sw = h ? 150 : 0;
    return { base: m * 6, sw, mA: m * 6 + sw, hA: m / 2 + sw / 12 };
  },
  draw(p, E) {
    at(E.sweep, { d: sector(p.base, Math.max(0, p.sw), 7) });
    at(E.mh, { transform: `rotate(${f(p.mA)} 15.5 12.5)` });
    at(E.hh, { transform: `rotate(${f(p.hA)} 15.5 12.5)` });
  }
});
