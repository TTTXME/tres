// Authoring kit for Kubai Icons: the geometry helpers, fill radii and attribute
// shorthands every icon definition uses. Icon modules import from here; the runtime
// needs only the parts that an icon's draw() calls.
//
// Canvas: viewBox 0 -2 31 29, centre (15.5, 12.5). Keylines (fills; outside outlines add
// 1 all round): circle Ø23, square 20.6, landscape 23×18.4, portrait 18.4×23.
import { unease, PHASE } from './motion.js';

/** Fill radius of a body. With the 1-unit outside outline the visible corner is 3.2. */
export const KR = 2.2;
/** Fill radius of white content inside a body. */
export const CR = 1.2;
/** The clock rests at 10:08, the pose that reads as a clock at any size. */
export const CLOCK_MINUTES = 10 * 60 + 8;

/** Rounds to three decimals so path data stays short and stable. */
export const f = (n) => +n.toFixed(3);
export const clamp01 = (n) => Math.max(0, Math.min(1, n));

// ---------- Attribute shorthands ----------
/** Outside outline: a 2-wide stroke masked by its own shape, so 1 unit shows outside. */
export const OL = 'class="ol" data-t="stroke:#5393FF"';
/** Light content line, 1.6 wide. */
export const LINE = 'class="ln" data-t="stroke:#D3E2FC"';
/** Accent content line, 1.6 wide. */
export const ACC = 'class="ln" data-t="stroke:#5393FF"';
/** Deep mark fill. */
export const DEEP = 'data-t="fill:#2F6FE0"';
/** Deep mark stroke. */
export const DEEPS = 'data-t="stroke:#2F6FE0"';
/** A lighter mark, for the lower of two overlapping marks. */
export const SOFT = 'data-t="fill:#89B5FF"';
export const SOFTS = 'data-t="stroke:#89B5FF"';
/** White mark on a mid-tone body; soft variants turn it deep. */
export const WHITE = 'fill="#fff" data-w="fill"';
export const WHITES = 'stroke="#fff" data-w="stroke"';

// ---------- Geometry ----------
/** Corner points of a rectangle, clockwise. */
export const R = (x, y, w, h) => [[x, y], [x + w, y], [x + w, y + h], [x, y + h]];

/**
 * Rounded polygon. `r` is one radius or one per corner; `full` lets a corner use a whole
 * edge when its neighbour on that edge is small.
 */
export function rp(pts, r, full) {
  const n = pts.length;
  let d = '';
  for (let i = 0; i < n; i++) {
    const v = pts[i], a = pts[(i + n - 1) % n], b = pts[(i + 1) % n];
    const ri = Array.isArray(r) ? r[i] : r;
    const la = Math.hypot(a[0] - v[0], a[1] - v[1]) || 1e-6;
    const lb = Math.hypot(b[0] - v[0], b[1] - v[1]) || 1e-6;
    const lim = full ? 1 : 0.5;
    const k1 = Math.min(ri, la * lim) / la, k2 = Math.min(ri, lb * lim) / lb;
    d += (i ? 'L' : 'M') + f(v[0] + (a[0] - v[0]) * k1) + ' ' + f(v[1] + (a[1] - v[1]) * k1)
      + 'Q' + f(v[0]) + ' ' + f(v[1]) + ' ' + f(v[0] + (b[0] - v[0]) * k2) + ' ' + f(v[1] + (b[1] - v[1]) * k2);
  }
  return d + 'Z';
}
/** Rounded rectangle, clockwise. */
export const rr = (x, y, w, h, r) => rp(R(x, y, w, h), r);
/** Circle, counter-clockwise: overlapping it with a clockwise shape in one path punches a hole. */
export const circ = (cx, cy, r) => `M${f(cx - r)} ${f(cy)}A${f(r)} ${f(r)} 0 1 0 ${f(cx + r)} ${f(cy)}A${f(r)} ${f(r)} 0 1 0 ${f(cx - r)} ${f(cy)}Z`;
/** Ellipse, counter-clockwise. */
export const ellipse = (cx, cy, rx, ry) => `M${f(cx - rx)} ${f(cy)}A${f(rx)} ${f(ry)} 0 1 0 ${f(cx + rx)} ${f(cy)}A${f(rx)} ${f(ry)} 0 1 0 ${f(cx - rx)} ${f(cy)}Z`;
/** Open polyline through the points. */
export const poly = (pts) => 'M' + pts.map((p) => f(p[0]) + ' ' + f(p[1])).join('L');
/** Content lines 3 apart, starting at (x, y), one per width. */
export const lines = (x, y, widths) => widths.map((w, i) => `M${f(x)} ${f(y + i * 3)}H${f(x + w)}`).join('');

