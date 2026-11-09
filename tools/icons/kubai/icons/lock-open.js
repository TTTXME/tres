import { defineIcon, KR, f, OL, rr, at, clamp01 } from '../packages/core/src/kit.js';

const SHACKLE = 'M10.5 7.6A5 5 0 0 1 20.5 7.6V12';

export default defineIcon({
  name: 'lock-open',
  zh: '已解锁',
  category: 'security',
  tags: ['unlock', 'unlocked', 'open', 'padlock', '解锁'],
  variants: ['check'],
  // An open padlock, its free shackle raised; on hover the shackle swivels round on its leg.
  svg: (u) => `
    <g data-n="sk" filter="${u.sh}">
      <path d="${SHACKLE}" fill="none" stroke-width="3.2" stroke-linecap="round" data-t="stroke:#81B0FF"/>
      <path d="${SHACKLE}" fill="none" stroke-width="1" stroke-linecap="round" stroke="#fff" stroke-opacity=".45"/>
    </g>
    <g filter="${u.sh}"><g filter="${u.hi}"><path d="${rr(5.6, 11, 19.8, 12.6, KR)}" fill="${u.upS}"/></g></g>
    <path d="${rr(5.6, 11, 19.8, 12.6, KR)}" ${OL}/>
    <g data-t="fill:#2F6FE0"><circle cx="15.5" cy="16.2" r="2.5"/><path d="${rr(14.6, 16.6, 1.8, 4.6, 0.9)}"/></g>`,
  target: (h) => ({ o: h ? 1 : 0 }),
  draw(p, E) {
    // The shackle turns about its right leg: seen from the front it narrows, then
    // comes out on the other side.
    const sx = Math.cos(clamp01(p.o) * 0.66 * Math.PI);
    at(E.sk, { transform: `translate(20.5 -1.8) scale(${f(Math.abs(sx) < 0.02 ? 0.02 * Math.sign(sx || 1) : sx)} 1) translate(-20.5 0)` });
  }
});
