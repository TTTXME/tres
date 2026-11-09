import { defineIcon, KR, f, OL, R, rp, rr, lines, at } from '../packages/core/src/kit.js';

export default defineIcon({
  name: 'search',
  zh: '搜索',
  category: 'interface',
  tags: ['find', 'lookup', 'magnifier', 'zoom', 'page'],
  // The page is the subject; the lens is a supporting mark on its corner, a solid
  // ring with no inner outline so it never leaves a thin band.
  svg: (u) => `
    <g filter="${u.sh}"><g filter="${u.hi}"><path d="${rr(4, 1, 16.8, 20.2, KR)}" fill="${u.lo(20.2)}"/></g></g>
    <path d="${rr(4, 1, 16.8, 20.2, KR)}" ${OL}/>
    <path d="${lines(7.4, 4.8, [10, 6.6])}" class="ln" stroke="#fff" data-w="stroke"/>
    <g filter="${u.hi}"><path d="${rp(R(4, 10.8, 16.8, 10.4), [1.6, 1.6, KR, KR])}" fill="${u.upS}"/></g>
    <path d="${rp(R(4, 10.8, 16.8, 10.4), [1.6, 1.6, KR, KR])}" ${OL}/>
    <g data-n="lens" filter="${u.sh}">
      <circle r="3.6" fill="#fff" fill-opacity=".8"/>
      <circle r="3.6" fill="${u.upS}" fill-opacity=".35"/>
      <circle r="4.5" fill="none" stroke-width="1.8" data-t="stroke:#2F6FE0"/>
      <path d="M3.8 3.8L6.6 6.6" stroke-width="2.2" stroke-linecap="round" data-t="stroke:#2F6FE0"/>
    </g>`,
  target: (h) => ({ lens: h ? [13.2, 10.4, 1.05] : [19.8, 16.8, 1] }),
  draw(p, E) {
    const [x, y, s] = p.lens;
    at(E.lens, { transform: `translate(${f(x)} ${f(y)}) scale(${f(s)})` });
  }
});
