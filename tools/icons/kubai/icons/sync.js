import { defineIcon, f, OL, circ, arcArrow, at } from '../packages/core/src/kit.js';

// Each arrow's tip stops well short of the other arc's tail.
const SYNC_A = arcArrow(215, 300, 4.4), SYNC_B = arcArrow(35, 120, 4.4);

export default defineIcon({
  name: 'sync',
  zh: '同步',
  category: 'arrows',
  tags: ['refresh', 'reload', 'update', 'rotate', 'cycle'],
  variants: ['alert', 'check', 'x', 'clock'],
  // A dial with two chasing arrows that turn half a revolution on hover.
  svg: (u) => `
    <g filter="${u.sh}"><g filter="${u.hi}"><path d="${circ(15.5, 12.5, 11.5)}" fill="${u.loS}"/></g></g>
    <path d="${circ(15.5, 12.5, 11.5)}" ${OL}/>
    <g filter="${u.hi}"><path d="${circ(15.5, 12.5, 8)}" fill="${u.upS}"/></g>
    <path d="${circ(15.5, 12.5, 8)}" ${OL}/>
    <g data-n="arr"><path d="${SYNC_A.arc}${SYNC_B.arc}" fill="none" stroke-width="2.2" stroke-linecap="round" data-t="stroke:#2F6FE0"/><path d="${SYNC_A.head}${SYNC_B.head}" data-t="fill:#2F6FE0"/></g>`,
  target: (h) => ({ rot: h ? 180 : 0 }),
  draw(p, E) {
    at(E.arr, { transform: `translate(15.5 12.5) rotate(${f(p.rot)})` });
  }
});
