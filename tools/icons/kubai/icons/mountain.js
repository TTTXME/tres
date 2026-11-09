import { defineIcon, KR, f, OL, rp, at } from '../packages/core/src/kit.js';

const BIG = rp([[17.6, 2.4], [28.6, 22.6], [6.6, 22.6]], [1.6, KR, KR]);
const SMALL = rp([[9.6, 10], [17.2, 22.6], [2.4, 22.6]], [1.6, KR, KR]);

export default defineIcon({
  name: 'mountain',
  zh: '山',
  category: 'nature',
  tags: ['mountain', 'peak', 'hiking', 'landscape', 'outdoor', 'summit', '山峰'],
  // A snow-capped peak with a lighter hill in front; on hover fresh snow spreads far down the peak.
  svg: (u) => `<clipPath id="${u.id('pk')}"><path d="${BIG}"/></clipPath>
    <g filter="${u.sh}"><g filter="${u.hi}"><path d="${BIG}" fill="${u.loS}"/></g></g>
    <path data-n="snow" clip-path="url(#${u.id('pk')})" fill="#fff"/><path d="${BIG}" stroke-linejoin="round" ${OL}/>
    <g filter="${u.sh}"><g filter="${u.hi}"><path d="${SMALL}" fill="${u.upS}"/></g></g><path d="${SMALL}" stroke-linejoin="round" ${OL}/>`,
  target: (h) => ({ y: h ? 14.4 : 8.6 }),
  draw(p, E) {
    const y = p.y, x = (dy) => f(17.6 + (dy - 2.4) * 0.545);
    at(E.snow, { d: `M8 0H28V${f(y)}L${x(y)} ${f(y)}L${f(17.6 + 2.2)} ${f(y - 2.2)}L17.6 ${f(y)}L${f(17.6 - 2.2)} ${f(y - 2.2)}L${f(35.2 - x(y))} ${f(y)}L8 ${f(y)}Z` });
  }
});
