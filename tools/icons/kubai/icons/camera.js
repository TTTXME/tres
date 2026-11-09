import { defineIcon, KR, f, OL, DEEP, R, rp, rr, circ, at, clamp01 } from '../packages/core/src/kit.js';

export default defineIcon({
  name: 'camera',
  zh: '相机',
  category: 'media',
  tags: ['photo', 'shutter', 'lens', 'picture'],
  variants: ['plus', 'check', 'x', 'lock', 'sync', 'cog', 'sparkle', 'bolt'],
  off: true,
  // A camera body with a lens in front; on hover the lens pushes out and the aperture snaps shut and reopens small.
  svg: (u) => {
    const body = rr(4, 6.6, 23, 15.1, KR), top = rp(R(9.6, 3.6, 7.4, 4), [1.6, 1.6, 0, 0]);
    return `<g filter="${u.sh}"><path d="${top}" fill="${u.loS}"/></g><path d="${top}" ${OL}/>
      <g filter="${u.sh}"><g filter="${u.hi}"><path d="${body}" fill="${u.loS}"/></g></g><path d="${body}" ${OL}/>
      <g filter="${u.hi}"><path data-n="lens" d="${circ(15.5, 14.2, 5.4)}" fill="${u.upS}"/></g><path data-n="lensO" d="${circ(15.5, 14.2, 5.4)}" ${OL}/>
      <circle data-n="iris" cx="15.5" cy="14.2" ${DEEP}/>`;
  },
  target: (h) => ({ o: h ? 1 : 0 }),
  draw(p, E) {
    // The iris closes fully halfway through, then opens to a small aperture.
    const o = clamp01(p.o), d = circ(15.5, 14.2, 5.4 + 0.8 * o);
    at(E.lens, { d }); at(E.lensO, { d });
    at(E.iris, { r: f(o < 0.5 ? 2.8 * (1 - o * 2) : 1.2 * (o * 2 - 1)) });
  }
});
