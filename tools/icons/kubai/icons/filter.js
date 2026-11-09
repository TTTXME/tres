import { defineIcon, f, OL, rp, lines, at } from '../packages/core/src/kit.js';

export default defineIcon({
  name: 'filter',
  zh: '筛选',
  category: 'interface',
  tags: ['funnel', 'sort', 'refine'],
  variants: ['plus', 'minus', 'check', 'x', 'cog'],
  off: true,
  // A funnel whose lines narrow and sink toward the neck on hover, as if drawn through.
  svg: (u) => {
    const top = rp([[5.2, 2.6], [25.8, 2.6], [18.2, 12.8], [12.8, 12.8]], [1.6, 1.6, 0.8, 0.8]);
    const spout = rp([[12.8, 12], [18.2, 12], [18.2, 21.4], [12.8, 24]], [0.4, 0.4, 1, 1]);
    return `<g filter="${u.sh}"><g filter="${u.hi}"><path d="${top}" fill="${u.loS}"/></g></g><path d="${top}" ${OL}/>
      <g filter="${u.sh}"><g filter="${u.hi}"><path d="${spout}" fill="${u.upS}"/></g></g><path d="${spout}" ${OL}/>
      <path data-n="ln" class="ln" stroke="#fff" data-w="stroke"/>`;
  },
  target: (h) => ({ w: h ? [8.4, 3.4] : [12.4, 6.6], y: h ? 1.8 : 0 }),
  draw(p, E) {
    const [a, b] = p.w;
    at(E.ln, { d: `M${f(15.5 - a / 2)} ${f(5.8 + p.y)}H${f(15.5 + a / 2)}M${f(15.5 - b / 2)} ${f(8.8 + p.y)}H${f(15.5 + b / 2)}` });
  }
});
