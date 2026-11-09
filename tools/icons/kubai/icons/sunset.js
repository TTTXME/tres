import { defineIcon, KR, f, OL, DEEP, DEEPS, R, rp, circ, arrow, at } from '../packages/core/src/kit.js';

const AR = arrow(15.5, 1.4, 15.5, 7.8);
const RAYS = [-50, 50].map((a) => {
  const s = Math.sin(a * Math.PI / 180), c = Math.cos(a * Math.PI / 180);
  return `M${f(15.5 + s * 11.3)} ${f(18 - c * 11.3)}L${f(15.5 + s * 12.4)} ${f(18 - c * 12.4)}`;
}).join('');
const GROUND = rp(R(3.6, 18, 23.8, 4.6), [1.8, 1.8, KR, KR]);

export default defineIcon({
  name: 'sunset',
  zh: '日落',
  category: 'weather',
  tags: ['dusk', 'evening', 'sun down', 'twilight', '傍晚'],
  // The sun half above the horizon with a down arrow for its top ray; on hover it sinks.
  svg: (u) => `<clipPath id="${u.id('sky')}"><path d="M0 -3H31V20H0Z"/></clipPath><g clip-path="url(#${u.id('sky')})"><g data-n="sun">
      <path d="${RAYS}${AR.shaft}" fill="none" stroke-width="2.2" stroke-linecap="round" ${DEEPS}/><path d="${AR.head}" ${DEEP}/>
      <g filter="${u.sh}"><g filter="${u.hi}"><path d="${circ(15.5, 18, 7.2)}" fill="${u.loS}"/></g></g><path d="${circ(15.5, 18, 7.2)}" ${OL}/></g></g>
    <g filter="${u.sh}"><g filter="${u.hi}"><path d="${GROUND}" fill="${u.upS}"/></g></g><path d="${GROUND}" ${OL}/>`,
  target: (h) => ({ y: h ? 1.4 : 0 }),
  draw(p, E) { at(E.sun, { transform: `translate(0 ${f(p.y)})` }); }
});
