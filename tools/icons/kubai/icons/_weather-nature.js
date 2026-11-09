// Shared geometry for the weather and nature icons: the small cloud that rain and snow
// fall from, crescents, capsules and the cycle that carries falling particles.
import { f, OL, rp, cloud, frontPiece, clamp01 } from '../packages/core/src/kit.js';

export const rad = (d) => d * Math.PI / 180;
/** Point p turned by a degrees (clockwise on screen) about (cx, cy). */
export const turn = (p, a, cx, cy) => {
  const c = Math.cos(rad(a)), s = Math.sin(rad(a)), x = p[0] - cx, y = p[1] - cy;
  return [cx + x * c - y * s, cy + x * s + y * c];
};
/** A capsule of width w round the segment (x1, y1)–(x2, y2), clockwise. */
export function capsule(x1, y1, x2, y2, w) {
  const d = Math.hypot(x2 - x1, y2 - y1) || 1e-6, nx = (y2 - y1) / d * w / 2, ny = -(x2 - x1) / d * w / 2;
  return rp([[x1 + nx, y1 + ny], [x2 + nx, y2 + ny], [x2 - nx, y2 - ny], [x1 - nx, y1 - ny]], w / 2, true);
}

const ang = (c, p) => Math.atan2(p[1] - c[1], p[0] - c[0]);
const cwSpan = (a, b) => { let d = b - a; while (d < 0) d += Math.PI * 2; while (d >= Math.PI * 2) d -= Math.PI * 2; return d; };
/** Arc command along circle c ([cx, cy, r]) from one point to another, passing `via`. */
export function arcVia(c, from, to, via) {
  const span = cwSpan(ang(c, from), ang(c, to)), sweep = cwSpan(ang(c, from), ang(c, via)) < span ? 1 : 0;
  const d = sweep ? span : Math.PI * 2 - span;
  return `A${f(c[2])} ${f(c[2])} 0 ${d > Math.PI ? 1 : 0} ${sweep} ${f(to[0])} ${f(to[1])}`;
}
/** Both intersections of two circles [cx, cy, r]. */
export function meet(a, b) {
  const dx = b[0] - a[0], dy = b[1] - a[1], d = Math.hypot(dx, dy);
  const l = (a[2] * a[2] - b[2] * b[2] + d * d) / (2 * d), h = Math.sqrt(Math.max(0, a[2] * a[2] - l * l));
  const mx = a[0] + dx * l / d, my = a[1] + dy * l / d;
  return [[mx + h * dy / d, my - h * dx / d], [mx - h * dy / d, my + h * dx / d]];
}
/** The lit part of disc O ([cx, cy, r]) left over when disc C covers it. */
export function crescent(O, C) {
  const [p1, p2] = meet(O, C), d = Math.hypot(C[0] - O[0], C[1] - O[1]);
  const ux = (C[0] - O[0]) / d, uy = (C[1] - O[1]) / d;
  const far = [O[0] - ux * O[2], O[1] - uy * O[2]], near = [C[0] - ux * C[2], C[1] - uy * C[2]];
  return `M${f(p1[0])} ${f(p1[1])}${arcVia(O, p1, p2, far)}${arcVia(C, p2, p1, near)}Z`;
}

/** The small cloud that rain, snow and hail fall from; its lower part is a front piece. */
export const SKY = { L: [8.8, 11.4, 4.8], T: [15.1, 7.2, 6.4], Rc: [22, 11.8, 4.4], chord: 13.8 };
/** The cloud's layers, with markup drawn behind it and in front of it. */
export function sky(u, behind = '', front = '') {
  const d = cloud(SKY.L, SKY.T, SKY.Rc), fr = frontPiece(SKY.L, SKY.Rc, SKY.chord, 2);
  return `${behind}<g filter="${u.sh}"><g filter="${u.hi}"><path d="${d}" fill="${u.loS}"/></g></g><path d="${d}" ${OL}/>
    <g filter="${u.hi}"><path d="${fr}" fill="${u.upS}"/></g><path d="${fr}" ${OL}/>${front}`;
}
/**
 * One full fall cycle for a particle resting at height y0: over o = 0..1 it drops a little
 * and fades out, comes round behind the cloud and falls back to y0.
 */
export function fall(o, y0, top = 12.4, drop = 3) {
  const span = y0 + drop - top, t = ((y0 - top) / span + o) % 1, y = top + t * span;
  return { dy: y - y0, op: clamp01(1 - Math.max(0, y - y0) / drop) };
}

/** A leaf along the negative y axis from its base at the origin: length l, width w. */
export const leafShape = (l, w) => `M0 0C${f(-w * 0.62)} ${f(-l * 0.08)} ${f(-w * 0.66)} ${f(-l * 0.6)} 0 ${f(-l)}C${f(w * 0.66)} ${f(-l * 0.6)} ${f(w * 0.62)} ${f(-l * 0.08)} 0 0Z`;
/**
 * A flame standing on (15.5, by): half width hw, height ht, tip leaning by lean. Its tip
 * is the one sharp corner.
 */
export function flameShape(by, hw, ht, lean = 0) {
  const x = (v) => f(15.5 + v), y = (v) => f(by + v), tx = lean;
  return `M${x(0)} ${y(0)}C${x(-hw * 0.57)} ${y(0)} ${x(-hw)} ${y(-ht * 0.15)} ${x(-hw)} ${y(-ht * 0.36)}`
    + `C${x(-hw)} ${y(-ht * 0.6)} ${x(tx - hw * 0.42)} ${y(-ht * 0.74)} ${x(tx)} ${y(-ht)}`
    + `C${x(tx + hw * 0.24)} ${y(-ht * 0.8)} ${x(hw)} ${y(-ht * 0.62)} ${x(hw)} ${y(-ht * 0.36)}`
    + `C${x(hw)} ${y(-ht * 0.15)} ${x(hw * 0.57)} ${y(0)} ${x(0)} ${y(0)}Z`;
}
