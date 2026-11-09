import { defineIcon, f, OL, DEEPS, rp, rr, at } from '../packages/core/src/kit.js';

export default defineIcon({
  name: 'volume',
  zh: '音量',
  category: 'media',
  tags: ['sound', 'speaker', 'audio', 'loud'],
  off: true,
  // A speaker whose sound waves grow louder on hover.
  svg: (u) => {
    const box = rr(4.4, 9, 6, 7, 1.4), cone = rp([[9.6, 9], [16, 4.2], [16, 20.8], [9.6, 16]], [0.6, 1.2, 1.2, 0.6]);
    return `<g filter="${u.sh}"><g filter="${u.hi}"><path d="${box}" fill="${u.loS}"/></g></g><path d="${box}" ${OL}/>
      <g filter="${u.sh}"><g filter="${u.hi}"><path d="${cone}" fill="${u.upS}"/></g></g><path d="${cone}" ${OL}/>
      <path data-n="w1" fill="none" stroke-width="2.2" stroke-linecap="round" ${DEEPS}/>
      <path data-n="w2" fill="none" stroke-width="2.2" stroke-linecap="round" ${DEEPS}/>`;
  },
  target: (h) => ({ r: h ? [5.4, 9.8] : [4.6, 8.6], o: h ? 1 : 0.3 }),
  draw(p, E) {
    const w = (r) => { const a = 50 * Math.PI / 180; return `M${f(16.4 + r * Math.cos(a))} ${f(12.5 - r * Math.sin(a))}A${f(r)} ${f(r)} 0 0 1 ${f(16.4 + r * Math.cos(a))} ${f(12.5 + r * Math.sin(a))}`; };
    at(E.w1, { d: w(p.r[0]) }); at(E.w2, { d: w(p.r[1]), opacity: f(p.o) });
  }
});
