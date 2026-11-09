import { defineIcon, f, OL, rp, circ, at } from '../packages/core/src/kit.js';

export default defineIcon({
  name: 'key',
  zh: '钥匙',
  category: 'security',
  tags: ['password', 'unlock', 'access', 'login'],
  variants: ['plus', 'check', 'x', 'lock', 'sync', 'search'],
  // A key that turns over on hover, its teeth flipping to the other side.
  svg: (u) => {
    const shaft = rp([[13.6, 10.9], [28.2, 10.9], [28.2, 17.6], [25.4, 17.6], [25.4, 14.1], [23.6, 14.1], [23.6, 18.6], [20.8, 18.6], [20.8, 14.1], [13.6, 14.1]], [0.4, 0.9, 0.7, 0.7, 0.3, 0.3, 0.7, 0.7, 0.3, 0.4]);
    const head = circ(9.4, 12.5, 7) + circ(9.4, 12.5, 2.6);
    return `<g filter="${u.sh}"><g filter="${u.hi}"><g data-n="k"><path d="${shaft}" fill="${u.loS}"/><path d="${head}" fill-rule="evenodd" fill="${u.upS}"/></g></g></g>
      <g data-n="ko"><path d="${shaft}" ${OL}/><path d="${head}" fill-rule="evenodd" ${OL}/></g>`;
  },
  target: (h) => ({ o: h ? 1 : 0 }),
  draw(p, E) {
    const sy = Math.cos(Math.max(0, Math.min(1, p.o)) * Math.PI);
    const t = `translate(0 12.5) scale(1 ${f(Math.abs(sy) < 0.001 ? 0.001 : sy)}) translate(0 -12.5)`;
    at(E.k, { transform: t }); at(E.ko, { transform: t });
  }
});
