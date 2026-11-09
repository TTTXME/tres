import { defineIcon, f, OL, rp, at } from '../packages/core/src/kit.js';

export default defineIcon({
  name: 'music',
  zh: '音乐',
  category: 'media',
  tags: ['note', 'song', 'audio', 'melody'],
  variants: ['plus', 'heart', 'star', 'sparkle'],
  off: true,
  // Two beamed notes that bounce on hover.
  svg: (u) => {
    const stems = rp([[12.4, 5], [25, 2.4], [25, 17.2], [22.8, 17.2], [22.8, 6.6], [14.6, 8.4], [14.6, 19.2], [12.4, 19.2]], [0.8, 0.8, 0.6, 0.6, 0.3, 0.3, 0.6, 0.6]);
    const head = (cx, cy) => `M${f(cx - 3.6)} ${f(cy)}A3.6 2.7 0 1 0 ${f(cx + 3.6)} ${f(cy)}A3.6 2.7 0 1 0 ${f(cx - 3.6)} ${f(cy)}Z`;
    const heads = head(10.8, 19.4) + head(21, 17.4);
    return `<g data-n="g">
      <g filter="${u.sh}"><g filter="${u.hi}"><path d="${stems}" fill="${u.loS}"/></g></g><path d="${stems}" ${OL}/>
      <g transform="rotate(-18 15.9 18.4)"><g filter="${u.hi}"><path d="${heads}" fill="${u.upS}"/></g><path d="${heads}" ${OL}/></g>
    </g>`;
  },
  target: (h) => ({ y: h ? -1.4 : 0, a: h ? -6 : 0 }),
  draw(p, E) { at(E.g, { transform: `translate(0 ${f(p.y)}) rotate(${f(p.a)} 15.5 12.5)` }); }
});
