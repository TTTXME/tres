import { defineIcon, f, OL, DEEP, DEEPS, rp, circ, rattle, at, clamp01 } from '../packages/core/src/kit.js';

// A dart stuck in the centre, flying in from the upper right.
const D = [Math.SQRT1_2, -Math.SQRT1_2], N = [Math.SQRT1_2, Math.SQRT1_2], T = [23, 5];
const pt = (a, b) => [T[0] + D[0] * a + N[0] * b, T[1] + D[1] * a + N[1] * b];
const FLETCH = rp([pt(-1, 0), pt(1.4, 2.2), pt(3, 2.2), pt(2.2, 0), pt(3, -2.2), pt(1.4, -2.2)], 0.4);

export default defineIcon({
  name: 'target',
  zh: '靶心',
  category: 'sports',
  tags: ['goal', 'aim', 'dart', 'bullseye', 'focus', '目标'],
  variants: ['check', 'star', 'clock'],
  // A target with a dart in the bullseye; on hover the dart quivers hard and comes to rest.
  svg: (u) => `<g filter="${u.sh}"><g filter="${u.hi}"><path d="${circ(15.5, 12.5, 11.5)}" fill="${u.loS}"/></g></g>
    <path d="${circ(15.5, 12.5, 11.5)}" ${OL}/>
    <path d="${circ(15.5, 12.5, 8.6)}" fill="#fff"/>
    <g filter="${u.hi}"><path d="${circ(15.5, 12.5, 5.8)}" fill="${u.loS}"/></g>
    <path d="${circ(15.5, 12.5, 2.9)}" fill="#fff"/>
    <g data-n="d"><path d="M15.5 12.5L${f(T[0])} ${f(T[1])}" fill="none" stroke-width="2.2" stroke-linecap="round" ${DEEPS}/><path d="${FLETCH}" ${DEEP}/></g>`,
  target: (h) => ({ o: h ? 1 : 0 }),
  draw(p, E) { at(E.d, { transform: `rotate(${f(rattle(clamp01(p.o), 13, 3))} 15.5 12.5)` }); }
});
