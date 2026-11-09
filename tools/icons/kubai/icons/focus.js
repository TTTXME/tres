import { defineIcon, f, OL, DEEPS, circ, at } from '../packages/core/src/kit.js';

export default defineIcon({
  name: 'focus',
  zh: '对焦',
  category: 'media',
  tags: ['viewfinder', 'target', 'autofocus', 'frame', 'capture', 'scan', '聚焦'],
  // A subject inside four viewfinder corners; on hover the corners close in to lock focus.
  svg: (u) => `<g filter="${u.sh}"><g filter="${u.hi}"><path d="${circ(15.5, 12.5, 4.8)}" fill="${u.loS}"/></g></g><path d="${circ(15.5, 12.5, 4.8)}" ${OL}/>
    <path data-n="br" fill="none" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" ${DEEPS}/>`,
  target: (h) => ({ d: h ? 1.6 : 0 }),
  draw(p, E) {
    const a = 9.6 - p.d, r = 2.6, l = 5.2;
    let d = '';
    for (const [sx, sy] of [[-1, -1], [1, -1], [1, 1], [-1, 1]]) {
      const cx = 15.5 + sx * a, cy = 12.5 + sy * a;
      d += `M${f(cx)} ${f(cy - sy * l)}V${f(cy - sy * r)}Q${f(cx)} ${f(cy)} ${f(cx - sx * r)} ${f(cy)}H${f(cx - sx * l)}`;
    }
    at(E.br, { d });
  }
});
