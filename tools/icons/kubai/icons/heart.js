import { defineIcon, f, OL, at } from '../packages/core/src/kit.js';

export default defineIcon({
  name: 'heart',
  zh: '喜欢',
  category: 'people',
  tags: ['like', 'love', 'favorite', 'health'],
  variants: ['plus', 'minus', 'check', 'x'],
  off: true,
  // A heart with a lighter heart inside; it beats on hover.
  svg: (u) => {
    const heart = 'M0 9.6C-5.5 5.6 -10.9 1.4 -10.9 -3.6C-10.9 -7 -8.3 -9.6 -5.1 -9.6C-2.9 -9.6 -1.1 -8.4 0 -6.6C1.1 -8.4 2.9 -9.6 5.1 -9.6C8.3 -9.6 10.9 -7 10.9 -3.6C10.9 1.4 5.5 5.6 0 9.6Z';
    return `<g data-n="g">
      <g filter="${u.sh}"><g filter="${u.hi}"><path d="${heart}" fill="${u.loS}"/></g></g><path d="${heart}" ${OL}/>
      <g transform="translate(0 -0.6) scale(0.5)"><g filter="${u.hi}"><path d="${heart}" fill="${u.upS}"/></g></g>
    </g>`;
  },
  target: (h) => ({ s: h ? 1.12 : 1 }),
  draw(p, E) { at(E.g, { transform: `translate(15.5 13.2) scale(${f(p.s)})` }); }
});
