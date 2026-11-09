// Shared shapes for the interface and layout icons: the disc, tile and window bodies,
// their layered markup, and the white symbol stroke drawn on them.
import { KR, OL, WHITES, f, circ, rr, rp } from '../packages/core/src/kit.js';

/** Round body on the circle keyline. */
export const DISC = circ(15.5, 12.5, 11.5);
/** Square body on the square keyline. */
export const TILE = rr(5.2, 2.2, 20.6, 20.6, KR);
/** Landscape window body. */
export const WIN = rr(4, 3.3, 23, 18.4, KR);

/** A back piece: mid-tone fill with shadow and top glow, plus its outline. */
export const back = (u, d, fill = u.loS) =>
  `<g filter="${u.sh}"><g filter="${u.hi}"><path d="${d}" fill="${fill}"/></g></g><path d="${d}" ${OL}/>`;
/** A front piece: light fill with top glow, plus its outline. */
export const front = (u, d, fill = u.upS) =>
  `<g filter="${u.hi}"><path d="${d}" fill="${fill}"/></g><path d="${d}" ${OL}/>`;

/** A white symbol stroke (2.2) on a mid-tone body. */
export const MARK = `fill="none" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" ${WHITES}`;

/** Plus centred on (cx, cy) with arms of half-length a. */
export const plusPath = (a, cx = 15.5, cy = 12.5) => `M${f(cx - a)} ${f(cy)}H${f(cx + a)}M${f(cx)} ${f(cy - a)}V${f(cy + a)}`;

/** A rounded bar between two points, w wide, as a closed shape (for outlined parts). */
export function bar(x1, y1, x2, y2, w, r = w / 2) {
  const d = Math.hypot(x2 - x1, y2 - y1) || 1e-6, nx = -(y2 - y1) / d * w / 2, ny = (x2 - x1) / d * w / 2;
  return rp([[x1 + nx, y1 + ny], [x2 + nx, y2 + ny], [x2 - nx, y2 - ny], [x1 - nx, y1 - ny]], r);
}

/** Progress of one item in a staggered run: item i of n starts later, all end by o=1. */
export const stagger = (o, i, n, span = 0.6) => {
  const s = n > 1 ? (1 - span) * i / (n - 1) : 0;
  return Math.max(0, Math.min(1, (o - s) / span));
};

/** A tick mark scaled by s about (15.5, 12.9) and moved down by dy. */
export const tick = (s = 1, dy = 0) => 'M' + [[10.4, 12.9], [14, 16.4], [20.6, 9.4]]
  .map(([x, y]) => `${f(15.5 + (x - 15.5) * s)} ${f(12.9 + (y - 12.9) * s + dy)}`).join('L');