/** Five-pointed star centred on the origin. */
export function star(r1, r2, points = 5) {
  const pts = [];
  for (let i = 0; i < points * 2; i++) {
    const a = -Math.PI / 2 + i * Math.PI / points, r = i % 2 ? r2 : r1;
    pts.push([Math.cos(a) * r, Math.sin(a) * r]);
  }
  return rp(pts, 0.2);
}
/** Gear centred on (cx, cy). */
export function gear(teeth, rOut, rIn, cx = 15.5, cy = 12.5) {
  const pts = [], step = 360 / teeth, rad = (d) => d * Math.PI / 180;
  for (let i = 0; i < teeth; i++) {
    const a = i * step;
    [[rIn, a - step * 0.3], [rOut, a - step * 0.17], [rOut, a + step * 0.17], [rIn, a + step * 0.3]].forEach(([r, d]) => {
      pts.push([cx + r * Math.sin(rad(d)), cy - r * Math.cos(rad(d))]);
    });
  }
  return rp(pts, 0.8);
}
/** Upper intersection of two circles given as [cx, cy, r]. */
export function cross(a, b) {
  const dx = b[0] - a[0], dy = b[1] - a[1], d = Math.hypot(dx, dy);
  const l = (a[2] * a[2] - b[2] * b[2] + d * d) / (2 * d);
  const h = Math.sqrt(Math.max(0, a[2] * a[2] - l * l));
  const mx = a[0] + dx * l / d, my = a[1] + dy * l / d;
  const p1 = [mx + h * dy / d, my - h * dx / d], p2 = [mx - h * dy / d, my + h * dx / d];
  return p1[1] < p2[1] ? p1 : p2;
}
/** Arc command along circle c ([cx, cy, r]) from one point to another, clockwise. */
export function arc(c, from, to) {
  let dl = Math.atan2(to[1] - c[1], to[0] - c[0]) - Math.atan2(from[1] - c[1], from[0] - c[0]);
  while (dl < 0) dl += Math.PI * 2;
  return `A${f(c[2])} ${f(c[2])} 0 ${dl > Math.PI ? 1 : 0} 1 ${f(to[0])} ${f(to[1])}`;
}
/** Cloud from a left, top and right circle, each [cx, cy, r], with a flat bottom. */
export function cloud(L, T, Rc) {
  const s = [L[0], L[1] + L[2]], i1 = cross(L, T), i2 = cross(T, Rc), e = [Rc[0], Rc[1] + Rc[2]];
  return `M${f(s[0])} ${f(s[1])}${arc(L, s, i1)}${arc(T, i1, i2)}${arc(Rc, i2, e)}Z`;
}
/**
 * The part of a round shape below y=c: left side on circle A, right side on circle B,
 * flat bottom between their lowest points, top corners rounded by rc like a front panel.
 */
export function frontPiece(A, B, c, rc) {
  const xl = A[0] - Math.sqrt(A[2] ** 2 - (c - A[1]) ** 2), xr = B[0] + Math.sqrt(B[2] ** 2 - (c - B[1]) ** 2);
  const aL = Math.atan2(c - A[1], xl - A[0]) - rc / A[2];
  const aR = Math.atan2(c - B[1], xr - B[0]) + rc / B[2];
  const bL = [A[0] + A[2] * Math.cos(aL), A[1] + A[2] * Math.sin(aL)];
  const bR = [B[0] + B[2] * Math.cos(aR), B[1] + B[2] * Math.sin(aR)];
  return `M${f(xl + rc)} ${f(c)}Q${f(xl)} ${f(c)} ${f(bL[0])} ${f(bL[1])}`
    + `A${f(A[2])} ${f(A[2])} 0 0 0 ${f(A[0])} ${f(A[1] + A[2])}L${f(B[0])} ${f(B[1] + B[2])}`
    + `A${f(B[2])} ${f(B[2])} 0 0 0 ${f(bR[0])} ${f(bR[1])}Q${f(xr)} ${f(c)} ${f(xr - rc)} ${f(c)}Z`;
}
/** Pie slice from the canvas centre: start angle and sweep in degrees, clockwise from north. */
export function sector(base, sw, r, cx = 15.5, cy = 12.5) {
  const p = (a) => [cx + r * Math.sin(a * Math.PI / 180), cy - r * Math.cos(a * Math.PI / 180)];
  const a = p(base), b = p(base + sw);
  return `M${f(cx)} ${f(cy)}L${f(a[0])} ${f(a[1])}A${r} ${r} 0 ${sw > 180 ? 1 : 0} 1 ${f(b[0])} ${f(b[1])}Z`;
}
/** A capsule ring centred on the origin: outer w×h, wall t (use fill-rule evenodd). */
export const capsuleRing = (w, h, t) => rr(-w / 2, -h / 2, w, h, h / 2) + rr(-w / 2 + t, -h / 2 + t, w - 2 * t, h - 2 * t, (h - 2 * t) / 2);
/**
 * An arc of radius r round the origin from angle a0 to a1 (degrees, clockwise) and a solid
 * arrowhead whose base covers the arc's end, so strokes never overlap at the tip.
 */
