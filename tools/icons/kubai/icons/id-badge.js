import { defineIcon, KR, CR, f, OL, DEEPS, R, rp, rr, circ, clamp01, rattle, at } from '../packages/core/src/kit.js';

export default defineIcon({
  name: 'id-badge',
  zh: '工牌',
  category: 'people',
  tags: ['badge', 'id card', 'employee', 'name tag', 'identity', 'pass', '胸牌'],
  variants: ['check', 'x', 'lock', 'search', 'plus', 'alert', 'star', 'clock', 'sync'],
  // A clip-on name badge with a photo and a name strip; on hover it swings on its clip.
  svg: (u) => {
    const card = rr(6.4, 4.2, 18.2, 19.8, KR), clip = rr(12.8, 1.2, 5.4, 5, CR);
    const strip = rp(R(6.4, 17.4, 18.2, 6.6), [1.6, 1.6, KR, KR]);
    return `<g data-n="g"><path d="${clip}" fill="#fff"/><path d="${clip}" ${OL}/>
      <g filter="${u.sh}"><g filter="${u.hi}"><path d="${card}" fill="${u.loS}"/></g></g><path d="${card}" ${OL}/>
      <path d="${rr(13.2, 2.8, 4.6, 1.8, 0.9)}" fill="#fff"/>
      <path d="${circ(15.5, 9.6, 2.8)}" fill="#fff"/><path d="${rp(R(10.6, 13.4, 9.8, 4.6), [3.8, 3.8, 0, 0])}" fill="#fff"/>
      <g filter="${u.hi}"><path d="${strip}" fill="${u.upS}"/></g><path d="${strip}" ${OL}/>
      <path d="M11.4 20.7H19.6" class="ln" ${DEEPS}/></g>`;
  },
  target: (h) => ({ o: h ? 1 : 0 }),
  draw(p, E) { at(E.g, { transform: `rotate(${f(rattle(clamp01(p.o), 9, 3))} 15.5 2.4)` }); }
});
