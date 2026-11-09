import { defineIcon, KR, CR, f, OL, DEEP, LINE, R, rp, rr, lines, at } from '../packages/core/src/kit.js';
import { solid } from './_devices.js';

export default defineIcon({
  name: 'save',
  zh: '保存',
  category: 'devices',
  tags: ['floppy disk', 'disk', 'store', 'write', '软盘'],
  variants: ['check', 'x', 'alert', 'sync', 'clock', 'up', 'down', 'lock'],
  // A floppy disk with a metal shutter and a label; on hover the shutter slides open.
  svg: (u) => {
    const body = rp([[5.2, 2.2], [21.6, 2.2], [25.8, 6.4], [25.8, 22.8], [5.2, 22.8]], [KR, 1.2, 1.2, KR, KR]);
    const sh = rp(R(0, 2.2, 9.4, 7.4), [0, 0, 1.4, 1.4]);
    return `${solid(u, body, u.loS)}
      <g data-n="sh"><g filter="${u.hi}"><path d="${sh}" fill="${u.upS}"/></g><path d="${sh}" ${OL}/>
        <path d="${rr(5.6, 3.6, 2.4, 4.4, 0.6)}" ${DEEP}/></g>
      <path d="${rr(8.2, 12.8, 14.6, 8, CR)}" fill="#fff"/>
      <path d="${lines(10.6, 15.6, [9.8, 6])}" ${LINE}/>`;
  },
  target: (h) => ({ x: h ? 8.2 : 11.2 }),
  draw(p, E) { at(E.sh, { transform: `translate(${f(p.x)} 0)` }); }
});
