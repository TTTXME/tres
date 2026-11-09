import { defineIcon, KR, f, OL, DEEPS, rr, at } from '../packages/core/src/kit.js';
import { solid } from './_devices.js';

// Two pins on each side of the package, ending 2.3 past its edge (round caps add 1.1).
const P = [12.5, 18.5];
const PINS = P.map((v) => `M${v} 4.4V2.1M${v} 20.6V22.9M7.4 ${v - 3}H5.1M23.6 ${v - 3}H25.9`).join('');

export default defineIcon({
  name: 'cpu',
  zh: '处理器',
  category: 'devices',
  tags: ['chip', 'processor', 'microchip', 'hardware', 'compute', '芯片'],
  variants: ['check', 'alert', 'bolt', 'cog', 'sparkle'],
  // A processor with pins on every side; on hover its core lights up.
  svg: (u) => {
    const die = rr(11.2, 8.2, 8.6, 8.6, 1.8);
    return `<path d="${PINS}" fill="none" stroke-width="2.2" stroke-linecap="round" ${DEEPS}/>
      ${solid(u, rr(7.4, 4.4, 16.2, 16.2, KR), u.loS)}
      <g filter="${u.hi}"><path d="${die}" fill="${u.upS}"/><path data-n="lit" d="${die}" fill="${u.pure('#4389FF')}"/></g><path d="${die}" ${OL}/>`;
  },
  target: (h) => ({ o: h ? 1 : 0 }),
  draw(p, E) {
    at(E.lit, { opacity: f(p.o) });
  }
});
