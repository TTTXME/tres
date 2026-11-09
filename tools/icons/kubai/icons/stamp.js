import { defineIcon, f, OL, DEEP, R, rp, rr, circ, at } from '../packages/core/src/kit.js';

export default defineIcon({
  name: 'stamp',
  zh: '印章',
  category: 'files',
  tags: ['stamp', 'seal', 'approve', 'rubber stamp', 'official', '盖章'],
  variants: ['check', 'x', 'plus', 'clock', 'star', 'lock'],
  // A rubber stamp held over a sheet; on hover it presses down onto the paper.
  svg: (u) => {
    const paper = rr(4, 19.8, 23, 3.6, 1.2);
    const knob = circ(15.5, 3.8, 3.2), neck = rr(13.2, 5.4, 4.6, 5.2, 0.8), base = rr(5.4, 9.6, 20.2, 5.2, 1.6);
    return `<g filter="${u.sh}"><g filter="${u.hi}"><path d="${paper}" fill="${u.upS}"/></g></g><path d="${paper}" ${OL}/>
      <g data-n="st">
        <g filter="${u.sh}"><g filter="${u.hi}"><path d="${neck}" fill="${u.loS}"/></g></g><path d="${neck}" ${OL}/>
        <g filter="${u.hi}"><path d="${knob}" fill="${u.upS}"/></g><path d="${knob}" ${OL}/>
        <g filter="${u.sh}"><g filter="${u.hi}"><path d="${base}" fill="${u.loS}"/></g></g><path d="${base}" ${OL}/>
        <path d="${rp(R(6.6, 14.4, 17.8, 2.2), [0, 0, 0.8, 0.8])}" ${DEEP}/>
      </g>`;
  },
  target: (h) => ({ y: h ? 3.2 : 0 }),
  draw(p, E) { at(E.st, { transform: `translate(0 ${f(p.y)})` }); }
});
