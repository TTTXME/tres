// Shared geometry for the science, education, sports and shapes icons.
import { f, OL, rp } from '../packages/core/src/kit.js';

/** Corner points of a regular polygon round (cx, cy), the first corner at `rot` degrees from north. */
export function regular(n, r, cx = 15.5, cy = 12.5, rot = 0) {
  const pts = [];
  for (let i = 0; i < n; i++) {
    const a = (rot + i * 360 / n) * Math.PI / 180;
    pts.push([cx + r * Math.sin(a), cy - r * Math.cos(a)]);
  }
  return pts;
}

/** The same points in the other winding, so a shape joins circles (counter-clockwise) in one path. */
export const ccw = (pts) => pts.slice().reverse();

/** Closed polyline through dense samples of a curve. */
export const closed = (pts) => 'M' + pts.map((p) => f(p[0]) + ' ' + f(p[1])).join('L') + 'Z';

/**
 * A flat shape with a lighter copy inset, both turning about (cx, cy). The light stays on
 * the outer groups; `o`, `oO`, `i`, `iO` are the turning parts.
 */
export const turning = (u, outer, inner) => `
  <g filter="${u.sh}"><g filter="${u.hi}"><g data-n="o"><path d="${outer}" fill="${u.loS}"/></g></g></g>
  <g data-n="oO"><path d="${outer}" ${OL}/></g>
  <g filter="${u.hi}"><g data-n="i"><path d="${inner}" fill="${u.upS}"/></g></g>
  <g data-n="iO"><path d="${inner}" ${OL}/></g>`;

/** Rounded polygon from regular(): a shorthand used by the polygon shapes. */
export const polygon = (n, r, cx, cy, rot, rad) => rp(regular(n, r, cx, cy, rot), rad);

/**
 * A band drawn as a wide stroke: an outline stroke 2 wider underneath, then the fill.
 * Use for shapes that cross themselves (an infinity loop, a helix strand).
 */
export const band = (u, d, w, fill, name = '') => `<g filter="${u.sh}">
  <path${name ? ` data-n="${name}E"` : ''} d="${d}" fill="none" stroke-width="${f(w + 2)}" stroke-linecap="round" stroke-linejoin="round" data-t="stroke:#5393FF"/>
  <g filter="${u.hi}"><path${name ? ` data-n="${name}"` : ''} d="${d}" fill="none" stroke="${fill}" stroke-width="${f(w)}" stroke-linecap="round" stroke-linejoin="round"/></g></g>`;

/**
 * One coloured layer of a moving part: the fill keeps its shadow and top light outside the
 * part's transform, the outline sits in a twin group. Set the same transform on `name`
 * and `name + 'O'`.
 */
export const layer = (u, name, d, fill, { sh = true, hi = true, extra = '' } = {}) => {
  let g = `<g data-n="${name}"><path d="${d}" fill="${fill}"/></g>`;
  if (hi) g = `<g filter="${u.hi}">${g}</g>`;
  if (sh) g = `<g filter="${u.sh}">${g}</g>`;
  return `${g}<g data-n="${name}O"><path d="${d}" ${OL}/>${extra}</g>`;
};
/** Sets one transform on every layer name and its outline twin. */
export const moveAll = (E, names, t) => names.forEach((n) => { if (E[n]) E[n].setAttribute('transform', t); if (E[n + 'O']) E[n + 'O'].setAttribute('transform', t); });
