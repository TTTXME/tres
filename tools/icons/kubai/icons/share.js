import { defineIcon, f, OL, DEEPS, circ, at } from '../packages/core/src/kit.js';

export default defineIcon({
  name: 'share',
  zh: '分享',
  category: 'communication',
  tags: ['network', 'connect', 'send', 'social'],
  variants: ['check', 'lock', 'plus'],
  // Three connected nodes; the shared ones spread out on hover.
  svg: (u) => `<path data-n="ln" fill="none" stroke-width="2.2" stroke-linecap="round" ${DEEPS}/>
    <g filter="${u.sh}"><g filter="${u.hi}"><path d="${circ(9.4, 12.5, 4.4)}" fill="${u.loS}"/></g></g><path d="${circ(9.4, 12.5, 4.4)}" ${OL}/>
    <g data-n="n1"><g filter="${u.sh}"><g filter="${u.hi}"><path d="${circ(0, 0, 4)}" fill="${u.upS}"/></g></g><path d="${circ(0, 0, 4)}" ${OL}/></g>
    <g data-n="n2"><g filter="${u.sh}"><g filter="${u.hi}"><path d="${circ(0, 0, 4)}" fill="${u.upS}"/></g></g><path d="${circ(0, 0, 4)}" ${OL}/></g>`,
  target: (h) => ({ d: h ? 1.2 : 0 }),
  draw(p, E) {
    const a = [21.8 + p.d, 6.4 - p.d * 0.6], b = [21.8 + p.d, 18.6 + p.d * 0.6];
    at(E.n1, { transform: `translate(${f(a[0])} ${f(a[1])})` });
    at(E.n2, { transform: `translate(${f(b[0])} ${f(b[1])})` });
    at(E.ln, { d: `M9.4 12.5L${f(a[0])} ${f(a[1])}M9.4 12.5L${f(b[0])} ${f(b[1])}` });
  }
});
