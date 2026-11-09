import { defineIcon, KR, f, OL, rp, rr, at } from '../packages/core/src/kit.js';

// The two hills, each lowered by dy.
const BACK = [[-1, 23], [-1, 20.4], [10.6, 10.6], [23, 21], [23, 23]], FRONT = [[8, 23], [8, 21.6], [19.8, 13.2], [32, 21], [32, 23]];
const hill = (pts, dy) => rp(pts.map(([x, y], i) => [x, i && i < 4 ? y + dy : y]), [0, 0, 1.8, 0, 0]);

export default defineIcon({
  name: 'image',
  zh: '图片',
  category: 'media',
  tags: ['picture', 'photo', 'landscape', 'img', '照片', '图像'],
  variants: ['plus', 'minus', 'check', 'x', 'alert', 'up', 'down', 'search', 'lock', 'heart', 'star', 'sparkle'],
  // A single picture of hills under a sun; on hover the sun rises high and the hills sink as the view tilts up.
  svg: (u) => {
    const frame = rr(4, 3.3, 23, 18.4, KR);
    return `<clipPath id="${u.id('sky')}"><path d="${frame}"/></clipPath>
    <g filter="${u.sh}"><g filter="${u.hi}"><path d="${frame}" fill="${u.upS}"/></g></g>
    <g clip-path="url(#${u.id('sky')})">
      <circle data-n="sun" cx="20.2" r="2.6" fill="#fff"/>
      <path data-n="back" d="${hill(BACK, 0)}" fill="${u.loS}"/>
      <path data-n="front" d="${hill(FRONT, 0)}" fill="${u.pure('#5393FF')}"/>
    </g>
    <path d="${frame}" ${OL}/>`;
  },
  target: (h) => ({ y: h ? 6.3 : 9.2, r: h ? 2.9 : 2.6, dy: h ? 2.4 : 0 }),
  draw(p, E) {
    at(E.sun, { r: f(p.r), cy: f(p.y) });
    at(E.back, { d: hill(BACK, p.dy * 0.6) }); at(E.front, { d: hill(FRONT, p.dy) });
  }
});
