import { defineIcon, OL, DEEPS, WHITES, rp, lines, at } from '../packages/core/src/kit.js';
import { corners } from './_w2-interface.js';

const X0 = 10.4, X1 = 20.6, Y0 = 6.9, Y1 = 18.1, FOLD = 3.6;
const PAGE = rp([[X0, Y0], [X1 - FOLD, Y0], [X1, Y0 + FOLD], [X1, Y1], [X0, Y1]], [1.6, 0.4, 0.4, 1.6, 1.6]);
const FOLDP = rp([[X1 - FOLD, Y0], [X1, Y0 + FOLD], [X1 - FOLD, Y0 + FOLD]], [0.3, 0.3, 0.8]);

export default defineIcon({
  name: 'scan',
  zh: '扫描文档',
  category: 'devices',
  tags: ['scan', 'document scan', 'viewfinder', 'capture', 'scanner', 'ocr', '扫描'],
  variants: ['check', 'x'],
  // A page inside the four corners of a viewfinder; on hover the corners close in on the page.
  svg: (u) => `<path data-n="fr" fill="none" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" ${DEEPS}/>
    <g filter="${u.sh}"><g filter="${u.hi}"><path d="${PAGE}" fill="${u.loS}"/></g></g><path d="${PAGE}" ${OL}/>
    <path d="${lines(12.6, 12.2, [5.8, 3.8])}" class="ln" ${WHITES}/>
    <g filter="${u.hi}"><path d="${FOLDP}" fill="${u.upS}"/></g><path d="${FOLDP}" ${OL}/>`,
  target: (h) => ({ i: h ? 0.8 : 0 }),
  draw(p, E) { at(E.fr, { d: corners(5 + p.i, 2 + p.i, 26 - p.i, 23 - p.i) }); }
});
