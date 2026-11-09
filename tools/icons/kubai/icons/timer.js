import { defineIcon, f, OL, DEEP, rr, circ, sector, at } from '../packages/core/src/kit.js';

const CY = 13.6, BTN = rr(12.6, 0.2, 5.8, 3.8, 1.4);

export default defineIcon({
  name: 'timer',
  zh: '计时器',
  category: 'time',
  tags: ['countdown', 'timer', 'remaining', 'duration', '倒计时'],
  // A countdown timer showing the time left as a wedge; on hover it counts down and
  // the wedge shrinks behind the hand.
  svg: (u) => `
    <g filter="${u.sh}"><g filter="${u.hi}"><path d="${BTN}" fill="${u.loS}"/></g></g><path d="${BTN}" ${OL}/>
    <g filter="${u.sh}"><g filter="${u.hi}"><path d="${circ(15.5, CY, 10)}" fill="${u.loS}"/></g></g>
    <path d="${circ(15.5, CY, 10)}" ${OL}/>
    <path d="${circ(15.5, CY, 7.4)}" fill="#fff"/>
    <g filter="${u.hi}"><path data-n="left" fill="${u.upS}"/></g>
    <path data-n="hand" d="M15.5 ${CY}V${CY - 6}" stroke-width="2" stroke-linecap="round" data-t="stroke:#2F6FE0"/>
    <circle cx="15.5" cy="${CY}" r="1.7" ${DEEP}/>`,
  target: (h) => ({ sw: h ? 140 : 250 }),
  draw(p, E) {
    at(E.left, { d: sector(0, p.sw, 6.4, 15.5, CY) });
    at(E.hand, { transform: `rotate(${f(p.sw)} 15.5 ${CY})` });
  }
});
