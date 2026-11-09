// The tray shared by import and export: a landscape back and a lighter front wall with a
// dip in its top edge, like an in-tray.
import { KR, OL, rr, rp, at } from '../packages/core/src/kit.js';
import { back } from './_arrows.js';

const BACK = rr(4, 3.3, 23, 18.4, KR);
/** The front wall with its top edge lowered by dy. */
const frontAt = (dy) => rp([[4, 14 + dy], [10.4, 14 + dy], [12, 16.4 + dy], [19, 16.4 + dy], [20.6, 14 + dy], [27, 14 + dy], [27, 21.7], [4, 21.7]],
  [1.8, 0.8, 0.8, 0.8, 0.8, 1.8, KR, KR]);

export const trayBack = (u) => back(u, BACK);
/** The front wall; trayLower() moves its top edge. */
export const trayFront = (u) => {
  const d = frontAt(0);
  return `<g filter="${u.hi}"><path data-n="tf" d="${d}" fill="${u.upS}"/></g><path data-n="tfO" d="${d}" ${OL}/>`;
};
/** Lowers the top edge of trayFront() by dy. */
export const trayLower = (E, dy) => {
  const d = frontAt(dy);
  at(E.tf, { d }); at(E.tfO, { d });
};
