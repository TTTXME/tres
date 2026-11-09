import { defineIcon, KR, f, OL, rr, at, bump } from '../packages/core/src/kit.js';

export default defineIcon({
  name: 'lock',
  zh: '锁',
  category: 'security',
  tags: ['padlock', 'secure', 'private', 'password'],
  // A closed padlock; on hover the key turns a quarter and returns upright as the shackle lifts.
  svg: (u) => `
    <g data-n="shackle" filter="${u.sh}">
      <path d="M10.5 12V7.6A5 5 0 0 1 20.5 7.6V12" fill="none" stroke-width="3.2" stroke-linecap="round" data-t="stroke:#81B0FF"/>
      <path d="M10.5 12V7.6A5 5 0 0 1 20.5 7.6V12" fill="none" stroke-width="1" stroke-linecap="round" stroke="#fff" stroke-opacity=".45"/>
    </g>
    <g filter="${u.sh}"><g filter="${u.hi}"><path d="${rr(5.6, 11, 19.8, 12.6, KR)}" fill="${u.upS}"/></g></g>
    <path d="${rr(5.6, 11, 19.8, 12.6, KR)}" ${OL}/>
    <g data-n="key" data-t="fill:#2F6FE0"><circle cx="15.5" cy="16.2" r="2.5"/><path d="${rr(14.6, 16.6, 1.8, 4.6, 0.9)}"/></g>`,
  target: (h) => ({ sh: h ? [-2.4, 20] : [0, 0], o: h ? 1 : 0 }),
  draw(p, E) {
    at(E.shackle, { transform: `translate(0 ${f(p.sh[0])}) rotate(${f(p.sh[1])} 20.5 12)` });
    at(E.key, { transform: `rotate(${f(-90 * bump(p.o))} 15.5 16.2)` });
  }
});
