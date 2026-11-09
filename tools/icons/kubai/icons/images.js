import { defineIcon, KR, f, OL, rp, rr, at } from '../packages/core/src/kit.js';

export default defineIcon({
  name: 'images',
  zh: '照片',
  category: 'media',
  tags: ['photo', 'picture', 'gallery', 'album'],
  variants: ['plus', 'minus', 'check', 'x', 'search', 'heart', 'star', 'up', 'down', 'lock', 'sparkle'],
  // Two prints, one behind the other: on hover the back one fans out to the left and
  // the front one tilts, like leafing through an album.
  svg: (u) => {
    const frame = rr(4, 5.6, 23, 16.1, KR), back = rr(6, 3.3, 19, 15, KR);
    return `
    <g data-n="back"><g filter="${u.sh}"><g filter="${u.hi}"><path d="${back}" fill="${u.lo(15)}"/></g></g><path d="${back}" ${OL}/></g>
    <g data-n="front">
      <clipPath id="${u.id('sky')}"><path d="${frame}"/></clipPath>
      <g filter="${u.sh}"><g filter="${u.hi}"><path d="${frame}" fill="${u.upS}"/></g></g>
      <g clip-path="url(#${u.id('sky')})">
        <circle cx="20.4" cy="9.8" r="2.5" fill="#fff"/>
        <path d="${rp([[-1, 23], [-1, 20.2], [10.6, 10.8], [23, 20.8], [23, 23]], [0, 0, 1.8, 0, 0])}" fill="${u.loS}"/>
        <path d="${rp([[8, 23], [8, 21.4], [19.8, 13.2], [32, 20.8], [32, 23]], [0, 0, 1.8, 0, 0])}" fill="${u.pure('#5393FF')}"/>
      </g>
      <path d="${frame}" ${OL}/>
    </g>`;
  },
  target: (h) => ({ back: h ? [-1.4, -1.2, -9] : [0, 0, 0], front: h ? [0.6, 0.2, 4] : [0, 0, 0] }),
  draw(p, E) {
    at(E.back, { transform: `translate(${f(p.back[0])} ${f(p.back[1])}) rotate(${f(p.back[2])} 15.5 21.7)` });
    at(E.front, { transform: `translate(${f(p.front[0])} ${f(p.front[1])}) rotate(${f(p.front[2])} 15.5 21.7)` });
  }
});
