// A countdown timer with its seconds written on the face (stroked digits, not text): the body and button of the library's
// timer; on press the ring of the remaining time shrinks.
import { defineIcon, OL, f, rr, circ, sector, at } from '../kubai/packages/core/src/kit.js';
import { digits } from './_camera.js';

const CY = 13.6, BTN = rr(12.6, 0.2, 5.8, 3.8, 1.4);
export function timerWith(name, text) {
  return defineIcon({
    name,
    zh: '倒计时',
    category: 'camera',
    tags: ['timer', 'self timer', 'countdown'],
    svg: (u) => `
    <g filter="${u.sh}"><g filter="${u.hi}"><path d="${BTN}" fill="${u.loS}"/></g></g><path d="${BTN}" ${OL}/>
    <g filter="${u.sh}"><g filter="${u.hi}"><path d="${circ(15.5, CY, 10)}" fill="${u.loS}"/></g></g>
    <path d="${circ(15.5, CY, 10)}" ${OL}/>
    <path d="${circ(15.5, CY, 7.4)}" fill="#fff"/>
    <g filter="${u.hi}"><path data-n="left" fill="${u.upS}" opacity=".55"/></g>
    <path d="${digits(text, 15.5, CY, text.length > 1 ? 0.78 : 0.92)}" fill="none" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" data-t="stroke:#2F6FE0"/>`,
    target: (h) => ({ sw: h ? 120 : 360 }),
    draw(p, E) { at(E.left, { d: sector(360 - p.sw, p.sw - 0.01, 7.4, 15.5, CY) }); }
  });
}
