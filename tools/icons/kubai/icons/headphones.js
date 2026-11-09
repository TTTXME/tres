import { defineIcon, f, OL, rr, at } from '../packages/core/src/kit.js';

export default defineIcon({
  name: 'headphones',
  zh: '耳机',
  category: 'media',
  tags: ['audio', 'listen', 'music', 'headset'],
  variants: ['check', 'x'],
  off: true,
  // Headphones whose cups press in on hover.
  svg: (u) => {
    const band = 'M4.4 14.4A11.1 11.1 0 0 1 26.6 14.4H24A8.5 8.5 0 0 0 7 14.4Z';
    const cup = rr(0, 0, 6.8, 10.6, 3);
    return `<g filter="${u.sh}"><g filter="${u.hi}"><path d="${band}" fill="${u.loS}"/></g></g><path d="${band}" ${OL}/>
      <g data-n="l"><g filter="${u.sh}"><g filter="${u.hi}"><path d="${cup}" fill="${u.upS}"/></g></g><path d="${cup}" ${OL}/></g>
      <g data-n="r"><g filter="${u.sh}"><g filter="${u.hi}"><path d="${cup}" fill="${u.upS}"/></g></g><path d="${cup}" ${OL}/></g>`;
  },
  target: (h) => ({ d: h ? 0.8 : 0 }),
  draw(p, E) {
    at(E.l, { transform: `translate(${f(3.6 + p.d)} 12.6)` });
    at(E.r, { transform: `translate(${f(20.6 - p.d)} 12.6)` });
  }
});
