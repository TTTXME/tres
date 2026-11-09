import { defineIcon, f, OL, DEEPS, circ, at } from '../packages/core/src/kit.js';

const RAYS = (r0, r1) => Array.from({ length: 8 }, (_, i) => {
  const a = i * Math.PI / 4, s = Math.sin(a), c = -Math.cos(a);
  return `M${f(15.5 + s * r0)} ${f(12.5 + c * r0)}L${f(15.5 + s * r1)} ${f(12.5 + c * r1)}`;
}).join('');

export default defineIcon({
  name: 'sun',
  zh: '太阳',
  category: 'weather',
  tags: ['sunny', 'day', 'brightness', 'light mode', 'clear', '晴天', '亮度'],
  variants: ['plus', 'minus'],
  // A sun with a lighter core; on hover its rays turn an eighth and reach a little further.
  svg: (u) => `<path data-n="rays" fill="none" stroke-width="2.2" stroke-linecap="round" ${DEEPS}/>
    <g filter="${u.sh}"><g filter="${u.hi}"><path d="${circ(15.5, 12.5, 6)}" fill="${u.loS}"/></g></g><path d="${circ(15.5, 12.5, 6)}" ${OL}/>
    <g filter="${u.hi}"><path d="${circ(15.5, 12.5, 3.6)}" fill="${u.upS}"/></g>`,
  target: (h) => ({ rot: h ? 45 : 0, r1: h ? 12 : 11.6 }),
  draw(p, E) { at(E.rays, { d: RAYS(10.1, p.r1), transform: `rotate(${f(p.rot)} 15.5 12.5)` }); }
});
