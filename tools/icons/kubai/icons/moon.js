import { defineIcon, f, OL, at } from '../packages/core/src/kit.js';
import { crescent } from './_weather-nature.js';

export default defineIcon({
  name: 'moon',
  zh: '月亮',
  category: 'weather',
  tags: ['night', 'dark mode', 'sleep', 'crescent', '夜间', '深色模式'],
  variants: ['clock'],
  // A crescent moon with a lighter inner crescent; on hover it waxes a little fuller.
  svg: (u) => `<g filter="${u.sh}"><g filter="${u.hi}"><path data-n="m" fill="${u.loS}"/></g></g><path data-n="mO" stroke-linejoin="round" ${OL}/>
    <g filter="${u.hi}"><path data-n="in" fill="${u.upS}"/></g>`,
  target: (h) => ({ cut: h ? [23.6, 5.4] : [22, 6.8] }),
  draw(p, E) {
    const [x, y] = p.cut, d = crescent([15, 13, 11], [x, y, 8.8]);
    at(E.m, { d }); at(E.mO, { d });
    at(E.in, { d: crescent([15, 13, 8.4], [x, y, 11.4]) });
  }
});
