import { defineIcon, f, OL, rr, circ, at } from '../packages/core/src/kit.js';
import { back } from './_interface.js';

const ROWS = [7.2, 17.8];

export default defineIcon({
  name: 'sliders',
  zh: '调节',
  category: 'interface',
  tags: ['sliders', 'adjust', 'controls', 'settings', 'equalizer', 'filter', '滑块'],
  // Two sliders; on hover their knobs glide to new values in opposite directions.
  svg: (u) => ROWS.map((y, i) => `${back(u, rr(4, y - 2.2, 23, 4.4, 2.2))}<path data-n="v${i}" data-t="fill:#4389FF"/>
    <g data-n="k${i}"><g filter="${u.sh}"><g filter="${u.hi}"><path d="${circ(0, 0, 3.6)}" fill="${u.upS}"/></g></g><path d="${circ(0, 0, 3.6)}" ${OL}/></g>`).join(''),
  target: (h) => ({ x: h ? [12, 19.6] : [19.6, 11.4] }),
  draw(p, E) {
    ROWS.forEach((y, i) => {
      const x = p.x[i];
      at(E['k' + i], { transform: `translate(${f(x)} ${y})` });
      at(E['v' + i], { d: rr(4, y - 2.2, x - 4, 4.4, 2.2) });
    });
  }
});
