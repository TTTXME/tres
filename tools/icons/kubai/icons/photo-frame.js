import { defineIcon, KR, CR, f, OL, DEEP, DEEPS, SOFT, rp, rr, at, clamp01, rattle } from '../packages/core/src/kit.js';

export default defineIcon({
  name: 'photo-frame',
  zh: '相框',
  category: 'media',
  tags: ['picture frame', 'photo', 'wall', 'gallery', 'portrait', '画框'],
  variants: ['plus', 'check', 'x', 'heart', 'star', 'up', 'down', 'search', 'lock'],
  // A framed picture hanging from a nail; on hover it swings on its wire and settles level.
  svg: (u) => {
    const frame = rr(4.6, 6.4, 21.8, 16.2, KR), pic = rr(7, 8.8, 17, 11.4, CR);
    return `<clipPath id="${u.id('pic')}"><path d="${pic}"/></clipPath>
    <g data-n="hang"><path d="M10 7.4L15.5 2.4L21 7.4" fill="none" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" ${DEEPS}/>
      <g filter="${u.sh}"><g filter="${u.hi}"><path d="${frame}" fill="${u.loS}"/></g></g><path d="${frame}" ${OL}/>
      <path d="${pic}" fill="#fff"/>
      <g clip-path="url(#${u.id('pic')})">
        <circle cx="19.8" cy="11.8" r="1.8" ${SOFT}/>
        <path d="${rp([[5, 22], [5, 18.6], [12.4, 13.4], [20, 19.4], [20, 22]], [0, 0, 1.4, 0, 0])}" ${SOFT}/>
        <path d="${rp([[11, 22], [11, 20.4], [18.6, 15], [27, 20.4], [27, 22]], [0, 0, 1.4, 0, 0])}" fill="${u.pure('#5393FF')}"/>
      </g></g>
    <circle cx="15.5" cy="2.4" r="1.4" ${DEEP}/>`;
  },
  target: (h) => ({ o: h ? 1 : 0 }),
  draw(p, E) { at(E.hang, { transform: `rotate(${f(rattle(clamp01(p.o), 7, 3))} 15.5 2.4)` }); }
});
