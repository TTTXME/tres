import { defineIcon, KR, f, OL, DEEP, R, rp, rr, circ, at, clamp01, bump } from '../packages/core/src/kit.js';

export default defineIcon({
  name: 'camera-retro',
  zh: '复古相机',
  category: 'media',
  tags: ['film camera', 'vintage', 'photo', 'shutter', 'flash', 'rangefinder', '胶片相机'],
  // A film camera with a leather band and a flash; on hover the shutter button goes down, the flash fires and the shutter blinks.
  svg: (u) => {
    const body = rr(4, 5.8, 23, 16.6, KR), band = rp(R(4, 10.6, 23, 11.8), [1.8, 1.8, KR, KR]);
    const flash = rp(R(6.4, 2.4, 6.4, 4.4), [1.4, 1.4, 0, 0]);
    return `<g filter="${u.sh}"><g filter="${u.hi}"><path d="${flash}" fill="${u.upS}"/></g></g>
    <path data-n="fl" d="${flash}" fill="#fff"/><path d="${flash}" ${OL}/>
    <g data-n="btn"><path d="${rp(R(19.6, 3.4, 4.4, 3.4), [1, 1, 0, 0])}" fill="${u.loS}"/><path d="${rp(R(19.6, 3.4, 4.4, 3.4), [1, 1, 0, 0])}" ${OL}/></g>
    <g filter="${u.sh}"><g filter="${u.hi}"><path d="${body}" fill="${u.loS}"/></g></g><path d="${body}" ${OL}/>
    <g filter="${u.hi}"><path d="${band}" fill="${u.upS}"/></g><path d="${band}" ${OL}/>
    <g filter="${u.hi}"><path d="${circ(15.5, 14.8, 4.8)}" fill="${u.loS}"/></g><path d="${circ(15.5, 14.8, 4.8)}" ${OL}/>
    <path d="${circ(15.5, 14.8, 2.6)}" fill="#fff"/><circle data-n="sh" cx="15.5" cy="14.8" r="1.2" ${DEEP}/>`;
  },
  target: (h) => ({ o: h ? 1 : 0 }),
  draw(p, E) {
    // The button bottoms out first; the shutter closes over the lens and opens again.
    const o = clamp01(p.o), y = 1.6 * Math.min(1, o * 1.6);
    at(E.btn, { transform: `translate(0 ${f(y)})` }); at(E.fl, { opacity: f(o) });
    at(E.sh, { r: f(1.2 + 1.4 * bump(o) ** 2) });
  }
});
