import { defineIcon, f, OL, DEEPS, rp, rr, at, rattle, clamp01 } from '../packages/core/src/kit.js';

export default defineIcon({
  name: 'broom',
  zh: '扫帚',
  category: 'home',
  tags: ['sweep', 'clean', 'cleanup', 'housework', 'tidy', '清扫', '打扫'],
  variants: ['check'],
  // A broom leaning at an angle; it sweeps to and fro on hover and comes to rest.
  svg: (u) => {
    const bristles = rp([[11, 14.4], [20, 14.4], [23, 24.6], [8, 24.6]], [0.8, 0.8, 1.4, 1.4]);
    const band = rr(11.4, 11.8, 8.2, 3.8, 1.2);
    return `<g data-n="g">
      <path d="M15.5 -0.6V12.4" fill="none" stroke-width="2.2" stroke-linecap="round" ${DEEPS}/>
      <g filter="${u.sh}"><g filter="${u.hi}"><path d="${bristles}" fill="${u.upS}"/></g></g><path d="${bristles}" ${OL}/>
      <path d="M13.2 18V22.4M17.8 18V22.4" fill="none" stroke-width="1.6" stroke-linecap="round" ${DEEPS} stroke-opacity=".45"/>
      <g filter="${u.hi}"><path d="${band}" fill="${u.loS}"/></g><path d="${band}" ${OL}/>
    </g>`;
  },
  target: (h) => ({ o: h ? 1 : 0 }),
  draw(p, E) {
    const a = 30 + rattle(clamp01(p.o), 14, 2);
    at(E.g, { transform: `rotate(${f(a)} 15.5 12.2)` });
  }
});
