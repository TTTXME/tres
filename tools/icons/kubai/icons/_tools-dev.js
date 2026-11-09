// Shared helpers for the tools and development icons.
import { OL, f, at } from '../packages/core/src/kit.js';

/** Circle, clockwise: inside a counter-clockwise shape it punches a hole under nonzero. */
export const cwCirc = (cx, cy, r) => `M${f(cx - r)} ${f(cy)}A${f(r)} ${f(r)} 0 1 1 ${f(cx + r)} ${f(cy)}A${f(r)} ${f(r)} 0 1 1 ${f(cx - r)} ${f(cy)}Z`;

/**
 * Pieces that move under one transform, back to front. Each piece is either a string of
 * markup (content and marks) or { d, fill, rule, sh, hi, ol }: its shadow and top light
 * sit outside the transform, so light keeps coming from above, and its outline follows it.
 */
export function rig(u, id, parts) {
  return parts.map((p, i) => {
    if (typeof p === 'string') return `<g data-n="${id}${i}">${p}</g>`;
    const rule = p.rule ? ` fill-rule="${p.rule}"` : '';
    let fill = `<g data-n="${id}${i}"><path d="${p.d}"${rule} fill="${p.fill}"/></g>`;
    if (p.hi !== false) fill = `<g filter="${u.hi}">${fill}</g>`;
    if (p.sh !== false) fill = `<g filter="${u.sh}">${fill}</g>`;
    const ol = p.ol === false ? '' : `<g data-n="${id}${i}o"><path d="${p.d}"${rule} ${OL}/></g>`;
    return fill + ol;
  }).join('');
}
/** Applies one transform to the n pieces a rig() with this id produced. */
export function pose(E, id, n, transform) {
  for (let i = 0; i < n; i++) { at(E[id + i], { transform }); at(E[id + i + 'o'], { transform }); }
}
/** Rotation about a point. */
export const rot = (a, cx, cy) => `rotate(${f(a)} ${f(cx)} ${f(cy)})`;
/** Both crossing points of two circles given as [cx, cy, r]. */
export function meet(a, b) {
  const dx = b[0] - a[0], dy = b[1] - a[1], d = Math.hypot(dx, dy);
  const l = (a[2] * a[2] - b[2] * b[2] + d * d) / (2 * d), h = Math.sqrt(Math.max(0, a[2] * a[2] - l * l));
  const mx = a[0] + dx * l / d, my = a[1] + dy * l / d;
  return [[mx + h * dy / d, my - h * dx / d], [mx - h * dy / d, my + h * dx / d]];
}
/**
 * A band of width w along an open polyline, as polygon points: one side forward, the
 * other back, mitred at the bends. Round its corners with rp().
 */
export function band(pts, w) {
  const h = w / 2, n = pts.length, L = [], Rt = [];
  const nrm = (a, b) => { const dx = b[0] - a[0], dy = b[1] - a[1], d = Math.hypot(dx, dy) || 1; return [-dy / d, dx / d]; };
  for (let i = 0; i < n; i++) {
    const a = nrm(pts[Math.max(0, i - 1)], pts[Math.max(1, i)]), b = nrm(pts[Math.min(n - 2, i)], pts[Math.min(n - 1, i + 1)]);
    let mx = a[0] + b[0], my = a[1] + b[1];
    const ml = Math.hypot(mx, my) || 1; mx /= ml; my /= ml;
    const k = h / Math.max(0.3, mx * a[0] + my * a[1]);
    L.push([pts[i][0] + mx * k, pts[i][1] + my * k]); Rt.push([pts[i][0] - mx * k, pts[i][1] - my * k]);
  }
  return [...L, ...Rt.reverse()];
}
