import { defineIcon, WHITE, WHITES, f, arrow, at } from '../packages/core/src/kit.js';
import { trayBack, trayFront, trayLower } from './_tray.js';
import { swell } from './_arrows.js';

const A = arrow(15.5, 5.8, 15.5, 12.4, 3.4, 2.9);

export default defineIcon({
  name: 'import',
  zh: '导入',
  category: 'arrows',
  tags: ['import', 'inbox', 'tray', 'receive', 'arrow down', '收件'],
  // A tray with an arrow pointing down into it; on hover the front wall dips to take the
  // arrow, which drops in clear of it and settles a little lower.
  svg: (u) => `${trayBack(u)}
    <g data-n="arr"><path d="${A.shaft}" fill="none" stroke-width="2.2" stroke-linecap="round" ${WHITES}/><path d="${A.head}" ${WHITE}/></g>
    ${trayFront(u)}`,
  target: (h) => ({ o: h ? 1 : 0 }),
  draw(p, E) {
    const w = swell(p.o);
    trayLower(E, 2 * w);
    at(E.arr, { transform: `translate(0 ${f(p.o + 2.2 * w)})` });
  }
});
