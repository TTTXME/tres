import { defineIcon, CR, f, OL, rp, rr, at } from '../packages/core/src/kit.js';

const TRASH_LID = rp([[5.4, 6.6], [5.4, 4], [12.4, 4], [12.4, 1.6], [18.6, 1.6], [18.6, 4], [25.6, 4], [25.6, 6.6]], [1, 1, 0.5, 0.8, 0.8, 0.5, 1, 1]);

export default defineIcon({
  name: 'trash',
  zh: '回收站',
  category: 'interface',
  tags: ['delete', 'remove', 'bin', 'garbage', 'recycle'],
  variants: ['clock', 'x'],
  svg: (u) => `
    <g filter="${u.sh}"><path data-n="back" fill="${u.lo(18.6)}"/></g>
    <g data-n="card" filter="${u.sh}"><path d="${rr(0, 0, 10.4, 7.6, CR)}" fill="${u.upS}"/><circle cx="3" cy="3" r="1.4" fill="#fff"/></g>
    <g filter="${u.hi}"><path data-n="front" fill="${u.up(17.4)}"/></g>
    <path data-n="frontO" ${OL}/>
    <path data-n="ribs" fill="none" stroke-width="1.6" stroke-linecap="round" data-t="stroke:#5393FF" stroke-opacity=".6"/>
    <g data-n="lid"><g filter="${u.hi}"><path d="${TRASH_LID}" fill="${u.upS}"/></g><path d="${TRASH_LID}" ${OL}/></g>`,
  target: (h) => ({
    back: [[7.4, 4.8], [23.6, 4.8], [22.4, 23.4], [8.6, 23.4]],
    front: h ? [[6.6, 6], [24.4, 6], [22.6, 23.4], [8.4, 23.4]] : [[6.8, 6], [24.2, 6], [22.6, 23.4], [8.4, 23.4]],
    card: h ? [10.3, 2.4, -8] : [10.3, 12, 0],
    lid: h ? [0, -5.8, -6] : [0, 0, 0]
  }),
  draw(p, E) {
    at(E.back, { d: rp(p.back, [0.6, 0.6, 2.8, 2.8]) });
    const fr = rp(p.front, [1.2, 1.2, 2.8, 2.8]);
    at(E.front, { d: fr }); at(E.frontO, { d: fr });
    const F = p.front, top = F[0][1] + 3.4;
    at(E.ribs, { d: [0.36, 0.64].map((k) => `M${f(F[0][0] + (F[1][0] - F[0][0]) * k)} ${f(top)}L${f(F[3][0] + (F[2][0] - F[3][0]) * k)} 20.2`).join('') });
    at(E.card, { transform: `translate(${f(p.card[0])} ${f(p.card[1])}) rotate(${f(p.card[2])} 5.2 3.8)` });
    at(E.lid, { transform: `translate(${f(p.lid[0])} ${f(p.lid[1])}) rotate(${f(p.lid[2])} 15.5 5.3)` });
  }
});
