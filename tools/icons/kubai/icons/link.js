import { defineIcon, f, OL, capsuleRing, at } from '../packages/core/src/kit.js';

const LINK_RING = capsuleRing(15.6, 9.6, 2.8);

export default defineIcon({
  name: 'link',
  zh: '链接',
  category: 'interface',
  tags: ['chain', 'url', 'hyperlink', 'connect'],
  variants: ['plus', 'x', 'check', 'lock'],
  off: true,
  // Two links of a chain; hover draws them tighter.
  svg: (u) => `
    <g data-n="g">
      <g data-n="a"><g filter="${u.sh}"><g filter="${u.hi}"><path d="${LINK_RING}" fill-rule="evenodd" fill="${u.loS}"/></g></g><path d="${LINK_RING}" fill-rule="evenodd" ${OL}/></g>
      <g data-n="bb"><g filter="${u.sh}"><g filter="${u.hi}"><path d="${LINK_RING}" fill-rule="evenodd" fill="${u.upS}"/></g></g><path d="${LINK_RING}" fill-rule="evenodd" ${OL}/></g>
    </g>`,
  target: (h) => ({ gap: h ? 3.5 : 4.2, rot: h ? -40 : -45 }),
  draw(p, E) {
    at(E.g, { transform: `rotate(${f(p.rot)} 15.5 12.5)` });
    at(E.a, { transform: `translate(${f(15.5 - p.gap)} 12.5)` });
    at(E.bb, { transform: `translate(${f(15.5 + p.gap)} 12.5)` });
  }
});
