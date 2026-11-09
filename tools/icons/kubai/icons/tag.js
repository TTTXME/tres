import { defineIcon, KR, f, OL, rp, at } from '../packages/core/src/kit.js';

export default defineIcon({
  name: 'tag',
  zh: '标签',
  category: 'commerce',
  tags: ['label', 'price', 'category'],
  variants: ['plus', 'minus', 'check', 'x', 'search', 'heart', 'star', 'sparkle'],
  // A hanging tag that swings on hover.
  svg: (u) => {
    const tag = rp([[4.4, 12.5], [11, 5.2], [26, 5.2], [26, 19.8], [11, 19.8]], [1.6, 1.6, KR, KR, 1.6]);
    return `<g filter="${u.sh}"><g filter="${u.hi}"><g data-n="t"><path d="${tag}" fill="${u.loS}"/><circle cx="11.6" cy="12.5" r="2.2" fill="#fff"/></g></g></g>
      <g data-n="tO"><path d="${tag}" ${OL}/></g>`;
  },
  target: (h) => ({ a: h ? -22 : -34 }),
  draw(p, E) {
    const t = `rotate(${f(p.a)} 15.5 12.5)`;
    at(E.t, { transform: t }); at(E.tO, { transform: t });
  }
});
