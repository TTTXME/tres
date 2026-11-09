// Motion: 460ms on cubic-bezier(.22, 1, .36, 1), every number interpolated on its own.

export function bezier(x1, y1, x2, y2) {
  const cx = 3 * x1, bx = 3 * (x2 - x1) - cx, ax = 1 - cx - bx;
  const cy = 3 * y1, by = 3 * (y2 - y1) - cy, ay = 1 - cy - by;
  const sx = (t) => ((ax * t + bx) * t + cx) * t;
  const sy = (t) => ((ay * t + by) * t + cy) * t;
  const dx = (t) => (3 * ax * t + 2 * bx) * t + cx;
  return (x) => {
    if (x <= 0) return 0;
    if (x >= 1) return 1;
    let t = x;
    for (let i = 0; i < 8; i++) {
      const e = sx(t) - x;
      if (Math.abs(e) < 1e-5) break;
      const d = dx(t);
      if (Math.abs(d) < 1e-6) break;
      t -= e / d;
    }
    return sy(t);
  };
}
export const EASE = bezier(0.22, 1, 0.36, 1);
export const DURATION = 460;

/** Interpolates numbers, arrays and objects of numbers from a to b. */
export function interp(a, b, t) {
  if (typeof b === 'number') return typeof a === 'number' ? a + (b - a) * t : b;
  if (Array.isArray(b)) return b.map((v, i) => interp(a ? a[i] : v, v, t));
  if (b && typeof b === 'object') {
    const o = {};
    for (const k in b) o[k] = interp(a ? a[k] : undefined, b[k], t);
    return o;
  }
  return b;
}

let table;
/** Inverse of EASE: the share of the motion's time that has passed when the curve reads y. */
export function unease(y) {
  if (y <= 0) return 0;
  if (y >= 1) return 1;
  table = table || Array.from({ length: 129 }, (_, i) => EASE(i / 128));
  let lo = 0, hi = 128;
  while (hi - lo > 1) {
    const m = (lo + hi) >> 1;
    if (table[m] < y) lo = m; else hi = m;
  }
  return (lo + (y - table[lo]) / (table[hi] - table[lo])) / 128;
}

/**
 * Key of the phase the runtime publishes while it calls draw(): { out, e }, with `out` true
 * on the way back to rest and `e` the eased progress of the current motion. It lives on
 * globalThis because each icon bundle may carry its own copy of the authoring kit.
 */
export const PHASE = Symbol.for('kubai-icons.phase');
