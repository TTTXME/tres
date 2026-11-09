import { defineIcon, f, OL, DEEP, circ, at, clamp01 } from '../packages/core/src/kit.js';
import { meet } from './_tools-dev.js';

// A round palette with a bite out of its lower right and a thumb hole.
const C = [15.5, 12.5, 11.4], N = [23.4, 20.2, 4.4];
const [P, Q] = meet(C, N);
const BOARD = `M${f(P[0])} ${f(P[1])}A${C[2]} ${C[2]} 0 1 0 ${f(Q[0])} ${f(Q[1])}A${N[2]} ${N[2]} 0 0 1 ${f(P[0])} ${f(P[1])}Z` + circ(12.4, 17.6, 2.3);
const DABS = [[9.6, 10.4], [13, 5.6], [18.8, 5.6], [22.2, 10.6]];

export default defineIcon({
  name: 'palette',
  zh: '调色板',
  category: 'tools',
  tags: ['paint', 'color', 'art', 'theme', 'design', '颜色'],
  // An artist's palette with dabs of paint; on hover it tips in the hand and the dabs well up fresh.
  svg: (u) => `<g filter="${u.sh}"><g filter="${u.hi}"><g data-n="b"><path d="${BOARD}" fill-rule="evenodd" fill="${u.loS}"/></g></g></g>
    <g data-n="bo"><path d="${BOARD}" fill-rule="evenodd" ${OL}/>
    ${DABS.map((d, i) => `<circle data-n="d${i}" cx="${d[0]}" cy="${d[1]}" ${i % 2 ? `fill="${u.pure('#2F6FE0')}"` : 'fill="#fff"'}/>`).join('')}</g>`,
  target: (h) => ({ a: h ? -10 : 0, o: h ? 1 : 0 }),
  draw(p, E) {
    const t = `rotate(${f(p.a)} 12.4 17.6)`;
    at(E.b, { transform: t }); at(E.bo, { transform: t });
    for (let i = 0; i < 4; i++) {
      const k = clamp01(p.o * 1.6 - i * 0.2);
      at(E['d' + i], { r: f(2.4 + 0.5 * Math.sin(k * Math.PI)) });
    }
  }
});
