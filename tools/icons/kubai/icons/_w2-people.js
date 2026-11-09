// Shared pieces for the people, animal and messaging icons in this group: layers that can
// be moved as one, jointed limbs and the closed envelope.
import { KR, OL, rp, at } from '../packages/core/src/kit.js';

/**
 * A layer whose fill and outline move together: shadow (optional) and top glow stay
 * outside the transform, so light always comes from above. Name it to move it with
 * `move(E, n, transform)`.
 */
export const layer = (u, d, fill, n, { sh = true, extra = '' } = {}) => {
  const name = (s) => (n ? ` data-n="${n}${s}"` : '');
  const inner = `<g${name('')}><path d="${d}" fill="${fill}"${extra}/></g>`;
  return `${sh ? `<g filter="${u.sh}">` : ''}<g filter="${u.hi}">${inner}</g>${sh ? '</g>' : ''}<g${name('O')}><path d="${d}"${extra} ${OL}/></g>`;
};
/** Applies one transform to a named layer and its outline. */
export const move = (E, n, t) => { at(E[n], { transform: t }); at(E[`${n}O`], { transform: t }); };

const rad = (d) => d * Math.PI / 180;
/** A capsule of width w whose round ends are centred on a and b, clockwise. */
export function capsule(a, b, w) {
  const d = Math.hypot(b[0] - a[0], b[1] - a[1]) || 1e-6, r = w / 2;
  const ux = (b[0] - a[0]) / d * r, uy = (b[1] - a[1]) / d * r;
  const A = [a[0] - ux, a[1] - uy], B = [b[0] + ux, b[1] + uy];
  return rp([[A[0] + uy, A[1] - ux], [B[0] + uy, B[1] - ux], [B[0] - uy, B[1] + ux], [A[0] - uy, A[1] + ux]], r, true);
}
/**
 * A limb of two segments from a root joint: a1 is the upper segment's angle from straight
 * down (positive turns it forward, towards +x), a2 the lower segment's. Both capsules wind
 * the same way, so one path fills as their union and its outline shows only outside.
 */
export function limb(root, a1, a2, l1, l2, w) {
  const k = [root[0] + Math.sin(rad(a1)) * l1, root[1] + Math.cos(rad(a1)) * l1];
  const e = [k[0] + Math.sin(rad(a2)) * l2, k[1] + Math.cos(rad(a2)) * l2];
  return capsule(root, k, w) + capsule(k, e, w);
}

/**
 * A closed envelope with its top left corner at (x, y): body, lighter pocket and the flap
 * folded down onto the pocket's V. Returns path data for the three parts.
 */
export function envelope(x, y, w, h) {
  const side = h * 0.24, v = h * 0.6, cx = x + w / 2, b = y + h;
  return {
    body: rp([[x, y], [x + w, y], [x + w, b], [x, b]], KR),
    pocket: rp([[x, y + side], [cx, y + v], [x + w, y + side], [x + w, b], [x, b]], [0.6, 2.4, 0.6, KR, KR]),
    flap: rp([[x, y], [x + w, y], [x + w, y + side], [cx, y + v], [x, y + side]], [KR, KR, 0.6, 2.4, 0.6], true)
  };
}

/** A layer whose path is drawn in draw(): set `d` on both `n` and `nO`. */
export const shape = (u, fill, n, sh = true) => `${sh ? `<g filter="${u.sh}">` : ''}<g filter="${u.hi}"><path data-n="${n}" fill="${fill}"/></g>${sh ? '</g>' : ''}<path data-n="${n}O" stroke-linejoin="round" ${OL}/>`;
/** Sets the same path on a shape and its outline. */
export const setD = (E, n, d) => { at(E[n], { d }); at(E[`${n}O`], { d }); };

/**
 * A walking or running figure facing right. Each limb is [a1, a2] (see limb); the near
 * arm and leg are lighter and in front of the torso, the far ones behind it.
 */
export function figure(u, head) {
  return `${shape(u, u.loS, 'fa')}${shape(u, u.loS, 'fl')}${shape(u, u.loS, 'to')}
    ${shape(u, u.upS, 'nl')}${shape(u, u.upS, 'na')}
    <g filter="${u.sh}"><g filter="${u.hi}"><path d="${head}" fill="${u.upS}"/></g></g><path d="${head}" ${OL}/>`;
}
export function pose(E, g, p) {
  setD(E, 'to', capsule(g.S, g.H, g.tw));
  setD(E, 'fa', limb(g.A, p.fa[0], p.fa[1], g.arm[0], g.arm[1], g.aw));
  setD(E, 'na', limb(g.A, p.na[0], p.na[1], g.arm[0], g.arm[1], g.aw));
  setD(E, 'fl', limb(g.H, p.fl[0], p.fl[1], g.leg[0], g.leg[1], g.lw));
  setD(E, 'nl', limb(g.H, p.nl[0], p.nl[1], g.leg[0], g.leg[1], g.lw));
}

/**
 * Path data made only of absolute commands with coordinate pairs (M, L, C, Q, Z), turned
 * by deg about the origin and moved to (tx, ty). Lets several copies of one shape share a
 * single path, so their outline shows only round the outside of the union.
 */
export function place(d, tx, ty, deg) {
  const c = Math.cos(deg * Math.PI / 180), s = Math.sin(deg * Math.PI / 180);
  return d.replace(/(-?\d*\.?\d+)[ ,](-?\d*\.?\d+)/g, (_, a, b) => {
    const x = +a, y = +b;
    return `${+(tx + x * c - y * s).toFixed(3)} ${+(ty + x * s + y * c).toFixed(3)}`;
  });
}
