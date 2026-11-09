// P570: SCAMERA's own icons in the Kubai Icons format (the camera's concepts the MIT library has no object for): shared
// shapes. Geometry, layers, outlines and colours follow the library's (tools/icons/kubai/icons/_interface.js).
import { KR, CR, OL, f, rr, rp, at, scaleAt, clamp01 } from '../kubai/packages/core/src/kit.js';
import { back, front, WIN } from '../kubai/icons/_interface.js';

export { back, front, WIN };

/** The viewfinder: a landscape window body with a white screen inset by 2.2 (the band the rules allow). */
export const SCREEN = [6.2, 5.5, 18.6, 14];
export const viewfinder = (u) => `${back(u, WIN)}<path d="${rr(SCREEN[0], SCREEN[1], SCREEN[2], SCREEN[3], CR)}" fill="#fff"/>`;

/** Lines on the screen as one accent stroke path (data-n `ln`), drawn by drawLines. */
export const linesMark = `<path data-n="ln" fill="none" stroke-width="1.6" stroke-linecap="round" data-t="stroke:#5393FF"/>`;

/** Path of segments [[x1, y1, x2, y2], ...] scaled by s about the screen's centre. */
export function segs(list, s = 1) {
  const cx = SCREEN[0] + SCREEN[2] / 2, cy = SCREEN[1] + SCREEN[3] / 2;
  const P = (x, y) => `${f(cx + (x - cx) * s)} ${f(cy + (y - cy) * s)}`;
  return list.map(([x1, y1, x2, y2]) => `M${P(x1, y1)}L${P(x2, y2)}`).join('');
}

/** Grid lines dividing the screen into n x n cells (inset 0 from its edges: they end at the screen's sides). */
export function gridLines(n) {
  const [x, y, w, h] = SCREEN, out = [];
  for (let i = 1; i < n; i++) {
    out.push([x + w * i / n, y + 1.4, x + w * i / n, y + h - 1.4]);
    out.push([x + 1.4, y + h * i / n, x + w - 1.4, y + h * i / n]);
  }
  return out;
}

/** Digit strokes (a seven-segment-like hand), each [x, y] list a polyline, in a box 4 wide x 7 high at (x, y). */
const DIGITS = {
  0: [[[0, 0.9], [0.9, 0], [3.1, 0], [4, 0.9], [4, 6.1], [3.1, 7], [0.9, 7], [0, 6.1], [0, 0.9]]],
  1: [[[1, 1.2], [2.6, 0], [2.6, 7]]],
  3: [[[0.2, 0.6], [1, 0], [3, 0], [4, 0.9], [4, 2.4], [3, 3.4], [1.6, 3.4]], [[3, 3.4], [4, 4.4], [4, 6.1], [3.1, 7], [1, 7], [0.2, 6.4]]]
};
export function digits(text, cx, cy, scale = 1) {
  const n = text.length, wd = 4 * scale, gap = 1.6 * scale, total = n * wd + (n - 1) * gap;
  let d = '', x0 = cx - total / 2;
  for (const ch of text) {
    for (const pl of DIGITS[ch]) d += 'M' + pl.map(([x, y]) => `${f(x0 + x * scale)} ${f(cy - 3.5 * scale + y * scale)}`).join('L');
    x0 += wd + gap;
  }
  return d;
}

export { KR, CR, OL, f, rr, rp, at, scaleAt, clamp01 };
