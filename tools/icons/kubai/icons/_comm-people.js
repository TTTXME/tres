// Shared geometry for the communication and people icons: the telephone handset and the
// head-and-shoulders figure.
import { KR, f, R, rp, circ } from '../packages/core/src/kit.js';

/**
 * A telephone handset drawn straight onto the canvas: centre (cx, cy), scale s, turned by
 * deg. In its own frame the handle arches up and the two cups hang below its ends; at
 * -135 degrees the earpiece is top left, the mouthpiece bottom right and the cups face
 * the upper right. Returns { handle, cups } path data.
 */
export function handset(cx, cy, s, deg) {
  const a = deg * Math.PI / 180, ca = Math.cos(a), sa = Math.sin(a);
  const P = ([x, y]) => [cx + (x * ca - y * sa) * s, cy + (x * sa + y * ca) * s];
  const q = (p) => { const [x, y] = P(p); return `${f(x)} ${f(y)}`; };
  const handle = `M${q([-11, 1.4])}Q${q([0, -12.6])} ${q([11, 1.4])}L${q([6.2, 2.6])}Q${q([0, -2.4])} ${q([-6.2, 2.6])}Z`;
  const cup = (x) => rp(R(x, -1.4, 8.8, 7).map(P), [1.8 * s, 1.8 * s, KR * s, KR * s]);
  return { handle, cups: cup(-13.8) + cup(5) };
}

/**
 * A head-and-shoulders figure: the head of radius r centred on (cx, top + r) and the
 * shoulders below it, 2 units of clear space between their outlines.
 */
export function person(cx, top, r, w, h) {
  const by = top + 2 * r + 4;
  return {
    head: circ(cx, top + r, r),
    body: rp(R(cx - w / 2, by, w, h), [Math.min(w * 0.44, h - KR - 0.2), Math.min(w * 0.44, h - KR - 0.2), KR, KR], true),
    by
  };
}

/** The round face shared by the face icons. */
export const FACE = circ(15.5, 12.5, 11.5);

/**
 * A thumb raised from a fist with a cuff on its left; `down` mirrors it top to bottom.
 * Returns { cuff, hand, seams } path data.
 */
export function thumb(down) {
  const y = (v) => (down ? 25 - v : v);
  const pts = (list) => (down ? list.map(([a, b]) => [a, y(b)]).reverse() : list);
  const rad = (list) => (down ? [...list].reverse() : list);
  const cuff = rp(pts(R(4, 11.2, 4.8, 12.6)), 1.4);
  const handPts = [[9.6, 23.8], [9.6, 11.4], [12.6, 9.6], [13.6, 3.6], [17.6, 2.8], [17.6, 9.2], [24, 9.2], [26, 11.6], [24.6, 23.8]];
  const hand = rp(pts(handPts), rad([KR, 1.2, 2, 2.4, 2.2, 1.2, 2, 2.4, KR]));
  const seams = `M18.6 ${f(y(14.6))}H24.2M18.6 ${f(y(19))}H23.6`;
  return { cuff, hand, seams };
}
