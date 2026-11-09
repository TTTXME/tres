import { defineIcon, KR, f, OL, rp, rr, at } from '../packages/core/src/kit.js';

export default defineIcon({
  name: 'video',
  zh: '视频',
  category: 'media',
  tags: ['camera', 'record', 'film', 'movie'],
  variants: ['plus', 'check', 'x', 'lock', 'sparkle'],
  off: true,
  // A video camera; its lens opens wider and the record light grows on hover.
  svg: (u) => {
    const body = rr(4, 6, 15.8, 13, KR);
    return `<g filter="${u.sh}"><g filter="${u.hi}"><path d="${body}" fill="${u.loS}"/></g></g><path d="${body}" ${OL}/>
      <g filter="${u.hi}"><path data-n="lens" fill="${u.upS}"/></g><path data-n="lensO" ${OL}/>
      <circle data-n="dot" cx="8.8" cy="10.4" fill="#fff" data-w="fill"/>`;
  },
  target: (h) => ({ w: h ? [8.6, 6.2] : [7.6, 4.8], dot: h ? 2.3 : 1.8 }),
  draw(p, E) {
    const [wx, wy] = p.w;
    const d = rp([[18.6, 9.6], [18.6 + wx, 12.5 - wy], [18.6 + wx, 12.5 + wy], [18.6, 15.4]], [0.6, 1.4, 1.4, 0.6]);
    at(E.lens, { d }); at(E.lensO, { d });
    at(E.dot, { r: f(p.dot) });
  }
});
