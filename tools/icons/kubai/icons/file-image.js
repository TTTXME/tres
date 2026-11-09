import { defineIcon, CR, f, SOFT, rp, rr, at } from '../packages/core/src/kit.js';
import { typedPage, FILE_BADGES } from './_files-text.js';

const CARD = rr(9, 9.4, 13, 10.8, CR);
// The two hills, lowered by dy.
const hill0 = (dy) => rp([[8, 21 + dy], [8, 19.6 + dy], [13.2, 15 + dy], [19, 21 + dy]], [0, 0, 1.4, 0]);
const hill1 = (dy) => rp([[11.6, 21 + dy], [17.6, 13.4 + dy], [24, 21 + dy]], [0, 1.4, 0]);

export default defineIcon({
  name: 'file-image',
  zh: '图片文件',
  category: 'files',
  tags: ['image', 'picture', 'photo', 'jpg', 'png', '图片'],
  variants: FILE_BADGES,
  // A page holding a picture of hills; on hover the sun climbs clear of them along an arc, growing larger, as the hills settle lower.
  svg: (u) => typedPage(u, `<clipPath id="${u.id('pic')}"><path d="${CARD}"/></clipPath>
    <path d="${CARD}" fill="#fff"/>
    <g clip-path="url(#${u.id('pic')})">
      <circle data-n="sun" cx="13" r="1.8" ${SOFT}/>
      <path data-n="h0" d="${hill0(0)}" fill="${u.loS}"/>
      <path data-n="h1" d="${hill1(0)}" fill="${u.pure('#5393FF')}"/>
    </g>`),
  target: (h) => ({ sun: h ? 1 : 0 }),
  draw(p, E) {
    const o = Math.max(0, Math.min(1, p.sun));
    at(E.h0, { d: hill0(0.9 * o) }); at(E.h1, { d: hill1(1.1 * o) });
    at(E.sun, { cx: f(13 + 2 * o), cy: f(15.2 - 3.4 * Math.sin(Math.PI / 2 * o)), r: f(1.8 + 0.5 * o) });
  }
});
