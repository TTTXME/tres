import { defineIcon, f, OL, DEEPS, rattle, clamp01, at } from '../packages/core/src/kit.js';
import { leafShape } from './_weather-nature.js';

const LEAF = leafShape(21.4, 13);

export default defineIcon({
  name: 'leaf',
  zh: '叶子',
  category: 'nature',
  tags: ['eco', 'green', 'plant', 'nature', 'organic', 'environment', '环保'],
  // A leaf whose right half is lighter, on a short stem; on hover it sways on its stem and settles.
  svg: (u) => `<clipPath id="${u.id('lf')}"><path d="${LEAF}"/></clipPath>
    <g data-n="st"><path d="M0 -1V3.6" fill="none" stroke-width="2.2" stroke-linecap="round" ${DEEPS}/></g>
    <g filter="${u.sh}"><g filter="${u.hi}"><g data-n="a"><path d="${LEAF}" fill="${u.loS}"/></g></g></g>
    <g filter="${u.hi}"><g data-n="b"><path d="M0 1H8V-23H0Z" clip-path="url(#${u.id('lf')})" fill="${u.upS}"/></g></g>
    <g data-n="aO"><path d="${LEAF}" stroke-linejoin="round" ${OL}/></g>`,
  target: (h) => ({ o: h ? 1 : 0 }),
  draw(p, E) {
    const a = 40 + rattle(clamp01(p.o), 9, 3), t = `translate(9.6 20.4) rotate(${f(a)})`;
    ['a', 'b', 'aO'].forEach((n) => at(E[n], { transform: t }));
    at(E.st, { transform: `translate(9.6 20.4) rotate(${f(40 + (a - 40) * 0.4)})` });
  }
});