export function arcArrow(a0, a1, r, head = 2.5, len = 3) {
  const rad = (d) => d * Math.PI / 180;
  const pt = (a) => [r * Math.cos(rad(a)), r * Math.sin(rad(a))];
  const s = pt(a0), e = pt(a1);
  const t = [-Math.sin(rad(a1)), Math.cos(rad(a1))], n = [Math.cos(rad(a1)), Math.sin(rad(a1))];
  const tip = [e[0] + t[0] * len, e[1] + t[1] * len];
  const hd = rp([[e[0] + n[0] * head, e[1] + n[1] * head], tip, [e[0] - n[0] * head, e[1] - n[1] * head]], 0.5);
  return { arc: `M${f(s[0])} ${f(s[1])}A${r} ${r} 0 ${Math.abs(a1 - a0) > 180 ? 1 : 0} 1 ${f(e[0])} ${f(e[1])}`, head: hd };
}
/**
 * A straight arrow from (x1, y1) to (x2, y2): a shaft ending under a solid head.
 * Returns { shaft, head }: stroke the shaft at 2.2 with round caps, fill the head.
 */
export function arrow(x1, y1, x2, y2, head = 3.4, half = 2.9) {
  const d = Math.hypot(x2 - x1, y2 - y1) || 1e-6, ux = (x2 - x1) / d, uy = (y2 - y1) / d;
  const bx = x2 - ux * head, by = y2 - uy * head;
  const hd = rp([[bx - uy * half, by + ux * half], [x2, y2], [bx + uy * half, by - ux * half]], [0.5, 0.6, 0.5]);
  return { shaft: `M${f(x1)} ${f(y1)}L${f(bx + ux * 0.6)} ${f(by + uy * 0.6)}`, head: hd };
}
/** Sets attributes on an element; ignores a missing element. */
export const at = (el, o) => { if (el) for (const k in o) el.setAttribute(k, o[k]); };
/** A transform that scales by s about (cx, cy). */
export const scaleAt = (s, cx, cy, sy = s) => `translate(${f(cx)} ${f(cy)}) scale(${f(s)} ${f(sy)}) translate(${f(-cx)} ${f(-cy)})`;
/**
 * Evaluates fn(t), a movement that does not run straight from rest to hover: a swing, a
 * pulse, a blink. `o` is the eased progress; fn gets real time from 0 to 1, so the movement
 * fills the whole motion rather than bunching at the start of the curve. On the way back
 * to rest it does not replay: whatever is left of it eases into its rest value.
 */
export function wave(o, fn) {
  const m = globalThis[PHASE];
  if (!m) return fn(unease(o));
  if (m.out) {
    const left = 1 - m.e;
    return fn(left > 1e-4 ? unease(Math.min(1, o / left)) : 1) * left + fn(0) * m.e;
  }
  // Hovered again part-way back: the movement starts afresh from the pose on screen.
  return fn(unease(o > m.e + 1e-3 ? m.e : o));
}
const SWING = 0.75, MAX_HALF_TURNS = 3;
/** A damped swing that is 0 at rest and at the end of the hover: amplitude a, k half-turns. */
export const rattle = (o, a, k = 3) => wave(o, (t) => a * SWING * (1 - t) * Math.sin(Math.min(k, MAX_HALF_TURNS) * Math.PI * t * t * (3 - 2 * t)));
/** A single out-and-back pulse, 0 at rest and at the end of the hover, 1 halfway through. */
export const bump = (o) => wave(o, (t) => Math.sin(Math.PI * t));

/**
 * Declares an icon. Fields:
 * - name: kebab-case English id, unique across the set.
 * - zh: Simplified Chinese label.
 * - category: one of the categories in categories.json.
 * - tags: search terms (English, plus Chinese synonyms when helpful).
 * - variants: badge ids this object takes (see badges/), e.g. ['plus', 'check'].
 * - off: true if a slashed "-off" variant makes sense.
 * - svg(u), target(hover, state), draw(p, E): the layers and the motion.
 * - tap: true when a click toggles `state.active`.
 * - by: contributor GitHub handle.
 */
export function defineIcon(def) {
  return def;
}
/** Declares a badge: a small supporting mark composed onto a base icon's corner. */
export function defineBadge(def) {
  return def;
}
