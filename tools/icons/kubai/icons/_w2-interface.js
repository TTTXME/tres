// Shared geometry for the text-formatting, scan and sparkle icons: stroked letter forms
// turned into filled outlines, the four-point sparkle, and the scanner's corner brackets.
import { f, rp, WHITE } from '../packages/core/src/kit.js';
import { bar } from './_interface.js';

/**
 * A filled ribbon of width w along a sampled centreline (an array of [x, y]).
 * Ends are cut square, or rounded when `round` is true.
 */
export function ribbon(pts, w, round = false) {
  const h = w / 2, n = pts.length, L = [], Rt = [];
  for (let i = 0; i < n; i++) {
    const a = pts[Math.max(0, i - 1)], b = pts[Math.min(n - 1, i + 1)];
    const dx = b[0] - a[0], dy = b[1] - a[1], d = Math.hypot(dx, dy) || 1e-6;
    const nx = -dy / d * h, ny = dx / d * h;
    L.push([pts[i][0] + nx, pts[i][1] + ny]); Rt.push([pts[i][0] - nx, pts[i][1] - ny]);
  }
  const P = (p) => `${f(p[0])} ${f(p[1])}`;
  const cap = (to) => (round ? `A${f(h)} ${f(h)} 0 0 1 ${P(to)}` : `L${P(to)}`);
  return `M${P(L[0])}` + L.slice(1).map((p) => `L${P(p)}`).join('') + cap(Rt[n - 1])
    + Rt.slice(0, -1).reverse().map((p) => `L${P(p)}`).join('') + cap(L[0]) + 'Z';
}

/** Points along a circular arc round (cx, cy), from angle a0 to a1 in degrees (y down). */
export function arcPts(cx, cy, r, a0, a1, steps = 16) {
  const out = [];
  for (let i = 0; i <= steps; i++) {
    const a = (a0 + (a1 - a0) * i / steps) * Math.PI / 180;
    out.push([cx + r * Math.cos(a), cy + r * Math.sin(a)]);
  }
  return out;
}

/** A four-point sparkle centred on the origin: radius r, sides curving in, tips softened. */
export function sparkle(r, pinch = 0.2) {
  const k = r * pinch, pts = [], rad = [];
  const tips = [[0, -r], [r, 0], [0, r], [-r, 0]];
  for (let i = 0; i < 4; i++) {
    const a = tips[i], b = tips[(i + 1) % 4];
    const c = [Math.sign(a[0] + b[0]) * k, Math.sign(a[1] + b[1]) * k];
    for (let s = 0; s < 6; s++) {
      const t = s / 6, u = 1 - t;
      pts.push([u * u * a[0] + 2 * u * t * c[0] + t * t * b[0], u * u * a[1] + 2 * u * t * c[1] + t * t * b[1]]);
      rad.push(s === 0 ? 0.5 : 0.01);
    }
  }
  return rp(pts, rad);
}

/** Four L-shaped scanner corners on the box x0..x1, y0..y1, each arm L long. */
export function corners(x0, y0, x1, y1, L = 3.6) {
  const c = (x, y, sx, sy) => `M${f(x)} ${f(y + sy * L)}V${f(y + sy * 1.6)}Q${f(x)} ${f(y)} ${f(x + sx * 1.6)} ${f(y)}H${f(x + sx * L)}`;
  return c(x0, y0, 1, 1) + c(x1, y0, -1, 1) + c(x1, y1, -1, -1) + c(x0, y1, 1, -1);
}

// A letter X from two crossing bars, centred on (cx, cy), half-size a.
export const X = (cx, cy, a) => `<path d="${bar(cx - a, cy - a, cx + a, cy + a, 2.8, 0.6)}" ${WHITE}/><path d="${bar(cx + a, cy - a, cx - a, cy + a, 2.8, 0.6)}" ${WHITE}/>`;
// A small figure 2 drawn as one stroke, about 4.4 wide and 6 tall, centred on the origin.
export const TWO = 'M-2.1 -1.3C-2.1 -4.1 2.2 -4.1 2.2 -1.4C2.2 0.2 -0.4 0.9 -2.2 3H2.3';
